package de.kronwerke.chronik.track;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.ChronikConfig;
import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.db.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Turns what the hooks see into log entries. Server thread only. */
public final class Track {

    private static final Map<ResourceKey<Level>, String> WORLDS = new ConcurrentHashMap<>();

    private Track() {
    }

    public static String world(Level level) {
        return WORLDS.computeIfAbsent(level.dimension(), k -> k.location().toString());
    }

    private static java.util.List<? extends String> skipFrom, quietFrom;
    private static java.util.Set<String> skip = java.util.Set.of();
    private static java.util.List<TagKey<Block>> quietTags = java.util.List.of();
    private static java.util.Set<Block> quietIds = java.util.Set.of();

    /** Whether a world is left out of the log (log.skipWorlds). */
    public static boolean skipped(String world) {
        java.util.List<? extends String> l = ChronikConfig.SPEC.isLoaded() ? ChronikConfig.SKIP_WORLDS.get() : java.util.List.of();
        if (l != skipFrom) {
            skip = java.util.Set.copyOf(l);
            skipFrom = l;
        }
        return !skip.isEmpty() && skip.contains(world);
    }

    /** Natural ground a machine digs: log.quietBlocks. */
    private static boolean quiet(BlockState s) {
        java.util.List<? extends String> l = ChronikConfig.QUIET_BLOCKS.get();
        if (l != quietFrom) {
            java.util.List<TagKey<Block>> tags = new java.util.ArrayList<>();
            java.util.Set<Block> ids = new java.util.HashSet<>();
            for (String e : l) {
                if (e.startsWith("#")) {
                    ResourceLocation rl = ResourceLocation.tryParse(e.substring(1));
                    if (rl != null) tags.add(TagKey.create(Registries.BLOCK, rl));
                } else {
                    ResourceLocation rl = ResourceLocation.tryParse(e);
                    if (rl != null) BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(ids::add);
                }
            }
            quietTags = tags;
            quietIds = ids;
            quietFrom = l;
        }
        if (quietIds.contains(s.getBlock())) return true;
        for (TagKey<Block> t : quietTags) if (s.is(t)) return true;
        return false;
    }

    /**
     * A block changed while someone acted. Placing, breaking and replacing are logged for every
     * actor; a change of the same block (a door opened, a lever flipped, a repeater set) only
     * when a player did it directly, so redstone and machines do not flood the log.
     */
    public static void block(Level level, BlockPos pos, BlockState before, BlockState after, CompoundTag blockEntity, String actor, String cause) {
        Db db = Chronik.db();
        if (db == null || before == after) return;
        boolean person = Actors.isPlayer(actor);
        if (!person && !ChronikConfig.LOG_MOBS.get()) return;
        int action;
        if (before.isAir() && !after.isAir()) action = Entry.PLACE;
        else if (after.isAir()) action = Entry.BREAK;
        else if (before.getBlock() != after.getBlock()) action = Entry.CHANGE;
        else if (person && cause == null && interactive(after)) action = Entry.CHANGE;
        else return;
        // a quarry or a miner in the ground: what it digs is no one's work
        if (action == Entry.BREAK && cause != null && quiet(before)) return;
        db.add(Entry.block(action, world(level), pos.getX(), pos.getY(), pos.getZ(), actor, cause, before, after, blockEntity));
    }

    /** Blocks whose state a player changes on purpose, worth a line in the log. */
    private static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.BUTTONS)
                || b instanceof LeverBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof NoteBlock
                || b instanceof DaylightDetectorBlock;
    }
}
