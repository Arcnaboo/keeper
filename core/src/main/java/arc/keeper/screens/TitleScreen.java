package arc.keeper.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import arc.keeper.Constants;
import arc.keeper.Main;
import arc.keeper.data.Skin;
import arc.keeper.fx.ParticleSystem;

/**
 * Minimal title menu - designed to keep distance to "first jump" as short as possible.
 * Single big PLAY button, a few small affordances around the edges.
 */
public class TitleScreen extends ScreenAdapter {

    private final Main app;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final Viewport viewport = new FitViewport(Constants.WORLD_W, Constants.WORLD_H, camera);
    private final ParticleSystem particles = new ParticleSystem();
    private float time;
    private float demoY = 80f;
    private float demoVY = 0f;
    private boolean demoCharging;
    private float demoCharge;
    private float demoX = Constants.WORLD_W * 0.5f;
    private float demoVX = 0f;

    private final Rectangle playBtn  = new Rectangle();
    private final Rectangle dailyBtn = new Rectangle();
    private final Rectangle skinBtn  = new Rectangle();
    private final Rectangle audioBtn = new Rectangle();

    private boolean skinPickerOpen;
    private final Rectangle[] skinCells = new Rectangle[10];
    private final Rectangle skinClose = new Rectangle();

    private final Vector3 tmp = new Vector3();
    private TextureRegion bgRegion;

