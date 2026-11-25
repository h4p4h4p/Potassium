package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Tahoma text for the ClickGUI and HUD, matching Adjust's UI font.
 *
 * <p>The face is read from the operating system (Tahoma ships with Windows) instead of being
 * bundled: the font itself is not redistributable. Glyphs are rasterised once with AWT into a
 * texture atlas, so the client does not need an extra font dependency. Atlases are baked at
 * the pixels-per-unit the caller draws at - Minecraft's GUI scale for the HUD, and the
 * ClickGUI's own fixed unit - and sampled 1:1, which keeps the small 10 px text crisp
 * instead of shrinking a larger bitmap with linear filtering. The baked ranges cover ASCII,
 * Latin-1 (accented letters and the section sign), Latin Extended-A, Cyrillic and the common
 * punctuation HUD text uses. If AWT or the font is unavailable the renderer silently falls
 * back to Minecraft's font.</p>
 */
public final class GuiFont {

    /** Nominal GUI size in scaled pixels. */
    public static final float SIZE = 10f;
    private static final int ATLAS_W = 1024;
    private static final int ATLAS_H = 1024;
    private static final int MAX_CODE = 0x2200;

    /** Code point ranges baked into every atlas. */
    private static final int[][] RANGES = {
            {0x0020, 0x007E},   // ASCII
            {0x00A0, 0x00FF},   // Latin-1: accents and §
            {0x0100, 0x017F},   // Latin Extended-A
            {0x0400, 0x04FF},   // Cyrillic
            {0x2010, 0x2027},   // dashes, quotes, bullet, ellipsis
            {0x20AC, 0x20AC},   // euro
            {0x2116, 0x2116}    // №
    };

    private static final String FORMAT_CODES = "0123456789abcdefklmnor";
    private static final int[] COLOR_CODES = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private static final Atlas REGULAR = new Atlas(Font.PLAIN);
    private static final Atlas BOLD = new Atlas(Font.BOLD);

    /** Pixels per GUI unit the caller draws at; zero follows Minecraft's GUI scale. */
    private static float overrideScale;

    private GuiFont() {
    }

    /**
     * Switches text to a caller that draws at its own pixels-per-unit instead of Minecraft's
     * GUI scale, so its glyphs stay 1:1 instead of being stretched to some other size. The
     * ClickGUI uses this; the HUD leaves it unset. Always pair with {@link #popScale()}.
     */
    public static void pushScale(float pixelsPerUnit) {
        overrideScale = pixelsPerUnit > 0f ? pixelsPerUnit : 0f;
    }

    public static void popScale() {
        overrideScale = 0f;
    }

    private static float activeScale() {
        return overrideScale > 0f ? overrideScale : guiScale();
    }

    public static void drawString(String text, float x, float y, int color, boolean bold) {
        draw(text, x, y, color, bold, false);
    }

    /**
     * HUD text with a one-pixel drop shadow. The shadow pass is drawn first and forced to
     * black, so a {@code §c}-coloured line gets a dark shadow instead of a red one. The
     * ClickGUI keeps using {@link #drawString} and stays flat.
     */
    public static void drawStringWithShadow(String text, float x, float y, int color, boolean bold) {
        if (text == null || text.isEmpty()) return;

        Atlas atlas = bold ? BOLD : REGULAR;
        if (atlas.bake(activeScale()) == null) {
            Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(text, (int) x, (int) y, color);
            return;
        }

        draw(text, x + 1f, y + 1f, 0xC8000000, bold, true);
        draw(text, x, y, color, bold, false);
    }

