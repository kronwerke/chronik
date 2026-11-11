package de.kronwerke.chronik.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.ChronikConfig;
import de.kronwerke.chronik.cmd.Params;
import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.db.Row;
import net.minecraft.core.BlockPos;

/**
 * A small read-only JSON API for the web view, only on 127.0.0.1 and only with the token from
 * the config. The console (the launcher) asks it and shows the answer; nothing outside the
 * machine can reach it.
 * <pre>
 *   GET /status
 *   GET /lookup?q=u:Name t:2h a:break&amp;world=minecraft:overworld&amp;x=..&amp;y=..&amp;z=..&amp;limit=200&amp;offset=0
 * </pre>
 */
public final class Api {

    private static final Gson GSON = new Gson();
    private static HttpServer http;

    private Api() {
    }

    public static void start() {
        // the launcher gives every server its own port and its token in the environment: the
        // servers of a network may share one config folder
        int port = ChronikConfig.WEB_PORT.get();
        String token = ChronikConfig.WEB_TOKEN.get();
        String envPort = System.getenv("CHRONIK_WEB_PORT"), envToken = System.getenv("CHRONIK_WEB_TOKEN");
        if (envPort != null && !envPort.isBlank() && envToken != null && !envToken.isBlank()) {
            try {
                port = Integer.parseInt(envPort.trim());
                token = envToken.trim();
            } catch (NumberFormatException e) {
                Chronik.LOG.warn("CHRONIK_WEB_PORT is not a number: {}", envPort);
            }
        }
        if (port <= 0 || token.isBlank()) return;
        final String key = token;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
            http.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "Chronik web");
                t.setDaemon(true);
                return t;
            }));
            http.createContext("/status", ex -> guarded(ex, key, Api::status));
            http.createContext("/lookup", ex -> guarded(ex, key, Api::lookup));
            http.start();
            Chronik.LOG.info("Chronik API on 127.0.0.1:{}", port);
        } catch (IOException e) {
            Chronik.LOG.error("Chronik API could not start on port {}", port, e);
            http = null;
        }
    }

    public static void stop() {
        if (http != null) http.stop(0);
        http = null;
    }

    private interface Handler {
        void handle(HttpExchange ex, Map<String, String> q) throws Exception;
    }

    private static void guarded(HttpExchange ex, String token, Handler h) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] want = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            if (auth == null || !MessageDigest.isEqual(want, auth.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, error("unauthorized"));
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, error("GET only"));
                return;
            }
            h.handle(ex, query(ex.getRequestURI().getRawQuery()));
        } catch (Exception e) {
            send(ex, 500, error(e.toString()));
        } finally {
            ex.close();
        }
    }

    private static void status(HttpExchange ex, Map<String, String> q) throws IOException {
        Db db = Chronik.db();
        JsonObject o = new JsonObject();
        o.addProperty("open", db != null);
        if (db != null) {
            o.addProperty("bytes", db.fileSize());
            o.addProperty("written", db.written());
            o.addProperty("queued", db.queued());
            o.addProperty("dropped", db.dropped());
        }
        send(ex, 200, o);
    }

    private static void lookup(HttpExchange ex, Map<String, String> q) throws Exception {
        Db db = Chronik.db();
        if (db == null) {
            send(ex, 503, error("log not open"));
            return;
        }
        BlockPos here = new BlockPos(num(q, "x", 0), num(q, "y", 64), num(q, "z", 0));
        Params p = Params.parse(q.getOrDefault("q", ""), db, q.getOrDefault("world", "minecraft:overworld"), here);
        if (p.error != null) {
            send(ex, 400, error(p.error));
            return;
        }
        p.filter.limit = Math.max(1, Math.min(1000, num(q, "limit", 200)));
        p.filter.offset = Math.max(0, num(q, "offset", 0));
        List<Row> rows = db.lookup(p.filter).get(30, TimeUnit.SECONDS);
        JsonArray out = new JsonArray();
        for (Row r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("time", r.time());
            o.addProperty("action", name(r.action()));
            o.addProperty("world", r.world());
            o.addProperty("x", r.x());
            o.addProperty("y", r.y());
            o.addProperty("z", r.z());
            o.addProperty("actor", r.actor());
            o.addProperty("actorName", r.actorLabel() != null ? r.actorLabel() : r.actor());
            if (r.cause() != null) o.addProperty("via", r.cause());
            if (r.a() != null) o.addProperty("before", r.a());
            if (r.b() != null) o.addProperty("after", r.b());
            if (r.amount() != 0) o.addProperty("amount", r.amount());
            if (r.text() != null) o.addProperty("text", r.text());
            o.addProperty("rolledBack", r.rolled());
            out.add(o);
        }
        JsonObject res = new JsonObject();
        res.add("rows", out);
        send(ex, 200, res);
    }

    private static String name(int action) {
        return switch (action) {
            case 0 -> "break";
            case 1 -> "place";
            case 2 -> "change";
            case 3 -> "take";
            case 4 -> "put";
            case 5 -> "kill";
            case 6 -> "drop";
            case 7 -> "pickup";
            case 10 -> "chat";
            case 11 -> "command";
            case 12 -> "join";
            case 13 -> "leave";
            default -> "unknown";
        };
    }

    private static int num(Map<String, String> q, String k, int def) {
        try {
            return q.containsKey(k) ? Integer.parseInt(q.get(k)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private static void send(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] b = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
