package de.kronwerke.chronik;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ChronikConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue RETENTION_DAYS;
    public static final ModConfigSpec.BooleanValue LOG_MOBS;
    public static final ModConfigSpec.BooleanValue LOG_ITEMS;
    public static final ModConfigSpec.BooleanValue LOG_KILLS;
    public static final ModConfigSpec.BooleanValue LOG_CHAT;
    public static final ModConfigSpec.BooleanValue LOG_COMMANDS;
    public static final ModConfigSpec.BooleanValue LOG_SESSIONS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SKIP_WORLDS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> QUIET_BLOCKS;
    public static final ModConfigSpec.IntValue PICKUP_MERGE_SECONDS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PRIVATE_COMMANDS;
    public static final ModConfigSpec.IntValue MAX_ROLLBACK_ROWS;
    public static final ModConfigSpec.IntValue INSPECT_LEVEL;
    public static final ModConfigSpec.IntValue ROLLBACK_LEVEL;
    public static final ModConfigSpec.DoubleValue ROLLBACK_MS_PER_TICK;
    public static final ModConfigSpec.IntValue WEB_PORT;
    public static final ModConfigSpec.ConfigValue<String> WEB_TOKEN;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("log");
        RETENTION_DAYS = b.comment("How many days the log keeps. Older entries are deleted once a day.")
                .defineInRange("retentionDays", 30, 1, 3650);
        LOG_MOBS = b.comment("Log block changes by mobs (creepers, endermen, ravagers). Players, machines with an owner and explosions someone lit are always logged.")
                .define("mobs", true);
        LOG_ITEMS = b.comment("Log items players drop and pick up.")
                .define("items", true);
        LOG_KILLS = b.comment("Log what players (and their machines, TNT and pets) kill.")
                .define("kills", true);
        LOG_CHAT = b.define("chat", true);
        LOG_COMMANDS = b.define("commands", true);
        LOG_SESSIONS = b.comment("Log joins and leaves.").define("sessions", true);
        SKIP_WORLDS = b.comment("Worlds that are not logged at all, such as minecraft:overworld on a world that is reset regularly.")
                .defineListAllowEmpty("skipWorlds", java.util.List.of(), o -> o instanceof String);
        QUIET_BLOCKS = b.comment("Natural blocks a machine digs (a quarry, a digital miner, a drill) are not logged when they are in one of these block tags or ids. What a player breaks by hand is always logged, and so is everything else a machine breaks.")
                .defineListAllowEmpty("quietBlocks", java.util.List.of("#minecraft:base_stone_overworld", "#minecraft:base_stone_nether", "#c:ores",
                        "#minecraft:dirt", "#c:gravels", "#c:sands", "minecraft:end_stone",
                        "minecraft:tuff", "minecraft:calcite", "minecraft:dripstone_block", "minecraft:clay", "minecraft:water", "minecraft:lava"), o -> o instanceof String);
        PICKUP_MERGE_SECONDS = b.comment("Pickups of the same item by the same player within this many seconds are one entry (magnets, mob farms). 0 logs every pickup.")
                .defineInRange("pickupMergeSeconds", 10, 0, 600);
        PRIVATE_COMMANDS = b.comment("Commands whose text is private (direct messages): only the command and the first word (the receiver) are stored, never the message.")
                .defineListAllowEmpty("privateCommands", java.util.List.of("msg", "tell", "w", "whisper", "teammsg", "tm", "r", "reply", "m", "pm", "dm"), o -> o instanceof String);
        b.pop();
        b.push("permissions");
        INSPECT_LEVEL = b.comment("Operator level for /chronik inspect and lookup.")
                .defineInRange("inspect", 2, 0, 4);
        ROLLBACK_LEVEL = b.comment("Operator level for /chronik rollback, restore, undo and purge.")
                .defineInRange("rollback", 3, 0, 4);
        b.pop();
        b.push("rollback");
        ROLLBACK_MS_PER_TICK = b.comment("How many milliseconds per tick a rollback or restore may use. It goes on over as many ticks as it needs.")
                .defineInRange("msPerTick", 5.0, 0.5, 40.0);
        MAX_ROLLBACK_ROWS = b.comment("The most changes one rollback or restore takes on. A larger one is refused: narrow it down by player, time or radius.")
                .defineInRange("maxRows", 200_000, 1_000, 2_000_000);
        b.pop();
        b.push("web");
        WEB_PORT = b.comment("Port of the JSON API for the web view, only on 127.0.0.1. 0 turns it off.")
                .defineInRange("port", 0, 0, 65535);
        WEB_TOKEN = b.comment("Bearer token the API asks for. Empty turns the API off.")
                .define("token", "");
        b.pop();
        SPEC = b.build();
    }

    private ChronikConfig() {
    }
}
