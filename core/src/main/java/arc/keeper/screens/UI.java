package arc.keeper.screens;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;

/** Tiny helpers shared by all screens to avoid duplicating boilerplate. */
public final class UI {
    private UI() {}

    private static final GlyphLayout LAYOUT = new GlyphLayout();

    /** Draws text centered on (cx, cy). cy is the text's *baseline-ish* center. */
    public static void drawCentered(SpriteBatch batch, BitmapFont font, String text,
                                    float cx, float cy, Color color) {
        font.setColor(color);
        LAYOUT.setText(font, text);
        font.draw(batch, text, cx - LAYOUT.width * 0.5f, cy + LAYOUT.height * 0.5f);
    }

    /** Draws text aligned to the right of (rx, cy). */
    public static void drawRight(SpriteBatch batch, BitmapFont font, String text,
                                 float rx, float cy, Color color) {
        font.setColor(color);
        LAYOUT.setText(font, text);
        font.draw(batch, text, rx - LAYOUT.width, cy + LAYOUT.height * 0.5f);
    }

    /** Draws a rectangle with an additive outer glow + opaque core (matches platform style). */
    public static void glowRect(SpriteBatch batch, Texture pixel, float x, float y, float w, float h,
                                 Color c, float alpha) {
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE);
        batch.setColor(c.r, c.g, c.b, 0.35f * alpha);
        batch.draw(pixel, x - 6f, y - 6f, w + 12f, h + 12f);
        batch.setColor(c.r, c.g, c.b, 0.55f * alpha);
        batch.draw(pixel, x - 2f, y - 2f, w + 4f, h + 4f);
        batch.flush();
        batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(c.r, c.g, c.b, alpha);
        batch.draw(pixel, x, y, w, h);
        batch.setColor(Color.WHITE);
    }

    /** Hit-test helper for tappable rectangles. */
    public static boolean hit(Rectangle r, float x, float y) {
        return r.contains(x, y);
    }
}
