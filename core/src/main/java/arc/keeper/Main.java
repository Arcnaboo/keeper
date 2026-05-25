package arc.keeper;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import arc.keeper.audio.AudioManager;
import arc.keeper.data.SaveData;
import arc.keeper.data.SkinManager;
import arc.keeper.screens.TitleScreen;

/**
 * Root game class. Holds shared GPU resources (batch, shape renderer, fonts, pixel texture)
 * so individual screens can swap cheaply, which keeps restarts instant.
 */
public class Main extends Game {

    public SpriteBatch batch;
    public ShapeRenderer shapes;
    public BitmapFont fontSmall;
    public BitmapFont fontMedium;
    public BitmapFont fontLarge;

    /** A single white 1x1 pixel texture — used to draw arbitrary glow quads with SpriteBatch. */
    public Texture pixel;

    /** Cyberpunk scrolling backdrop, shared by every screen. */
    public Texture background;

    public SaveData save;
    public SkinManager skins;
    public AudioManager audio;

    @Override
    public void create() {
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();

        // Default libGDX bitmap font, scaled into a couple of sizes for our minimal UI.
        fontSmall = new BitmapFont();
        fontSmall.getData().setScale(0.95f);
        fontSmall.setColor(Color.WHITE);

        fontMedium = new BitmapFont();
        fontMedium.getData().setScale(1.45f);
        fontMedium.setColor(Color.WHITE);

        fontLarge = new BitmapFont();
        fontLarge.getData().setScale(2.4f);
        fontLarge.setColor(Color.WHITE);

        // 1x1 white pixel used as the source for every additive rect we draw. We pass
        // managed=true and disposePixmap=true so the texture can survive a GL context
        // loss on Android (the Pixmap stays alive until the Texture is disposed).
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        pixel = new Texture(new PixmapTextureData(pm, null, false, true, true));

        // Cyberpunk space background. ClampToEdge so scrolling past the image just
        // shows the deep-space top pixel row rather than tiling the city back in.
        background = new Texture(Gdx.files.internal("background.png"));
        background.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        background.setWrap(TextureWrap.ClampToEdge, TextureWrap.ClampToEdge);

        save = new SaveData();
        skins = new SkinManager(save);
        audio = new AudioManager(save);

        setScreen(new TitleScreen(this));
    }

    /**
     * Replaces the current screen and disposes the previous one. Use this rather than the
     * raw {@link #setScreen(Screen)} call so we don't slowly leak old screens on transition.
     */
    public void switchScreen(Screen next) {
        Screen old = getScreen();
        setScreen(next); // hide() runs on old, show() runs on next
        if (old != null && old != next) old.dispose();
    }

    @Override
    public void render() {
        // Always clear with a deep purple-black before screens render. Individual screens
        // paint their own gradient backgrounds on top of this baseline.
        Gdx.gl.glClearColor(Constants.BG_BOT.r, Constants.BG_BOT.g, Constants.BG_BOT.b, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
        super.render();
    }

    @Override
    public void dispose() {
        if (getScreen() != null) getScreen().dispose();
        batch.dispose();
        shapes.dispose();
        fontSmall.dispose();
        fontMedium.dispose();
        fontLarge.dispose();
        pixel.dispose();
        if (background != null) background.dispose();
        audio.dispose();
    }
}
