package de.kronwerke.chronik.cmd;

import java.util.List;
import java.util.Locale;

import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.db.Entry;
import de.kronwerke.chronik.db.Filter;
import net.minecraft.core.BlockPos;

/**
 * The parameters of lookup, rollback and restore, CoreProtect style:
 * <pre>
 *   u:Name,Name2    who (players by name, or #creeper, #explosion, #minecraft:zombie)
 *   t:2h30m         how far back (s, m, h, d, w)
 *   r:10            radius around you in blocks (all heights), r:#world for the whole world
 *   a:break,place   what: break, place, change, block, take, put, container, kill, drop, pickup, item, chat, command, session
 *   b:diamond_ore   which blocks, items or entities (id or part of it)
 *   w:minecraft:the_nether  which world (default: yours)
 * </pre>
 */
public final class Params {

    public final Filter filter = new Filter();
    public boolean hasTime, hasRadius, hasUser;
    public String error;

    private Params() {
    }

    public static Params parse(String input, Db db, String world, BlockPos here) {
        Params p = new Params();
        Filter f = p.filter;
        f.world = world;
        for (String tok : input.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            int c = tok.indexOf(':');
            if (c <= 0) {
                p.error = "Unbekannter Parameter: " + tok;
                return p;
            }
            String key = tok.substring(0, c).toLowerCase(Locale.ROOT);
            String val = tok.substring(c + 1);
            switch (key) {
                case "u", "user", "p" -> {
                    for (String name : val.split(",")) {
                        if (name.isEmpty()) continue;
                        if (name.startsWith("#")) {
                            String id = name.substring(1);
                            f.actors.add("#" + (id.contains(":") || id.equals("explosion") ? id : "minecraft:" + id));
                        } else {
                            String k = db.actorByName(name);
                            if (k == null) {
                                p.error = "Diesen Spieler kennt das Log nicht: " + name;
                                return p;
                            }
                            f.actors.add(k);
                        }
                    }
                    p.hasUser = true;
                }
                case "t", "time" -> {
                    long ms = duration(val);
                    if (ms <= 0) {
                        p.error = "Zeit nicht verstanden: " + val + " (zum Beispiel t:2h30m)";
                        return p;
                    }
                    f.since = System.currentTimeMillis() - ms;
                    p.hasTime = true;
                }
                case "r", "radius" -> {
                    if (val.equalsIgnoreCase("#world") || val.equalsIgnoreCase("#welt")) {
                        f.radius = -1;
                    } else if (val.equalsIgnoreCase("#global")) {
                        f.radius = -1;
                        f.world = null;
                    } else {
                        try {
                            f.radius = Math.max(0, Math.min(1000, Integer.parseInt(val)));
                        } catch (NumberFormatException e) {
                            p.error = "Radius nicht verstanden: " + val;
                            return p;
                        }
                        f.x = here.getX();
                        f.y = here.getY();
                        f.z = here.getZ();
                    }
                    p.hasRadius = true;
                }
                case "a", "action" -> {
                    for (String a : val.toLowerCase(Locale.ROOT).split(",")) {
                        List<Integer> codes = switch (a) {
                            case "break", "-block" -> List.of(Entry.BREAK);
                            case "place", "+block" -> List.of(Entry.PLACE);
                            case "change", "use", "interact" -> List.of(Entry.CHANGE);
                            case "block" -> List.of(Entry.BREAK, Entry.PLACE, Entry.CHANGE);
                            case "take", "-container" -> List.of(Entry.TAKE);
                            case "put", "+container" -> List.of(Entry.PUT);
                            case "container" -> List.of(Entry.TAKE, Entry.PUT);
                            case "kill" -> List.of(Entry.KILL);
                            case "drop" -> List.of(Entry.DROP);
                            case "pickup" -> List.of(Entry.PICKUP);
                            case "item" -> List.of(Entry.DROP, Entry.PICKUP);
                            case "chat" -> List.of(Entry.CHAT);
                            case "command" -> List.of(Entry.COMMAND);
                            case "session" -> List.of(Entry.JOIN, Entry.LEAVE);
                            default -> null;
                        };
                        if (codes == null) {
                            p.error = "Aktion nicht verstanden: " + a;
                            return p;
                        }
                        f.actions.addAll(codes);
                    }
                }
                case "b", "block", "i", "item" -> {
                    for (String b : val.toLowerCase(Locale.ROOT).split(",")) if (!b.isEmpty()) f.things.add(b);
                }
                case "w", "world" -> f.world = val.contains(":") ? val : "minecraft:" + val;
                default -> {
                    p.error = "Unbekannter Parameter: " + key;
                    return p;
                }
            }
        }
        return p;
    }

    /** "2h30m" to milliseconds; 0 when it cannot be read. */
    public static long duration(String s) {
        long total = 0;
        long num = 0;
        boolean any = false;
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(ch)) {
                num = num * 10 + (ch - '0');
                any = true;
                continue;
            }
            long unit = switch (ch) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> -1;
            };
            if (unit < 0 || !any) return 0;
            total += num * unit;
            num = 0;
            any = false;
        }
        if (any) total += num * 3_600_000L; // a bare number is hours
        return total;
    }
}
