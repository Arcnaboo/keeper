package arc.keeper.data;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

import arc.keeper.Constants;

/**
 * Catalog of cosmetic skins. The starter cyan skin is always free; the rest
 * unlock based on milestones (total runs played and best height reached).
 */
public class SkinManager {
    private final SaveData save;
    private final Array<Skin> all = new Array<>();
    private final ObjectMap<String, Skin> byId = new ObjectMap<>();

    public SkinManager(SaveData save) {
        this.save = save;
        register(new Skin("cyan",    "Neon Cyan",
            Constants.CYAN,    new Color(0.30f, 0.95f, 1f, 1f), Constants.CYAN, 0, 0));
        register(new Skin("magenta", "Hot Magenta",
            Constants.MAGENTA, new Color(1f, 0.30f, 0.77f, 1f), Constants.MAGENTA, 5, 0));
        register(new Skin("orange",  "Sunset Orange",
            Constants.ORANGE,  new Color(1f, 0.54f, 0.24f, 1f), Constants.ORANGE,  15, 0));
        register(new Skin("mint",    "Toxic Mint",
            Constants.MINT,    new Color(0.30f, 1f, 0.69f, 1f), Constants.MINT,    0,   800));
        register(new Skin("purple",  "Violet Pulse",
            Constants.PURPLE,  new Color(0.61f, 0.30f, 1f, 1f), Constants.PURPLE,  0,   2000));
        register(new Skin("yellow",  "Solar Flare",
            Constants.YELLOW,  new Color(1f, 0.89f, 0.30f, 1f), Constants.YELLOW,  40, 4000));
        register(new Skin("ghost",   "Ghost Shell",
            new Color(0.85f, 0.92f, 1f, 0.55f),
            new Color(1f, 1f, 1f, 1f),
            new Color(0.7f, 0.85f, 1f, 0.9f), 80, 6000));
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
