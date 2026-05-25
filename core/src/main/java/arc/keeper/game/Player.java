package arc.keeper.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

import arc.keeper.Constants;
import arc.keeper.data.Skin;

/**
 * The little cube the player controls. Owns its own physics, charge state, squash/stretch,
 * and rendering. Designed so the GameScreen can just call {@link #update(float, Tower)}
 * and {@link #draw(SpriteBatch, Texture)} without thinking about feel-tuning details.
 */
public class Player {

    // Cached AABB to avoid per-frame allocation.
    private final Rectangle aabb = new Rectangle();
    private final Vector2 tmpVec = new Vector2();

    public float x, y;
    public float vx, vy;
    public float size = Constants.PLAYER_SIZE;

    /** Player is touching ground (top of a platform or a floor) this frame. */
    public boolean grounded;
    /** -1 if touching left wall, +1 if right wall, 0 otherwise. */
    public int wallContact;
    /** Coyote-time countdown — > 0 means we can still jump even though grounded just became false. */
    public float coyoteTimer;
    /** Jump-buffer countdown — > 0 means we should auto-fire a queued jump on landing. */
    public float jumpBufferTimer;

    /** Currently building a charged jump (player is holding down). */
    public boolean charging;
    /** Charge level in seconds (0..JUMP_CHARGE_TIME). */
    public float chargeTime;
    /** Aim point in world coordinates — updated by the input system every drag/move. */
    public final Vector2 aim = new Vector2();
    /** Was the current charge initiated while sticking to a wall? */
    public boolean wallChargeStartedLeft;
    public boolean wallChargeStartedRight;

    // Visual / feel state.
    public float squashX = 1f, squashY = 1f;
    public float facing = 1f;             // +1 right, -1 left — purely cosmetic
    public boolean alive = true;
    public float spawnFlash; // > 0 for a couple frames after a new run begins

    // Surface modifiers (driven by platforms).
    /** Active for a short window after touching ice — friction stays low even if airborne briefly. */
    public float iceContact;
    /** Active for a short window after a GRAVITY_FLIP touch — reduces effective gravity. */
    public float lowGravityTimer;

    /** Tracks last platform stood on so we don't re-trigger DISAPPEARING etc. every frame. */
    public Platform lastPlatform;

    /** Tracks the most recent landing impact speed (for sounds / shake amount). */
    public float lastImpactSpeed;

    /** True for the single frame the player landed — read & reset by the screen. */
    public boolean landedThisFrame;
    /** True for the single frame the player jumped — read & reset by the screen. */
    public boolean jumpedThisFrame;
    /** Charge strength at the moment of the last jump release — read by audio / FX. */
    public float lastJumpStrength;

    /** Increments every time we successfully fire a jump — used for score & stats. */
    public int jumpCount;

    /** Highest y reached this run (for scoring / camera). */
    public float maxY;

    /** Skin currently rendering this player. */
    public Skin skin;

    /** Coyote-time we just spent — used so the FX layer can emit a tiny puff. */
    public boolean usedCoyote;

    public Player(float startX, float startY, Skin skin) {
        this.x = startX;
        this.y = startY;
        this.maxY = startY;
        this.aim.set(startX, startY + 200f);
        this.skin = skin;
        this.spawnFlash = 0.35f;
    }

    public Rectangle bounds() {
        aabb.set(x - size * 0.5f, y, size, size);
        return aabb;
    }

    /** Begin charging a new jump (called from input on touchDown). */
    public void beginCharge(float worldAimX, float worldAimY) {
        // Allow charging if grounded, in coyote time, or pressed against a wall.
        if (!grounded && coyoteTimer <= 0f && wallContact == 0) {
            // Buffer the jump request so it fires the moment we land/touch a wall.
            jumpBufferTimer = Constants.JUMP_BUFFER_TIME;
            charging = false;
            return;
        }
        charging = true;
        chargeTime = 0f;
        aim.set(worldAimX, worldAimY);
        wallChargeStartedLeft  = wallContact == -1;
        wallChargeStartedRight = wallContact == +1;
    }

    /** Update aim point while still holding (touchDragged). */
    public void updateAim(float worldAimX, float worldAimY) {
        aim.set(worldAimX, worldAimY);
    }

    /** Release the charged jump (touchUp). */
    public void releaseCharge() {
        if (!charging) return;
        fireJump(chargeTime / Constants.JUMP_CHARGE_TIME);
        charging = false;
        chargeTime = 0f;
    }

    /** Cancels the charge without firing — used when the player dies mid-charge. */
    public void cancelCharge() {
        charging = false;
        chargeTime = 0f;
    }

