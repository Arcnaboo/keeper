package arc.keeper;

import com.badlogic.gdx.graphics.Color;

/**
 * Game-wide constants: virtual world dimensions, physics tuning, palette, and difficulty curves.
 * All in one place so designers can tweak feel without hunting through files.
 */
public final class Constants {
    private Constants() {}

    // ---------------------------------------------------------------------
    // World / camera
    // ---------------------------------------------------------------------
    /** Virtual world width — designed for a 9:16 portrait phone. */
    public static final float WORLD_W = 360f;
    public static final float WORLD_H = 640f;

    /** Width of the playable tower column, centered horizontally in the world. */
    public static final float TOWER_WIDTH = 320f;
    public static final float TOWER_LEFT = (WORLD_W - TOWER_WIDTH) * 0.5f;
    public static final float TOWER_RIGHT = TOWER_LEFT + TOWER_WIDTH;

    /** Thickness of the tower's left/right walls. */
    public static final float WALL_THICKNESS = 14f;

    /** Player vertical offset from camera center (player sits slightly below mid-screen). */
    public static final float CAMERA_PLAYER_OFFSET = 90f;

    // ---------------------------------------------------------------------
    // Player physics
    // ---------------------------------------------------------------------
    public static final float PLAYER_SIZE = 26f;
    public static final float GRAVITY = 1450f;
    public static final float WALL_SLIDE_GRAVITY = 280f;

    /** Minimum and maximum jump impulse magnitudes (units / second). */
    public static final float JUMP_MIN_IMPULSE = 470f;
    public static final float JUMP_MAX_IMPULSE = 1080f;

    /** Time in seconds to fully charge the jump bar. */
    public static final float JUMP_CHARGE_TIME = 0.55f;

    /** Seconds between automatic jumps when AI assist is enabled. */
    public static final float AI_ASSIST_INTERVAL = 60f;

    /** Wall-jump horizontal kick (pushed away from the wall). */
    public static final float WALL_JUMP_HORIZONTAL_BOOST = 280f;

    /** A jump always has at least this fraction of vertical component (keeps you climbing). */
    public static final float MIN_VERTICAL_FRACTION = 0.32f;

    /** Friction applied to horizontal velocity when grounded on a non-ice platform. */
    public static final float GROUND_FRICTION = 9.0f;
    /** Friction applied while on slippery (ice) platforms. */
    public static final float ICE_FRICTION = 0.55f;

    /** Maximum horizontal speed clamp (lets bounce pad + wall jump still feel fast). */
    public static final float MAX_HORIZONTAL_SPEED = 540f;
    /** Hard cap so terminal velocity doesn't escape collision detection. */
    public static final float MAX_VERTICAL_SPEED = 1400f;

    /** Coyote-time: window after walking off a ledge during which you can still jump. */
    public static final float COYOTE_TIME = 0.10f;
    /** Jump buffer: window before landing during which a queued tap will fire on landing. */
    public static final float JUMP_BUFFER_TIME = 0.12f;

    // ---------------------------------------------------------------------
    // Tower / death line
    // ---------------------------------------------------------------------
    /** Starting upward speed of the death line, in world units per second. */
    public static final float DEATH_LINE_START_SPEED = 22f;
    /** Death line speed after the difficulty ramp is complete. */
    public static final float DEATH_LINE_MAX_SPEED = 95f;
    /** Height (player Y) at which the death line speed reaches its max. */
    public static final float DEATH_LINE_RAMP_HEIGHT = 9000f;

    /** Vertical distance below which the death line shows danger glow. */
    public static final float DANGER_GLOW_DIST = 220f;

    // ---------------------------------------------------------------------
    // Scoring
    // ---------------------------------------------------------------------
    /** 1 height unit = this many score points. */
    public static final float SCORE_PER_UNIT = 0.10f;
    /** Bonus per chained perfect-tap jump (released within sweet spot). */
    public static final int PERFECT_JUMP_BONUS = 25;
    /** Bonus per laser/spike near-miss (counted at most once per hazard). */
    public static final int NEAR_MISS_BONUS = 40;

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------
    public static final float SLOW_MO_NEAR_MISS_DURATION = 0.22f;
    public static final float SLOW_MO_SCALE = 0.30f;
    public static final float MAX_TRAUMA = 1f;
    public static final float TRAUMA_DECAY = 1.4f;

    // ---------------------------------------------------------------------
    // Palette — neon synthwave on dark purple/black
    // ---------------------------------------------------------------------
    public static final Color BG_TOP = c("0c0414");
    public static final Color BG_BOT = c("050208");
    public static final Color GRID   = c("1a0a2e");

    public static final Color CYAN    = c("4df9ff");
    public static final Color MAGENTA = c("ff4dc5");
    public static final Color ORANGE  = c("ff8a3c");
    public static final Color PURPLE  = c("9a4bff");
    public static final Color WHITE   = c("ffffff");
    public static final Color RED     = c("ff4d6a");
    public static final Color YELLOW  = c("ffe24d");
    public static final Color MINT    = c("4dffaf");

    /** Pretty hex -> Color helper. Accepts 6-char "rrggbb" strings. */
    private static Color c(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return new Color(r / 255f, g / 255f, b / 255f, 1f);
    }

    /** Linear ramp from a -> b parameterised by t in [0,1]. */
    public static float lerp(float a, float b, float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return a + (b - a) * t;
    }

    /** Smoothstep easing — much nicer for camera & color transitions than raw lerp. */
    public static float smoothstep(float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    public static float clamp(float x, float min, float max) {
        return x < min ? min : (x > max ? max : x);
    }
}
