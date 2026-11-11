package de.kronwerke.chronik.db;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * The log in one SQLite file. The server thread only ever puts entries into a queue
 * ({@link #add}); one writer thread turns them into rows, a few thousand per transaction, and a
 * second thread answers lookups. Nothing here waits on the server thread, and the server thread
 * never waits on the disk. If the writer falls far behind, new entries are dropped and counted
 * instead of filling the memory.
 */
public final class Db {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int QUEUE = 1_000_000;
    private static final int BATCH = 5_000;

    /** Kinds of names: who acted, through what, which world, which block state, which item or entity. */
    static final int ACTOR = 0, CAUSE = 1, WORLD = 2, STATE = 3, ITEM = 4, ENTITY = 5;

    private final Path file;
    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(QUEUE);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> daemon(r, "Chronik reader"));
    /** Work that needs the write connection (marking rollbacks, purging), done by the writer between batches. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<String, Integer> nameIds = new HashMap<>();
    private final Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();
    private Connection write;
    private Connection read;
    private Thread writer;
    private volatile boolean running;

    public Db(Path file) {
        this.file = file;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ---- lifecycle ----

    public void open() throws SQLException, IOException {
        Files.createDirectories(file.getParent());
        boolean fresh = !Files.exists(file);
        write = connect();
        try (Statement s = write.createStatement()) {
            // freed pages go back to the file system bit by bit after each purge; a file of
            // Chronik 1.0.0 is converted once while it is still small
            if (fresh) {
                s.execute("PRAGMA auto_vacuum=INCREMENTAL");
            } else if (Files.size(file) < 256L * 1024 * 1024) {
                try (ResultSet r = s.executeQuery("PRAGMA auto_vacuum")) {
                    if (r.next() && r.getInt(1) == 0) {
                        s.execute("PRAGMA auto_vacuum=INCREMENTAL");
                        s.execute("VACUUM");
                        LOG.info("Chronik converted {} to incremental vacuum", file);
                    }
                }
            }
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS names (
                      id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, key TEXT NOT NULL, label TEXT,
                      UNIQUE(kind, key))""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS actions (
                      id INTEGER PRIMARY KEY, t INTEGER NOT NULL, action INTEGER NOT NULL,
                      world INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      actor INTEGER NOT NULL, cause INTEGER,
                      a INTEGER, b INTEGER, amount INTEGER NOT NULL DEFAULT 0, data BLOB,
                      rolled INTEGER NOT NULL DEFAULT 0)""");
            s.execute("CREATE INDEX IF NOT EXISTS actions_pos ON actions(world, x, z, y, t)");
            s.execute("CREATE INDEX IF NOT EXISTS actions_actor ON actions(actor, t)");
            s.execute("CREATE INDEX IF NOT EXISTS actions_t ON actions(t)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                      id INTEGER PRIMARY KEY, t INTEGER NOT NULL, kind INTEGER NOT NULL,
                      world INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      actor INTEGER NOT NULL, text TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS messages_actor ON messages(actor, t)");
            s.execute("CREATE INDEX IF NOT EXISTS messages_t ON messages(t)");
        }
        try (Statement s = write.createStatement(); ResultSet r = s.executeQuery("SELECT id, kind, key, label FROM names")) {
            while (r.next()) {
                nameIds.put(r.getInt(2) + ":" + r.getString(3), r.getInt(1));
                if (r.getInt(2) == ACTOR && r.getString(4) != null) labels.put(r.getString(3), r.getString(4));
            }
        }
        read = connect();
        try (Statement s = read.createStatement()) {
            s.execute("PRAGMA query_only=1");
        }
        running = true;
        writer = daemon(this::writeLoop, "Chronik writer");
        writer.start();
    }

    private Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("busy_timeout", "10000");
        p.setProperty("cache_size", "-32000");
        p.setProperty("temp_store", "MEMORY");
        // the driver is created directly: DriverManager does not see drivers inside mod jars
        Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), p);
        if (c == null) throw new SQLException("SQLite driver refused " + file);
        return c;
    }

    /** Writes what is still queued and closes. Called once when the server stops. */
    public void close() {
        running = false;
        if (writer != null) {
            writer.interrupt();
            try {
                writer.join(30_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        reader.shutdown();
        try {
            reader.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(read);
        closeQuietly(write);
    }

    private static void closeQuietly(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException ignored) {
        }
    }

    // ---- writing ----

    /** From the server thread: never blocks. */
    public void add(Entry e) {
        if (de.kronwerke.chronik.track.Track.skipped(e.world) || de.kronwerke.chronik.track.Actors.bot(e.actor)) return;
        if (!running || !queue.offer(e)) dropped.incrementAndGet();
    }

    /** A player's current name, shown instead of their UUID. */
    public void name(String actorKey, String label) {
        if (label == null || label.equals(labels.get(actorKey))) return;
        labels.put(actorKey, label);
        add(Entry.message(-1, "", 0, 0, 0, actorKey, label));
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    public int queued() {
        return queue.size();
    }

    public long fileSize() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private void writeLoop() {
        List<Entry> batch = new ArrayList<>(BATCH);
        while (running || !queue.isEmpty()) {
            try {
                runTasks();
                Entry first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                writeBatch(batch);
            } catch (InterruptedException e) {
                // stopping: loop once more to empty the queue
                if (!running) {
                    queue.drainTo(batch);
                    if (!batch.isEmpty()) writeBatch(batch);
                    break;
                }
            } catch (RuntimeException e) {
                LOG.error("Chronik could not write {} entries", batch.size(), e);
            } finally {
                batch.clear();
            }
        }
    }

    /** Runs the tasks queued so far; a task that queues its next step runs it after the next batch of entries. */
    private void runTasks() {
        int n = tasks.size();
        Runnable t;
        while (n-- > 0 && (t = tasks.poll()) != null) {
            try {
                t.run();
            } catch (RuntimeException e) {
                LOG.error("Chronik task failed", e);
            }
        }
    }

    private void writeBatch(List<Entry> batch) {
        try {
            write.setAutoCommit(false);
            try (PreparedStatement act = write.prepareStatement(
                    "INSERT INTO actions (t, action, world, x, y, z, actor, cause, a, b, amount, data) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement msg = write.prepareStatement(
                         "INSERT INTO messages (t, kind, world, x, y, z, actor, text) VALUES (?,?,?,?,?,?,?,?)");
                 PreparedStatement label = write.prepareStatement("UPDATE names SET label = ? WHERE kind = 0 AND key = ?")) {
                for (Entry e : batch) {
                    if (e.action == -1) { // a player's name
                        id(ACTOR, e.actor, e.text);
                        label.setString(1, e.text);
                        label.setString(2, e.actor);
                        label.addBatch();
                        continue;
                    }
                    if (e.isMessage()) {
                        msg.setLong(1, e.time);
                        msg.setInt(2, e.action);
                        msg.setInt(3, id(WORLD, e.world, null));
                        msg.setInt(4, e.x);
                        msg.setInt(5, e.y);
                        msg.setInt(6, e.z);
                        msg.setInt(7, id(ACTOR, e.actor, null));
                        msg.setString(8, e.text);
                        msg.addBatch();
                        continue;
                    }
                    act.setLong(1, e.time);
                    act.setInt(2, e.action);
                    act.setInt(3, id(WORLD, e.world, null));
                    act.setInt(4, e.x);
                    act.setInt(5, e.y);
                    act.setInt(6, e.z);
                    act.setInt(7, id(ACTOR, e.actor, null));
                    if (e.cause == null) act.setNull(8, java.sql.Types.INTEGER);
                    else act.setInt(8, id(CAUSE, e.cause, null));
                    byte[] data = null;
                    if (e.before != null || e.after != null) {
                        act.setInt(9, id(STATE, BlockStateParser.serialize(e.before), null));
                        act.setInt(10, id(STATE, BlockStateParser.serialize(e.after), null));
                        if (e.blockEntity != null) data = pack(e.blockEntity);
                    } else if (e.item != null) {
                        act.setInt(9, id(ITEM, BuiltInRegistries.ITEM.getKey(e.item.getItem()).toString(), null));
                        act.setNull(10, java.sql.Types.INTEGER);
                        if (e.itemTag != null) data = pack(e.itemTag);
                    } else if (e.text != null) {
                        act.setInt(9, id(ENTITY, e.text, null));
                        act.setNull(10, java.sql.Types.INTEGER);
                    } else {
                        act.setNull(9, java.sql.Types.INTEGER);
                        act.setNull(10, java.sql.Types.INTEGER);
                    }
                    act.setInt(11, e.amount);
                    act.setBytes(12, data);
                    act.addBatch();
                }
                act.executeBatch();
                msg.executeBatch();
                label.executeBatch();
            }
            write.commit();
            written.addAndGet(batch.size());
        } catch (SQLException | IOException e) {
            LOG.error("Chronik could not write {} entries", batch.size(), e);
            try {
                write.rollback();
            } catch (SQLException ignored) {
            }
        } finally {
            try {
                write.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    /** The id of a name, created on first use. Only the writer thread calls this. */
    private int id(int kind, String key, String label) throws SQLException {
        String k = kind + ":" + key;
        Integer known = nameIds.get(k);
        if (known != null) return known;
        try (PreparedStatement p = write.prepareStatement("INSERT INTO names (kind, key, label) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setInt(1, kind);
            p.setString(2, key);
            p.setString(3, label);
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                int id = r.getInt(1);
                nameIds.put(k, id);
                return id;
            }
        }
    }

    public static byte[] pack(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    public static CompoundTag unpack(byte[] data) {
        if (data == null) return null;
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            return null;
        }
    }

    /** The shown name of an actor key (a player's name for their UUID), the key itself otherwise. */
    public String label(String actorKey) {
        if (actorKey == null) return "?";
        String l = labels.get(actorKey);
        return l != null ? l : actorKey;
    }

    /** The actor key of a player name seen before, or null. */
    public String actorByName(String name) {
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    // ---- reading ----

    public CompletableFuture<List<Row>> lookup(Filter f) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Lookup.run(read, f);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, reader);
    }

    /** Marks rows as rolled back (or not), after a rollback or restore went through. */
    public void setRolled(List<Long> ids, boolean rolled) {
        if (ids.isEmpty()) return;
        List<Long> copy = List.copyOf(ids);
        tasks.add(() -> {
            try {
                write.setAutoCommit(false);
                try (PreparedStatement p = write.prepareStatement("UPDATE actions SET rolled = ? WHERE id = ?")) {
                    for (long id : copy) {
                        p.setInt(1, rolled ? 1 : 0);
                        p.setLong(2, id);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                write.commit();
            } catch (SQLException e) {
                LOG.error("Chronik could not mark {} rows", copy.size(), e);
            } finally {
                try {
                    write.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        });
    }

    /**
     * A consistent copy of the whole log for a backup: VACUUM INTO on the writer thread, so no
     * entry is half written, then an empty marker file says it is done. The launcher waits for
     * the marker and zips the copy instead of the live file.
     */
    public void snapshot(Path target, Path done) {
        tasks.add(() -> {
            try {
                Files.deleteIfExists(target);
                try (PreparedStatement p = write.prepareStatement("VACUUM INTO ?")) {
                    p.setString(1, target.toAbsolutePath().toString());
                    p.execute();
                }
                Files.writeString(done, "");
            } catch (SQLException | IOException e) {
                LOG.error("Chronik could not write a snapshot to {}", target, e);
            }
        });
    }

    /** Writes a block entity a rollback is about to remove into its row, so a restore brings it back with its contents. */
    public void setData(long id, byte[] data) {
        tasks.add(() -> {
            try (PreparedStatement p = write.prepareStatement("UPDATE actions SET data = ? WHERE id = ?")) {
                p.setBytes(1, data);
                p.setLong(2, id);
                p.executeUpdate();
            } catch (SQLException e) {
                LOG.error("Chronik could not keep the block entity of row {}", id, e);
            }
        });
    }

    /**
     * Deletes everything older than the cutoff, 5,000 rows per step. Each step is its own task,
     * so the writer stores a batch of new entries between two steps and the queue does not fill
     * up while millions of old rows go. Afterwards the freed pages are given back to the file
     * system, again in steps.
     */
    public CompletableFuture<Long> purge(long olderThan) {
        CompletableFuture<Long> done = new CompletableFuture<>();
        long[] total = {0};
        String[] sql = {"DELETE FROM actions WHERE id IN (SELECT id FROM actions WHERE t < ? LIMIT 5000)",
                "DELETE FROM messages WHERE id IN (SELECT id FROM messages WHERE t < ? LIMIT 5000)"};
        Runnable[] step = new Runnable[1];
        int[] table = {0};
        step[0] = () -> {
            try (PreparedStatement p = write.prepareStatement(sql[table[0]])) {
                p.setLong(1, olderThan);
                int n = p.executeUpdate();
                total[0] += n;
                if (n == 0) table[0]++;
                if (table[0] < sql.length) {
                    tasks.add(step[0]);
                } else {
                    done.complete(total[0]);
                    if (total[0] > 0) tasks.add(this::vacuumStep);
                }
            } catch (SQLException e) {
                LOG.error("Chronik purge failed", e);
                done.completeExceptionally(e);
            }
        };
        tasks.add(step[0]);
        return done;
    }

    /** Gives up to 2,000 free pages back to the file system; again until none are left. */
    private void vacuumStep() {
        try (Statement s = write.createStatement()) {
            try (ResultSet r = s.executeQuery("PRAGMA auto_vacuum")) {
                if (!r.next() || r.getInt(1) != 2) return; // a file made before 1.1.0 has no incremental vacuum
            }
            int free;
            try (ResultSet r = s.executeQuery("PRAGMA freelist_count")) {
                free = r.next() ? r.getInt(1) : 0;
            }
            if (free <= 0) return;
            s.execute("PRAGMA incremental_vacuum(2000)");
            if (free > 2000) tasks.add(this::vacuumStep);
        } catch (SQLException e) {
            LOG.warn("Chronik could not shrink its file: {}", e.toString());
        }
    }
}
