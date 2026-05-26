package arc.keeper.data;

/**
 * Immutable row in a leaderboard. {@code timestamp} is epoch millis at the time
 * the score was recorded (0 for default-seeded rows that predate the player).
 */
public class HighScoreEntry {

    /** Hard cap on stored / displayed names so the database stays predictable. */
    public static final int MAX_NAME_LENGTH = 12;

    public final String name;
    public final int    score;
    public final int    height;
    public final long   timestamp;

    public HighScoreEntry(String name, int score, int height, long timestamp) {
        this.name      = sanitizeName(name);
        this.score     = Math.max(0, score);
        this.height    = Math.max(0, height);
        this.timestamp = timestamp;
    }

    /**
     * Strips control characters, uppercases, trims, and caps length so the same
     * value always round-trips through both the local prefs file and Postgres.
     * A blank / null input falls back to {@code "ARC"}.
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return "ARC";
        StringBuilder sb = new StringBuilder(MAX_NAME_LENGTH);
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_LENGTH; i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 || ch == 0x7F) continue;
            if (ch == '\n' || ch == '\r' || ch == '\t') continue;
            sb.append(Character.toUpperCase(ch));
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? "ARC" : cleaned;
    }
}
