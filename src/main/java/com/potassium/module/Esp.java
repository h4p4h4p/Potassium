package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ColorSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * Player boxes in 2D or 3D, with optional tracers from the bottom of the screen.
 *
 * <p>The 2D and tracer passes share the projection captured during the world render, so
 * their positions are the same points the world boxes were built from. Names and the old
 * visibility-limited LegitESP mode have been removed; vanilla nametags are left alone.</p>
 */
public class Esp extends Module {

    public static final String MODE_2D = "2D";
    public static final String MODE_3D = "3D";

    private static final int MAX_QUADS = 16384;
    private static final int FILL_ALPHA = 100;
    private static final double NEAR_CAMERA_MARGIN = 0.2D;
    /** The same padding Blink draws its anchor box with, so both boxes read as one size. */
    private static final double BOX_PADDING = 0.1D;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_2D, MODE_3D}, 1);
    private final BooleanSetting cornersSetting = new BooleanSetting("Corners", false);
    private final BooleanSetting tracersSetting = new BooleanSetting("Tracers", false);
    private final ColorSetting colorSetting = new ColorSetting("Color", 0xFF1B395C);
    private final ColorSetting tracerColorSetting =
            new ColorSetting("Tracer Color", 0xFFF0A612);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through Walls", true);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 64, 8, 256, 1);

    private final Minecraft mc = Minecraft.getMinecraft();

    private final float[] mv = new float[16];
    private final float[] pr = new float[16];
    private final double[] feetScratch = new double[2];
    private final double[] headScratch = new double[2];
    private IntBuffer viewport;
    private FloatBuffer mvBuf;
    private FloatBuffer prBuf;
    private int vw;
    private int vh;
    private double scale = 1.0D;
    private int lastDisplayWidth = -1;
    private int lastDisplayHeight = -1;

    private float[] quadBuf = new float[512];
    private int quadCount;

    private float[] tracerXs = new float[64];
    private float[] tracerYs = new float[64];
    private int tracerCount;

    public Esp() {
        super("ESP", Category.RENDER);
        settings.add(modeSetting);
        settings.add(cornersSetting);
        settings.add(tracersSetting);
        settings.add(colorSetting);
        settings.add(tracerColorSetting);
        settings.add(throughWallsSetting);
        settings.add(rangeSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == cornersSetting) return MODE_2D.equals(modeSetting.getValue());
        if (setting == tracerColorSetting) return tracersSetting.getValue();
        if (setting == throughWallsSetting) return MODE_3D.equals(modeSetting.getValue());
        return true;
    }

    public int getPreviewColor() {
        return colorSetting.getValue();
    }

    public boolean getPreviewCorners() {
        return cornersSetting.getValue();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        quadCount = 0;
        tracerCount = 0;
        if (!captureMatrices()) return;
        collect2D(event.partialTicks);
        if (MODE_3D.equals(modeSetting.getValue())) render3D(event.partialTicks);
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (!isEnabled()) return;
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (quadCount > 0) {
            RenderUtil.drawBatchedRects(quadBuf, quadCount, colorSetting.getValue());
        }
        drawTracers();
    }

    /**
     * World-space boxes the size of the entity's real hitbox, so the outline matches
     * what the server and vanilla ray tracing use rather than an enlarged test box.
     */
    private void render3D(float partialTicks) {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;
        int color = colorSetting.getValue();
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int outlineAlpha = (color >>> 24) & 0xFF;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        if (throughWallsSetting.getValue()) GlStateManager.disableDepth();

        Tessellator tessellator = Tessellator.getInstance();
        for (Entity entity : mc.theWorld.playerEntities) {
            if (entity == mc.thePlayer) continue;
            if (AntiBot.isBot(entity)) continue;
            if (!inRange(entity, camX, camY, camZ)) continue;

            double ox = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - entity.posX;
            double oy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - entity.posY;
            double oz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - entity.posZ;

            // The unmodified entity box is the player's actual 0.6 x 1.8 hitbox, grown by
            // Blink's padding so the ESP and the anchor marker are the same size.
            AxisAlignedBB box = entity.getEntityBoundingBox()
                    .offset(ox, oy, oz).expand(BOX_PADDING, BOX_PADDING, BOX_PADDING);
            if (RenderUtil.pointNearBox(camX, camY, camZ, box, NEAR_CAMERA_MARGIN)) continue;

            RenderUtil.drawFilledBox(tessellator,
                    box.minX - camX, box.minY - camY, box.minZ - camZ,
                    box.maxX - camX, box.maxY - camY, box.maxZ - camZ,
                    r, g, b, FILL_ALPHA);
            RenderUtil.drawOutlinedBox(tessellator,
                    box.offset(-camX, -camY, -camZ), r, g, b, outlineAlpha);
        }

        if (throughWallsSetting.getValue()) GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    private boolean captureMatrices() {
        if (mvBuf == null) {
            viewport = BufferUtils.createIntBuffer(16);
            mvBuf = BufferUtils.createFloatBuffer(16);
            prBuf = BufferUtils.createFloatBuffer(16);
        }
        mvBuf.clear();
        prBuf.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuf);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, prBuf);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        mvBuf.get(mv);
        prBuf.get(pr);
        vw = viewport.get(2);
        vh = viewport.get(3);
        if (vw <= 0 || vh <= 0) return false;

        if (mc.displayWidth != lastDisplayWidth || mc.displayHeight != lastDisplayHeight) {
            lastDisplayWidth = mc.displayWidth;
            lastDisplayHeight = mc.displayHeight;
            scale = vh / (double) new ScaledResolution(mc).getScaledHeight();
        }
        return scale > 0;
    }

    private void storeTracer(double screenX, double screenY) {
        if (tracerCount == tracerXs.length) {
            tracerXs = Arrays.copyOf(tracerXs, tracerCount * 2);
            tracerYs = Arrays.copyOf(tracerYs, tracerCount * 2);
        }
        tracerXs[tracerCount] = (float) (screenX / scale);
        tracerYs[tracerCount] = (float) ((vh - screenY) / scale);
        tracerCount++;
    }

    private void drawTracers() {
        if (tracerCount == 0) return;
        ScaledResolution sr = new ScaledResolution(mc);
        float originX = sr.getScaledWidth() / 2f;
        float originY = sr.getScaledHeight();
        int color = tracerColorSetting.getValue();
        for (int i = 0; i < tracerCount; i++) {
            RenderUtil.drawLine(originX, originY, tracerXs[i], tracerYs[i], 1.5f, color);
        }
        tracerCount = 0;
    }

    private void collect2D(float partialTicks) {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;
        boolean showTracers = tracersSetting.getValue();

        List<EntityPlayer> players = mc.theWorld.playerEntities;
        for (int i = 0; i < players.size(); i++) {
            Entity entity = players.get(i);
            if (entity == mc.thePlayer) continue;
            if (AntiBot.isBot(entity)) continue;
            if (!inRange(entity, camX, camY, camZ)) continue;
            AxisAlignedBB bb = entity.getEntityBoundingBox();

            double ox = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - entity.posX;
            double oy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - entity.posY;
            double oz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - entity.posZ;

            double midX = (bb.minX + bb.maxX) * 0.5D + ox;
            double midZ = (bb.minZ + bb.maxZ) * 0.5D + oz;
            double minY = bb.minY + oy;
            double maxY = bb.maxY + oy;

            if (!project(midX - camX, minY - camY, midZ - camZ, feetScratch)) continue;
            if (!project(midX - camX, maxY - camY, midZ - camZ, headScratch)) continue;

            double topDownHead = vh - headScratch[1];
            double topDownFeet = vh - feetScratch[1];
            double h = Math.abs(topDownFeet - topDownHead);
            if (h < 2.0D) continue;
            double w = h * 0.5D;

            float x = (float) ((feetScratch[0] - w * 0.5D) / scale);
            float y = (float) (topDownHead / scale);
            float rw = (float) (w / scale);
            float rh = (float) (h / scale);

            if (MODE_2D.equals(modeSetting.getValue())) addRect(x, y, rw, rh);
            if (showTracers) storeTracer(headScratch[0], headScratch[1]);
        }
    }

    private void addRect(float x, float y, float w, float h) {
        if (!ensureQuadRoom(8)) return;
        if (cornersSetting.getValue()) {
            float len = Math.max(4f, Math.min(h / 6f, 10f));
            addQuad(x, y, len, 1f);
            addQuad(x, y, 1f, len);
            addQuad(x + w - len, y, len, 1f);
            addQuad(x + w - 1f, y, 1f, len);
            addQuad(x, y + h - 1f, len, 1f);
            addQuad(x, y + h - len, 1f, len);
            addQuad(x + w - len, y + h - 1f, len, 1f);
            addQuad(x + w - 1f, y + h - len, 1f, len);
        } else {
            addQuad(x, y, w, 1f);
            addQuad(x, y + h - 1f, w, 1f);
            addQuad(x, y, 1f, h);
            addQuad(x + w - 1f, y, 1f, h);
        }
    }

    /**
     * quadBuf holds four floats per quad, so capacity has to be reasoned about in floats.
     * The old guard compared a quad count against the array length and let addQuad run off
     * the end once 128 quads were queued, which any server with 33 visible entities hits.
     * Bounded at MAX_QUADS so a pathological entity count drops quads instead of
     * allocating its way into an OutOfMemoryError mid-frame.
     */
    private boolean ensureQuadRoom(int quads) {
        if ((quadCount + quads) * 4 > quadBuf.length) {
            int needed = (quadCount + quads) * 4;
            if (needed > MAX_QUADS * 4) return false;
            quadBuf = Arrays.copyOf(quadBuf, Math.max(needed, quadBuf.length * 2));
        }
        return true;
    }

    private void addQuad(float x, float y, float w, float h) {
        int o = quadCount << 2;
        quadBuf[o] = x;
        quadBuf[o + 1] = y;
        quadBuf[o + 2] = w;
        quadBuf[o + 3] = h;
        quadCount++;
    }

    private boolean project(double x, double y, double z, double[] out) {
        double inX = mv[0] * x + mv[4] * y + mv[8] * z + mv[12];
        double inY = mv[1] * x + mv[5] * y + mv[9] * z + mv[13];
        double inZ = mv[2] * x + mv[6] * y + mv[10] * z + mv[14];
        double inW = mv[3] * x + mv[7] * y + mv[11] * z + mv[15];

        double clipX = pr[0] * inX + pr[4] * inY + pr[8] * inZ + pr[12] * inW;
        double clipY = pr[1] * inX + pr[5] * inY + pr[9] * inZ + pr[13] * inW;
        double clipZ = pr[2] * inX + pr[6] * inY + pr[10] * inZ + pr[14] * inW;
        double clipW = pr[3] * inX + pr[7] * inY + pr[11] * inZ + pr[15] * inW;

        if (clipW <= 0.0D) return false;
        double invW = 1.0D / clipW;
        double ndcX = clipX * invW;
        double ndcY = clipY * invW;
        double ndcZ = clipZ * invW;
        if (ndcX < -1.5D || ndcX > 1.5D) return false;
        if (ndcY < -1.5D || ndcY > 1.5D) return false;
        if (ndcZ < -1.0D || ndcZ > 1.0D) return false;

        out[0] = (ndcX + 1.0D) * 0.5D * vw;
        out[1] = (ndcY + 1.0D) * 0.5D * vh;
        return true;
    }

    /** Squared distance from the camera to the entity's centre; Range is in blocks. */
    private boolean inRange(Entity entity, double camX, double camY, double camZ) {
        double dx = entity.posX - camX;
        double dy = entity.posY + entity.height * 0.5D - camY;
        double dz = entity.posZ - camZ;
        double range = rangeSetting.getValue();
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        quadCount = 0;
        tracerCount = 0;
    }
}
