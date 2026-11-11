package de.kronwerke.chronik.track;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Turns things into actor keys: a player's UUID, or "#" and a type id for everything else. */
public final class Actors {

    private static final Map<EntityType<?>, String> ENTITY_KEYS = new ConcurrentHashMap<>();
    private static final Map<BlockEntityType<?>, String> BLOCK_ENTITY_KEYS = new ConcurrentHashMap<>();

    private Actors() {
    }

    public static String of(Entity e) {
        if (e == null) return null;
        return ((Owned) e).chronik$actor();
    }

    /** Test players of a load test (Kronwerke Core's LoadBot): what they do is not logged. */
    private static final java.util.Set<String> BOTS = ConcurrentHashMap.newKeySet();

    public static void joined(net.minecraft.server.level.ServerPlayer p) {
        if (p.getClass().getSimpleName().equals("LoadBot")) BOTS.add(p.getUUID().toString());
    }

    public static void left(net.minecraft.server.level.ServerPlayer p) {
        BOTS.remove(p.getUUID().toString());
    }

    public static boolean bot(String actorKey) {
        return actorKey != null && !BOTS.isEmpty() && BOTS.contains(actorKey);
    }

    /** Whether the key is a person (a UUID), the only kind of actor that can own things. */
    public static boolean isPlayer(String actorKey) {
        return actorKey != null && !actorKey.isEmpty() && actorKey.charAt(0) != '#';
    }

    public static String type(EntityType<?> t) {
        return ENTITY_KEYS.computeIfAbsent(t, k -> BuiltInRegistries.ENTITY_TYPE.getKey(k).toString());
    }

    public static String type(BlockEntityType<?> t) {
        return BLOCK_ENTITY_KEYS.computeIfAbsent(t, k -> {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(k);
            return id == null ? "unknown" : id.toString();
        });
    }
}
