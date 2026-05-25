package arc.keeper.data;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;

/**
 * A purely-cosmetic player skin. We never gate gameplay behind skins — only the
 * cube sprite, glow / trail color, and the displayed name.
 */
public class Skin {
    public final String id;
    public final String displayName;
    public final Color body;
    public final Color glow;
    public final Color trail;
    /** Required total runs to unlock. 0 = always available. */
    public final int unlockRunsRequired;
    /** Required best height to unlock. 0 = always available. */
    public final float unlockHeightRequired;

    /** Asset path under assets/. Owned & loaded by SkinManager. */
    public final String texturePath;
    /** Loaded texture (alpha-keyed from luminance). Null while not yet loaded. */
    public Texture texture;

    public Skin(String id, String displayName, Color body, Color glow, Color trail,
                int unlockRunsRequired, float unlockHeightRequired, String texturePath) {
        this.id = id;
        this.displayName = displayName;
        this.body = body;
        this.glow = glow;
        this.trail = trail;
        this.unlockRunsRequired = unlockRunsRequired;
        this.unlockHeightRequired = unlockHeightRequired;
        this.texturePath = texturePath;
    }
}