    private static void draw(String text, float x, float y, int color, boolean bold, boolean shadow) {
        if (text == null || text.isEmpty()) return;

        Atlas atlas = bold ? BOLD : REGULAR;
        Bake bake = atlas.bake(activeScale());
        if (bake == null) {
            Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(text, (int) x, (int) y, color);
            return;
        }

        // The glyph pass changes renderer state. Put back what was found instead of leaving
        // depth off, lighting off and the atlas bound: the next 3D draw inherits that state,
        // and on the inventory screen it drew the player model with no depth test, the
        // skin's hat layer showing through the head. Taken after bake(), which may have
        // rebuilt and bound the atlas.
        RenderUtil.GlState glState = RenderUtil.saveGlState();

        float alpha = ((color >>> 24) & 0xFF) / 255f;
        if ((color >>> 24) == 0) alpha = 1f;
        int baseRgb = color & 0xFFFFFF;
        int currentRgb = baseRgb;

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.bindTexture(bake.texture);

        // Snap the run to the device pixel grid so every texel lands on exactly one screen
        // pixel. Without this, fractional GUI positions make 1:1 text shimmer.
        float scale = bake.scale;
        float drawY = Math.round(y * scale) / scale;
        float pen = Math.round(x * scale) / scale;

        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\u00A7' && i + 1 < text.length()) {
                int code = FORMAT_CODES.indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (code >= 0) {
                    if (!shadow) {
                        if (code < 16) {
                            currentRgb = COLOR_CODES[code];
                        } else if (code == 21) {
                            currentRgb = baseRgb;
                        }
                    }
                    i++;
                    continue;
                }
            }

            Glyph glyph = bake.glyph(c);
            if (glyph == null) continue;

            float glyphX = Math.round(pen * scale) / scale;
            GlStateManager.color(((currentRgb >> 16) & 0xFF) / 255f,
                    ((currentRgb >> 8) & 0xFF) / 255f,
                    (currentRgb & 0xFF) / 255f, alpha);

            GL11.glTexCoord2f(glyph.u0, glyph.v0);
            GL11.glVertex2f(glyphX, drawY);
            GL11.glTexCoord2f(glyph.u0, glyph.v1);
            GL11.glVertex2f(glyphX, drawY + glyph.h);
            GL11.glTexCoord2f(glyph.u1, glyph.v1);
            GL11.glVertex2f(glyphX + glyph.w, drawY + glyph.h);
            GL11.glTexCoord2f(glyph.u1, glyph.v0);
            GL11.glVertex2f(glyphX + glyph.w, drawY);

