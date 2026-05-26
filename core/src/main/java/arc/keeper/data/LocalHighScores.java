package arc.keeper.data;

import com.badlogic.gdx.utils.Array;

/**
 * On-device top-N leaderboard, persisted as a single newline-delimited blob in
 * libGDX {@link com.badlogic.gdx.Preferences}. Newline+pipe is friendlier across
 * Android's XML-backed Preferences than embedding raw JSON quotes.
 *
 * <p>Format: one entry per line, fields delimited by {@code |}:
 * <pre>NAME|SCORE|HEIGHT|TIMESTAMP</pre>
 * Names are guaranteed not to contain newlines or pipes by {@link HighScoreEntry#sanitizeName}.
 */
public class LocalHighScores {

    /** Number of rows we keep on disk and show in the UI. */
    public static final int CAPACITY = 10;

    private final SaveData save;
    private final Array<HighScoreEntry> entries = new Array<>(CAPACITY);

    public LocalHighScores(SaveData save) {
        this.save = save;
        load();
    }

    /** Snapshot, sorted high-to-low. Safe to iterate from any thread (copy). */
    public synchronized Array<HighScoreEntry> snapshot() {
        Array<HighScoreEntry> copy = new Array<>(entries.size);
        for (HighScoreEntry e : entries) copy.add(e);
        return copy;
    }

    /** Returns true if the given score would make the local top-{@value #CAPACITY}. */
    public synchronized boolean qualifies(int score) {
        if (score <= 0) return false;
        if (entries.size < CAPACITY) return true;
        return score > entries.peek().score;
    }

    /**
     * Inserts a new run if it beats the current worst-of-top-N. Returns the
     * rank (1-based) of the inserted row, or 0 if it didn't qualify.
     */
    public synchronized int submit(String name, int score, int height) {
        if (!qualifies(score)) return 0;
        HighScoreEntry e = new HighScoreEntry(name, score, height, System.currentTimeMillis());

        int insertIdx = entries.size;
        for (int i = 0; i < entries.size; i++) {
            if (e.score > entries.get(i).score) { insertIdx = i; break; }
        }
        entries.insert(insertIdx, e);
        while (entries.size > CAPACITY) entries.removeIndex(entries.size - 1);
        persist();
        return insertIdx + 1;
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private void load() {
        entries.clear();
        String raw = save.getLocalScoresRaw();
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) continue;
            try {
                String name   = parts[0];
                int    score  = Integer.parseInt(parts[1]);
                int    height = Integer.parseInt(parts[2]);
                long   ts     = Long.parseLong(parts[3]);
                entries.add(new HighScoreEntry(name, score, height, ts));
            } catch (NumberFormatException ignored) {
                // Corrupt row — skip silently rather than wipe everything.
            }
        }
        // Defensive re-sort in case the file was hand-edited.
        entries.sort((a, b) -> Integer.compare(b.score, a.score));
        while (entries.size > CAPACITY) entries.removeIndex(entries.size - 1);
    }

    private void persist() {
        StringBuilder sb = new StringBuilder(entries.size * 24);
        for (int i = 0; i < entries.size; i++) {
            HighScoreEntry e = entries.get(i);
            if (i > 0) sb.append('\n');
            sb.append(e.name).append('|')
              .append(e.score).append('|')
              .append(e.height).append('|')
              .append(e.timestamp);
        }
        save.setLocalScoresRaw(sb.toString());
    }
}
