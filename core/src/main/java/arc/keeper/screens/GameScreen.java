package arc.keeper.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import arc.keeper.Constants;
import arc.keeper.Main;
import arc.keeper.fx.ParticleSystem;
import arc.keeper.fx.ScreenShake;
import arc.keeper.game.Platform;
import arc.keeper.game.PlatformType;
import arc.keeper.game.Player;
import arc.keeper.game.Tower;

import java.util.Calendar;

/**
 * The main gameplay screen. Owns the camera, the player, the tower, the death line,
 * and the scoring loop. Handles input directly (no Scene2D) so the touch model is
 * exactly the one-thumb experience designed in the brief.
 */
public class GameScreen extends ScreenAdapter {

    private enum State { RUNNING, DYING, GAME_OVER }

    private final Main app;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final Viewport viewport = new FitViewport(Constants.WORLD_W, Constants.WORLD_H, camera);

    private final OrthographicCamera uiCamera = new OrthographicCamera();
    private final Viewport uiViewport = new FitViewport(Constants.WORLD_W, Constants.WORLD_H, uiCamera);

    private final ParticleSystem particles = new ParticleSystem();
    private final ScreenShake shake = new ScreenShake();

    private Player player;
    private Tower tower;
    private State state = State.RUNNING;

    /** Y of the rising death line, in world units. */
    private float deathLineY;
    /** Camera y this frame (lags behind the player slightly). */
    private float cameraY;
    /** Smoothed camera X for the gentle anticipation effect. */
    private float cameraXAnticipation;
    private float cameraZoom = 1f;

    /** Score / combo state. */
    private int score;
    private int bonusScore;              // accumulated combo + near-miss bonuses
    private int combo;
    private int nearMissCount;
    private float comboFlash;

    /** Game-over animation timer (slow-mo + freeze before overlay shows). */
    private float dyingTimer;
    private float deathSlowMo;

    /** Generic slow-mo (near-miss). */
    private float slowMo;

    /** "Chase laser" pressure event timer. */
    private float chaseTimer;
    private float chaseCooldown = 12f;

    /** Daily challenge fields. */
    private final boolean dailyChallenge;
    private final long seed;
    private final String seedLabel;

    /** Cached screen-space buttons (in world UI coords). */
    private final Rectangle audioBtn = new Rectangle();
    private final Rectangle menuBtn  = new Rectangle();
    private final Rectangle retryBtn = new Rectangle();
    private final Rectangle quitBtn  = new Rectangle();

    /** Pre-allocated scratch for input - world unprojection. */
    private final Vector3 tmpUnproject = new Vector3();
    private final Vector2 tmpAim = new Vector2();

    /** Reusable region used to parallax-scroll the cyberpunk backdrop via UV math. */
    private TextureRegion bgRegion;

    /** Active touch pointer (we only respect the first touch). */
    private int activePointer = -1;

    public GameScreen(Main app, boolean dailyChallenge) {
        this.app = app;
        this.dailyChallenge = dailyChallenge;
        if (dailyChallenge) {
            // Stable seed for the calendar day.
            Calendar c = Calendar.getInstance();
            int y = c.get(Calendar.YEAR);
            int d = c.get(Calendar.DAY_OF_YEAR);
            this.seed = y * 1000L + d;
            this.seedLabel = "DAILY  Y" + y + "D" + d;
        } else {
            this.seed = System.nanoTime();
            this.seedLabel = "ENDLESS";
        }
        reset();
    }

