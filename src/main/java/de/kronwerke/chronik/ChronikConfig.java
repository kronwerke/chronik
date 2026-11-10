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
