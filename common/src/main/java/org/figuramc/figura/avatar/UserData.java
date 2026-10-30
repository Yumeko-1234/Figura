package org.figuramc.figura.avatar;

import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.local.CacheAvatarLoader;
import org.figuramc.figura.backend2.NetworkStuff;
import org.figuramc.figura.config.Configs;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class UserData {

    public final UUID id;
    private final Queue<Avatar> avatars = new ConcurrentLinkedQueue<>();
    private Pair<BitSet, BitSet> badges;

    // the avatars the backend lists for this user (hash -> (id, owner)), only loaded once something that renders them asks
    // so the tab list, chat and badges of the whole server don't load every avatar on it
    private volatile List<Pair<String, Pair<String, UUID>>> available = List.of();
    private boolean loading;
    // last tick a render path asked for the avatar, -1 before the first time, see want()
    public volatile int lastWanted = -1;

    public UserData(UUID id) {
        this.id = id;
    }

    public void loadData(ArrayList<Pair<String, Pair<String, UUID>>> avatars, Pair<BitSet,BitSet> badges) {
        loadBadges(badges);
        clear();
        synchronized (this) {
            available = new ArrayList<>(avatars);
            if (isWanted())
                loadAvailable();
        }
    }

    // the host avatar is always wanted, the others while a render path asked for them recently
    private boolean isWanted() {
        if (FiguraMod.isLocal(id))
            return true;
        if (lastWanted < 0)
            return false;
        int limit = Configs.UNLOAD_UNUSED_AVATARS.value * 20;
        return limit <= 0 || FiguraMod.ticks - lastWanted < limit;
    }

    // starts loading the listed avatars, from the disk cache when possible
    private void loadAvailable() {
        if (loading)
            return;
        loading = true;
        for (Pair<String, Pair<String, UUID>> avatar : available) {
            if (!CacheAvatarLoader.checkAndLoad(avatar.getFirst(), this)) {
                Pair<String, UUID> pair = avatar.getSecond();
                NetworkStuff.getAvatar(this, pair.getSecond(), pair.getFirst(), avatar.getFirst());
            }
        }
    }

    /**
     * Called by everything that would render the avatar: loads it if it only was listed so far, and keeps it loaded
     */
    public void want() {
        lastWanted = FiguraMod.ticks;
        if (loading || available.isEmpty())
            return;
        synchronized (this) {
            loadAvailable();
        }
    }

    public void loadAvatar(CompoundTag nbt) {
        FiguraMod.debug("--- avatar loading: " + id + " ---");
        Avatar avatar = new Avatar(id);
        this.avatars.add(avatar);
        avatar.load(nbt);
        FiguraMod.debug("--- loaded " + id + " ---");
    }

    public void loadBadges(Pair<BitSet, BitSet> pair) {
        this.badges = pair;
    }

    public Pair<BitSet, BitSet> getBadges() {
        return badges;
    }

    public Queue<Avatar> getAvatars() {
        return avatars;
    }

    public Avatar getMainAvatar() {
        return avatars.peek();
    }

    public boolean hasAvatars() {
        return !avatars.isEmpty();
    }

    // frees the loaded avatars, they load again from the listing (and the disk cache) the next time they are wanted
    public synchronized void unloadAvatars() {
        for (Avatar avatar : avatars)
            avatar.clean();
        avatars.clear();
        loading = false;
    }

    public void clear() {
        unloadAvatars();
        available = List.of();
    }
}
