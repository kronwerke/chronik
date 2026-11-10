package de.kronwerke.chronik.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds and runs the SQL for a {@link Filter}. Only the reader thread calls this. */
final class Lookup {

    private Lookup() {
    }

    static List<Row> run(Connection c, Filter f) throws SQLException {
        List<Row> out = new ArrayList<>();
        if (f.wantsActions()) out.addAll(actions(c, f));
        if (f.wantsMessages() && f.rolled == null) out.addAll(messages(c, f));
        if (f.wantsActions() && f.wantsMessages()) {
            Comparator<Row> byTime = Comparator.comparingLong(Row::time).thenComparingLong(Row::id);
            out.sort(f.newestFirst ? byTime.reversed() : byTime);
            if (out.size() > f.limit) out = new ArrayList<>(out.subList(0, f.limit));
        }
        return out;
    }

    private static List<Row> actions(Connection c, Filter f) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.t, a.action, w.key, a.x, a.y, a.z, n.key, n.label, cz.key, na.key, nb.key, a.amount, a.data, a.rolled
                FROM actions a
                JOIN names w ON w.id = a.world
                JOIN names n ON n.id = a.actor
                LEFT JOIN names cz ON cz.id = a.cause
                LEFT JOIN names na ON na.id = a.a
                LEFT JOIN names nb ON nb.id = a.b
                WHERE a.t >= ? AND a.t <= ?""");
        List<Object> args = new ArrayList<>();
        args.add(f.since);
        args.add(f.until);
        where(sql, args, f, "a", "w");
        if (!f.actions.isEmpty()) {
            sql.append(" AND a.action IN (");
            boolean first = true;
            for (int act : f.actions) {
                if (act >= Entry.CHAT) continue;
                sql.append(first ? "?" : ",?");
                args.add(act);
                first = false;
            }
            sql.append(")");
        }
        if (!f.things.isEmpty()) {
            sql.append(" AND (");
            boolean first = true;
            for (String thing : f.things) {
                if (!first) sql.append(" OR ");
                sql.append("na.key LIKE ? OR nb.key LIKE ?");
                args.add("%" + thing + "%");
                args.add("%" + thing + "%");
                first = false;
            }
            sql.append(")");
        }
        if (f.rolled != null) {
            sql.append(" AND a.rolled = ?");
            args.add(f.rolled ? 1 : 0);
        }
        sql.append(f.newestFirst ? " ORDER BY a.t DESC, a.id DESC" : " ORDER BY a.t ASC, a.id ASC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(f.limit);
        args.add(f.offset);
        List<Row> out = new ArrayList<>();
        try (PreparedStatement p = prepare(c, sql.toString(), args); ResultSet r = p.executeQuery()) {
            while (r.next()) {
                out.add(new Row(r.getLong(1), false, r.getLong(2), r.getInt(3), r.getString(4), r.getInt(5), r.getInt(6), r.getInt(7),
                        r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getInt(13), r.getBytes(14),
                        r.getInt(15) != 0, null));
            }
        }
        return out;
    }

    private static List<Row> messages(Connection c, Filter f) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.t, m.kind, w.key, m.x, m.y, m.z, n.key, n.label, m.text
                FROM messages m
                JOIN names w ON w.id = m.world
                JOIN names n ON n.id = m.actor
                WHERE m.t >= ? AND m.t <= ?""");
        List<Object> args = new ArrayList<>();
        args.add(f.since);
        args.add(f.until);
        where(sql, args, f, "m", "w");
        sql.append(" AND m.kind IN (");
        boolean first = true;
        for (int act : f.actions) {
            if (act < Entry.CHAT) continue;
            sql.append(first ? "?" : ",?");
            args.add(act);
            first = false;
        }
        sql.append(")");
        if (!f.things.isEmpty()) {
            sql.append(" AND (");
            first = true;
            for (String thing : f.things) {
                if (!first) sql.append(" OR ");
                sql.append("m.text LIKE ?");
                args.add("%" + thing + "%");
                first = false;
            }
            sql.append(")");
        }
        sql.append(f.newestFirst ? " ORDER BY m.t DESC, m.id DESC" : " ORDER BY m.t ASC, m.id ASC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(f.limit);
        args.add(f.offset);
        List<Row> out = new ArrayList<>();
        try (PreparedStatement p = prepare(c, sql.toString(), args); ResultSet r = p.executeQuery()) {
            while (r.next()) {
                out.add(new Row(r.getLong(1), true, r.getLong(2), r.getInt(3), r.getString(4), r.getInt(5), r.getInt(6), r.getInt(7),
                        r.getString(8), r.getString(9), null, null, null, 0, null, false, r.getString(10)));
            }
        }
        return out;
    }

    private static void where(StringBuilder sql, List<Object> args, Filter f, String t, String w) {
        if (f.world != null) {
            sql.append(" AND ").append(w).append(".key = ?");
            args.add(f.world);
        }
        if (f.radius >= 0) {
            // a radius is a column (all heights, like CoreProtect); radius 0 is exactly one block
            sql.append(" AND ").append(t).append(".x BETWEEN ? AND ? AND ").append(t).append(".z BETWEEN ? AND ?");
            args.add(f.x - f.radius);
            args.add(f.x + f.radius);
            args.add(f.z - f.radius);
            args.add(f.z + f.radius);
            if (f.radius == 0) {
                sql.append(" AND ").append(t).append(".y = ?");
                args.add(f.y);
            }
        }
        if (!f.actors.isEmpty()) {
            sql.append(" AND n.key IN (");
            boolean first = true;
            for (String a : f.actors) {
                sql.append(first ? "?" : ",?");
                args.add(a);
                first = false;
            }
            sql.append(")");
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, List<Object> args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v instanceof Long l) p.setLong(i + 1, l);
            else if (v instanceof Integer n) p.setInt(i + 1, n);
            else p.setString(i + 1, String.valueOf(v));
        }
        return p;
    }
}
