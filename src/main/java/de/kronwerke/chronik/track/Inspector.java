package de.kronwerke.chronik.track;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Who has the inspector on, and which block each player last right clicked. */
public final class Inspector {

    private static final Set<UUID> ON = new HashSet<>();
    private static final Map<UUID, long[]> LAST_USE = new HashMap<>();

    private Inspector() {
    }

    public static boolean toggle(ServerPlayer p) {
        if (ON.remove(p.getUUID())) return false;
        ON.add(p.getUUID());
        return true;
    }

    public static boolean on(ServerPlayer p) {
        return ON.contains(p.getUUID());
    }

    public static void lastUsed(ServerPlayer p, BlockPos pos) {
        LAST_USE.put(p.getUUID(), new long[]{pos.asLong(), p.serverLevel().getGameTime()});
    }

    /** The block the player right clicked within the last two seconds, the container they opened. */
    public static BlockPos recentUse(ServerPlayer p) {
        long[] u = LAST_USE.get(p.getUUID());
        if (u == null || p.serverLevel().getGameTime() - u[1] > 40) return null;
        return BlockPos.of(u[0]);
    }

    public static void left(ServerPlayer p) {
        ON.remove(p.getUUID());
        LAST_USE.remove(p.getUUID());
    }
}