    private void reset() {
        particles.clear();
        shake.reset();
        player = new Player(Constants.WORLD_W * 0.5f, 60f, app.skins.getActive());
        tower = new Tower(seed);
        state = State.RUNNING;
        deathLineY = -100f;
        cameraY = Constants.WORLD_H * 0.5f - Constants.CAMERA_PLAYER_OFFSET;
        cameraXAnticipation = 0f;
        cameraZoom = 1f;
        score = 0;
        bonusScore = 0;
        combo = 0;
        nearMissCount = 0;
        comboFlash = 0f;
        dyingTimer = 0f;
        deathSlowMo = 0f;
        slowMo = 0f;
        chaseTimer = 0f;
        chaseCooldown = 12f;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputHandler());
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        if (app.background != null) bgRegion = new TextureRegion(app.background);
    }

    @Override
    public void hide() {
        Gdx.input.setInputProcessor(null);
        app.audio.setMusicIntensity(0f);
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, false);
        uiViewport.update(width, height, true);
    }

    // ---------------------------------------------------------------------
    // Frame loop
    // ---------------------------------------------------------------------

    @Override
    public void render(float deltaTime) {
        // Clamp dt to avoid huge jumps after window pauses.
        float dt = Math.min(deltaTime, 1f / 30f);
        update(dt);
        draw();
    }

    private void update(float dt) {
        // Resolve composite slow-mo (death > near-miss > 1.0).
        float ts;
        if (state == State.DYING) ts = 0.20f;
        else if (slowMo > 0f) ts = Constants.SLOW_MO_SCALE;
        else ts = 1f;
        float gameDt = dt * ts;

        slowMo = Math.max(0f, slowMo - dt);
        if (state == State.DYING) {
            dyingTimer += dt;
            updateDying(gameDt);
            return;
        }
        if (state == State.GAME_OVER) {
            // Particles & shake keep flowing even after game over so it doesn't freeze visually.
            particles.update(dt);
            shake.update(dt);
            return;
        }

        // ----- gameplay tick -----
        tower.generateUpTo(player.y + Constants.WORLD_H);
        tower.cullBelow(deathLineY - 200f);
        tower.update(gameDt);
        player.update(gameDt, tower);

        // Process landing / jump events for FX + sound + scoring.
        if (player.landedThisFrame) {
            Color c = (player.lastPlatform != null) ? player.lastPlatform.color : Constants.CYAN;
            particles.dust(player.x, player.y - 1f, 10, c, -1f);
            particles.dust(player.x, player.y - 1f, 10, c, +1f);
            shake.add(0.06f + 0.18f * Math.min(1f, player.lastImpactSpeed / 900f));
            app.audio.playLand(player.lastImpactSpeed);
            // Combo logic: landing after a high-charge jump counts as a "perfect" - feeds the combo.
            if (player.lastJumpStrength >= 0.72f) {
                combo++;
                bonusScore += Constants.PERFECT_JUMP_BONUS * Math.max(1, combo / 2);
                comboFlash = 0.5f;
            } else if (player.lastJumpStrength < 0.25f) {
                combo = Math.max(0, combo - 1);
            }
        }
        if (player.jumpedThisFrame) {
            Color c = player.skin.glow;
            particles.dust(player.x, player.y - 1f, 8, c, -1f);
            particles.dust(player.x, player.y - 1f, 8, c, +1f);
            app.audio.playJump(player.lastJumpStrength);
            shake.add(0.04f + 0.06f * player.lastJumpStrength);
        }

        // Charge halo + faint tick sound.
        if (player.charging) {
            particles.chargeHalo(player.x, player.y + player.size * 0.5f,
                player.skin.glow, player.chargeFraction());
            // Every ~0.10s emit a tick to give "rising" audible feedback.
            int idx = (int) (player.chargeTime / 0.10f);
            if (idx != lastChargeTickIdx) {
                lastChargeTickIdx = idx;
                app.audio.playChargeTick(player.chargeFraction());
            }
        } else {
            lastChargeTickIdx = -1;
        }

        // Trail particles while moving fast.
        float speed = (float) Math.sqrt(player.vx * player.vx + player.vy * player.vy);
        if (speed > 380f) particles.trail(player.x, player.y + player.size * 0.5f,
            player.vx, player.vy, player.skin.trail);

        // Hazards & teleporters.
        for (int i = 0; i < tower.platforms.size; i++) {
            Platform p = tower.platforms.get(i);
            if (p.overlapsHazard(player.x, player.y + player.size * 0.5f, player.size)) {
                killPlayer("HAZARD");
                break;
            }
            if (p.type == PlatformType.TELEPORT && p.teleportCooldown <= 0f) {
                if (rectIntersects(p, player.x, player.y + player.size * 0.5f, player.size)) {
                    Vector2 dest = p.teleportTarget();
                    if (dest != null) {
                        particles.burst(player.x, player.y + player.size * 0.5f,
                            22, Constants.CYAN, Constants.PURPLE, 320f, 0.50f, 8f, true);
                        player.x = dest.x;
                        player.y = dest.y;
                        player.vy = Math.max(player.vy, 220f);
                        particles.burst(player.x, player.y, 22, Constants.PURPLE, Constants.CYAN,
                            320f, 0.50f, 8f, true);
                        shake.add(0.20f);
                        app.audio.playTeleport();
                    }
                }
            }
            // Near-miss detection for lasers & spikes.
            if ((p.type == PlatformType.LASER || p.type == PlatformType.SPIKE) && !p.nearMissAwarded) {
                if (player.alive && nearMissCheck(p, player)) {
                    p.nearMissAwarded = true;
                    nearMissCount++;
                    bonusScore += Constants.NEAR_MISS_BONUS;
                    comboFlash = 0.55f;
                    slowMo = Constants.SLOW_MO_NEAR_MISS_DURATION;
                    particles.spark(player.x, player.y + player.size * 0.5f, 18, Constants.YELLOW);
                    app.audio.playNearMiss();
                }
            }
        }

        // Death line rises.
        float ramp = Constants.smoothstep(player.maxY / Constants.DEATH_LINE_RAMP_HEIGHT);
        float deathSpeed = Constants.lerp(Constants.DEATH_LINE_START_SPEED, Constants.DEATH_LINE_MAX_SPEED, ramp);

        // "Chase laser" pressure event: every chaseCooldown seconds, briefly accelerate the death line.
        chaseCooldown -= gameDt;
        if (chaseCooldown <= 0f && player.maxY > 900f) {
            chaseTimer = 4.2f;
            chaseCooldown = MathUtils.random(14f, 22f);
            particles.spark(Constants.WORLD_W * 0.5f, deathLineY, 32, Constants.RED);
            shake.add(0.30f);
        }
        if (chaseTimer > 0f) {
            chaseTimer -= gameDt;
            deathSpeed *= 2.4f;
        }
        deathLineY += deathSpeed * gameDt;
        if (player.y + player.size * 0.4f < deathLineY) {
            killPlayer("FELL");
        }

        // Scoring: distance climbed + accumulated combo / near-miss bonuses.
        int distanceScore = (int) (player.maxY * Constants.SCORE_PER_UNIT);
        int newScore = distanceScore + bonusScore;
        if (newScore > score) score = newScore;

        comboFlash = Math.max(0f, comboFlash - dt);

        // FX / camera update.
        particles.update(dt);
        shake.update(dt);
        updateCamera(dt);

        // Drive music intensity from danger proximity + chase event.
        float dist = player.y - deathLineY;
        float danger = 1f - MathUtils.clamp(dist / Constants.DANGER_GLOW_DIST, 0f, 1f);
        if (chaseTimer > 0f) danger = Math.max(danger, 0.85f);
        app.audio.setMusicIntensity(danger);
    }

    private int lastChargeTickIdx = -1;

    private void updateDying(float dt) {
        deathSlowMo += dt;
        particles.update(dt);
        shake.update(dt);
        if (dyingTimer > 0.6f) state = State.GAME_OVER;
    }

    /**
     * Triggers the player's death sequence with juice. Bumps the personal-best record
     * once the game-over overlay actually shows so we don't double-record on quick restart.
     */
    private void killPlayer(String reason) {
        if (!player.die()) return;
        // Big particle shatter in skin colors.
        particles.burst(player.x, player.y + player.size * 0.5f,
            48, player.skin.glow, player.skin.body, 480f, 0.65f, 10f, true);
        particles.burst(player.x, player.y + player.size * 0.5f,
            32, Constants.WHITE, player.skin.body, 220f, 0.55f, 6f, false);
        shake.add(0.85f);
        slowMo = 0f;
        app.audio.playDeath();
        state = State.DYING;
        dyingTimer = 0f;
        // Record run only when transitioning to game over, not on every input poll -
        // we hand off to onGameOver() once dying finishes.
    }

    private void onGameOver() {
        app.save.recordRun(score, player.maxY, player.jumpCount);
        if (dailyChallenge && score > app.save.getDailyBest()) {
            app.save.setDaily(seed, score);
        }
    }

    private boolean recordedGameOver;

    private void updateCamera(float dt) {
        // Y follow - keeps the player on screen in both directions. The lower bound is
        // tied to the death line so the camera never drops into the kill zone (which
        // would let the player fall off-screen while still technically alive).
        float deathFloor = deathLineY + Constants.WORLD_H * 0.5f - 60f;
        float targetY = player.y + Constants.CAMERA_PLAYER_OFFSET;
        if (targetY < deathFloor) targetY = deathFloor;

        // Snap up fast (climbing pressure), drift down gently (avoid disorienting
        // whip-pans when the player falls a long way after a missed jump).
        float speedUp   = 7.0f;
        float speedDown = 3.5f;
        float lerpSpeed = targetY > cameraY ? speedUp : speedDown;
        cameraY += (targetY - cameraY) * Math.min(1f, dt * lerpSpeed);

        // X anticipation - slight tilt toward velocity direction.
        float targetAntic = MathUtils.clamp(player.vx * 0.04f, -36f, 36f);
        cameraXAnticipation += (targetAntic - cameraXAnticipation) * Math.min(1f, dt * 4f);

        // Mild zoom-out at high speed.
        float speed = (float) Math.sqrt(player.vx * player.vx + player.vy * player.vy);
        float targetZoom = 1f + MathUtils.clamp((speed - 550f) / 4000f, 0f, 0.18f);
        cameraZoom += (targetZoom - cameraZoom) * Math.min(1f, dt * 3f);

        camera.zoom = cameraZoom;
        camera.position.set(
            Constants.WORLD_W * 0.5f + cameraXAnticipation + shake.offsetX(),
            cameraY + shake.offsetY(),
            0f);
        camera.update();
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private void draw() {
        // World pass.
        viewport.apply();
        app.batch.setProjectionMatrix(camera.combined);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        app.batch.begin();
        drawBackground();
        drawDeathLine();
        drawWalls();
        // Platforms - only those near the camera to keep it cheap.
        float top = camera.position.y + Constants.WORLD_H * 0.5f * cameraZoom + 80f;
        float bot = camera.position.y - Constants.WORLD_H * 0.5f * cameraZoom - 80f;
        for (int i = 0; i < tower.platforms.size; i++) {
            Platform p = tower.platforms.get(i);
            if (p.y + p.h < bot || p.y > top) continue;
            p.draw(app.batch, app.pixel);
        }
        particles.draw(app.batch, app.pixel);
        player.draw(app.batch, app.pixel);
        drawAimAndTrajectory();
        app.batch.end();

        // UI pass.
        uiViewport.apply();
        app.batch.setProjectionMatrix(uiCamera.combined);
        app.batch.begin();
        drawHUD();
        if (state == State.GAME_OVER) {
            if (!recordedGameOver) { onGameOver(); recordedGameOver = true; }
            drawGameOverOverlay();
        }
        app.batch.end();
    }

    private void drawBackground() {
        float halfH = Constants.WORLD_H * 0.5f * cameraZoom;
        float halfW = Constants.WORLD_W * 0.5f * cameraZoom;
        float cx = camera.position.x;
        float cy = camera.position.y;

        // Cyberpunk space backdrop - drawn locked to the camera but scrolled in UV
        // space so the player feels lift-off without the image ever leaving the view.
        // At cy=startCameraY the player sees the cityscape (bottom of image), and as
        // they climb the camera reveals progressively deeper space (top of image).
        if (bgRegion != null) {
            float parallax = 0.18f;             // 0 = static, 1 = locks to camera
            float startCy = Constants.WORLD_H * 0.5f - Constants.CAMERA_PLAYER_OFFSET;
            float climbedView = Math.max(0f, cy - startCy);

            float bgWorldH = (float) app.background.getHeight() / app.background.getWidth() * Constants.WORLD_W;
            float vRange   = Math.min(1f, Constants.WORLD_H / bgWorldH);
            float scrollV  = MathUtils.clamp(climbedView * parallax / bgWorldH, 0f, 1f - vRange);

            float v  = 1f - vRange - scrollV;
            float v2 = 1f - scrollV;
            bgRegion.setRegion(0f, v, 1f, v2);

            app.batch.setColor(Color.WHITE);
            app.batch.draw(bgRegion, cx - halfW, cy - halfH, halfW * 2f, halfH * 2f);

            // Subtle dark overlay: just enough to keep the death-line fog and the
            // neon platforms legible without crushing the cyberpunk vibe.
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            app.batch.setColor(0.02f, 0.01f, 0.05f, 0.28f);
            app.batch.draw(app.pixel, cx - halfW, cy - halfH, halfW * 2f, halfH * 2f);
        } else {
            // Fallback if the image failed to load.
            app.batch.setColor(Constants.BG_BOT);
            app.batch.draw(app.pixel, cx - halfW, cy - halfH, halfW * 2f, halfH);
            app.batch.setColor(Constants.BG_TOP);
            app.batch.draw(app.pixel, cx - halfW, cy, halfW * 2f, halfH);
        }

        // Subtle grid lines - give the eye motion reference as the camera rises.
        float gridSpacing = 80f;
        app.batch.setColor(0.42f, 0.72f, 1f, 0.10f);
        float startY = ((float) Math.floor((cy - halfH) / gridSpacing)) * gridSpacing;
        for (float gy = startY; gy < cy + halfH; gy += gridSpacing) {
            app.batch.draw(app.pixel, cx - halfW, gy, halfW * 2f, 1f);
        }
        // Vertical helper lines inside tower (electric blue, neon vibe).
        for (int i = 1; i < 5; i++) {
            float gx = Constants.TOWER_LEFT + (Constants.TOWER_WIDTH / 5f) * i;
            app.batch.setColor(0.38f, 0.75f, 1f, 0.08f);
            app.batch.draw(app.pixel, gx, cy - halfH, 1f, halfH * 2f);
        }
        app.batch.setColor(Color.WHITE);
    }

    private void drawWalls() {
        float halfH = Constants.WORLD_H * 0.5f * cameraZoom;
        float cy = camera.position.y;
        Color wall = new Color(Constants.PURPLE);
        wall.a = 0.55f;
        // Left
        UI.glowRect(app.batch, app.pixel,
            Constants.TOWER_LEFT, cy - halfH,
            Constants.WALL_THICKNESS, halfH * 2f, wall, 0.95f);
        // Right
        UI.glowRect(app.batch, app.pixel,
            Constants.TOWER_RIGHT - Constants.WALL_THICKNESS, cy - halfH,
            Constants.WALL_THICKNESS, halfH * 2f, wall, 0.95f);
    }

    private void drawDeathLine() {
        float halfH = Constants.WORLD_H * 0.5f * cameraZoom;
        float cy = camera.position.y;
        // The death zone (everything below the death line) gets a red fog.
        float fogTop = Math.min(deathLineY, cy + halfH);
        if (fogTop > cy - halfH) {
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            for (int i = 0; i < 4; i++) {
                float a = 0.06f + 0.06f * i;
                float h = 24f + 20f * i;
                app.batch.setColor(0.85f, 0.18f, 0.32f, a);
                app.batch.draw(app.pixel,
                    Constants.TOWER_LEFT, fogTop - h,
                    Constants.TOWER_WIDTH, h);
            }
            // Solid red line.
            app.batch.setColor(1f, 0.30f, 0.42f, 0.95f);
            app.batch.draw(app.pixel,
                Constants.TOWER_LEFT, fogTop - 2f,
                Constants.TOWER_WIDTH, 4f);
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            // Fill the below-line area dark red.
            app.batch.setColor(0.07f, 0.02f, 0.08f, 1f);
            app.batch.draw(app.pixel,
                Constants.TOWER_LEFT, cy - halfH,
                Constants.TOWER_WIDTH, Math.max(0f, fogTop - (cy - halfH) - 2f));
            app.batch.setColor(Color.WHITE);
        }
    }

    private void drawAimAndTrajectory() {
        if (!player.charging) return;
        // Trajectory preview: simulate forward using the current preview launch velocity.
        Vector2 v = player.previewLaunchVelocity();
        float px = player.x;
        float py = player.y + player.size * 0.5f;
        float vx = v.x, vy = v.y;
        float gravity = (player.lowGravityTimer > 0f) ? Constants.GRAVITY * 0.45f : Constants.GRAVITY;
        float stepDt = 1f / 30f;
        app.batch.flush();
        app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        Color glow = player.skin.glow;
        for (int i = 0; i < 26; i++) {
            px += vx * stepDt;
            py += vy * stepDt;
            vy -= gravity * stepDt;
            // Stop when leaving the playable column or hitting a platform.
            if (px < Constants.TOWER_LEFT + Constants.WALL_THICKNESS ||
                px > Constants.TOWER_RIGHT - Constants.WALL_THICKNESS) break;
            float dotAlpha = 0.85f * (1f - i / 26f);
            if (dotAlpha < 0.05f) break;
            float dotSize = 4.4f - i * 0.10f;
            if (dotSize < 1.5f) dotSize = 1.5f;
            app.batch.setColor(glow.r, glow.g, glow.b, dotAlpha);
            app.batch.draw(app.pixel, px - dotSize * 0.5f, py - dotSize * 0.5f, dotSize, dotSize);
        }
        app.batch.flush();
        app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        app.batch.setColor(Color.WHITE);
    }

    private void drawHUD() {
        // Score top-center.
        UI.drawCentered(app.batch, app.fontMedium, formatNumber(score),
            Constants.WORLD_W * 0.5f, Constants.WORLD_H - 32f, Constants.WHITE);
        UI.drawCentered(app.batch, app.fontSmall,
            "HEIGHT  " + (int) player.maxY + "m",
            Constants.WORLD_W * 0.5f, Constants.WORLD_H - 62f,
            new Color(0.78f, 0.78f, 0.92f, 0.75f));

        // Combo flash (top-center under score).
        if (combo > 0 && (comboFlash > 0f || state == State.RUNNING)) {
            float a = MathUtils.clamp(0.55f + comboFlash, 0.55f, 1f);
            Color cc = new Color(Constants.YELLOW);
            cc.a = a;
            UI.drawCentered(app.batch, app.fontSmall, "x" + combo + " COMBO",
                Constants.WORLD_W * 0.5f, Constants.WORLD_H - 88f, cc);
        }

        // Charge bar (bottom-center).
        if (player.charging) {
            float chargeFrac = player.chargeFraction();
            float barW = 200f;
            float barH = 8f;
            float bx = (Constants.WORLD_W - barW) * 0.5f;
            float by = 36f;
            UI.glowRect(app.batch, app.pixel, bx - 2f, by - 2f, barW + 4f, barH + 4f,
                new Color(0.2f, 0.2f, 0.3f, 1f), 0.6f);
            Color charge = new Color().set(Constants.CYAN).lerp(Constants.MAGENTA, chargeFrac);
            UI.glowRect(app.batch, app.pixel, bx, by, barW * chargeFrac, barH, charge, 1f);
        }

        // Audio toggle top-right.
        audioBtn.set(Constants.WORLD_W - 50f, Constants.WORLD_H - 50f, 38f, 38f);
        drawIconButton(audioBtn, app.audio.isMuted() ? "OFF" : "ON ");

        // Menu button top-left.
        menuBtn.set(12f, Constants.WORLD_H - 50f, 38f, 38f);
        drawIconButton(menuBtn, "MENU");

        // Daily challenge tag (right under menu).
        if (dailyChallenge) {
            Color cc = new Color(Constants.YELLOW);
            cc.a = 0.9f;
            UI.drawCentered(app.batch, app.fontSmall, seedLabel,
                Constants.WORLD_W * 0.5f, Constants.WORLD_H - 110f, cc);
        }

        // Chase-laser warning banner.
        if (chaseTimer > 0f) {
            float a = 0.6f + 0.4f * (float) Math.sin(chaseTimer * 18f);
            Color cc = new Color(Constants.RED);
            cc.a = a;
            UI.drawCentered(app.batch, app.fontMedium, "! CHASE !",
                Constants.WORLD_W * 0.5f, Constants.WORLD_H * 0.5f + 220f, cc);
        }

        // Hint on the first run.
        if (player.maxY < 120f && state == State.RUNNING) {
            Color cc = new Color(1f, 1f, 1f, 0.55f + 0.25f * (float) Math.sin(MathUtils.PI2 * (System.currentTimeMillis() % 1500L) / 1500f));
            UI.drawCentered(app.batch, app.fontSmall, "HOLD & RELEASE TO JUMP",
                Constants.WORLD_W * 0.5f, 110f, cc);
            UI.drawCentered(app.batch, app.fontSmall, "DRAG TO AIM",
                Constants.WORLD_W * 0.5f, 90f, cc);
        }
    }

    private void drawIconButton(Rectangle r, String label) {
        UI.glowRect(app.batch, app.pixel, r.x, r.y, r.width, r.height,
            new Color(0.13f, 0.07f, 0.22f, 1f), 0.85f);
        UI.drawCentered(app.batch, app.fontSmall, label,
            r.x + r.width * 0.5f, r.y + r.height * 0.5f, Constants.WHITE);
    }

    private void drawGameOverOverlay() {
        // Translucent dimmer.
        app.batch.setColor(0f, 0f, 0f, 0.65f);
        app.batch.draw(app.pixel, 0, 0, Constants.WORLD_W, Constants.WORLD_H);
        app.batch.setColor(Color.WHITE);

        UI.drawCentered(app.batch, app.fontLarge, "RUN OVER",
            Constants.WORLD_W * 0.5f, Constants.WORLD_H * 0.5f + 130f, Constants.RED);
        UI.drawCentered(app.batch, app.fontMedium, "SCORE  " + formatNumber(score),
            Constants.WORLD_W * 0.5f, Constants.WORLD_H * 0.5f + 70f, Constants.WHITE);
        UI.drawCentered(app.batch, app.fontSmall,
            "HEIGHT " + (int) player.maxY + "m   JUMPS " + player.jumpCount + "   NEAR MISS " + nearMissCount,
            Constants.WORLD_W * 0.5f, Constants.WORLD_H * 0.5f + 42f,
            new Color(0.78f, 0.78f, 0.92f, 1f));
        UI.drawCentered(app.batch, app.fontSmall,
            "BEST " + formatNumber(Math.max(score, app.save.getHighScore())),
            Constants.WORLD_W * 0.5f, Constants.WORLD_H * 0.5f + 18f, Constants.YELLOW);

        retryBtn.set(Constants.WORLD_W * 0.5f - 100f, Constants.WORLD_H * 0.5f - 50f, 200f, 50f);
        UI.glowRect(app.batch, app.pixel, retryBtn.x, retryBtn.y, retryBtn.width, retryBtn.height,
            Constants.CYAN, 0.95f);
        UI.drawCentered(app.batch, app.fontMedium, "RETRY",
            retryBtn.x + retryBtn.width * 0.5f, retryBtn.y + retryBtn.height * 0.5f,
            new Color(0.05f, 0.04f, 0.10f, 1f));

        quitBtn.set(Constants.WORLD_W * 0.5f - 100f, Constants.WORLD_H * 0.5f - 110f, 200f, 40f);
        UI.glowRect(app.batch, app.pixel, quitBtn.x, quitBtn.y, quitBtn.width, quitBtn.height,
            new Color(0.20f, 0.10f, 0.30f, 1f), 0.85f);
        UI.drawCentered(app.batch, app.fontSmall, "BACK TO MENU",
            quitBtn.x + quitBtn.width * 0.5f, quitBtn.y + quitBtn.height * 0.5f, Constants.WHITE);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean rectIntersects(Platform p, float px, float py, float ps) {
        float halfP = ps * 0.5f;
        return px + halfP > p.x && px - halfP < p.x + p.w &&
               py + halfP > p.y && py - halfP < p.y + p.h;
    }

    /** Near-miss: player was within a small margin of the hazard within the past frame. */
    private boolean nearMissCheck(Platform p, Player pl) {
        float margin = 14f;
        float halfP = pl.size * 0.5f;
        float px = pl.x;
        float py = pl.y + pl.size * 0.5f;
        if (!p.isHazard()) return false;
        return px + halfP + margin > p.x && px - halfP - margin < p.x + p.w &&
               py + halfP + margin > p.y && py - halfP - margin < p.y + p.h &&
               !rectIntersects(p, px, py, pl.size);
    }

    /** Formats large numbers with comma separators (e.g. 12345 -> 12,345). */
    private static String formatNumber(int n) {
        return String.format("%,d", n);
    }

    @Override
    public void dispose() {
        particles.clear();
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    private class InputHandler extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (activePointer != -1) return false;
            tmpUnproject.set(screenX, screenY, 0f);
            uiViewport.unproject(tmpUnproject);

            // GAME_OVER state - only the two buttons respond.
            if (state == State.GAME_OVER) {
                if (retryBtn.contains(tmpUnproject.x, tmpUnproject.y)) {
                    app.audio.playMenuClick();
                    recordedGameOver = false;
                    reset();
                    return true;
                }
                if (quitBtn.contains(tmpUnproject.x, tmpUnproject.y)) {
                    app.audio.playMenuClick();
                    app.switchScreen(new TitleScreen(app));
                    return true;
                }
                return true;
            }

            // RUNNING state - UI buttons first, then jump charge.
            if (audioBtn.contains(tmpUnproject.x, tmpUnproject.y)) {
                app.audio.toggleMuted();
                app.audio.playMenuClick();
                return true;
            }
            if (menuBtn.contains(tmpUnproject.x, tmpUnproject.y)) {
                app.audio.playMenuClick();
                app.switchScreen(new TitleScreen(app));
                return true;
            }
            if (state == State.DYING) return true;

            activePointer = pointer;
            // Aim in world space, not UI space.
            tmpUnproject.set(screenX, screenY, 0f);
            viewport.unproject(tmpUnproject);
            tmpAim.set(tmpUnproject.x, tmpUnproject.y);
            // If aim is below player, snap upward - never charge downward.
            if (tmpAim.y < player.y + 20f) tmpAim.y = player.y + 20f;
            player.beginCharge(tmpAim.x, tmpAim.y);
            return true;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (pointer != activePointer || state != State.RUNNING) return false;
            tmpUnproject.set(screenX, screenY, 0f);
            viewport.unproject(tmpUnproject);
            float ax = tmpUnproject.x;
            float ay = tmpUnproject.y;
            if (ay < player.y + 20f) ay = player.y + 20f;
            player.updateAim(ax, ay);
            return true;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (pointer != activePointer) return false;
            activePointer = -1;
            if (state == State.RUNNING) player.releaseCharge();
            return true;
        }

        @Override
        public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) {
                app.switchScreen(new TitleScreen(app));
                return true;
            }
            if (keycode == Input.Keys.M) {
                app.audio.toggleMuted();
                return true;
            }
            if (keycode == Input.Keys.R && state == State.GAME_OVER) {
                recordedGameOver = false;
                reset();
                return true;
            }
            return false;
        }
    }
}