            pen = glyphX + glyph.advance;
        }
        GL11.glEnd();

        RenderUtil.restoreGlState(glState);
    }

    public static float width(String text, boolean bold) {
        if (text == null || text.isEmpty()) return 0f;
        Bake bake = (bold ? BOLD : REGULAR).bake(activeScale());
        if (bake == null) return Minecraft.getMinecraft().fontRendererObj.getStringWidth(text);

        float w = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()
                    && FORMAT_CODES.indexOf(Character.toLowerCase(text.charAt(i + 1))) >= 0) {
                i++;
                continue;
            }
            Glyph glyph = bake.glyph(c);
            if (glyph != null) w += glyph.advance;
        }
        return w;
    }

    public static float height() {
        Bake bake = REGULAR.bake(activeScale());
        if (bake == null) return Minecraft.getMinecraft().fontRendererObj.FONT_HEIGHT;
        return bake.cellHeight;
    }

    private static int guiScale() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return 1;
            return Math.max(1, new ScaledResolution(mc).getScaleFactor());
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static final class Glyph {
        private final float u0;
        private final float v0;
        private final float u1;
        private final float v1;
        private final float w;
        private final float h;
        private final float advance;

        private Glyph(float u0, float v0, float u1, float v1, float w, float h, float advance) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.w = w;
            this.h = h;
            this.advance = advance;
        }
    }

    private static final class Atlas {

        private final int style;
        /** Bake for the HUD's Minecraft GUI scale; rebuilt when that setting changes. */
        private Bake hud;
        private int hudScale = -1;
        /** Bake for a caller with its own pixels-per-unit (the ClickGUI), kept alongside. */
        private Bake fixed;

        private Atlas(int style) {
            this.style = style;
        }

        /**
         * The glyphs for the scale being drawn at, or null when AWT or the font is missing.
         * The HUD and the ClickGUI draw in the same frame at different scales, so each keeps
         * its own bake rather than making the other rebuild its atlas on every frame.
         */
        private Bake bake(float scale) {
            Bake result;
            if (overrideScale > 0f) {
                if (fixed == null || Math.abs(fixed.scale - scale) > 1.0E-3f) {
                    fixed = build(scale);
                }
                result = fixed;
            } else {
                int guiScale = Math.max(1, Math.round(scale));
                if (hud == null || hudScale != guiScale) {
                    if (hud != null && hud.texture != 0) {
                        GlStateManager.bindTexture(0);
                        GL11.glDeleteTextures(hud.texture);
                    }
                    hud = build(guiScale);
                    hudScale = guiScale;
                }
                result = hud;
            }
            return result != null && !result.failed ? result : null;
        }

        private Bake build(float scale) {
            Bake bake = new Bake(scale);
            try {
                int fontPx = Math.max(1, Math.round(SIZE * scale));
                Font font = new Font("Tahoma", style, fontPx);
                BufferedImage image = new BufferedImage(ATLAS_W, ATLAS_H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                g.setFont(font);
                g.setColor(Color.WHITE);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                FontMetrics metrics = g.getFontMetrics();
                int ascent = metrics.getAscent();
                int descent = metrics.getDescent();
                int cell = ascent + descent;
                bake.cellHeight = cell / scale;

                int penX = 1;
                int penY = 1;
                for (int[] range : RANGES) {
                    for (int code = range[0]; code <= range[1]; code++) {
                        if (code >= bake.glyphs.length) continue;
                        int w = Math.max(1, metrics.charWidth((char) code));
                        if (penX + w + 1 > ATLAS_W) {
                            penX = 1;
                            penY += cell + 1;
                        }
                        if (penY + cell + 1 > ATLAS_H) {
                            bake.failed = true;
                            break;
                        }

                        g.drawString(new String(Character.toChars(code)), penX, penY + ascent);

                        bake.glyphs[code] = new Glyph(
                                penX / (float) ATLAS_W,
                                penY / (float) ATLAS_H,
                                (penX + w) / (float) ATLAS_W,
                                (penY + cell) / (float) ATLAS_H,
                                w / scale,
                                bake.cellHeight,
                                w / scale);

                        penX += w + 1;
                    }
                    if (bake.failed) break;
                }
                g.dispose();

                if (!bake.failed) upload(bake, image);
            } catch (Throwable ignored) {
                bake.failed = true;
            }
            return bake;
        }

        private void upload(Bake bake, BufferedImage image) {
            int[] pixels = image.getRGB(0, 0, ATLAS_W, ATLAS_H, null, 0, ATLAS_W);
            ByteBuffer buffer = BufferUtils.createByteBuffer(ATLAS_W * ATLAS_H * 4);
            for (int argb : pixels) {
                buffer.put((byte) (argb >> 16));
                buffer.put((byte) (argb >> 8));
                buffer.put((byte) argb);
                buffer.put((byte) (argb >>> 24));
            }
            buffer.flip();

            bake.texture = GL11.glGenTextures();
            GlStateManager.bindTexture(bake.texture);
            // The atlas is baked at the scale it is sampled at, so it is copied 1:1.
            // Nearest sampling preserves the AWT antialiasing and prevents neighbour bleed.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, ATLAS_W, ATLAS_H, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        }
    }

    /** One atlas bake: the glyphs, their texture and the scale they were rasterised for. */
    private static final class Bake {

        private final float scale;
        private final Glyph[] glyphs = new Glyph[MAX_CODE];
        private int texture;
        private float cellHeight;
        private boolean failed;

        private Bake(float scale) {
            this.scale = scale;
        }

        private Glyph glyph(char c) {
            if (c == '\n' || c == '\r' || c == '\t') return glyphs[' '];
            if (c < glyphs.length && glyphs[c] != null) return glyphs[c];
            return glyphs['?'];
        }
    }
}
