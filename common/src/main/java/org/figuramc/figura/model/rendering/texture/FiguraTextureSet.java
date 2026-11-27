package org.figuramc.figura.model.rendering.texture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.figuramc.figura.mixin.render.layers.elytra.ElytraLayerAccessor;
import org.figuramc.figura.model.TextureCustomization;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.UUID;

public class FiguraTextureSet {

    public final String name;
    public final FiguraTexture[] textures = new FiguraTexture[4];

    public FiguraTextureSet(String name, FiguraTexture mainData, FiguraTexture emissiveData, FiguraTexture specularData, FiguraTexture normalData) {
        this.name = name;
        textures[0] = mainData;
        textures[1] = emissiveData;
        textures[2] = specularData;
        textures[3] = normalData;
    }

    public void clean() {
        for (FiguraTexture texture : textures) {
            if (texture != null)
                texture.close();
        }
    }

    public void uploadIfNeeded() {
        for (FiguraTexture texture : textures) {
            if (texture != null)
                texture.uploadIfDirty();
        }
    }

    public int getWidth() {
        for (FiguraTexture texture : textures) {
            if (texture != null)
                return texture.getWidth();
        }
        return -1;
    }

    public int getHeight() {
        for (FiguraTexture texture : textures) {
            if (texture != null)
                return texture.getHeight();
        }
        return -1;
    }

    // the skin, cape and elytra textures of a player now, or null when unknown; read on the render thread (looking them up can
    // start loading them), for model walks on the workers (getOverrideTexture with player textures)
    public static ResourceLocation[] playerTextures(UUID owner) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(owner);
        if (info == null)
            return null;
        return new ResourceLocation[] {
                info.getSkinLocation(),
                info.getCapeLocation(),
                info.getElytraLocation() == null ? ElytraLayerAccessor.getWingsLocation() : info.getElytraLocation()
        };
    }

    public ResourceLocation getOverrideTexture(UUID owner, TextureCustomization pair) {
        return getOverrideTexture(owner, pair, false, null);
    }

    // known: the player textures were looked up before (playerTextures, may be null), instead of looking them up now
    public ResourceLocation getOverrideTexture(UUID owner, TextureCustomization pair, boolean known, ResourceLocation[] player) {
        OverrideType type;

        if (pair == null || (type = pair.getOverrideType()) == null)
            return null;

        return switch (type) {
            case SKIN, CAPE, ELYTRA -> {
                if (known) {
                    if (player == null)
                        yield null;
                    yield player[type == OverrideType.SKIN ? 0 : type == OverrideType.CAPE ? 1 : 2];
                }

                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null)
                    yield null;

                PlayerInfo info = connection.getPlayerInfo(owner);
                if (info == null)
                    yield null;

                yield switch (type) {
                    case CAPE -> info.getCapeLocation();
                    case ELYTRA -> info.getElytraLocation() == null ? ElytraLayerAccessor.getWingsLocation() : info.getElytraLocation();
                    default -> info.getSkinLocation();
                };
            }
            case RESOURCE -> {
                try {
                    yield new ResourceLocation(String.valueOf(pair.getValue()));
                } catch (Exception ignored) {
                    yield MissingTextureAtlasSprite.getLocation();
                }
            }
            case PRIMARY -> textures[0] == null ? null : textures[0].getRawLocation();
            case SECONDARY -> textures[1] == null ? null : textures[1].getRawLocation();
            case SPECULAR -> textures[2] == null ? null : textures[2].getRawLocation();
            case NORMAL -> textures[3] == null ? null : textures[3].getRawLocation();
            case CUSTOM -> {
                try {
                    yield ((FiguraTexture) pair.getValue()).getRawLocation();
                } catch (Exception ignored) {
                    yield MissingTextureAtlasSprite.getLocation();
                }
            }
        };
    }

    // the avatar texture a customization draws with, null for skins, capes, elytras and resource textures
    public FiguraTexture getFiguraTexture(TextureCustomization pair) {
        OverrideType type;
        if (pair == null || (type = pair.getOverrideType()) == null)
            return null;
        return switch (type) {
            case PRIMARY -> textures[0];
            case SECONDARY -> textures[1];
            case SPECULAR -> textures[2];
            case NORMAL -> textures[3];
            case CUSTOM -> pair.getValue() instanceof FiguraTexture t ? t : null;
            default -> null;
        };
    }

    public enum OverrideType {
        SKIN,
        CAPE,
        ELYTRA,
        RESOURCE(String.class, "String"),
        PRIMARY,
        SECONDARY,
        SPECULAR,
        NORMAL,
        CUSTOM(FiguraTexture.class, "Texture");

        public final @Nullable Type argumentType;
        public final @Nullable String typeName;

        OverrideType() {
            argumentType = null;
            typeName = null;
        }

        OverrideType(@Nullable Type argumentType, String typeName) {
            this.argumentType = argumentType;
            this.typeName = typeName;
        }
    }
}
