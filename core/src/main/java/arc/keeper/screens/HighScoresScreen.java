package arc.keeper.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import arc.keeper.Constants;
import arc.keeper.Main;
import arc.keeper.data.GlobalHighScores;
import arc.keeper.data.HighScoreEntry;

/**
 * Two-tab leaderboard view (LOCAL / GLOBAL). Reads cached snapshots every frame
 * so the GLOBAL tab updates live as the IO thread streams in new data, without
 * ever blocking input.
 */
public class HighScoresScreen extends ScreenAdapter {

    private enum Tab { LOCAL, GLOBAL }

    private final Main app;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final Viewport viewport = new FitViewport(Constants.WORLD_W, Constants.WORLD_H, camera);
    private final Vector3 tmp = new Vector3();

    private final Rectangle localTab   = new Rectangle();
    private final Rectangle globalTab  = new Rectangle();
    private final Rectangle backBtn    = new Rectangle();
    private final Rectangle refreshBtn = new Rectangle();

    private Tab tab = Tab.LOCAL;
    private TextureRegion bgRegion;
    private float time;

    public HighScoresScreen(Main app) {
        this.app = app;
        if (app.background != null) bgRegion = new TextureRegion(app.background);
        // Kick off a fresh global pull whenever this screen opens so the player
        // sees other devices' recent submissions, not just the cached top.
        app.globalScores.refresh();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                tmp.set(sx, sy, 0f); viewport.unproject(tmp);
                float x = tmp.x, y = tmp.y;
                if (localTab.contains(x, y))   { tab = Tab.LOCAL;  app.audio.playMenuClick(); return true; }
                if (globalTab.contains(x, y))  { tab = Tab.GLOBAL; app.audio.playMenuClick(); app.globalScores.refresh(); return true; }
                if (refreshBtn.contains(x, y) && tab == Tab.GLOBAL) {
                    app.globalScores.refresh(); app.audio.playMenuClick(); return true;
                }
                if (backBtn.contains(x, y)) {
                    app.audio.playMenuClick();
                    app.switchScreen(new TitleScreen(app));
                    return true;
                }
                return false;
            }
            @Override public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    app.switchScreen(new TitleScreen(app));
                    return true;
                }
                return false;
            }
        });
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        app.audio.setMusicIntensity(0.05f);
    }

    @Override public void hide() { Gdx.input.setInputProcessor(null); }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void render(float dt) {
        time += dt;
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        viewport.apply();
        app.batch.setProjectionMatrix(camera.combined);
        app.batch.begin();
        drawBackground();
        drawHeader();
        drawTabs();
        if (tab == Tab.LOCAL) drawLocalList();
        else drawGlobalList();
        drawFooter();
        app.batch.end();
    }

    private void drawBackground() {
        if (bgRegion != null) {
            float bgWorldH = (float) app.background.getHeight() / app.background.getWidth() * Constants.WORLD_W;
            float vRange   = Math.min(1f, Constants.WORLD_H / bgWorldH);
            float drift    = (1f - vRange) * (0.5f + 0.5f * (float) Math.sin(time * 0.08f));
            bgRegion.setRegion(0f, 1f - vRange - drift, 1f, 1f - drift);
            app.batch.setColor(Color.WHITE);
            app.batch.draw(bgRegion, 0, 0, Constants.WORLD_W, Constants.WORLD_H);
            app.batch.flush();
            app.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            app.batch.setColor(0.02f, 0.01f, 0.05f, 0.55f);
            app.batch.draw(app.pixel, 0, 0, Constants.WORLD_W, Constants.WORLD_H);
        } else {
            app.batch.setColor(Constants.BG_BOT);
            app.batch.draw(app.pixel, 0, 0, Constants.WORLD_W, Constants.WORLD_H);
        }
        app.batch.setColor(Color.WHITE);
    }

    private void drawHeader() {
        Color titleGlow = new Color(Constants.CYAN).lerp(Constants.MAGENTA, 0.5f);
        UI.drawCentered(app.batch, app.fontLarge, "HIGH SCORES",
            Constants.WORLD_W * 0.5f, Constants.WORLD_H - 60f, titleGlow);
        UI.drawCentered(app.batch, app.fontSmall, "YOU - " + app.save.getPlayerName(),
            Constants.WORLD_W * 0.5f, Constants.WORLD_H - 86f,
            new Color(1f, 1f, 1f, 0.55f));
    }

    private void drawTabs() {
        float y = Constants.WORLD_H - 130f;
        localTab.set(Constants.WORLD_W * 0.5f - 150f, y, 140f, 38f);
        globalTab.set(Constants.WORLD_W * 0.5f + 10f, y, 140f, 38f);

        Color activeBg   = new Color(0.30f, 0.16f, 0.48f, 1f);
        Color inactiveBg = new Color(0.13f, 0.07f, 0.22f, 1f);

        UI.glowRect(app.batch, app.pixel, localTab.x, localTab.y, localTab.width, localTab.height,
            tab == Tab.LOCAL ? activeBg : inactiveBg, 0.95f);
        UI.drawCentered(app.batch, app.fontSmall, "LOCAL",
            localTab.x + localTab.width * 0.5f, localTab.y + localTab.height * 0.5f,
            tab == Tab.LOCAL ? Constants.CYAN : Constants.WHITE);

        UI.glowRect(app.batch, app.pixel, globalTab.x, globalTab.y, globalTab.width, globalTab.height,
            tab == Tab.GLOBAL ? activeBg : inactiveBg, 0.95f);
        UI.drawCentered(app.batch, app.fontSmall, "GLOBAL",
            globalTab.x + globalTab.width * 0.5f, globalTab.y + globalTab.height * 0.5f,
            tab == Tab.GLOBAL ? Constants.MAGENTA : Constants.WHITE);
    }

    private void drawLocalList() {
        Array<HighScoreEntry> rows = app.localScores.snapshot();
        drawList(rows, "NO LOCAL RUNS YET. PLAY A ROUND!", Constants.CYAN);
    }

    private void drawGlobalList() {
        Array<HighScoreEntry> rows = app.globalScores.snapshot();

        GlobalHighScores.Status st = app.globalScores.getStatus();
        if (rows.isEmpty()) {
            String msg;
            switch (st) {
                case CONNECTING: msg = "CONNECTING..."; break;
                case LOADING:    msg = "LOADING..."; break;
                case ERROR:      msg = "OFFLINE\n" + safe(app.globalScores.getLastError()); break;
                default:         msg = "NO DATA YET.";
            }
            drawList(rows, msg, Constants.MAGENTA);
        } else {
            drawList(rows, "", Constants.MAGENTA);
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private void drawList(Array<HighScoreEntry> rows, String emptyMessage, Color accent) {
        float topY = Constants.WORLD_H - 190f;
        float rowH = 32f;
        float listW = Constants.WORLD_W - 60f;
        float listX = 30f;

        if (rows.isEmpty()) {
            for (String line : emptyMessage.split("\n")) {
                UI.drawCentered(app.batch, app.fontSmall, line,
                    Constants.WORLD_W * 0.5f, topY - 60f,
                    new Color(0.85f, 0.85f, 0.95f, 0.7f));
                topY -= 22f;
            }
            return;
        }

        String me = app.save.getPlayerName();
        for (int i = 0; i < rows.size; i++) {
            HighScoreEntry e = rows.get(i);
            float y = topY - (i + 1) * rowH;
            boolean isMe = e.name != null && e.name.equalsIgnoreCase(me);

            Color bg = (i % 2 == 0)
                ? new Color(0.08f, 0.04f, 0.16f, 0.85f)
                : new Color(0.12f, 0.06f, 0.20f, 0.85f);
            if (isMe) bg = new Color(accent.r * 0.35f, accent.g * 0.18f, accent.b * 0.45f, 0.9f);
            app.batch.setColor(bg);
            app.batch.draw(app.pixel, listX, y, listW, rowH - 4f);
            app.batch.setColor(Color.WHITE);

            // Rank pill on the left.
            Color rankCol = (i == 0) ? Constants.YELLOW
                          : (i == 1) ? new Color(0.85f, 0.85f, 0.9f, 1f)
                          : (i == 2) ? new Color(0.95f, 0.65f, 0.4f, 1f)
                          : accent;
            UI.drawCentered(app.batch, app.fontSmall, "#" + (i + 1),
                listX + 22f, y + (rowH - 4f) * 0.5f, rankCol);

            // Name.
            app.fontSmall.setColor(isMe ? Constants.YELLOW : Color.WHITE);
            app.fontSmall.draw(app.batch, e.name == null ? "?" : e.name,
                listX + 50f, y + (rowH - 4f) * 0.5f + 6f);

            // Score (right-aligned).
            UI.drawRight(app.batch, app.fontSmall, String.format("%,d", e.score),
                listX + listW - 56f, y + (rowH - 4f) * 0.5f,
                isMe ? Constants.YELLOW : Constants.WHITE);

            // Height in meters (far right small).
            UI.drawRight(app.batch, app.fontSmall, e.height + "m",
                listX + listW - 6f, y + (rowH - 4f) * 0.5f,
                new Color(0.7f, 0.7f, 0.85f, 0.85f));
        }
    }

    private void drawFooter() {
        backBtn.set(Constants.WORLD_W * 0.5f - 110f, 32f, 110f, 44f);
        UI.glowRect(app.batch, app.pixel, backBtn.x, backBtn.y, backBtn.width, backBtn.height,
            new Color(0.20f, 0.10f, 0.30f, 1f), 0.9f);
        UI.drawCentered(app.batch, app.fontSmall, "BACK",
            backBtn.x + backBtn.width * 0.5f, backBtn.y + backBtn.height * 0.5f, Constants.WHITE);

        refreshBtn.set(Constants.WORLD_W * 0.5f + 4f, 32f, 110f, 44f);
        Color rc = tab == Tab.GLOBAL ? Constants.MAGENTA : new Color(0.4f, 0.4f, 0.5f, 1f);
        UI.glowRect(app.batch, app.pixel, refreshBtn.x, refreshBtn.y, refreshBtn.width, refreshBtn.height,
            new Color(0.18f, 0.10f, 0.32f, 1f), 0.9f);
        UI.drawCentered(app.batch, app.fontSmall, "REFRESH",
            refreshBtn.x + refreshBtn.width * 0.5f, refreshBtn.y + refreshBtn.height * 0.5f, rc);

        if (tab == Tab.GLOBAL) {
            String stat;
            switch (app.globalScores.getStatus()) {
                case CONNECTING: stat = "CONNECTING..."; break;
                case LOADING:    stat = "LOADING..."; break;
                case READY:      stat = "ONLINE"; break;
                case ERROR:      stat = "OFFLINE"; break;
                default:         stat = "";
            }
            UI.drawCentered(app.batch, app.fontSmall, stat,
                Constants.WORLD_W * 0.5f, 92f,
                app.globalScores.getStatus() == GlobalHighScores.Status.READY
                    ? new Color(0.6f, 1f, 0.7f, 0.75f)
                    : new Color(1f, 0.7f, 0.5f, 0.75f));
        }
    }
}
