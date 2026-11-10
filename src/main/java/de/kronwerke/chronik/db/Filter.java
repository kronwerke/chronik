package de.kronwerke.chronik.db;

import java.util.HashSet;
import java.util.Set;

/** What a lookup, rollback or restore is about. Unset fields do not filter. */
public final class Filter {

    /** World key such as minecraft:overworld; null is every world. */
    public String world;
    /** Centre and radius of a box (radius -1: no area, 0: exactly this block). */
    public int x, y, z;
    public int radius = -1;
    /** Actor keys (a player's UUID or "#minecraft:creeper"); empty is everyone. */
    public final Set<String> actors = new HashSet<>();
    /** Action codes from {@link Entry}; empty is all block and item actions. */
    public final Set<Integer> actions = new HashSet<>();
    /** Only these blocks, items or entities (by id, such as minecraft:diamond_ore, or a part of it). */
    public final Set<String> things = new HashSet<>();
    public long since = 0;
    public long until = Long.MAX_VALUE;
    /** null: both; true: only rolled back rows; false: only rows still in effect. */
    public Boolean rolled;
    public int limit = 1000;
    public int offset = 0;
    /** Newest first (lookups and rollbacks) or oldest first (restores). */
    public boolean newestFirst = true;

    public boolean wantsMessages() {
        return actions.contains(Entry.CHAT) || actions.contains(Entry.COMMAND) || actions.contains(Entry.JOIN) || actions.contains(Entry.LEAVE);
    }

    public boolean wantsActions() {
        if (actions.isEmpty()) return true;
        for (int a : actions) if (a < Entry.CHAT) return true;
        return false;
    }
}
