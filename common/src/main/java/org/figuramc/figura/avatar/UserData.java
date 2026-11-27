package org.figuramc.figura.avatar;

import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.local.CacheAvatarLoader;
import org.figuramc.figura.backend2.NetworkStuff;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
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

    // the host avatar is always wanted, the others once a render path asked for them
    private boolean isWanted() {
        return FiguraMod.isLocal(id) || lastWanted >= 0;
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

    // while an override is active this is the copy of the source's avatar (null until it is built), see AvatarManager.setAvatarOverride
    public Avatar getMainAvatar() {
        return overrideSource != null ? overrideAvatar : avatars.peek();
    }

    // the user's own avatar, ignoring any override
    public Avatar getOwnAvatar() {
        return avatars.peek();
    }

    // -- avatar override -- //
    // kept apart from the own avatars: backend refreshes (loadData, clear) keep loading the own avatar in the
    // background without touching the override, and it is shown again as soon as the override ends

    private volatile UUID overrideSource;
    private volatile Avatar overrideAvatar;
    // the source avatar the copy was built from, to rebuild the copy when the source changes its avatar
    private Avatar overrideBuiltFrom;

    public UUID getOverrideSource() {
        return overrideSource;
    }

    // the override methods are only called on the client thread (AvatarManager), so they don't lock: they must not take
    // this object's monitor, which the backend thread holds in loadData while it loads avatars (deadlock)
    // other threads only read the volatile fields through getMainAvatar

    void setOverride(UUID source) {
        if (Objects.equals(source, overrideSource))
            return;
        cleanOverrideAvatar();
        overrideSource = source;
    }

    // builds (or rebuilds) the copy from the source's current own avatar; does nothing if it is already built from it
    void updateOverride(Avatar sourceAvatar) {
        UUID source = overrideSource;
        if (source == null || sourceAvatar == null || sourceAvatar.nbt == null || sourceAvatar == overrideBuiltFrom)
            return;
        cleanOverrideAvatar();
        // marked as built first, so a copy that fails to load is not retried every tick
        overrideBuiltFrom = sourceAvatar;
        Avatar copy = Avatar.overrideCopy(id, source);
        try {
            copy.load(sourceAvatar.nbt);
        } catch (Exception e) {
            copy.clean();
            throw e;
        }
        overrideAvatar = copy;
    }

    void cleanOverrideAvatar() {
        Avatar copy = overrideAvatar;
        overrideAvatar = null;
        overrideBuiltFrom = null;
        if (copy != null)
            copy.clean();
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
