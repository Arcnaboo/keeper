package arc.keeper.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

import arc.keeper.Constants;

/**
 * Catalog of cosmetic skins. The starter cyan skin is always free; the rest
 * unlock based on milestones (total runs played and best height reached).
 *
 * <p>Skin PNGs are authored as RGB on a solid black background (the artwork
 * includes a baked glow). We rewrite each one to RGBA at load time using
 * {@code alpha = max(r,g,b)} so the black margin becomes transparent and the
 * outer glow stays as a soft feathered alpha — no manual cutouts required.
 */
public class SkinManager {
    private final SaveData save;
    private final Array<Skin> all = new Array<>();
    private final ObjectMap<String, Skin> byId = new ObjectMap<>();
    private boolean texturesLoaded;

    public SkinManager(SaveData save) {
        this.save = save;
        register(new Skin("cyan",    "Neon Cyan",
            Constants.CYAN,    new Color(0.30f, 0.95f, 1f, 1f), Constants.CYAN, 0, 0,
            "player/player_neon_cyan.png"));
        register(new Skin("magenta", "Hot Magenta",
            Constants.MAGENTA, new Color(1f, 0.30f, 0.77f, 1f), Constants.MAGENTA, 5, 0,
            "player/player_hot_magenta.png"));
        register(new Skin("orange",  "Sunset Orange",
            Constants.ORANGE,  new Color(1f, 0.54f, 0.24f, 1f), Constants.ORANGE,  15, 0,
            "player/player_solar_orange.png"));
        register(new Skin("mint",    "Toxic Mint",
            Constants.MINT,    new Color(0.30f, 1f, 0.69f, 1f), Constants.MINT,    0,   800,
            "player/player_toxic_mint.png"));
        register(new Skin("purple",  "Violet Pulse",
            Constants.PURPLE,  new Color(0.61f, 0.30f, 1f, 1f), Constants.PURPLE,  0,   2000,
            "player/player_violet_pulse.png"));
        register(new Skin("yellow",  "Solar Flare",
            Constants.YELLOW,  new Color(1f, 0.89f, 0.30f, 1f), Constants.YELLOW,  40, 4000,
            "player/player_solar_flare.png"));
        register(new Skin("ghost",   "Ghost Shell",
            new Color(0.85f, 0.92f, 1f, 0.55f),
            new Color(1f, 1f, 1f, 1f),
            new Color(0.7f, 0.85f, 1f, 0.9f), 80, 6000,
            "player/player_ghost_shell.png"));
    }

    /**
     * Loads all skin textures. Must be called from the GL thread (e.g. from Main.create
     * after Gdx is initialised). Idempotent.
     */
    public void loadTextures() {
        if (texturesLoaded) return;
        for (Skin s : all) {
            if (s.texturePath == null) continue;
            try {
                s.texture = loadSkinTexture(s.texturePath);
            } catch (Throwable t) {
                // Missing or corrupt asset — fall back to procedural cube for this skin.
                Gdx.app.error("SkinManager", "Failed to load " + s.texturePath + ": " + t.getMessage());
                s.texture = null;
            }
        }
        texturesLoaded = true;
    }

    /**
     * Loads a PNG and rebuilds it as RGBA, computing alpha from per-pixel luminance.
     * Pure black pixels (the surrounding void in the artwork) become fully transparent;
     * the soft outer glow stays as a smooth alpha ramp.
     */
    private static Texture loadSkinTexture(String path) {
        Pixmap src = new Pixmap(Gdx.files.internal(path));
        int w = src.getWidth();
        int h = src.getHeight();
        Pixmap dst = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = src.getPixel(x, y);
                int r = (rgba >>> 24) & 0xFF;
                int g = (rgba >>> 16) & 0xFF;
                int b = (rgba >>>  8) & 0xFF;
                int a = Math.max(r, Math.max(g, b));
                int packed = (r << 24) | (g << 16) | (b << 8) | a;
                dst.drawPixel(x, y, packed);
            }
        }
        Texture tex = new Texture(dst);
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        src.dispose();
        dst.dispose();
        return tex;
    }

    /** Releases every loaded skin texture. */
    public void dispose() {
        for (Skin s : all) {
            if (s.texture != null) {
                s.texture.dispose();
                s.texture = null;
            }
        }
        texturesLoaded = false;
    }

    private void register(Skin s) {
        all.add(s);
        byId.put(s.id, s);
    }

    public Array<Skin> getAll() { return all; }
    public Skin get(String id)  { return byId.get(id, all.first()); }

    public Skin getActive() {
        Skin s = get(save.getActiveSkin());
        if (!isUnlocked(s)) return all.first();
        return s;
    }

    /** Considers both the persisted unlock flag and the milestone gates. */
    public boolean isUnlocked(Skin s) {
        if (save.isSkinUnlocked(s.id)) return true;
        boolean runsOk   = s.unlockRunsRequired   == 0 || save.getTotalRuns()  >= s.unlockRunsRequired;
        boolean heightOk = s.unlockHeightRequired == 0 || save.getBestHeight() >= s.unlockHeightRequired;
        if (runsOk && heightOk) {
            save.unlockSkin(s.id);
            return true;
        }
        return false;
    }

    /** Selects the skin. Returns false if the skin is locked. */
    public boolean select(String id) {
        Skin s = get(id);
        if (!isUnlocked(s)) return false;
        save.setActiveSkin(s.id);
        return true;
    }

    /** Short human-readable hint for what's needed to unlock a skin. */
    public String unlockHint(Skin s) {
        if (s.unlockRunsRequired > 0)  return "Play " + s.unlockRunsRequired + " runs";
        if (s.unlockHeightRequired > 0) return "Reach " + (int) s.unlockHeightRequired + "m";
        return "";
    }
}
