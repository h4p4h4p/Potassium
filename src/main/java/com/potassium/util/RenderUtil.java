package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;

public class RenderUtil {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public static boolean pointNearBox(double px, double py, double pz, AxisAlignedBB box, double margin) {
        double dx = Math.max(Math.max(box.minX - px, 0.0D), Math.max(px - box.maxX, 0.0D));
        double dy = Math.max(Math.max(box.minY - py, 0.0D), Math.max(py - box.maxY, 0.0D));
        double dz = Math.max(Math.max(box.minZ - pz, 0.0D), Math.max(pz - box.maxZ, 0.0D));
        return dx * dx + dy * dy + dz * dz < margin * margin;
    }

    /**
     * All twelve edges of the box, each as an explicit pair of vertices.
     *
     * The squares used to be four-vertex line strips, which draw three edges and leave the
     * fourth open: the top of the box read as an open shape with a stray edge across it
     * rather than a closed quad. Drawing the edges one by one removes the gaps and makes it
     * impossible for a strip to run a line between corners that are not an edge.
     */
    public static void drawOutlinedBox(Tessellator tessellator, AxisAlignedBB box, int r, int g, int b, int a) {
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(1, DefaultVertexFormats.POSITION_COLOR);

        edge(wr, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, r, g, b, a);
        edge(wr, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, r, g, b, a);
        edge(wr, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, r, g, b, a);
        edge(wr, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ, r, g, b, a);

        edge(wr, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, r, g, b, a);
        edge(wr, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, a);
        edge(wr, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, r, g, b, a);
        edge(wr, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, r, g, b, a);

        edge(wr, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, r, g, b, a);
        edge(wr, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, r, g, b, a);
        edge(wr, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, r, g, b, a);
        edge(wr, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, r, g, b, a);

        tessellator.draw();
    }

    private static void edge(WorldRenderer wr, double x1, double y1, double z1,
                             double x2, double y2, double z2, int r, int g, int b, int a) {
        vertex(wr, x1, y1, z1, r, g, b, a);
        vertex(wr, x2, y2, z2, r, g, b, a);
    }

    public static void drawFilledBox(Tessellator tessellator, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int r, int g, int b, int a) {
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        
        // Bottom (minY) - CCW from outside (viewed from below)
        vertex(wr, minX, minY, maxZ, r, g, b, a);
        vertex(wr, maxX, minY, maxZ, r, g, b, a);
        vertex(wr, maxX, minY, minZ, r, g, b, a);
        vertex(wr, minX, minY, minZ, r, g, b, a);
        
        // Top (maxY) - CCW from outside (above)
        vertex(wr, minX, maxY, minZ, r, g, b, a);
        vertex(wr, maxX, maxY, minZ, r, g, b, a);
        vertex(wr, maxX, maxY, maxZ, r, g, b, a);
        vertex(wr, minX, maxY, maxZ, r, g, b, a);
        
        // Front (maxZ) - CCW from outside (front)
        vertex(wr, minX, minY, maxZ, r, g, b, a);
        vertex(wr, maxX, minY, maxZ, r, g, b, a);
        vertex(wr, maxX, maxY, maxZ, r, g, b, a);
        vertex(wr, minX, maxY, maxZ, r, g, b, a);
        
        // Back (minZ) - CCW from outside (back)
        vertex(wr, maxX, minY, minZ, r, g, b, a);
        vertex(wr, minX, minY, minZ, r, g, b, a);
        vertex(wr, minX, maxY, minZ, r, g, b, a);
        vertex(wr, maxX, maxY, minZ, r, g, b, a);
        
        // Left (minX) - CCW from outside (left)
        vertex(wr, minX, minY, minZ, r, g, b, a);
        vertex(wr, minX, minY, maxZ, r, g, b, a);
        vertex(wr, minX, maxY, maxZ, r, g, b, a);
        vertex(wr, minX, maxY, minZ, r, g, b, a);
        
        // Right (maxX) - CCW from outside (right)
        vertex(wr, maxX, minY, maxZ, r, g, b, a);
        vertex(wr, maxX, minY, minZ, r, g, b, a);
        vertex(wr, maxX, maxY, minZ, r, g, b, a);
        vertex(wr, maxX, maxY, maxZ, r, g, b, a);
        
        tessellator.draw();
    }

    private static void vertex(WorldRenderer wr, double x, double y, double z, int r, int g, int b, int a) {
        wr.pos(x, y, z).color(r, g, b, a).endVertex();
    }

    public static void drawRect(float x, float y, float w, float h, int color) {
        if (w == 0 || h == 0) return;
        Gui.drawRect((int) x, (int) y, (int) (x + w), (int) (y + h), color);
    }

