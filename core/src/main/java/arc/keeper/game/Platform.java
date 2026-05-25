package arc.keeper.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

import arc.keeper.Constants;

/**
 * A single tower element. All platform behaviors live here, branched on {@link #type},
 * so we don't have to scatter dozens of subclasses across the project.
 *
 * <p>Platforms are <em>jump-through</em>: the player only collides when landing on top.
 * Hazards (SPIKE, LASER, FAKE) override collision behavior.
 */
public class Platform {

    public final PlatformType type;
    /** Bottom-left corner. */
    public float x, y;
    /** Width / height of the AABB. */
    public float w, h;
    /** The platform's full color (drawn with subtle bloom). */
    public final Color color = new Color();

    // --- type-specific state ---
    /** Always-rising timer used for animations (LASER, MAGNETIC, MOVING_*, TELEPORT). */
    public float timer;
    /** Countdown used by DISAPPEARING after the player triggers it. */
    public float vanishTimer;
    /** Whether the player has already triggered this platform once. */
    public boolean triggered;
    /** Whether the platform should be removed by the tower (e.g. after disappearing). */
    public boolean dead;
    /** Rotation in degrees for ROTATING platforms. */
    public float angleDeg;
    public float angularVelocity = 90f;
    /** For oscillating types — base position and amplitude. */
    public float baseX, baseY, ampX, ampY, phase, speed = 1f;
    /** For MAGNETIC — radius of pull. */
    public float radius = 110f;
    /** For LASER — on/off cycle. */
    public float laserPeriod = 1.6f;
    public float laserDutyOn = 0.6f;   // fraction of period the laser is active
    public float laserOffset = 0f;     // phase shift so multiple lasers desync
    /** For TELEPORT pairs. */
    public Platform pair;
    /** Cooldown after teleporting to avoid re-trigger ping-pong. */
    public float teleportCooldown;
    /** For FAKE — fades when player is close enough to "see" the trick. */
    public float fakeAlpha = 1f;
    /** For CRUMBLING — visible crack intensity 0..1. */
    public float crumble;
    /** Tracks whether this hazard's near-miss bonus has already been awarded. */
    public boolean nearMissAwarded;

    /** Cached reusable rectangle to avoid allocations per-frame. */
    private final Rectangle tmpRect = new Rectangle();

    public Platform(PlatformType type, float x, float y, float w, float h) {
        this.type = type;
        this.x = x; this.y = y; this.w = w; this.h = h;
        this.baseX = x; this.baseY = y;
        switch (type) {
            case STABLE:        color.set(Constants.CYAN); break;
            case DISAPPEARING:  color.set(Constants.MAGENTA); break;
            case MOVING_H:
            case MOVING_V:      color.set(Constants.PURPLE); break;
            case ROTATING:      color.set(Constants.MINT); break;
            case CRUMBLING:     color.set(Constants.ORANGE); break;
            case BOUNCE:        color.set(Constants.YELLOW); break;
            case ICE:           color.set(0.7f, 0.96f, 1f, 1f); break;
            case MAGNETIC:      color.set(0.55f, 0.45f, 1f, 1f); break;
            case GRAVITY_FLIP:  color.set(0.95f, 0.55f, 1f, 1f); break;
            case TELEPORT:      color.set(0.40f, 0.95f, 1f, 1f); break;
            case FAKE:          color.set(Constants.CYAN); break;
            case SPIKE:         color.set(Constants.RED); break;
            case LASER:         color.set(Constants.RED); break;
        }
    }

    /** AABB of the collidable surface. */
    public Rectangle bounds() {
        tmpRect.set(x, y, w, h);
        return tmpRect;
    }

    /** True if currently solid / collidable. Hazards return false (they kill, not land). */
    public boolean isSolid() {
        if (dead) return false;
        switch (type) {
            case SPIKE:
            case LASER:
                return false;
            case FAKE:
                return false; // looks solid, isn't
            case DISAPPEARING:
                // Once triggered, the platform "vanishes" after the countdown expires.
                return !triggered || vanishTimer > 0f;
            default:
                return true;
        }
    }

    /** True if this is a hazard (SPIKE / active LASER). */
    public boolean isHazard() {
        if (dead) return false;
        if (type == PlatformType.SPIKE) return true;
        if (type == PlatformType.LASER) return laserOn();
        return false;
    }

    /** Returns true if a LASER is currently in its "ON" half of the duty cycle. */
    public boolean laserOn() {
        float p = (timer + laserOffset) % laserPeriod;
        if (p < 0f) p += laserPeriod;
        return p < laserPeriod * laserDutyOn;
    }

    /** Predicate: how visible the laser charge-up is (used for warning glow). */
    public float laserChargeFraction() {
        // The half-second leading into the "on" phase brightens up.
        float warning = 0.4f;
        float p = (timer + laserOffset) % laserPeriod;
        if (p < 0f) p += laserPeriod;
        float offPhaseStart = laserPeriod * laserDutyOn;
        if (p < offPhaseStart) return 1f;
        float toOn = laserPeriod - p;
        if (toOn >= warning) return 0f;
        return 1f - (toOn / warning);
    }

