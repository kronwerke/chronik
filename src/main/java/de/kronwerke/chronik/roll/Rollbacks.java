package de.kronwerke.chronik.roll;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.ChronikConfig;
import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.db.Entry;
import de.kronwerke.chronik.db.Row;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Rollbacks and restores run on the server thread, a few milliseconds per tick, for as many
 * ticks as they need, so even a large one does not freeze the server. A rollback puts back what
 * was there before (blocks with their block entity, items taken out of containers), newest
 * change first; a restore applies the changes again, oldest first. Nothing they do is logged.
 */
public final class Rollbacks {

    /** One running rollback or restore. */
    private static final class Job {
        final List<Row> rows;
        final boolean restore;
        final Consumer<String> report;
        final String who;
        final List<Long> done = new ArrayList<>();
        int next;
        int changed, skipped;

        Job(List<Row> rows, boolean restore, Consumer<String> report, String who) {
            this.rows = rows;
            this.restore = restore;
            this.report = report;
            this.who = who;
        }
    }

    private static final List<Job> RUNNING = new ArrayList<>();
    /** Per command source: the last finished job, for undo. */
    private static final Map<String, Job> LAST = new HashMap<>();
    private static final Map<String, BlockState> STATES = new HashMap<>();

    private Rollbacks() {
    }

    public static void start(List<Row> rows, boolean restore, String who, Consumer<String> report) {
        List<Row> usable = new ArrayList<>();
        for (Row r : rows) {
            if (r.message()) continue;
            if (r.action() == Entry.KILL || r.action() == Entry.DROP || r.action() == Entry.PICKUP) continue;
            usable.add(r);
        }
        if (usable.isEmpty()) {
            report.accept("Nichts zum " + (restore ? "Wiederherstellen" : "Zurücksetzen") + " gefunden.");
            return;
        }
        RUNNING.add(new Job(usable, restore, report, who));
        report.accept((restore ? "Stelle " : "Setze ") + usable.size() + " Änderungen " + (restore ? "wieder her" : "zurück") + " ...");
    }

    /** Undoes the last rollback or restore of this source. */
    public static boolean undo(String who, Consumer<String> report) {
        Job last = LAST.remove(who);
        if (last == null) return false;
        List<Row> rows = new ArrayList<>(last.rows.subList(0, last.next));
        java.util.Collections.reverse(rows);
        RUNNING.add(new Job(rows, !last.restore, report, who + "#undo"));
        report.accept("Mache " + rows.size() + " Änderungen rückgängig ...");
        return true;
    }

    public static void stopAll() {
        for (Job j : RUNNING) finish(j, true);
        RUNNING.clear();
        STATES.clear();
    }

    public static void tick() {
        if (RUNNING.isEmpty()) return;
        MinecraftServer server = Chronik.server();
        if (server == null) return;
        long budget = (long) (ChronikConfig.ROLLBACK_MS_PER_TICK.get() * 1_000_000L);
        long start = System.nanoTime();
        Iterator<Job> it = RUNNING.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            while (j.next < j.rows.size() && System.nanoTime() - start < budget) {
                Row r = j.rows.get(j.next++);
                try {
                    if (apply(server, r, j.restore)) {
                        j.changed++;
                        j.done.add(r.id());
                    } else {
                        j.skipped++;
                    }
                } catch (RuntimeException e) {
                    j.skipped++;
                    Chronik.LOG.warn("Chronik could not {} entry {}: {}", j.restore ? "restore" : "roll back", r.id(), e.toString());
                }
            }
            if (j.next >= j.rows.size()) {
                finish(j, false);
                it.remove();
            }
            if (System.nanoTime() - start >= budget) break;
        }
    }

    private static void finish(Job j, boolean stopped) {
        Db db = Chronik.db();
        if (db != null) db.setRolled(j.done, !j.restore);
        if (!j.who.endsWith("#undo")) LAST.put(j.who, j);
        j.report.accept((stopped ? "Abgebrochen nach " : "Fertig: ") + j.changed + " Änderungen " + (j.restore ? "wiederhergestellt" : "zurückgesetzt")
                + (j.skipped > 0 ? ", " + j.skipped + " übersprungen" : "") + ".");
    }

    private static boolean apply(MinecraftServer server, Row r, boolean restore) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(r.world())));
        if (level == null) return false;
        BlockPos pos = new BlockPos(r.x(), r.y(), r.z());
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4); // loads it when needed
        if (r.action() == Entry.TAKE || r.action() == Entry.PUT) return container(level, pos, r, restore);
        BlockState target = state(level, restore ? r.b() : r.a());
        if (target == null) return false;
        level.setBlock(pos, target, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        if (!restore && r.data() != null) {
            CompoundTag tag = Db.unpack(r.data());
            BlockEntity be = level.getBlockEntity(pos);
            if (tag != null && be != null) {
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
                level.sendBlockUpdated(pos, target, target, Block.UPDATE_ALL);
            }
        }
        return true;
    }

    /** Items: a rollback puts taken items back and takes put items out again; a restore the other way round. */
    private static boolean container(ServerLevel level, BlockPos pos, Row r, boolean restore) {
        IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        CompoundTag tag = Db.unpack(r.data());
        if (h == null || tag == null) return false;
        Optional<ItemStack> parsed = ItemStack.parse(level.registryAccess(), tag);
        if (parsed.isEmpty()) return false;
        boolean putBack = (r.action() == Entry.TAKE) != restore;
        int left = r.amount();
        if (putBack) {
            ItemStack rest = ItemHandlerHelper.insertItemStacked(h, parsed.get().copyWithCount(left), false);
            return rest.getCount() < left;
        }
        for (int slot = 0; slot < h.getSlots() && left > 0; slot++) {
            if (ItemStack.isSameItemSameComponents(h.getStackInSlot(slot), parsed.get())) {
                left -= h.extractItem(slot, left, false).getCount();
            }
        }
        return left < r.amount();
    }

    private static BlockState state(Level level, String text) {
        if (text == null) return null;
        BlockState known = STATES.get(text);
        if (known != null) return known;
        try {
            BlockState s = BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), text, false).blockState();
            STATES.put(text, s);
            return s;
        } catch (CommandSyntaxException e) {
            return null;
        }
    }
}