    /**
     * State the client's own drawing changes, saved so a HUD line or a 3D model preview can
     * hand the renderer back exactly what it found.
     *
     * <p>Depth is the one that matters: 2D text turns the depth test off and used to leave it
     * off, so the next 3D draw inherited it. On the inventory screen that drew the player
     * model without any depth test, every skin layer painted over the ones behind it and the
     * hat showed through the head. Lighting leaks the same way and flattens the model.
     */
    public static final class GlState {

        private final boolean depthTest;
        private final boolean depthMask;
        private final boolean lighting;
        private final boolean colorMaterial;
        private final boolean texture2D;
        private final boolean blend;
        private final boolean alphaTest;
        private final boolean cull;
        private final int textureId;

        private GlState() {
            depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            colorMaterial = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL);
            texture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        public boolean isDepthTest() {
            return depthTest;
        }
    }

    public static GlState saveGlState() {
        return new GlState();
    }

    public static void restoreGlState(GlState state) {
        if (state == null) return;

        // Through GlStateManager, never raw GL: its cache has to agree with what is set, or
        // the next enable/disable becomes a no-op against the real state.
        if (state.depthTest) GlStateManager.enableDepth();
        else GlStateManager.disableDepth();
        GlStateManager.depthMask(state.depthMask);
        if (state.lighting) GlStateManager.enableLighting();
        else GlStateManager.disableLighting();
        if (state.colorMaterial) GlStateManager.enableColorMaterial();
        else GlStateManager.disableColorMaterial();
        if (state.texture2D) GlStateManager.enableTexture2D();
        else GlStateManager.disableTexture2D();
        if (state.blend) GlStateManager.enableBlend();
        else GlStateManager.disableBlend();
        if (state.alphaTest) GlStateManager.enableAlpha();
        else GlStateManager.disableAlpha();
        if (state.cull) GlStateManager.enableCull();
        else GlStateManager.disableCull();
        GlStateManager.bindTexture(state.textureId);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /**
     * Soft drop shadow behind a HUD element. The outermost layers fade into the world and
     * the inner plate keeps the text readable over bright grass, sky or water. Only the HUD
     * uses this; the ClickGUI keeps its own solid panels.
     */
    public static void drawHudShadow(float x, float y, float w, float h) {
        if (w <= 0f || h <= 0f) return;
        int pad = 2;
        drawRect(x - pad - 2, y - pad + 1, w + (pad + 2) * 2f, h + (pad + 2) * 2f, 0x0C000000);
        drawRect(x - pad - 1, y - pad + 1, w + (pad + 1) * 2f, h + (pad + 1) * 2f, 0x14000000);
        drawRect(x - pad, y - pad, w + pad * 2f, h + pad * 2f, 0x34000000);
    }

    /**
     * Flat plate for the HUD module's Regular panel mode: one rectangle exactly the size of
     * the text, with none of the soft shadow's padding or fade. Only the HUD uses it; the
     * ClickGUI keeps its own solid panels.
     */
    public static void drawHudPanel(float x, float y, float w, float h) {
        if (w <= 0f || h <= 0f) return;
        drawRect(x, y, w, h, 0x66000000);
    }

    /**
     * Clips all following drawing to the given rectangle in scaled GUI coordinates.
     * Scissor works in framebuffer pixels, so the rectangle is scaled and flipped
     * against the scaled height. Always pair with {@link #disableScissor()}.
     */
    public static void enableScissor(float x, float y, float w, float h) {
        ScaledResolution sr = new ScaledResolution(mc);
        int scale = sr.getScaleFactor();
        int sx = (int) (x * scale);
        int sy = sr.getScaledHeight() * scale - (int) ((y + h) * scale);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, Math.max(0, (int) Math.ceil(w * scale)),
                Math.max(0, (int) Math.ceil(h * scale)));
    }

