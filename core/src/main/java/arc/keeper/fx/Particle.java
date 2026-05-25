package arc.keeper.fx;

import com.badlogic.gdx.graphics.Color;

/** Lightweight particle struct — owned and recycled by {@link ParticleSystem}. */
class Particle {
    float x, y;
    float vx, vy;
    float ax, ay;
    float life;       // seconds remaining
    float lifeMax;
    float size;
    float sizeEnd;
    Color colorStart = new Color();
    Color colorEnd   = new Color();
    /** Additive blend gives the neon glow look; opaque rects look much flatter. */
    boolean additive;
    /** Slight drag — 0 = no friction, 1 = instantly stop. */
    float drag;
}
