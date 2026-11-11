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
 * <p>
 * A block is only changed while it still is what the entry left behind: when someone built
 * something newer in its place, the newer work stays and the entry counts as skipped. A block
 * entity a rollback removes (a chest someone placed) is kept in its entry, so a restore brings
 * it back full. Neighbours are told about the changed blocks once the job is through, so doors,
 * beds and multiblocks form again from all their parts at once.
 */
public final class Rollbacks {

    /** One running rollback or restore. */
    private static final class Job {
        final List<Row> rows;
        final boolean restore;
        final Consumer<String> report;
        final String who;
        final List<Long> done = new ArrayList<>();
        final java.util.Set<BlockPos> touched = new java.util.LinkedHashSet<>();
        /** Block entities this job kept for rows that had none (placements it took back), by row id. */
        final Map<Long, byte[]> kept = new HashMap<>();
        final String worldOf;
        int next;
        int changed, skipped, newer;

        Job(List<Row> rows, boolean restore, Consumer<String> report, String who) {
            this.rows = rows;
            this.restore = restore;
            this.report = report;
            this.who = who;
            this.worldOf = rows.isEmpty() ? null : rows.get(0).world();
        }
    }

    private static final List<Job> RUNNING = new ArrayList<>();
    /** Per command source: the last finished job, for undo. */
    private static final Map<String, Job> LAST = new HashMap<>();
    private static final Map<String, BlockState> STATES = new HashMap<>();

    private Rollbacks() {
    }

    /** What a rollback or restore would do; kept until it is confirmed or another one is asked for. */
    public record Plan(List<Row> rows, boolean restore, int unrollable, String area) {
    }

    private static final Map<String, Plan> PENDING = new HashMap<>();

    /** Looks at the rows and keeps the plan for /co rb confirm; returns what to tell the player. */
    public static String plan(List<Row> rows, boolean restore, String who) {
        List<Row> usable = new ArrayList<>();
        int unrollable = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        java.util.Set<String> worlds = new java.util.TreeSet<>();
        for (Row r : rows) {
            if (r.message()) continue;
            if (r.action() == Entry.KILL || r.action() == Entry.DROP || r.action() == Entry.PICKUP) {
                unrollable++;
                continue;
            }
            usable.add(r);
            worlds.add(r.world());
            minX = Math.min(minX, r.x()); minY = Math.min(minY, r.y()); minZ = Math.min(minZ, r.z());
            maxX = Math.max(maxX, r.x()); maxY = Math.max(maxY, r.y()); maxZ = Math.max(maxZ, r.z());
        }
        String what = restore ? "Wiederherstellen" : "Zurücksetzen";
        String cannot = unrollable > 0 ? " " + unrollable + " Einträge sind Kills, Drops oder Aufsammeln, die lassen sich nicht " + (restore ? "wiederherstellen" : "zurücksetzen") + "." : "";
        if (usable.isEmpty()) {
            PENDING.remove(who);
            return "Nichts zum " + what + " gefunden." + cannot;
        }
        String area = String.join(", ", worlds) + " von " + minX + " " + minY + " " + minZ + " bis " + maxX + " " + maxY + " " + maxZ;
        PENDING.put(who, new Plan(usable, restore, unrollable, area));
        return usable.size() + " Änderungen zum " + what + ", " + area + "." + cannot
                + " Bestätigen mit /co " + (restore ? "rs" : "rb") + " confirm (60 Sekunden).";
    }

    private static final Map<String, Long> PLANNED_AT = new HashMap<>();

    public static void planned(String who) {
        PLANNED_AT.put(who, System.currentTimeMillis());
    }

    /** Starts the plan this source looked at last, when it is not older than a minute. */
    public static boolean confirm(String who, boolean restore, Consumer<String> report) {
        Plan p = PENDING.get(who);
        Long at = PLANNED_AT.get(who);
        if (p == null || p.restore() != restore || at == null || System.currentTimeMillis() - at > 60_000) return false;
        PENDING.remove(who);
        PLANNED_AT.remove(who);
        RUNNING.add(new Job(p.rows(), restore, report, who));
        report.accept((restore ? "Stelle " : "Setze ") + p.rows().size() + " Änderungen " + (restore ? "wieder her" : "zurück") + " ...");
        return true;
    }