    /**
     * The same clip for a screen that draws at its own pixels-per-unit instead of the GUI
     * scale (the ClickGUI), so the rectangle goes straight to framebuffer pixels.
     */
    public static void enableScissor(float x, float y, float w, float h, float pixelsPerUnit) {
        int fbHeight = mc.displayHeight;
        int sx = (int) (x * pixelsPerUnit);
        int sy = fbHeight - (int) ((y + h) * pixelsPerUnit);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, Math.max(0, (int) Math.ceil(w * pixelsPerUnit)),
                Math.max(0, (int) Math.ceil(h * pixelsPerUnit)));
    }

    public static void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /** A 2D line with a fixed stroke width, used by the ESP's tracers. */
    public static void drawLine(float x1, float y1, float x2, float y2, float width, int color) {
        float a = (color >> 24 & 255) / 255.0F;
        float r = (color >> 16 & 255) / 255.0F;
        float g = (color >> 8 & 255) / 255.0F;
        float b = (color & 255) / 255.0F;

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(Math.max(1.0F, width));

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.0F);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    public static void drawBatchedRects(float[] quads, int count, int color) {
        if (count == 0) return;
        float a = (color >> 24 & 255) / 255.0F;
        float r = (color >> 16 & 255) / 255.0F;
        float g = (color >> 8 & 255) / 255.0F;
        float b = (color & 255) / 255.0F;

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            int o = i << 2;
            int x1 = (int) quads[o];
            int y1 = (int) quads[o + 1];
            int x2 = (int) (quads[o] + quads[o + 2]);
            int y2 = (int) (quads[o + 1] + quads[o + 3]);
            if (x1 == x2 || y1 == y2) continue;
            wr.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(x1, y2, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(x2, y1, 0.0D).color(r, g, b, a).endVertex();
        }
        tessellator.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    /**
     * Filled ring of the given thickness, built from quads rather than a GL line loop so the
     * stroke width is honoured on drivers that clamp glLineWidth to 1px.
     */
    public static void drawCircleOutline(float cx, float cy, float radius, float thickness, int color, int segments) {
        if (radius <= 0.0F || segments < 3) return;
        float a = (color >> 24 & 255) / 255.0F;
        float r = (color >> 16 & 255) / 255.0F;
        float g = (color >> 8 & 255) / 255.0F;
        float b = (color & 255) / 255.0F;

        float half = Math.max(thickness, 0.5F) * 0.5F;
        float inner = Math.max(radius - half, 0.0F);
        float outer = radius + half;

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        double step = Math.PI * 2.0 / segments;
        for (int i = 0; i < segments; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            double c0 = Math.cos(a0), s0 = Math.sin(a0);
            double c1 = Math.cos(a1), s1 = Math.sin(a1);
            wr.pos((float) (cx + c0 * inner), (float) (cy + s0 * inner), 0.0D).color(r, g, b, a).endVertex();
            wr.pos((float) (cx + c1 * inner), (float) (cy + s1 * inner), 0.0D).color(r, g, b, a).endVertex();
            wr.pos((float) (cx + c1 * outer), (float) (cy + s1 * outer), 0.0D).color(r, g, b, a).endVertex();
            wr.pos((float) (cx + c0 * outer), (float) (cy + s0 * outer), 0.0D).color(r, g, b, a).endVertex();
        }
        tessellator.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    public static void drawRoundedRect(float x, float y, float w, float h, float r, int color) {
        drawRect(x, y, w, h, color);
    }

    public static void drawString(String text, float x, float y, int color) {
        FontRenderer fr = mc.fontRendererObj;
        fr.drawStringWithShadow(text, (int) x, (int) y, color);
    }

    public static int getTextWidth(String text) {
        return mc.fontRendererObj.getStringWidth(text);
    }

    public static int getTextHeight() {
        return mc.fontRendererObj.FONT_HEIGHT;
    }

    /** Tahoma text for the ClickGUI, matching Adjust's UI face. */
    public static void drawGuiString(String text, float x, float y, int color) {
        GuiFont.drawString(text, x, y, color, false);
    }

    /** HUD text with a drop shadow. Kept separate from the flat ClickGUI text. */
    public static void drawGuiStringShadow(String text, float x, float y, int color) {
        GuiFont.drawStringWithShadow(text, x, y, color, false);
    }

    /** Tahoma bold, used by the ClickGUI category headers. */
    public static void drawGuiStringBold(String text, float x, float y, int color) {
        GuiFont.drawString(text, x, y, color, true);
    }

    public static float getGuiTextWidth(String text) {
        return GuiFont.width(text, false);
    }

    public static float getGuiTextWidthBold(String text) {
        return GuiFont.width(text, true);
    }

    public static float getGuiTextHeight() {
        return GuiFont.height();
    }

    /** Whole-pixel Tahoma measurements for HUD elements, which are laid out on an int grid. */
    public static int getGuiTextWidthInt(String text) {
        return (int) Math.ceil(GuiFont.width(text, false));
    }

    public static int getGuiTextHeightInt() {
        return (int) Math.ceil(GuiFont.height());
    }

    /** Top of a text line centred in a row of the given height. */
    public static float guiTextY(float rowTop, float rowHeight) {
        return rowTop + (rowHeight - GuiFont.height()) * 0.5f;
    }
}