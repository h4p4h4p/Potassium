package com.potassium.module;

import com.potassium.settings.ColorSetting;
import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Lines from the bottom of the screen to each player, drawn in the HUD pass.
 *
 * <p>This used to be a switch inside ESP. It is its own module now so tracers can be drawn
 * without the boxes, and so the tracer colour and thickness no longer live in the box
 * module's settings. The screen points are captured during the world render, while the
 * projection matrices are still the ones the frame was drawn with, and the lines are drawn
 * afterwards from those points.</p>
 *
 * <p>Every line goes into one tessellator pass instead of one draw call per player.</p>
 */
public class Tracers extends Module {

    private static final int MAX_TRACERS = 512;

    private final ColorSetting colorSetting = new ColorSetting("Color", 0xFFF0A612);
    private final SliderSetting thicknessSetting =
            new SliderSetting("Thickness", 1.5, 0.5, 4.0, 0.1);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 64, 8, 256, 1);

    private final Minecraft mc = Minecraft.getMinecraft();

    private final float[] mv = new float[16];
    private final float[] pr = new float[16];
    private final double[] point = new double[2];
    private IntBuffer viewport;
    private FloatBuffer mvBuf;
    private FloatBuffer prBuf;
    private int vw;
    private int vh;
    private double scale = 1.0D;
    private int lastDisplayWidth = -1;
    private int lastDisplayHeight = -1;

    private float[] targetsX = new float[64];
    private float[] targetsY = new float[64];
    private int targetCount;

    public Tracers() {
        super("Tracers", Category.RENDER);
        settings.add(colorSetting);
        settings.add(thicknessSetting);
        settings.add(rangeSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        targetCount = 0;
        if (!captureMatrices()) return;

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;
        double range = rangeSetting.getValue();
        double rangeSq = range * range;

        for (EntityPlayer entity : mc.theWorld.playerEntities) {
            if (entity == mc.thePlayer) continue;
            if (AntiBot.isBot(entity)) continue;

            double mx = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * event.partialTicks;
            double my = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * event.partialTicks
                    + entity.height * 0.9D;
            double mz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * event.partialTicks;

            double dx = mx - camX;
            double dy = my - camY;
            double dz = mz - camZ;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;
            if (!project(dx, dy, dz, point)) continue;

            store((float) (point[0] / scale), (float) ((vh - point[1]) / scale));
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!isEnabled()) {
            targetCount = 0;
            return;
        }
        drawTargets();
    }

    private void store(float x, float y) {
        if (targetCount == MAX_TRACERS) return;
        if (targetCount == targetsX.length) {
            targetsX = Arrays.copyOf(targetsX, targetCount * 2);
            targetsY = Arrays.copyOf(targetsY, targetCount * 2);
        }
        targetsX[targetCount] = x;
        targetsY[targetCount] = y;
        targetCount++;
    }

    /** One pass for every line: the per-line draw call was the expensive part. */
    private void drawTargets() {
        if (targetCount == 0) return;
        ScaledResolution sr = new ScaledResolution(mc);
        float originX = sr.getScaledWidth() / 2f;
        float originY = sr.getScaledHeight();

        int color = colorSetting.getValue();
        float a = (color >>> 24) / 255.0F;
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth((float) thicknessSetting.getValue());

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < targetCount; i++) {
            wr.pos(originX, originY, 0.0D).color(r, g, b, a).endVertex();
            wr.pos(targetsX[i], targetsY[i], 0.0D).color(r, g, b, a).endVertex();
        }
        tessellator.draw();

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        targetCount = 0;
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

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        targetCount = 0;
    }
}