    /** Undoes the last rollback or restore of this source. */
    public static boolean undo(String who, Consumer<String> report) {
        Job last = LAST.remove(who);
        if (last == null) return false;
        List<Row> rows = new ArrayList<>(last.rows.subList(0, last.next));
        java.util.Collections.reverse(rows);
        Job undo = new Job(rows, !last.restore, report, who + "#undo");
        undo.kept.putAll(last.kept);
        RUNNING.add(undo);
        report.accept("Mache " + rows.size() + " Änderungen rückgängig (nur bis zum nächsten Neustart möglich) ...");
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
                    int res = apply(server, r, j);
                    if (res == DONE) {
                        j.changed++;
                        j.done.add(r.id());
                    } else if (res == NEWER) {
                        j.newer++;
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

    private static final int DONE = 0, SKIPPED = 1, NEWER = 2;

    private static void finish(Job j, boolean stopped) {
        Db db = Chronik.db();
        if (db != null) db.setRolled(j.done, !j.restore);
        if (!j.who.endsWith("#undo")) LAST.put(j.who, j);
        // the neighbours learn about every changed block once all parts are back
        MinecraftServer server = Chronik.server();
        if (server != null) {
            for (Row r : j.rows.subList(0, j.next)) {
                BlockPos pos = new BlockPos(r.x(), r.y(), r.z());
                if (!j.touched.remove(pos)) continue;
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(r.world())));
                if (level != null && level.isLoaded(pos)) level.blockUpdated(pos, level.getBlockState(pos).getBlock());
            }
        }
        j.report.accept((stopped ? "Abgebrochen nach " : "Fertig: ") + j.changed + " Änderungen " + (j.restore ? "wiederhergestellt" : "zurückgesetzt")
                + (j.newer > 0 ? ", " + j.newer + " gelassen, weil dort inzwischen etwas Neueres steht" : "")
                + (j.skipped > 0 ? ", " + j.skipped + " übersprungen (Welt, Block oder Behälter nicht mehr da)" : "") + ".");
    }

    private static int apply(MinecraftServer server, Row r, Job j) {
        boolean restore = j.restore;
        byte[] data = r.data() != null ? r.data() : j.kept.get(r.id());
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(r.world())));
        if (level == null) return SKIPPED;
        BlockPos pos = new BlockPos(r.x(), r.y(), r.z());
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4); // loads it when needed
        if (r.action() == Entry.TAKE || r.action() == Entry.PUT) return container(level, pos, r, restore) ? DONE : SKIPPED;
        BlockState target = state(level, restore ? r.b() : r.a());
        BlockState expected = state(level, restore ? r.a() : r.b());
        if (target == null) return SKIPPED;
        BlockState now = level.getBlockState(pos);
        // only what the entry left behind is changed: newer work in the same place stays
        if (expected != null && now != expected && !(now.isAir() && expected.isAir())) return NEWER;
        BlockEntity old = level.getBlockEntity(pos);
        if (old != null && !restore && data == null && r.action() == Entry.PLACE) {
            // a block entity this rollback removes: kept in the entry for a restore
            try {
                byte[] kept = Db.pack(old.saveWithFullMetadata(level.registryAccess()));
                j.kept.put(r.id(), kept);
                Db db = Chronik.db();
                if (db != null) db.setData(r.id(), kept);
            } catch (java.io.IOException | RuntimeException e) {
                Chronik.LOG.warn("Chronik could not keep the block entity at {}: {}", pos, e.toString());
            }
        }
        level.setBlock(pos, target, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        j.touched.add(pos);
        // the block entity of the state put back: the data of a placement is what stood there
        // after it (kept by a rollback), of every other entry what stood there before
        boolean dataIsAfter = r.action() == Entry.PLACE;
        if (data != null && !target.isAir() && dataIsAfter == restore) {
            CompoundTag tag = Db.unpack(data);
            BlockEntity be = level.getBlockEntity(pos);
            if (tag != null && be != null) {
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
                level.sendBlockUpdated(pos, target, target, Block.UPDATE_ALL);
            }
        }
        return DONE;
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
