package org.figuramc.figura.backend2;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import org.figuramc.figura.FiguraMod;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class AuthHandler {

    // only one auth at a time, parallel ones overwrite each other's server id and token on the backend
    // a reauth asked for while one is running is done once it finishes
    private static final AtomicBoolean AUTHING = new AtomicBoolean();
    private static final AtomicBoolean PENDING = new AtomicBoolean();

    // consecutive 429s, doubles the retry delay so a rate limited client backs off instead of hammering the limiter
    // only touched from the auth task, which never runs in parallel
    private static int rateLimits;

    public static void auth(boolean reAuth) {
        if (!AUTHING.compareAndSet(false, true)) {
            if (reAuth) PENDING.set(true);
            return;
        }

        NetworkStuff.async(() -> {
            try {
                runAuth(reAuth);
            } finally {
                AUTHING.set(false);
                if (PENDING.getAndSet(false))
                    auth(true);
            }
        });
    }

    private static void runAuth(boolean reAuth) {
        if (!reAuth && NetworkStuff.isConnected())
            return;

        FiguraMod.LOGGER.info("Authenticating with " + FiguraMod.MOD_NAME + " server...");
        NetworkStuff.backendStatus = 2;

        Minecraft minecraft = Minecraft.getInstance();
        User user = minecraft.getUser();
        try {
            String username = user.getName();
            String serverID = getServerID(username);
            FiguraMod.debug("Joining \"{}\" on server \"{}\"", username, serverID);
            minecraft.getMinecraftSessionService().joinServer(user.getGameProfile(), user.getAccessToken(), serverID);
            NetworkStuff.authSuccess(getToken(serverID));
            rateLimits = 0;
        } catch (RateLimitedException e) {
            // retry soon instead of waiting the full reconnect timer, jittered so clients behind one ip don't retry in sync
            int seconds = Math.max(e.retryAfter, Math.min(2 << rateLimits, 60));
            rateLimits = Math.min(rateLimits + 1, 5);
            int ticks = (seconds + ThreadLocalRandom.current().nextInt(4)) * 20;
            NetworkStuff.authFail(e.getMessage());
            NetworkStuff.scheduleReconnect(ticks);
            FiguraMod.LOGGER.info("Auth was rate limited, retrying in {}s", ticks / 20);
        // cringe exceptions
        } catch (AuthenticationUnavailableException e) {
            NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.serversUnavailable").getString());
        } catch (InvalidCredentialsException e) {
            NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.invalidSession").getString());
        } catch (InsufficientPrivilegesException e) {
            NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.insufficientPrivileges").getString());
        } catch (UserBannedException e) {
            NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.userBanned").getString());
        } catch (Exception e) {
            NetworkStuff.authFail(e.getMessage());
        }
    }

    // requests // 

    protected static String request(HttpRequest request) throws Exception {
        HttpResponse<String> response = NetworkStuff.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 429)
            throw new RateLimitedException(response.body(), response.headers().firstValue("retry-after").orElse(""));
        if (response.statusCode() != 200)
            throw new Exception(response.body());
        return response.body();
    }

    private static String getServerID(String username) throws Exception {
        return request(HttpRequest.newBuilder(HttpAPI.getUri("/auth/id?username=" + username)).build());
    }

    private static String getToken(String serverID) throws Exception {
        return request(HttpRequest.newBuilder(HttpAPI.getUri("/auth/verify?id=" + serverID)).build());
    }

    private static class RateLimitedException extends Exception {
        private final int retryAfter;

        private RateLimitedException(String message, String retryAfter) {
            super(message);
            int seconds = 0;
            try {
                seconds = Integer.parseInt(retryAfter.trim());
            } catch (NumberFormatException ignored) {
                // http-date form or missing, the backoff covers it
            }
            this.retryAfter = Math.max(0, Math.min(seconds, 300));
        }
    }
}