    public void update(float dt) {
        timer += dt;
        teleportCooldown = Math.max(0f, teleportCooldown - dt);

        switch (type) {
            case DISAPPEARING:
                if (triggered) {
                    vanishTimer -= dt;
                    if (vanishTimer <= 0f) dead = true;
                }
                break;
            case MOVING_H:
                x = baseX + (float) Math.sin(timer * speed) * ampX;
                break;
            case MOVING_V:
                y = baseY + (float) Math.sin(timer * speed) * ampY;
                break;
            case ROTATING:
                angleDeg = (angleDeg + angularVelocity * dt) % 360f;
                break;
            case CRUMBLING:
                if (triggered) {
                    crumble = Math.min(1f, crumble + dt * 1.6f);
                    if (crumble >= 1f) dead = true;
                }
                break;
            default: break;
        }
    }

    /** Called once when the player lands on this platform (top-edge collision). */
    public void onPlayerLand(Player player) {
        switch (type) {
            case DISAPPEARING:
                if (!triggered) {
                    triggered = true;
                    vanishTimer = 0.35f;
                }
                break;
            case CRUMBLING:
                if (!triggered) triggered = true;
                break;
            case BOUNCE:
                player.applyBounce(1180f);
                break;
            case ICE:
                player.iceContact = 0.10f;
                break;
            case GRAVITY_FLIP:
                player.lowGravityTimer = 2.0f;
                player.applyBounce(720f);
                break;
            default: break;
        }
    }

    /** Called every frame while the player is in MAGNETIC range. */
    public void applyMagneticPull(Player player, float dt) {
        if (type != PlatformType.MAGNETIC) return;
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        float dx = cx - player.x;
        float dy = cy - player.y;
        float d2 = dx * dx + dy * dy;
        float r2 = radius * radius;
        if (d2 < 1f || d2 > r2) return;
        float d = (float) Math.sqrt(d2);
        float falloff = 1f - d / radius;          // 0 at edge, 1 at center
        float force = 720f * falloff * falloff;
        player.vx += (dx / d) * force * dt;
        player.vy += (dy / d) * force * dt;
    }

    /** Called when player enters a TELEPORT platform. Returns target position. */
    public Vector2 teleportTarget() {
        if (type != PlatformType.TELEPORT || pair == null || teleportCooldown > 0f) return null;
        // Land on top of the paired teleporter and put it on cooldown so we don't loop.
        teleportCooldown = 0.6f;
        pair.teleportCooldown = 0.6f;
        return new Vector2(pair.x + pair.w * 0.5f, pair.y + pair.h + 1f);
    }

    /** Returns whether the player overlaps this platform's hazardous area. */
    public boolean overlapsHazard(float px, float py, float ps) {
        if (!isHazard()) return false;
        float halfP = ps * 0.5f;
        return px + halfP > x && px - halfP < x + w &&
               py + halfP > y && py - halfP < y + h;
    }

    /** Draws the platform with a subtle outer glow. */
    public void draw(SpriteBatch batch, Texture pixel) {
        if (dead) return;
        switch (type) {
            case SPIKE: drawSpike(batch, pixel); break;
            case LASER: drawLaser(batch, pixel); break;
            case ROTATING: drawRotating(batch, pixel); break;
            case TELEPORT: drawTeleport(batch, pixel); break;
            case MAGNETIC: drawMagnetic(batch, pixel); break;
            case DISAPPEARING: drawDisappearing(batch, pixel); break;
            case CRUMBLING: drawCrumbling(batch, pixel); break;
            case FAKE: drawFake(batch, pixel); break;
            default: drawRect(batch, pixel, x, y, w, h, color, 1f); break;
        }
    }

    // -----------------------------------------------------------------
    // Drawing helpers
    // -----------------------------------------------------------------

    /** Draws a glowing rectangle (additive outer glow + opaque core). */
    static void drawRect(SpriteBatch batch, Texture pixel, float x, float y, float w, float h, Color c, float alpha) {
        // Outer glow (additive)
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE);
        batch.setColor(c.r, c.g, c.b, 0.35f * alpha);
        batch.draw(pixel, x - 6f, y - 6f, w + 12f, h + 12f);
        batch.setColor(c.r, c.g, c.b, 0.55f * alpha);
        batch.draw(pixel, x - 2f, y - 2f, w + 4f, h + 4f);

        // Inner solid
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(c.r, c.g, c.b, alpha);
        batch.draw(pixel, x, y, w, h);

