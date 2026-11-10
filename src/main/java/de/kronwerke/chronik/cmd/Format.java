package de.kronwerke.chronik.cmd;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import de.kronwerke.chronik.db.Entry;
import de.kronwerke.chronik.db.Row;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** How rows look in chat: "vor 3 min  Elchi_Sam hat Eichenholzbretter abgebaut". */
public final class Format {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private Format() {
    }

    public static MutableComponent row(Row r) {
        MutableComponent line = Component.literal(ago(r.time())).withStyle(ChatFormatting.GRAY)
                .append(Component.literal("  "))
                .append(actor(r.actor(), r.actorLabel()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" "));
        MutableComponent what = switch (r.action()) {
            case Entry.BREAK -> Component.literal("hat ").append(thing(r.a())).append(" abgebaut");
            case Entry.PLACE -> Component.literal("hat ").append(thing(r.b())).append(" platziert");
            case Entry.CHANGE -> sameBlock(r.a(), r.b())
                    ? Component.literal("hat ").append(thing(r.b())).append(" benutzt")
                    : Component.literal("hat ").append(thing(r.a())).append(" durch ").append(thing(r.b())).append(" ersetzt");
            case Entry.TAKE -> Component.literal("hat " + r.amount() + " ").append(item(r.a())).append(" genommen");
            case Entry.PUT -> Component.literal("hat " + r.amount() + " ").append(item(r.a())).append(" hineingelegt");
            case Entry.KILL -> Component.literal("hat ").append(entity(r.a())).append(" getötet");
            case Entry.DROP -> Component.literal("hat " + r.amount() + " ").append(item(r.a())).append(" fallen lassen");
            case Entry.PICKUP -> Component.literal("hat " + r.amount() + " ").append(item(r.a())).append(" aufgehoben");
            case Entry.CHAT -> Component.literal("schrieb: " + r.text());
            case Entry.COMMAND -> Component.literal("nutzte " + r.text());
            case Entry.JOIN -> Component.literal("kam auf den Server");
            case Entry.LEAVE -> Component.literal("ging vom Server");
            default -> Component.literal("?");
        };
        what.withStyle(ChatFormatting.WHITE);
        if (r.cause() != null) what.append(Component.literal(" (über ").append(cause(r.cause())).append(")").withStyle(ChatFormatting.DARK_GRAY));
        if (r.rolled()) what.withStyle(ChatFormatting.STRIKETHROUGH);
        line.append(what);
        String where = r.x() + " " + r.y() + " " + r.z();
        Component hover = Component.literal(WHEN.format(Instant.ofEpochMilli(r.time())) + "\n" + r.world() + "  " + where
                + (r.rolled() ? "\nzurückgesetzt" : "") + "\nKlick: hinteleportieren");
        line.withStyle(Style.EMPTY
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/execute in " + r.world() + " run tp @s " + r.x() + " " + (r.y() + 1) + " " + r.z())));
        return line;
    }

    public static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return "vor " + s + " s";
        if (s < 3600) return "vor " + s / 60 + " min";
        if (s < 86400) return "vor " + s / 3600 + " h";
        return "vor " + s / 86400 + " d";
    }

    public static MutableComponent actor(String key, String label) {
        if (key == null) return Component.literal("?");
        if (key.startsWith("#")) {
            String id = key.substring(1);
            if (id.equals("explosion")) return Component.literal("Explosion");
            return entity(id);
        }
        return Component.literal(label != null ? label : key);
    }

    private static boolean sameBlock(String a, String b) {
        return a != null && b != null && blockId(a).equals(blockId(b));
    }

    private static String blockId(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    public static MutableComponent thing(String state) {
        if (state == null) return Component.literal("?");
        ResourceLocation id = ResourceLocation.tryParse(blockId(state));
        if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
            return Component.translatable(BuiltInRegistries.BLOCK.get(id).getDescriptionId());
        }
        return Component.literal(blockId(state));
    }

    public static MutableComponent item(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) return Component.translatable(BuiltInRegistries.ITEM.get(rl).getDescriptionId());
        return Component.literal(String.valueOf(id));
    }

    public static MutableComponent entity(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) return Component.translatable(BuiltInRegistries.ENTITY_TYPE.get(rl).getDescriptionId());
        return Component.literal(String.valueOf(id));
    }

    /** What something acted through: an entity (TNT, contraption) or a machine (block entity, shown as its block). */
    private static MutableComponent cause(String id) {
        if (id.equals("explosion")) return Component.literal("Explosion");
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) return entity(id);
        if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) return Component.translatable(BuiltInRegistries.BLOCK.get(rl).getDescriptionId());
        return Component.literal(id);
    }
}