    /**
     * Internal: actually fires the jump in the current aim direction at the given strength (0..1).
     * Walls force the horizontal component to point away from them so wall-jumps always work.
     */
    private void fireJump(float strength) {
        strength = MathUtils.clamp(strength, 0f, 1f);
        lastJumpStrength = strength;
        // Direction from player to aim point. Clamp to upper hemisphere so you never
        // accidentally launch yourself into the death pit.
        float dx = aim.x - x;
        float dy = aim.y - y;
        // Wall jumps force horizontal direction away from wall.
        if (wallChargeStartedLeft)  dx = Math.max(dx,  20f);
        if (wallChargeStartedRight) dx = Math.min(dx, -20f);
        if (dy < Constants.MIN_VERTICAL_FRACTION * Math.abs(dx) + 30f)
            dy = Constants.MIN_VERTICAL_FRACTION * Math.abs(dx) + 30f;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) { dx = 0f; dy = 1f; len = 1f; }
        dx /= len; dy /= len;

        float impulse = Constants.lerp(Constants.JUMP_MIN_IMPULSE, Constants.JUMP_MAX_IMPULSE, strength);
        vx = dx * impulse;
        vy = dy * impulse;
        // Squash sideways, stretch vertically — anticipation pre-distort.
        squashX = 0.75f;
        squashY = 1.30f;
        grounded = false;
        wallContact = 0;
        coyoteTimer = 0f;
        jumpBufferTimer = 0f;
        wallChargeStartedLeft = wallChargeStartedRight = false;
        jumpCount++;
        jumpedThisFrame = true;
        facing = dx >= 0 ? 1f : -1f;
    }

    /** Used by BOUNCE pads / GRAVITY_FLIP — adds a strong upward impulse, ignoring charge. */
    public void applyBounce(float impulse) {
        vy = impulse;
        grounded = false;
        squashX = 0.65f;
        squashY = 1.45f;
        jumpedThisFrame = true;
    }

    /**
     * Main physics update. Order:
     *   1. Time-based modifiers (charge, surface buffs, animations).
     *   2. Gravity (+ wall-slide reduction).
     *   3. Friction.
     *   4. Integrate velocity → tentative position.
     *   5. Resolve collisions with platforms (jump-through, hazards handled separately).
     */
    public void update(float dt, Tower tower) {
        landedThisFrame = false;
        jumpedThisFrame = false;
        usedCoyote = false;

        // Charge accumulation — clamped to max, used to drive both impulse and FX.
        if (charging) chargeTime = Math.min(Constants.JUMP_CHARGE_TIME, chargeTime + dt);

        spawnFlash = Math.max(0f, spawnFlash - dt);
        iceContact = Math.max(0f, iceContact - dt);
        lowGravityTimer = Math.max(0f, lowGravityTimer - dt);

        // Visual squash recovery — exponential easing back to 1, 1.
        squashX += (1f - squashX) * Math.min(1f, dt * 12f);
        squashY += (1f - squashY) * Math.min(1f, dt * 12f);

        // Coyote / buffer timers.
        if (grounded || wallContact != 0) {
            coyoteTimer = Constants.COYOTE_TIME;
            // If we have a queued jump and just landed/touched a wall, auto-charge a tiny pulse.
            if (jumpBufferTimer > 0f) {
                charging = true;
                chargeTime = 0.10f;            // a small pre-charge so buffered jumps still feel quick
                wallChargeStartedLeft  = wallContact == -1;
                wallChargeStartedRight = wallContact == +1;
                jumpBufferTimer = 0f;
            }
        } else {
            coyoteTimer = Math.max(0f, coyoteTimer - dt);
            jumpBufferTimer = Math.max(0f, jumpBufferTimer - dt);
        }

        // Charging "freezes" the player — both for clarity and to make trajectory previews trustworthy.
        if (charging && (grounded || wallContact != 0)) {
            vx = 0f;
            vy = 0f;
        } else {
            // Gravity (reduced while wall-sliding or in low-grav buff).
            float gravity = Constants.GRAVITY;
            if (lowGravityTimer > 0f) gravity *= 0.45f;
            if (!grounded && wallContact != 0 && vy < 0f) {
                gravity = Constants.WALL_SLIDE_GRAVITY;
            }
            vy -= gravity * dt;

            // Ground friction (low on ice).
            if (grounded) {
                float fric = iceContact > 0f ? Constants.ICE_FRICTION : Constants.GROUND_FRICTION;
                vx -= vx * Math.min(1f, fric * dt);
            }

            // Clamp velocities so collisions don't tunnel.
            vx = MathUtils.clamp(vx, -Constants.MAX_HORIZONTAL_SPEED, Constants.MAX_HORIZONTAL_SPEED);
            vy = MathUtils.clamp(vy, -Constants.MAX_VERTICAL_SPEED, Constants.MAX_VERTICAL_SPEED);

            // Magnetic forces from any nearby MAGNETIC platforms.
            for (int i = 0; i < tower.platforms.size; i++) {
                Platform p = tower.platforms.get(i);
                p.applyMagneticPull(this, dt);
            }
        }

        // Integrate.
        float oldY = y;
        x += vx * dt;
        y += vy * dt;

        resolveCollisions(tower, oldY);

        // Track max height climbed (for scoring & camera).
        if (y > maxY) maxY = y;

        // Update facing direction visually based on motion.
        if (Math.abs(vx) > 50f) facing = vx > 0 ? 1f : -1f;
    }

    /**
     * Handles wall + jump-through-platform collisions. Hazards are checked by the screen
     * (death is a screen-level concern), but we do trigger landing-only behaviors here so
     * platforms like BOUNCE / DISAPPEARING / CRUMBLING fire reliably at impact.
     */
    private void resolveCollisions(Tower tower, float oldY) {
        // Walls.
        float left  = Constants.TOWER_LEFT + Constants.WALL_THICKNESS;
        float right = Constants.TOWER_RIGHT - Constants.WALL_THICKNESS;
        boolean prevWall = wallContact != 0;
        wallContact = 0;
        if (x - size * 0.5f < left) {
            x = left + size * 0.5f;
            if (vx < 0f) vx = 0f;
            wallContact = -1;
        } else if (x + size * 0.5f > right) {
            x = right - size * 0.5f;
            if (vx > 0f) vx = 0f;
            wallContact = +1;
        }

        // Wall slide on a wall while airborne — friction on vy to make wall slides slow / sticky.
        if (wallContact != 0 && !grounded) {
            // Subtle stickiness so wall jumps are reliable.
            if (vy < -50f) vy *= 0.94f;
        }
        // If we just lost wall contact, no special handling needed; coyote covers the case.
        // Use prevWall only to potentially trigger FX in a future enhancement.
        if (prevWall && wallContact == 0) { /* could emit sparks */ }

        // Platforms (jump-through). We only treat the top edge as solid, and only when
        // falling and the previous y put the player feet above the platform top.
        grounded = false;
        Platform stoodOn = null;
        float impactVy = 0f;
        if (vy <= 0f) {
            float feetOld = oldY;
            float feetNew = y;
            for (int i = 0; i < tower.platforms.size; i++) {
                Platform p = tower.platforms.get(i);
                if (!p.isSolid()) continue;
                if (p.type == PlatformType.ROTATING) {
                    // Treat the rotating bar as collidable only when it's near-horizontal.
                    float absAngle = Math.abs(((p.angleDeg % 180f) + 180f) % 180f - 90f);
                    if (absAngle < 55f) continue;
                }
                float px = p.x, py = p.y, pw = p.w, ph = p.h;
                float playerLeft  = x - size * 0.5f;
                float playerRight = x + size * 0.5f;
                if (playerRight < px || playerLeft > px + pw) continue;
                float platformTop = py + ph;
                if (feetOld >= platformTop - 1f && feetNew <= platformTop) {
                    impactVy = vy; // captured BEFORE we zero
                    y = platformTop;
                    vy = 0f;
                    grounded = true;
                    stoodOn = p;
                    break;
                }
            }
        }

        if (stoodOn != null) {
            if (stoodOn != lastPlatform) {
                lastImpactSpeed = -Math.min(0f, impactVy);
                landedThisFrame = true;
                // Bigger impacts squash harder — gives free game-feel for free falls.
                float impactT = MathUtils.clamp(lastImpactSpeed / 900f, 0f, 1f);
                squashX = 1.15f + 0.30f * impactT;
                squashY = 0.85f - 0.25f * impactT;
                stoodOn.onPlayerLand(this);
                lastPlatform = stoodOn;
            }
        } else {
            lastPlatform = null;
        }
    }

    /** Kills the player. Idempotent — first call wins so death FX play exactly once. */
    public boolean die() {
        if (!alive) return false;
        alive = false;
        cancelCharge();
        return true;
    }

    /** Draws the cube with squash & stretch, glow, charge halo, and aim arrow. */
    public void draw(SpriteBatch batch, Texture pixel) {
        if (!alive) return;
        if (skin.texture != null) {
            drawTexturedSkin(batch);
        } else {
            drawProceduralCube(batch, pixel);
        }
    }

    /**
     * Renders the player using a baked PNG skin (the artwork already includes a glow
     * and eyes). We center the texture on the hitbox center and slightly oversize it
     * so the glow extends past the collision rectangle — feels juicier and more alive.
     */
    private void drawTexturedSkin(SpriteBatch batch) {
        Texture tex = skin.texture;
        // The cube body fills roughly 55% of the source image; the remaining 45% is
        // the soft glow ring. Drawing at 1.8x hitbox makes the cube core read at
        // hitbox size and lets the glow spill over without enlarging collisions.
        float visualScale = 1.8f;
        float baseSize = size * visualScale;
        float sx = baseSize * squashX;
        float sy = baseSize * squashY;
        float drawX = x - sx * 0.5f;
        float drawY = (y + size * 0.5f) - sy * 0.5f;

        Color glow = skin.glow;
        float chargeFrac = chargeFraction();

        // Additive charge halo: pulses brighter as the jump charges.
        if (chargeFrac > 0f || charging) {
            batch.flush();
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            float aurora = 1f + 0.35f * chargeFrac;
            batch.setColor(glow.r, glow.g, glow.b, 0.35f + 0.45f * chargeFrac);
            batch.draw(tex,
                drawX - sx * 0.10f * aurora, drawY - sy * 0.10f * aurora,
                sx * (1f + 0.20f * aurora), sy * (1f + 0.20f * aurora));
        }

        // Spawn-in flash — full-bright additive overlay that fades over 0.35s.
        if (spawnFlash > 0f) {
            batch.flush();
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            batch.setColor(1f, 1f, 1f, spawnFlash / 0.35f * 0.85f);
            batch.draw(tex, drawX, drawY, sx, sy);
        }

        // Main skin sprite — standard alpha blend; the luminance-keyed alpha keeps
        // the surrounding void of the source PNG cleanly transparent.
        batch.flush();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(Color.WHITE);
        batch.draw(tex, drawX, drawY, sx, sy);
    }

    /** Fallback when a skin has no PNG — keeps the game playable even without assets. */
    private void drawProceduralCube(SpriteBatch batch, Texture pixel) {
        float sx = size * squashX;
        float sy = size * squashY;
        float drawX = x - sx * 0.5f;
        float drawY = y + (size - sy) * 0.5f - 0.5f;
        if (squashY < 1f) drawY = y;

        Color body = skin.body;
        Color glow = skin.glow;

        batch.flush();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        float glowSize = 14f + (charging ? chargeFraction() * 18f : 0f);
        batch.setColor(glow.r, glow.g, glow.b, 0.45f + 0.4f * chargeFraction());
        batch.draw(pixel, drawX - glowSize, drawY - glowSize, sx + glowSize * 2f, sy + glowSize * 2f);
        batch.setColor(glow.r, glow.g, glow.b, 0.30f);
        batch.draw(pixel, drawX - glowSize * 2f, drawY - glowSize * 2f, sx + glowSize * 4f, sy + glowSize * 4f);

        batch.flush();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        if (spawnFlash > 0f) {
            batch.setColor(1f, 1f, 1f, spawnFlash / 0.35f);
            batch.draw(pixel, drawX - 6f, drawY - 6f, sx + 12f, sy + 12f);
        }
        batch.setColor(body);
        batch.draw(pixel, drawX, drawY, sx, sy);
        batch.setColor(1f, 1f, 1f, 0.55f);
        batch.draw(pixel, drawX, drawY + sy - 2f, sx, 2f);
        batch.setColor(0.07f, 0.04f, 0.12f, 1f);
        float eyeX = drawX + sx * (facing > 0 ? 0.62f : 0.22f);
        float eyeY = drawY + sy * 0.62f;
        batch.draw(pixel, eyeX, eyeY, sx * 0.16f, sy * 0.16f);
        batch.setColor(Color.WHITE);
    }

    public float chargeFraction() {
        if (!charging) return 0f;
        return MathUtils.clamp(chargeTime / Constants.JUMP_CHARGE_TIME, 0f, 1f);
    }

    /**
     * Returns the launch velocity that {@link #fireJump} would produce right now.
     * Used by the trajectory preview so the player can read the exact arc.
     */
    public Vector2 previewLaunchVelocity() {
        float s = chargeFraction();
        float dx = aim.x - x;
        float dy = aim.y - y;
        if (wallChargeStartedLeft)  dx = Math.max(dx,  20f);
        if (wallChargeStartedRight) dx = Math.min(dx, -20f);
        if (dy < Constants.MIN_VERTICAL_FRACTION * Math.abs(dx) + 30f)
            dy = Constants.MIN_VERTICAL_FRACTION * Math.abs(dx) + 30f;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) { dx = 0f; dy = 1f; len = 1f; }
        dx /= len; dy /= len;
        float impulse = Constants.lerp(Constants.JUMP_MIN_IMPULSE, Constants.JUMP_MAX_IMPULSE, s);
        return tmpVec.set(dx * impulse, dy * impulse);
    }
}
