package arc.keeper.fx;

import com.badlogic.gdx.math.MathUtils;

import arc.keeper.Constants;

/**
 * Trauma-based screen shake. Each event raises trauma; the visible shake amplitude
 * is trauma squared so small bumps stay subtle while big hits feel huge.
 */
public class ScreenShake {
    private float trauma;
    private float maxOffset = 14f;

    public void add(float t) {
        trauma += t;
        if (trauma > Constants.MAX_TRAUMA) trauma = Constants.MAX_TRAUMA;
    }

    public void update(float dt) {
        trauma -= Constants.TRAUMA_DECAY * dt;
        if (trauma < 0f) trauma = 0f;
    }

    public float offsetX() {
        float s = trauma * trauma;
        return MathUtils.randomTriangular() * s * maxOffset;
    }

    public float offsetY() {
        float s = trauma * trauma;
        return MathUtils.randomTriangular() * s * maxOffset;
    }

    public float angleDegrees() {
        float s = trauma * trauma;
        return MathUtils.randomTriangular() * s * 2.2f;
    }

    public float trauma() { return trauma; }

    public void reset() { trauma = 0f; }
}
