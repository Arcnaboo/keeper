package arc.keeper.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

/**
 * Thin wrapper around libGDX {@link Preferences} for cross-platform persistence
 * (uses an XML file on desktop and SharedPreferences on Android).
 */
public class SaveData {
    private static final String PREFS = "thumbkeeper.save";

    private static final String K_HIGH_SCORE     = "highScore";
    private static final String K_BEST_HEIGHT    = "bestHeight";
    private static final String K_TOTAL_RUNS     = "totalRuns";
    private static final String K_TOTAL_JUMPS    = "totalJumps";
    private static final String K_TOTAL_DISTANCE = "totalDistance";
    private static final String K_ACTIVE_SKIN    = "activeSkin";
    private static final String K_UNLOCKED       = "skin.";          // prefix per skin id
    private static final String K_AUDIO_ON       = "audioOn";
    private static final String K_DAILY_SEED     = "dailySeed";
    private static final String K_DAILY_BEST     = "dailyBest";

    private final Preferences prefs;

    public SaveData() {
        prefs = Gdx.app.getPreferences(PREFS);
    }

    public int  getHighScore()        { return prefs.getInteger(K_HIGH_SCORE, 0); }
    public float getBestHeight()      { return prefs.getFloat(K_BEST_HEIGHT, 0f); }
    public int  getTotalRuns()        { return prefs.getInteger(K_TOTAL_RUNS, 0); }
    public int  getTotalJumps()       { return prefs.getInteger(K_TOTAL_JUMPS, 0); }
    public float getTotalDistance()   { return prefs.getFloat(K_TOTAL_DISTANCE, 0f); }
    public String getActiveSkin()     { return prefs.getString(K_ACTIVE_SKIN, "cyan"); }
    public boolean isAudioOn()        { return prefs.getBoolean(K_AUDIO_ON, true); }
    public long getDailySeed()        { return prefs.getLong(K_DAILY_SEED, 0L); }
    public int  getDailyBest()        { return prefs.getInteger(K_DAILY_BEST, 0); }

    public boolean isSkinUnlocked(String id) {
        // The default starter skin is always unlocked.
        if ("cyan".equals(id)) return true;
        return prefs.getBoolean(K_UNLOCKED + id, false);
    }

    public void unlockSkin(String id) {
        prefs.putBoolean(K_UNLOCKED + id, true);
        prefs.flush();
    }

    public void setActiveSkin(String id) {
        prefs.putString(K_ACTIVE_SKIN, id);
        prefs.flush();
    }

    public void setAudioOn(boolean on) {
        prefs.putBoolean(K_AUDIO_ON, on);
        prefs.flush();
    }

    public void setDaily(long seed, int best) {
        prefs.putLong(K_DAILY_SEED, seed);
        prefs.putInteger(K_DAILY_BEST, best);
        prefs.flush();
    }

    /**
     * Records the outcome of a finished run. Returns true if the score was a new personal best.
     */
    public boolean recordRun(int score, float height, int jumps) {
        boolean newBest = false;
        if (score > getHighScore()) {
            prefs.putInteger(K_HIGH_SCORE, score);
            newBest = true;
        }
        if (height > getBestHeight()) {
            prefs.putFloat(K_BEST_HEIGHT, height);
        }
        prefs.putInteger(K_TOTAL_RUNS, getTotalRuns() + 1);
        prefs.putInteger(K_TOTAL_JUMPS, getTotalJumps() + jumps);
        prefs.putFloat(K_TOTAL_DISTANCE, getTotalDistance() + height);
        prefs.flush();
        return newBest;
    }
}
