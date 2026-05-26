package arc.keeper.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global leaderboard backed by Neon's SQL-over-HTTP endpoint.
 *
 * <p>We talk to Neon directly with a plain {@link HttpURLConnection} POST per
 * query — no JDBC, no native postgres protocol code in the APK, no Android API
 * bump required. Each query is a single round-trip:
 * <pre>
 *   POST https://&lt;neon-host&gt;/sql
 *   Neon-Connection-String: postgresql://...
 *   Neon-Raw-Text-Output:   true                 ; all values returned as strings
 *   Content-Type:           application/json
 *   { "query": "...", "params": [...] }
 * </pre>
 * Response is {@code {command, rowCount, rows, fields}} where rows is an
 * array of objects keyed by column name.
 *
 * <p>All IO happens on a single daemon thread; the render loop interacts only
 * with {@link #getStatus()}, {@link #snapshot()}, and the callback hand-off
 * via {@link Gdx#app}{@code .postRunnable}.
 */
public class GlobalHighScores {

    /** Top-N we keep cached / display in the UI. */
    public static final int CAPACITY = 10;

    /** Neon connection string (libpq URL). Passed in a header to the SQL endpoint. */
    private static final String NEON_URL =
        "postgresql://neondb_owner:npg_Jmqky13zZGUn@ep-empty-bar-aliw2m32-pooler" +
        ".c-3.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require";

    /** Same host as the postgres URL but on HTTPS, path {@code /sql}. */
    private static final String SQL_ENDPOINT =
        "https://ep-empty-bar-aliw2m32-pooler.c-3.eu-central-1.aws.neon.tech/sql";

    public enum Status { IDLE, CONNECTING, LOADING, READY, ERROR }

    public interface SubmitCallback {
        /** Always called on the libGDX render thread. {@code rank} is 1-based, 0 if not top-N. */
        void onResult(boolean ok, int rank, String errorMessage);
    }

    private final ExecutorService io;
    private final AtomicBoolean   closed = new AtomicBoolean(false);

    private volatile Status status = Status.IDLE;
    private volatile String lastError;
    private volatile long   lastRefreshAt;

    /** Last refreshed snapshot, guarded by {@code this}. */
    private final Array<HighScoreEntry> top = new Array<>(CAPACITY);

    public GlobalHighScores() {
        this.io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "GlobalHighScores-IO");
                t.setDaemon(true);
                return t;
            }
        });
        io.submit(this::initAndLoad);
    }

    // ---------------------------------------------------------------------
    // Public, render-thread-safe API
    // ---------------------------------------------------------------------

    public Status getStatus()      { return status; }
    public String getLastError()   { return lastError; }
    public long   getLastRefresh() { return lastRefreshAt; }

    /** Defensive copy of the latest cached leaderboard. */
    public synchronized Array<HighScoreEntry> snapshot() {
        Array<HighScoreEntry> out = new Array<>(top.size);
        for (HighScoreEntry e : top) out.add(e);
        return out;
    }

    /** Re-fetches the top-{@value #CAPACITY} from the server. Idempotent. */
    public void refresh() {
        if (closed.get()) return;
        io.submit(this::refreshNow);
    }

    /**
     * Submits a row and re-fetches the leaderboard. The callback runs on the GL
     * thread; pass null if you don't need it.
     */
    public void submit(final String rawName, final int score, final int height,
                       final SubmitCallback cb) {
        if (closed.get()) { postCallback(cb, false, 0, "shut down"); return; }
        final String name = HighScoreEntry.sanitizeName(rawName);
        io.submit(() -> doSubmit(name, score, height, cb));
    }

    /** Shuts down the IO thread. Idempotent. */
    public void dispose() {
        if (closed.compareAndSet(false, true)) {
            io.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------
    // IO-thread internals
    // ---------------------------------------------------------------------

    private void initAndLoad() {
        status = Status.CONNECTING;
        try {
            execute("CREATE TABLE IF NOT EXISTS highscores (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "score INTEGER NOT NULL, " +
                    "height INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT now())");
            execute("CREATE INDEX IF NOT EXISTS idx_highscores_score ON highscores (score DESC)");

            // Idempotent seed: inserts the ten default Arc rows only if the
            // table is empty, all in a single statement so concurrent first-
            // launches across multiple devices can't double-seed.
            execute("INSERT INTO highscores (name, score, height, created_at) " +
                    "SELECT v.name, v.score, v.height, to_timestamp(0) FROM (VALUES " +
                    "('ARC', 5000, 500),  ('ARC', 10000, 1000), ('ARC', 15000, 1500), " +
                    "('ARC', 20000, 2000),('ARC', 25000, 2500), ('ARC', 30000, 3000), " +
                    "('ARC', 35000, 3500),('ARC', 40000, 4000), ('ARC', 45000, 4500), " +
                    "('ARC', 50000, 5000)) AS v(name, score, height) " +
                    "WHERE NOT EXISTS (SELECT 1 FROM highscores)");

            doRefresh();
        } catch (Throwable ex) {
            fail("Connect failed", ex);
        }
    }

    private void refreshNow() {
        status = Status.LOADING;
        try {
            doRefresh();
        } catch (Throwable ex) {
            fail("Refresh failed", ex);
        }
    }

    private void doRefresh() throws IOException {
        JsonValue resp = execute(
            "SELECT name, score, height, " +
            "(EXTRACT(EPOCH FROM created_at) * 1000)::bigint AS ts " +
            "FROM highscores ORDER BY score DESC, created_at ASC LIMIT $1",
            CAPACITY);

        Array<HighScoreEntry> fresh = new Array<>(CAPACITY);
        JsonValue rows = resp.get("rows");
        if (rows != null) {
            for (JsonValue row = rows.child; row != null; row = row.next) {
                String name = strField(row, "name", "?");
                int    sc   = intField(row, "score", 0);
                int    h    = intField(row, "height", 0);
                long   ts   = longField(row, "ts", 0L);
                fresh.add(new HighScoreEntry(name, sc, h, ts));
            }
        }
        synchronized (this) {
            top.clear();
            top.addAll(fresh);
        }
        lastRefreshAt = System.currentTimeMillis();
        lastError = null;
        status = Status.READY;
    }

    private void doSubmit(String name, int score, int height, SubmitCallback cb) {
        if (score <= 0) { postCallback(cb, false, 0, "no score"); return; }
        try {
            // Insert first so the row is durable even if a later request fails.
            execute("INSERT INTO highscores (name, score, height) VALUES ($1, $2, $3)",
                name, score, height);

            // Compute global rank from a count query (small race window vs.
            // concurrent inserts is fine for a casual leaderboard).
            JsonValue r = execute("SELECT count(*)::bigint AS c FROM highscores WHERE score > $1", score);
            long higher = 0L;
            JsonValue rows = r.get("rows");
            if (rows != null && rows.child != null) {
                higher = longField(rows.child, "c", 0L);
            }
            int rank = (int) (higher + 1);

            doRefresh();
            postCallback(cb, true, rank <= CAPACITY ? rank : 0, null);
        } catch (Throwable ex) {
            fail("Submit failed", ex);
            postCallback(cb, false, 0, friendly(ex));
        }
    }

    // ---------------------------------------------------------------------
    // HTTP layer
    // ---------------------------------------------------------------------

    /**
     * Sends one parameterised SQL query and returns the parsed JSON response.
     * Throws on network failure, non-2xx status, or malformed JSON.
     */
    private JsonValue execute(String sql, Object... params) throws IOException {
        if (closed.get()) throw new IOException("shut down");

        byte[] body = buildBody(sql, params).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(SQL_ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type",          "application/json");
            conn.setRequestProperty("Accept",                "application/json");
            conn.setRequestProperty("Neon-Connection-String", NEON_URL);
            // Returns every value as a JSON string so we don't have to worry
            // about how Neon picks between number/string for bigints, etc.
            conn.setRequestProperty("Neon-Raw-Text-Output",  "true");
            conn.setFixedLengthStreamingMode(body.length);

            try (OutputStream out = conn.getOutputStream()) { out.write(body); }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = stream == null ? "" : readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + truncate(responseBody, 240));
            }
            return new JsonReader().parse(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------

    /** Builds {@code {"query": "...", "params": [...]}} without depending on a Json writer. */
    static String buildBody(String sql, Object... params) {
        StringBuilder sb = new StringBuilder(64 + sql.length());
        sb.append("{\"query\":");
        jsonString(sb, sql);
        sb.append(",\"params\":[");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            Object p = params[i];
            if (p == null) {
                sb.append("null");
            } else if (p instanceof Integer || p instanceof Long ||
                       p instanceof Short   || p instanceof Byte) {
                sb.append(p);
            } else if (p instanceof Boolean) {
                sb.append(((Boolean) p) ? "true" : "false");
            } else {
                // Float / double / String / anything else: send as string. Numeric
                // params with raw-text-output=true round-trip through postgres'
                // text-mode binders just fine.
                jsonString(sb, p.toString());
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void jsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static String strField(JsonValue row, String name, String fallback) {
        JsonValue v = row.get(name);
        if (v == null || v.isNull()) return fallback;
        return v.asString();
    }

    private static int intField(JsonValue row, String name, int fallback) {
        JsonValue v = row.get(name);
        if (v == null || v.isNull()) return fallback;
        try { return Integer.parseInt(v.asString().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static long longField(JsonValue row, String name, long fallback) {
        JsonValue v = row.get(name);
        if (v == null || v.isNull()) return fallback;
        try { return Long.parseLong(v.asString().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---------------------------------------------------------------------
    // Error plumbing + thread hand-off
    // ---------------------------------------------------------------------

    private void fail(String stage, Throwable ex) {
        status    = Status.ERROR;
        lastError = stage + ": " + friendly(ex);
        if (Gdx.app != null) Gdx.app.error("GlobalHighScores", lastError, ex);
    }

    private static String friendly(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isEmpty()) return ex.getClass().getSimpleName();
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }

    private static void postCallback(final SubmitCallback cb, final boolean ok,
                                     final int rank, final String err) {
        if (cb == null) return;
        if (Gdx.app != null) {
            Gdx.app.postRunnable(() -> cb.onResult(ok, rank, err));
        } else {
            cb.onResult(ok, rank, err);
        }
    }
}
