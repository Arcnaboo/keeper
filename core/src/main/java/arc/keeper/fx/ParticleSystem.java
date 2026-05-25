package arc.keeper.fx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;

/**
 * Pooled particle system that draws additive rects through a SpriteBatch.
 * Used for jump dust, landing bursts, trail sparks, charge glow, death shatter, etc.
 */
public class ParticleSystem {

    private final Array<Particle> alive = new Array<>(256);
    private final Pool<Particle> pool = new Pool<Particle>() {
        @Override protected Particle newObject() { return new Particle(); }
    };

    public int count() { return alive.size; }

    public void clear() {
        for (int i = 0; i < alive.size; i++) pool.free(alive.get(i));
        alive.clear();
    }

    public void update(float dt) {
        for (int i = alive.size - 1; i >= 0; i--) {
            Particle p = alive.get(i);
            p.vx += p.ax * dt;
            p.vy += p.ay * dt;
            float dragFactor = 1f - p.drag * dt;
            if (dragFactor < 0f) dragFactor = 0f;
            p.vx *= dragFactor;
            p.vy *= dragFactor;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.life -= dt;
            if (p.life <= 0f) {
                pool.free(p);
                alive.removeIndex(i);
            }
        }
    }

    /**
     * Draws all particles using the provided batch and 1x1 pixel texture.
     * Caller must have begun the batch already; this method temporarily switches
     * blend modes between additive and standard alpha as needed.
     */
    public void draw(SpriteBatch batch, Texture pixel) {
        if (alive.size == 0) return;
        boolean wasAdditive = false;
        batch.setBlendFunctionSeparate(
            com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA,
            com.badlogic.gdx.graphics.GL20.GL_ONE,       com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        Color tmp = new Color();
        for (int i = 0; i < alive.size; i++) {
            Particle p = alive.get(i);
            if (p.additive != wasAdditive) {
                batch.flush();
                if (p.additive) {
                    batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                                           com.badlogic.gdx.graphics.GL20.GL_ONE);
                } else {
                    batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                                           com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
                }
                wasAdditive = p.additive;
            }
            float t = 1f - p.life / p.lifeMax;
            float size = p.size + (p.sizeEnd - p.size) * t;
            tmp.r = p.colorStart.r + (p.colorEnd.r - p.colorStart.r) * t;
            tmp.g = p.colorStart.g + (p.colorEnd.g - p.colorStart.g) * t;
            tmp.b = p.colorStart.b + (p.colorEnd.b - p.colorStart.b) * t;
            tmp.a = p.colorStart.a + (p.colorEnd.a - p.colorStart.a) * t;
            batch.setColor(tmp);
            batch.draw(pixel, p.x - size * 0.5f, p.y - size * 0.5f, size, size);
        }
        // Restore default blend so subsequent draws aren't affected.
        if (wasAdditive) {
            batch.flush();
            batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                                   com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
        batch.setColor(Color.WHITE);
    }

    // ---------------------------------------------------------------------
    // Emitters — keep call sites short.
    // ---------------------------------------------------------------------

    /** Burst of particles around a point, exploding outward with gravity. */
    public void burst(float x, float y, int n, Color a, Color b, float speed, float life,
                      float size, boolean additive) {
        for (int i = 0; i < n; i++) {
            Particle p = spawn();
            p.x = x; p.y = y;
            float ang = MathUtils.random(0f, MathUtils.PI2);
            float spd = speed * MathUtils.random(0.4f, 1.0f);
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.ax = 0; p.ay = -260f;
            p.drag = 2.4f;
            p.life = p.lifeMax = life * MathUtils.random(0.7f, 1.3f);
            p.size = size; p.sizeEnd = size * 0.2f;
            p.colorStart.set(a);
            p.colorEnd.set(b.r, b.g, b.b, 0f);
            p.additive = additive;
        }
    }

    /** Small ground-puff for jumps & landings (sprays sideways). */
    public void dust(float x, float y, int n, Color a, float sign) {
        for (int i = 0; i < n; i++) {
            Particle p = spawn();
            p.x = x + MathUtils.random(-6f, 6f);
            p.y = y + MathUtils.random(0f, 4f);
            p.vx = sign * MathUtils.random(60f, 180f) + MathUtils.random(-40f, 40f);
            p.vy = MathUtils.random(40f, 160f);
            p.ax = 0; p.ay = -340f;
            p.drag = 3.0f;
            p.life = p.lifeMax = MathUtils.random(0.18f, 0.40f);
            p.size = 6f; p.sizeEnd = 1.5f;
            p.colorStart.set(a);
            p.colorEnd.set(a.r, a.g, a.b, 0f);
            p.additive = true;
        }
    }

    /** Continuous trail emitter — call this every frame the player is moving fast. */
    public void trail(float x, float y, float vx, float vy, Color color) {
        Particle p = spawn();
        p.x = x; p.y = y;
        p.vx = -vx * 0.04f + MathUtils.random(-12f, 12f);
        p.vy = -vy * 0.04f + MathUtils.random(-12f, 12f);
        p.ax = 0; p.ay = 0;
        p.drag = 1.5f;
        p.life = p.lifeMax = 0.32f;
        p.size = 7f; p.sizeEnd = 1.5f;
        p.colorStart.set(color);
        p.colorEnd.set(color.r, color.g, color.b, 0f);
        p.additive = true;
    }

    /** Bright spark used for near-miss markers, pickups, and bounce-pad releases. */
    public void spark(float x, float y, int n, Color c) {
        for (int i = 0; i < n; i++) {
            Particle p = spawn();
            p.x = x; p.y = y;
            float ang = MathUtils.random(0f, MathUtils.PI2);
            float spd = MathUtils.random(180f, 360f);
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.ax = 0; p.ay = -120f;
            p.drag = 2.2f;
            p.life = p.lifeMax = MathUtils.random(0.25f, 0.55f);
            p.size = 4.5f; p.sizeEnd = 0.5f;
            p.colorStart.set(c);
            p.colorEnd.set(c.r, c.g, c.b, 0f);
            p.additive = true;
        }
    }

    /** Charge-up halo around the player — call while charging. */
    public void chargeHalo(float x, float y, Color c, float t) {
        for (int i = 0; i < 2; i++) {
            Particle p = spawn();
            float ang = MathUtils.random(0f, MathUtils.PI2);
            float r = 22f + 12f * t;
            p.x = x + (float) Math.cos(ang) * r;
            p.y = y + (float) Math.sin(ang) * r;
            p.vx = -((float) Math.cos(ang)) * 60f * (0.5f + t);
            p.vy = -((float) Math.sin(ang)) * 60f * (0.5f + t);
            p.ax = 0; p.ay = 0;
            p.drag = 1.6f;
            p.life = p.lifeMax = 0.25f;
            p.size = 5f; p.sizeEnd = 0f;
            p.colorStart.set(c.r, c.g, c.b, 0.7f + 0.3f * t);
            p.colorEnd.set(c.r, c.g, c.b, 0f);
            p.additive = true;
        }
    }

    private Particle spawn() {
        Particle p = pool.obtain();
        alive.add(p);
        return p;
    }
}