    public TitleScreen(Main app) {
        this.app = app;
        for (int i = 0; i < skinCells.length; i++) skinCells[i] = new Rectangle();
        if (app.background != null) bgRegion = new TextureRegion(app.background);
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                tmp.set(screenX, screenY, 0f);
                viewport.unproject(tmp);
                float x = tmp.x, y = tmp.y;
                if (skinPickerOpen) return handleSkinPickerTouch(x, y);
                if (playBtn.contains(x, y)) {
                    app.audio.playMenuClick();
                    app.switchScreen(new GameScreen(app, false));
                    return true;
                }
                if (dailyBtn.contains(x, y)) {
                    app.audio.playMenuClick();
                    app.switchScreen(new GameScreen(app, true));
                    return true;
                }
                if (skinBtn.contains(x, y)) {
                    app.audio.playMenuClick();
                    skinPickerOpen = true;
                    return true;
                }
                if (audioBtn.contains(x, y)) {
                    app.audio.toggleMuted();
                    app.audio.playMenuClick();
                    return true;
                }
                return false;
            }
        });
        app.audio.setMusicIntensity(0.10f);
    }

    @Override
    public void hide() { Gdx.input.setInputProcessor(null); }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void render(float dt) {
        time += dt;
        if (dt > 1f / 30f) dt = 1f / 30f;

        // Background "demo cube" - bounces happily for visual life.
        updateDemoCube(dt);
        particles.update(dt);

        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        viewport.apply();
        app.batch.setProjectionMatrix(camera.combined);
        app.batch.begin();
        drawBackground();

        if (!skinPickerOpen) {
            drawDemoCube();
            particles.draw(app.batch, app.pixel);
            drawTitle();
            drawMenu();
        } else {
            drawSkinPicker();
        }
        app.batch.end();
    }

    private void updateDemoCube(float dt) {
        // A tiny self-playing demo: charge, jump, land, repeat - sells the loop at a glance.
        float gravity = Constants.GRAVITY * 0.85f;
        if (demoCharging) {
            demoCharge += dt;
            if (demoCharge > 0.45f) {
                // release
                float angle = MathUtils.sin(time * 1.3f) * 0.6f + (float) Math.PI * 0.5f;
                float impulse = 700f + 200f * (demoCharge / 0.45f);
                demoVX = MathUtils.cos(angle) * impulse;
                demoVY = MathUtils.sin(angle) * impulse;
                demoCharging = false;
                demoCharge = 0f;
                particles.dust(demoX, demoY - 1f, 8, app.skins.getActive().glow, -1f);
                particles.dust(demoX, demoY - 1f, 8, app.skins.getActive().glow, +1f);
            }
        } else {
            demoVY -= gravity * dt;
            demoX += demoVX * dt;
            demoY += demoVY * dt;
            if (demoY <= 80f) {
                if (demoY < 80f) {
                    particles.dust(demoX, demoY - 1f, 14, app.skins.getActive().body, 0f);
                }
                demoY = 80f;
                demoVY = 0f;
                demoVX *= 0.5f;
                demoCharging = true;
                particles.chargeHalo(demoX, demoY + 13f, app.skins.getActive().glow, 0.5f);
            }
            if (demoX < Constants.TOWER_LEFT + 40f) { demoX = Constants.TOWER_LEFT + 40f; demoVX *= -0.6f; }
            if (demoX > Constants.TOWER_RIGHT - 40f) { demoX = Constants.TOWER_RIGHT - 40f; demoVX *= -0.6f; }
        }
    }

    private void drawDemoCube() {
        Skin s = app.skins.getActive();
        float size = 26f;
        if (s.texture != null) {
            // Same anchoring logic as Player.drawTexturedSkin so the menu preview
            // matches what the player will see in-game.
            float visual = size * 1.8f;
            float drawX = demoX - visual * 0.5f;
            float drawY = (demoY + size * 0.5f) - visual * 0.5f;
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            app.batch.setColor(s.glow.r, s.glow.g, s.glow.b, 0.50f + 0.35f * demoCharge / 0.45f);
            app.batch.draw(s.texture, drawX - 3f, drawY - 3f, visual + 6f, visual + 6f);
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            app.batch.setColor(Color.WHITE);
            app.batch.draw(s.texture, drawX, drawY, visual, visual);
        } else {
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            app.batch.setColor(s.glow.r, s.glow.g, s.glow.b, 0.55f);
            app.batch.draw(app.pixel, demoX - size, demoY - size * 0.5f, size * 2f, size * 2f);
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            app.batch.setColor(s.body);
            app.batch.draw(app.pixel, demoX - size * 0.5f, demoY, size, size);
            app.batch.setColor(Color.WHITE);
        }
    }

    private void drawBackground() {
        if (bgRegion != null) {
            // Slow vertical drift so the menu doesn't feel static.
            float bgWorldH = (float) app.background.getHeight() / app.background.getWidth() * Constants.WORLD_W;
            float vRange   = Math.min(1f, Constants.WORLD_H / bgWorldH);
            float drift    = (1f - vRange) * (0.5f + 0.5f * (float) Math.sin(time * 0.10f));
            bgRegion.setRegion(0f, 1f - vRange - drift, 1f, 1f - drift);
            app.batch.setColor(Color.WHITE);
            app.batch.draw(bgRegion, 0, 0, Constants.WORLD_W, Constants.WORLD_H);

            // Faint dark overlay so the title remains readable on bright frames.
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            app.batch.setColor(0.02f, 0.01f, 0.05f, 0.35f);
            app.batch.draw(app.pixel, 0, 0, Constants.WORLD_W, Constants.WORLD_H);
        } else {
            app.batch.setColor(Constants.BG_BOT);
            app.batch.draw(app.pixel, 0, 0, Constants.WORLD_W, Constants.WORLD_H * 0.5f);
            app.batch.setColor(Constants.BG_TOP);
            app.batch.draw(app.pixel, 0, Constants.WORLD_H * 0.5f, Constants.WORLD_W, Constants.WORLD_H * 0.5f);
        }
        app.batch.setColor(Color.WHITE);

        // Neon tower-wall hints framing the title.
        UI.glowRect(app.batch, app.pixel,
            Constants.TOWER_LEFT, 0f, Constants.WALL_THICKNESS, Constants.WORLD_H,
            new Color(Constants.PURPLE.r, Constants.PURPLE.g, Constants.PURPLE.b, 0.55f), 0.85f);
        UI.glowRect(app.batch, app.pixel,
            Constants.TOWER_RIGHT - Constants.WALL_THICKNESS, 0f,
            Constants.WALL_THICKNESS, Constants.WORLD_H,
            new Color(Constants.PURPLE.r, Constants.PURPLE.g, Constants.PURPLE.b, 0.55f), 0.85f);
    }

    private void drawTitle() {
        float titleY = Constants.WORLD_H - 110f;
        // Glowing title.
        Color glow = new Color(Constants.CYAN).lerp(Constants.MAGENTA, 0.5f);
        UI.drawCentered(app.batch, app.fontLarge, "THUMBKEEPER",
            Constants.WORLD_W * 0.5f, titleY, glow);
        UI.drawCentered(app.batch, app.fontSmall, "ONE THUMB. ONE TOWER. ONE MORE RUN.",
            Constants.WORLD_W * 0.5f, titleY - 28f,
            new Color(0.85f, 0.85f, 0.95f, 0.85f));

        // Personal best display.
        int hs = app.save.getHighScore();
        int runs = app.save.getTotalRuns();
        if (hs > 0 || runs > 0) {
            UI.drawCentered(app.batch, app.fontSmall,
                "BEST  " + String.format("%,d", hs) +
                "    RUNS  " + runs +
                "    HIGH  " + (int) app.save.getBestHeight() + "m",
                Constants.WORLD_W * 0.5f, titleY - 56f,
                new Color(1f, 1f, 1f, 0.55f));
        }
    }

    private void drawMenu() {
        // Big play button.
        float pulse = 0.94f + 0.06f * (float) Math.sin(time * 3f);
        playBtn.set(Constants.WORLD_W * 0.5f - 110f, 240f, 220f, 70f);
        Color base = new Color(Constants.CYAN).lerp(Constants.MAGENTA, 0.4f);
        UI.glowRect(app.batch, app.pixel, playBtn.x, playBtn.y, playBtn.width, playBtn.height,
            base, pulse);
        UI.drawCentered(app.batch, app.fontLarge, "PLAY",
            playBtn.x + playBtn.width * 0.5f, playBtn.y + playBtn.height * 0.5f,
            new Color(0.04f, 0.02f, 0.08f, 1f));

        dailyBtn.set(Constants.WORLD_W * 0.5f - 90f, 180f, 180f, 42f);
        UI.glowRect(app.batch, app.pixel, dailyBtn.x, dailyBtn.y, dailyBtn.width, dailyBtn.height,
            new Color(0.25f, 0.13f, 0.40f, 1f), 0.95f);
        UI.drawCentered(app.batch, app.fontSmall, "DAILY CHALLENGE",
            dailyBtn.x + dailyBtn.width * 0.5f, dailyBtn.y + dailyBtn.height * 0.5f,
            Constants.YELLOW);
        if (app.save.getDailyBest() > 0) {
            UI.drawCentered(app.batch, app.fontSmall,
                "DAILY BEST  " + String.format("%,d", app.save.getDailyBest()),
                Constants.WORLD_W * 0.5f, dailyBtn.y - 16f,
                new Color(0.85f, 0.85f, 0.85f, 0.7f));
        }

        // Skin & audio: bottom corners.
        skinBtn.set(20f, 30f, 110f, 42f);
        UI.glowRect(app.batch, app.pixel, skinBtn.x, skinBtn.y, skinBtn.width, skinBtn.height,
            new Color(0.18f, 0.10f, 0.32f, 1f), 0.9f);
        UI.drawCentered(app.batch, app.fontSmall, "SKINS",
            skinBtn.x + skinBtn.width * 0.5f, skinBtn.y + skinBtn.height * 0.5f, Constants.WHITE);

        audioBtn.set(Constants.WORLD_W - 130f, 30f, 110f, 42f);
        UI.glowRect(app.batch, app.pixel, audioBtn.x, audioBtn.y, audioBtn.width, audioBtn.height,
            new Color(0.18f, 0.10f, 0.32f, 1f), 0.9f);
        UI.drawCentered(app.batch, app.fontSmall, app.audio.isMuted() ? "AUDIO OFF" : "AUDIO ON",
            audioBtn.x + audioBtn.width * 0.5f, audioBtn.y + audioBtn.height * 0.5f,
            app.audio.isMuted() ? new Color(0.8f, 0.6f, 0.6f, 1f) : Constants.CYAN);
    }

    private void drawSkinPicker() {
        app.batch.setColor(0f, 0f, 0f, 0.85f);
        app.batch.draw(app.pixel, 0, 0, Constants.WORLD_W, Constants.WORLD_H);
        app.batch.setColor(Color.WHITE);

        UI.drawCentered(app.batch, app.fontMedium, "SKINS",
            Constants.WORLD_W * 0.5f, Constants.WORLD_H - 70f, Constants.WHITE);
        UI.drawCentered(app.batch, app.fontSmall,
            "PURELY COSMETIC  -  NO PAY-TO-WIN, EVER.",
            Constants.WORLD_W * 0.5f, Constants.WORLD_H - 95f,
            new Color(0.8f, 0.8f, 0.95f, 0.7f));

        // Grid layout.
        int cols = 2;
        float cellW = 140f, cellH = 80f;
        float gap = 14f;
        float startX = (Constants.WORLD_W - (cols * cellW + (cols - 1) * gap)) * 0.5f;
        float startY = Constants.WORLD_H - 150f;

        for (int i = 0; i < app.skins.getAll().size && i < skinCells.length; i++) {
            Skin s = app.skins.getAll().get(i);
            int row = i / cols, col = i % cols;
            float cx = startX + col * (cellW + gap);
            float cy = startY - (row + 1) * (cellH + gap);
            skinCells[i].set(cx, cy, cellW, cellH);
            boolean unlocked = app.skins.isUnlocked(s);
            boolean active = s.id.equals(app.skins.getActive().id);
            Color border = active ? Constants.YELLOW : (unlocked ? Constants.CYAN : new Color(0.4f, 0.4f, 0.5f, 1f));
            UI.glowRect(app.batch, app.pixel, cx, cy, cellW, cellH,
                new Color(0.13f, 0.07f, 0.22f, 1f), 0.95f);
            UI.glowRect(app.batch, app.pixel, cx, cy + cellH - 4f, cellW, 4f, border, 0.95f);

            // Preview cube — use the actual skin sprite when we have one.
            float pcx = cx + 28f, pcy = cy + cellH * 0.5f - 13f;
            if (unlocked) {
                if (s.texture != null) {
                    app.batch.flush();
                    app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
                    app.batch.setColor(s.glow.r, s.glow.g, s.glow.b, 0.4f);
                    app.batch.draw(s.texture, pcx - 12f, pcy - 12f, 50f, 50f);
                    app.batch.flush();
                    app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                    app.batch.setColor(Color.WHITE);
                    app.batch.draw(s.texture, pcx - 6f, pcy - 6f, 38f, 38f);
                } else {
                    app.batch.flush();
                    app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
                    app.batch.setColor(s.glow.r, s.glow.g, s.glow.b, 0.55f);
                    app.batch.draw(app.pixel, pcx - 8f, pcy - 8f, 42f, 42f);
                    app.batch.flush();
                    app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                    app.batch.setColor(s.body);
                    app.batch.draw(app.pixel, pcx, pcy, 26f, 26f);
                }
            } else if (s.texture != null) {
                // Locked: silhouette the sprite in dark gray so the shape is hinted at.
                app.batch.flush();
                app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                app.batch.setColor(0.18f, 0.18f, 0.22f, 1f);
                app.batch.draw(s.texture, pcx - 6f, pcy - 6f, 38f, 38f);
            } else {
                app.batch.setColor(0.5f, 0.5f, 0.55f, 1f);
                app.batch.draw(app.pixel, pcx, pcy, 26f, 26f);
            }
            app.batch.setColor(Color.WHITE);

            // Label and unlock status.
            app.fontSmall.setColor(unlocked ? Color.WHITE : new Color(0.6f, 0.6f, 0.7f, 1f));
            app.fontSmall.draw(app.batch, s.displayName, cx + 64f, cy + cellH - 18f);
            String status = active ? "EQUIPPED" : (unlocked ? "TAP TO EQUIP" : ("LOCKED  " + app.skins.unlockHint(s)));
            app.fontSmall.setColor(active ? Constants.YELLOW : (unlocked ? Constants.CYAN : new Color(1f, 0.6f, 0.6f, 1f)));
            app.fontSmall.draw(app.batch, status, cx + 64f, cy + 18f);
        }

        // Close button.
        skinClose.set(Constants.WORLD_W * 0.5f - 70f, 36f, 140f, 44f);
        UI.glowRect(app.batch, app.pixel, skinClose.x, skinClose.y, skinClose.width, skinClose.height,
            Constants.CYAN, 0.95f);
        UI.drawCentered(app.batch, app.fontSmall, "CLOSE",
            skinClose.x + skinClose.width * 0.5f, skinClose.y + skinClose.height * 0.5f,
            new Color(0.04f, 0.02f, 0.08f, 1f));
    }

    private boolean handleSkinPickerTouch(float x, float y) {
        if (skinClose.contains(x, y)) {
            app.audio.playMenuClick();
            skinPickerOpen = false;
            return true;
        }
        for (int i = 0; i < app.skins.getAll().size && i < skinCells.length; i++) {
            if (skinCells[i].contains(x, y)) {
                Skin s = app.skins.getAll().get(i);
                if (app.skins.isUnlocked(s)) {
                    app.skins.select(s.id);
                    app.audio.playPickup();
                } else {
                    app.audio.playMenuClick();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void dispose() {
        particles.clear();
    }
}