        // Top highlight
        batch.setColor(1f, 1f, 1f, 0.55f * alpha);
        batch.draw(pixel, x, y + h - 2f, w, 2f);
        batch.setColor(Color.WHITE);
    }

    private void drawDisappearing(SpriteBatch batch, Texture pixel) {
        float a = 1f;
        if (triggered) a = MathUtils.clamp(vanishTimer / 0.35f, 0f, 1f);
        drawRect(batch, pixel, x, y, w, h, color, a);
    }

    private void drawCrumbling(SpriteBatch batch, Texture pixel) {
        drawRect(batch, pixel, x, y, w, h, color, 1f - 0.6f * crumble);
        // Cracks as small dark rectangles overlayed when crumbling.
        if (crumble > 0f) {
            batch.flush();
            batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
            batch.setColor(0f, 0f, 0f, 0.55f * crumble);
            int cracks = 4;
            for (int i = 0; i < cracks; i++) {
                float cx = x + (i + 0.5f) * (w / cracks);
                batch.draw(pixel, cx - 1f, y + 1f, 2f, h - 2f);
            }
            batch.setColor(Color.WHITE);
        }
    }

    private void drawFake(SpriteBatch batch, Texture pixel) {
        // Fake platforms are intentionally a hair dimmer — sharp-eyed players notice.
        Color c = new Color(color);
        c.mul(0.78f);
        c.a = 1f;
        drawRect(batch, pixel, x, y, w, h, c, fakeAlpha);
    }

    private void drawRotating(SpriteBatch batch, Texture pixel) {
        // SpriteBatch supports rotated draws; we abuse pixel to draw a thin rotated bar.
        float originX = w * 0.5f;
        float originY = h * 0.5f;

        // Glow
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE);
        batch.setColor(color.r, color.g, color.b, 0.45f);
        batch.draw(pixel, x - 6f, y - 6f, originX + 6f, originY + 6f, w + 12f, h + 12f, 1f, 1f, angleDeg, 0, 0, 1, 1, false, false);

        // Solid
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(color);
        batch.draw(pixel, x, y, originX, originY, w, h, 1f, 1f, angleDeg, 0, 0, 1, 1, false, false);
        batch.setColor(Color.WHITE);
    }

    private void drawSpike(SpriteBatch batch, Texture pixel) {
        // Stylized: a fat red rectangle with a brighter top crown to imply teeth.
        drawRect(batch, pixel, x, y, w, h, color, 1f);
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE);
        int teeth = Math.max(3, (int)(w / 8f));
        for (int i = 0; i < teeth; i++) {
            float tx = x + (i + 0.5f) * (w / teeth);
            batch.setColor(1f, 0.85f, 0.55f, 0.85f);
            batch.draw(pixel, tx - 1.5f, y + h - 1f, 3f, 5f);
        }
        batch.setColor(Color.WHITE);
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawLaser(SpriteBatch batch, Texture pixel) {
        // Always show the emitters (the rectangle), pulse the beam between them.
        Color emitter = color;
        drawRect(batch, pixel, x, y, w, h, emitter, 0.85f);

        float charge = laserChargeFraction();
        boolean on = laserOn();
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE);
        if (on) {
            batch.setColor(emitter.r, emitter.g, emitter.b, 0.55f);
            batch.draw(pixel, Constants.TOWER_LEFT, y + h * 0.5f - 6f, Constants.TOWER_WIDTH, 12f);
            batch.setColor(1f, 1f, 1f, 0.9f);
            batch.draw(pixel, Constants.TOWER_LEFT, y + h * 0.5f - 1.5f, Constants.TOWER_WIDTH, 3f);
        } else if (charge > 0f) {
            // Warning flicker.
            batch.setColor(emitter.r, emitter.g, emitter.b, 0.35f * charge);
            batch.draw(pixel, Constants.TOWER_LEFT, y + h * 0.5f - 1.5f, Constants.TOWER_WIDTH, 3f);
        }
        batch.setColor(Color.WHITE);
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawMagnetic(SpriteBatch batch, Texture pixel) {
        // Field rings around the platform.
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE);
        for (int i = 0; i < 3; i++) {
            float t = ((timer * 0.6f) + i * 0.33f) % 1f;
            float r = 14f + t * radius;
            float a = 0.20f * (1f - t);
            batch.setColor(color.r, color.g, color.b, a);
            // Faux ring drawn as 4 thin rects (top/bottom/left/right of bounding box).
            batch.draw(pixel, cx - r, cy + r - 1.5f, r * 2f, 1.5f);
            batch.draw(pixel, cx - r, cy - r, r * 2f, 1.5f);
            batch.draw(pixel, cx - r, cy - r, 1.5f, r * 2f);
            batch.draw(pixel, cx + r - 1.5f, cy - r, 1.5f, r * 2f);
        }
        batch.setColor(Color.WHITE);
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        drawRect(batch, pixel, x, y, w, h, color, 1f);
    }

    private void drawTeleport(SpriteBatch batch, Texture pixel) {
        drawRect(batch, pixel, x, y, w, h, color, 1f);
        // Glowing portal halo above the platform.
        float cx = x + w * 0.5f;
        float cy = y + h + 14f;
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE);
        for (int i = 0; i < 5; i++) {
            float r = 6f + i * 4f + (float) Math.sin(timer * 3f + i) * 1.2f;
            batch.setColor(color.r, color.g, color.b, 0.18f);
            batch.draw(pixel, cx - r, cy - r * 0.4f, r * 2f, r * 0.8f);
        }
        batch.setColor(Color.WHITE);
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
    }
}
