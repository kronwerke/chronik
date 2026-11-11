package de.kronwerke.chronik.cmd;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.ChronikConfig;
import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.db.Filter;
import de.kronwerke.chronik.db.Row;
import de.kronwerke.chronik.roll.Rollbacks;
import de.kronwerke.chronik.track.Inspector;
import de.kronwerke.chronik.track.Track;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/** /chronik (also /co): inspect, lookup, near, page, rollback, restore, undo, purge, status. */
public final class ChronikCommand {

    private static final int PAGE = 8;
    /** Last lookup per source, for /chronik page. */
    private static final Map<String, List<Row>> RESULTS = new HashMap<>();

    private ChronikCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralCommandNode<CommandSourceStack> root = d.register(build("chronik"));
        d.register(Commands.literal("co").requires(s -> s.hasPermission(ChronikConfig.INSPECT_LEVEL.get())).redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name).requires(s -> s.hasPermission(ChronikConfig.INSPECT_LEVEL.get()))
                .executes(ChronikCommand::help)
                .then(Commands.literal("inspect").executes(ChronikCommand::inspect))
                .then(Commands.literal("i").executes(ChronikCommand::inspect))
                .then(Commands.literal("lookup").then(Commands.argument("params", StringArgumentType.greedyString()).executes(c -> lookup(c, false))))
                .then(Commands.literal("l").then(Commands.argument("params", StringArgumentType.greedyString()).executes(c -> lookup(c, false))))
                .then(Commands.literal("near").executes(c -> near(c, 5))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 100)).executes(c -> near(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> page(c, IntegerArgumentType.getInteger(c, "page")))))
                .then(Commands.literal("rollback").requires(s -> s.hasPermission(ChronikConfig.ROLLBACK_LEVEL.get()))
                        .then(Commands.argument("params", StringArgumentType.greedyString()).executes(c -> roll(c, false))))
                .then(Commands.literal("rb").requires(s -> s.hasPermission(ChronikConfig.ROLLBACK_LEVEL.get()))
                        .then(Commands.argument("params", StringArgumentType.greedyString()).executes(c -> roll(c, false))))
                .then(Commands.literal("restore").requires(s -> s.hasPermission(ChronikConfig.ROLLBACK_LEVEL.get()))
                        .then(Commands.argument("params", StringArgumentType.greedyString()).executes(c -> roll(c, true))))
                .then(Commands.literal("rs").requires(s -> s.hasPermission(ChronikConfig.ROLLBACK_LEVEL.get()))
                        .then(Commands.argument("params", StringArgumentType.greedyString()).executes(c -> roll(c, true))))
                .then(Commands.literal("undo").requires(s -> s.hasPermission(ChronikConfig.ROLLBACK_LEVEL.get())).executes(ChronikCommand::undo))
                .then(Commands.literal("purge").requires(s -> s.hasPermission(4))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1)).executes(ChronikCommand::purge)))
                .then(Commands.literal("status").executes(ChronikCommand::status))
                .then(Commands.literal("snapshot").requires(s -> s.hasPermission(4)).executes(ChronikCommand::snapshot));
    }

    private static int help(CommandContext<CommandSourceStack> c) {
        String[][] lines = {
                {"/co i", "Inspektor an/aus: Linksklick zeigt den Block, Rechtsklick den Container oder die Stelle davor"},
                {"/co near [Radius]", "was um dich herum passiert ist"},
                {"/co l u:Name t:2h r:10 a:break b:diamond", "suchen"},
                {"/co page 2", "nächste Seite"},
                {"/co rb u:Name t:1h r:20", "zurücksetzen (Zeit und Spieler oder Radius nötig)"},
                {"/co rs ...", "wiederherstellen"},
                {"/co rb confirm", "bestätigt das Zurücksetzen, das /co rb gerade gezeigt hat"},
                {"/co undo", "letztes Zurücksetzen oder Wiederherstellen rückgängig, bis zum nächsten Neustart"},
                {"/co status", "Log-Größe und Warteschlange"},
        };
        for (String[] l : lines) {
            c.getSource().sendSystemMessage(Component.literal(l[0]).withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("  " + l[1]).withStyle(ChatFormatting.GRAY)));
        }
        c.getSource().sendSystemMessage(Component.literal("a: break place change block take put container kill drop pickup item chat command session")
                .withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        boolean on = Inspector.toggle(p);
        c.getSource().sendSystemMessage(Component.literal(on
                ? "Inspektor an. Linksklick: wer hat diesen Block verändert. Rechtsklick: Container oder die Stelle davor."
                : "Inspektor aus.").withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static Db db(CommandContext<CommandSourceStack> c) {
        Db db = Chronik.db();
        if (db == null) c.getSource().sendFailure(Component.literal("Das Log ist gerade nicht offen."));
        return db;
    }

    private static int lookup(CommandContext<CommandSourceStack> c, boolean unused) {
        Db db = db(c);
        if (db == null) return 0;
        CommandSourceStack s = c.getSource();
        Params p = Params.parse(StringArgumentType.getString(c, "params"), db, Track.world(s.getLevel()), BlockPos.containing(s.getPosition()));
        if (p.error != null) {
            s.sendFailure(Component.literal(p.error));
            return 0;
        }
        run(s, p.filter);
        return 1;
    }

    private static int near(CommandContext<CommandSourceStack> c, int radius) {
        Db db = db(c);
        if (db == null) return 0;
        CommandSourceStack s = c.getSource();
        Filter f = new Filter();
        BlockPos here = BlockPos.containing(s.getPosition());
        f.world = Track.world(s.getLevel());
        f.x = here.getX();
        f.y = here.getY();
        f.z = here.getZ();
        f.radius = radius;
        run(s, f);
        return 1;
    }

    /** Runs a lookup on the reader thread and shows the first page when it is back. */
    public static void run(CommandSourceStack s, Filter f) {
        f.limit = 2000;
        Db db = Chronik.db();
        if (db == null) return;
        db.lookup(f).whenComplete((rows, err) -> s.getServer().execute(() -> {
            if (err != null) {
                s.sendFailure(Component.literal("Suche fehlgeschlagen: " + err.getMessage()));
                return;
            }
            RESULTS.put(s.getTextName(), rows);
            show(s, rows, 1);
        }));
    }

    private static int page(CommandContext<CommandSourceStack> c, int page) {
        List<Row> rows = RESULTS.get(c.getSource().getTextName());
        if (rows == null) {
            c.getSource().sendFailure(Component.literal("Erst suchen, dann blättern."));
            return 0;
        }
        show(c.getSource(), rows, page);
        return 1;
    }

    public static void show(CommandSourceStack s, List<Row> rows, int page) {
        if (rows.isEmpty()) {
            s.sendSystemMessage(Component.literal("Nichts gefunden.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int pages = (rows.size() + PAGE - 1) / PAGE;
        page = Math.max(1, Math.min(page, pages));
        s.sendSystemMessage(Component.literal("Chronik  " + rows.size() + (rows.size() >= 2000 ? "+" : "") + " Einträge").withStyle(ChatFormatting.GOLD));
        for (int i = (page - 1) * PAGE; i < Math.min(rows.size(), page * PAGE); i++) s.sendSystemMessage(Format.row(rows.get(i)));
        if (pages > 1) {
            MutableComponent nav = Component.literal("Seite " + page + " von " + pages).withStyle(ChatFormatting.GRAY);
            if (page < pages) {
                nav.append(Component.literal("   weiter »").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chronik page " + (page + 1)))));
            }
            s.sendSystemMessage(nav);
        }
    }

    private static int roll(CommandContext<CommandSourceStack> c, boolean restore) {
        Db db = db(c);
        if (db == null) return 0;
        CommandSourceStack s = c.getSource();
        String who = s.getTextName();
        String raw = StringArgumentType.getString(c, "params").trim();
        java.util.function.Consumer<String> report = msg -> s.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
        if (raw.equalsIgnoreCase("confirm")) {
            if (!Rollbacks.confirm(who, restore, report)) {
                s.sendFailure(Component.literal("Nichts zu bestätigen: erst /co " + (restore ? "rs" : "rb") + " mit Zeit und Spieler oder Radius, dann innerhalb einer Minute confirm."));
                return 0;
            }
            return 1;
        }
        Params p = Params.parse(raw, db, Track.world(s.getLevel()), BlockPos.containing(s.getPosition()));
        if (p.error != null) {
            s.sendFailure(Component.literal(p.error));
            return 0;
        }
        if (!p.hasTime || (!p.hasUser && !p.hasRadius)) {
            s.sendFailure(Component.literal("Zum " + (restore ? "Wiederherstellen" : "Zurücksetzen") + " braucht es eine Zeit (t:) und einen Spieler (u:) oder Radius (r:)."));
            return 0;
        }
        Filter f = p.filter;
        if (!p.hasUser && f.radius < 0) {
            s.sendFailure(Component.literal("Eine ganze Welt (r:#world, r:#global) nur mit einem Spieler (u:)."));
            return 0;
        }
        if (f.actions.isEmpty()) f.actions.addAll(List.of(0, 1, 2, 3, 4));
        f.rolled = restore;
        f.newestFirst = !restore;
        int max = ChronikConfig.MAX_ROLLBACK_ROWS.get();
        f.limit = max + 1;
        db.lookup(f).whenComplete((rows, err) -> s.getServer().execute(() -> {
            if (err != null) {
                s.sendFailure(Component.literal("Suche fehlgeschlagen: " + err.getMessage()));
                return;
            }
            if (rows.size() > max) {
                s.sendFailure(Component.literal("Mehr als " + max + " Änderungen auf einmal. Enger fassen: Spieler, kürzere Zeit oder kleinerer Radius."));
                return;
            }
            report.accept(Rollbacks.plan(rows, restore, who));
            Rollbacks.planned(who);
        }));
        return 1;
    }

    private static int undo(CommandContext<CommandSourceStack> c) {
        CommandSourceStack s = c.getSource();
        if (!Rollbacks.undo(s.getTextName(), msg -> s.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD)))) {
            s.sendFailure(Component.literal("Es gibt nichts rückgängig zu machen."));
            return 0;
        }
        return 1;
    }

    private static int purge(CommandContext<CommandSourceStack> c) {
        Db db = db(c);
        if (db == null) return 0;
        int days = IntegerArgumentType.getInteger(c, "days");
        CommandSourceStack s = c.getSource();
        db.purge(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)).thenAccept(n -> s.getServer().execute(() ->
                s.sendSystemMessage(Component.literal(n + " Einträge älter als " + days + " Tage gelöscht.").withStyle(ChatFormatting.GOLD))));
        return 1;
    }

    /** For the launcher's backup: a consistent copy of the log next to the live one. */
    private static int snapshot(CommandContext<CommandSourceStack> c) {
        Db db = db(c);
        if (db == null) return 0;
        java.nio.file.Path dir = c.getSource().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        db.snapshot(dir.resolve("chronik-snapshot.db"), dir.resolve("chronik-snapshot.done"));
        c.getSource().sendSystemMessage(Component.literal("OK snapshot requested"));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        Db db = db(c);
        if (db == null) return 0;
        c.getSource().sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                "Chronik: %.1f MB, %d geschrieben seit Start, %d in der Warteschlange, %d verworfen",
                db.fileSize() / 1048576.0, db.written(), db.queued(), db.dropped())).withStyle(ChatFormatting.GOLD));
        return 1;
    }
}
