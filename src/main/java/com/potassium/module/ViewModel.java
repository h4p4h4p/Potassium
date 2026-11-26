package com.potassium.module;

import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

/**
 * Changes the first-person hand without touching the rest of the camera.
 *
 * <p>Forge fires RenderHandEvent before vanilla sets up the hand projection. The event is
 * cancelled only when a setting is actually changed, and the hand is then drawn with the same
 * projection, hurt shake and view bobbing, plus this module's offsets and rotations. When every
 * setting is at its default, the event is left alone and vanilla renders the hand.</p>
 */
public class ViewModel extends Module {

    private static final double DEFAULT_ANIMATION_SPEED = 1.0D;
    /** Vanilla's first-person hand pivot, used so rotation turns the viewmodel in place. */
    private static final float HAND_PIVOT_X = 0.56F;
    private static final float HAND_PIVOT_Y = -0.52F;
    private static final float HAND_PIVOT_Z = -0.72F;

    private final SliderSetting animationSpeedSetting =
            new SliderSetting("Animation Speed", DEFAULT_ANIMATION_SPEED, 0.1, 3.0, 0.05);
    private final SliderSetting xSetting = new SliderSetting("X Offset", 0.0, -1.0, 1.0, 0.01);
    private final SliderSetting ySetting = new SliderSetting("Y Offset", 0.0, -1.0, 1.0, 0.01);
    private final SliderSetting zSetting = new SliderSetting("Z Offset", 0.0, -1.0, 1.0, 0.01);
    private final SliderSetting rotationXSetting =
            new SliderSetting("Rotation X", 0.0, -180.0, 180.0, 1.0);
    private final SliderSetting rotationYSetting =
            new SliderSetting("Rotation Y", 0.0, -180.0, 180.0, 1.0);
    private final SliderSetting rotationZSetting =
            new SliderSetting("Rotation Z", 0.0, -180.0, 180.0, 1.0);

    private final Minecraft mc = Minecraft.getMinecraft();

    /** When the current swing began, so animation speed can drive the hand from real time. */
    private long swingStartNanos;
    private boolean swinging;
    private float swingPhase;

    public ViewModel() {
        super("ViewModel", Category.RENDER);
        settings.add(animationSpeedSetting);
        settings.add(xSetting);
        settings.add(ySetting);
        settings.add(zSetting);
        settings.add(rotationXSetting);
        settings.add(rotationYSetting);
        settings.add(rotationZSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (!isEnabled() || !hasCustomization() || !canRenderHand()) return;
        event.setCanceled(true);
        renderHand(event.partialTicks, event.renderPass);
    }

    private boolean hasCustomization() {
        return Math.abs(animationSpeedSetting.getValue() - DEFAULT_ANIMATION_SPEED) > 1.0E-4D
                || Math.abs(xSetting.getValue()) > 1.0E-4D
                || Math.abs(ySetting.getValue()) > 1.0E-4D
                || Math.abs(zSetting.getValue()) > 1.0E-4D
                || Math.abs(rotationXSetting.getValue()) > 1.0E-4D
                || Math.abs(rotationYSetting.getValue()) > 1.0E-4D
                || Math.abs(rotationZSetting.getValue()) > 1.0E-4D;
    }

    /**
     * Mirrors the cases where vanilla skips the hand. The render view entity check keeps the
     * hand out of a Freecam view, and shader packs keep the vanilla path rather than a hand
     * rendered outside their pipeline.
     */
    private boolean canRenderHand() {
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) return false;
        if (mc.getRenderViewEntity() != mc.thePlayer) return false;
        if (mc.gameSettings.thirdPersonView != 0) return false;
        if (mc.gameSettings.hideGUI) return false;
        if (mc.playerController.isSpectator()) return false;
        if (mc.thePlayer.isPlayerSleeping()) return false;
        return !mc.entityRenderer.isShaderActive();
    }

    private void renderHand(float partialTicks, int renderPass) {
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        if (mc.gameSettings.anaglyph) {
            GlStateManager.translate(-(renderPass * 2 - 1) * 0.07F, 0.0F, 0.0F);
        }
        Project.gluPerspective(handFov(), (float) mc.displayWidth / (float) mc.displayHeight,
                0.05F, mc.gameSettings.renderDistanceChunks * 32.0F);

        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        if (mc.gameSettings.anaglyph) {
            GlStateManager.translate((renderPass * 2 - 1) * 0.1F, 0.0F, 0.0F);
        }

        GlStateManager.pushMatrix();
        try {
            hurtCamera(partialTicks);
            if (mc.gameSettings.viewBobbing) viewBobbing(partialTicks);

            // Offset first, then rotate around the hand's pivot. In OpenGL's reversed order
            // this keeps the three rotations local to the viewmodel instead of orbiting it
            // around the camera.
            GlStateManager.translate(xSetting.getValue(), ySetting.getValue(), zSetting.getValue());
            GlStateManager.translate(HAND_PIVOT_X, HAND_PIVOT_Y, HAND_PIVOT_Z);
            GlStateManager.rotate((float) rotationYSetting.getValue(), 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate((float) rotationXSetting.getValue(), 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate((float) rotationZSetting.getValue(), 0.0F, 0.0F, 1.0F);
            GlStateManager.translate(-HAND_PIVOT_X, -HAND_PIVOT_Y, -HAND_PIVOT_Z);

            EntityPlayerSP player = mc.thePlayer;
            float oldSwing = player.swingProgress;
            float oldPreviousSwing = player.prevSwingProgress;
            try {
                if (usesAnimationSpeed()) {
                    float phase = swingPhase(player);
                    // ItemRenderer reads getSwingProgress from these fields, so the hand is
                    // rendered at our phase without changing the player update for other views.
                    player.swingProgress = phase;
                    player.prevSwingProgress = phase;
                }

                mc.entityRenderer.enableLightmap();
                try {
                    mc.getItemRenderer().renderItemInFirstPerson(partialTicks);
                } finally {
                    mc.entityRenderer.disableLightmap();
                }
            } finally {
                player.swingProgress = oldSwing;
                player.prevSwingProgress = oldPreviousSwing;
            }
        } finally {
            GlStateManager.popMatrix();
        }

        if (!mc.gameSettings.showDebugInfo) {
            mc.getItemRenderer().renderOverlays(partialTicks);
            hurtCamera(partialTicks);
        }
        if (mc.gameSettings.viewBobbing) viewBobbing(partialTicks);
    }

    private float handFov() {
        float fov = 70.0F;
        Entity view = mc.getRenderViewEntity();
        if (view instanceof AbstractClientPlayer) {
            fov *= ((AbstractClientPlayer) view).getFovModifier();
        }
        return fov;
    }

    private boolean usesAnimationSpeed() {
        return Math.abs(animationSpeedSetting.getValue() - DEFAULT_ANIMATION_SPEED) > 1.0E-4D;
    }

    /**
     * A swing phase driven by wall time rather than the 20 Hz update, so speeds above and
     * below vanilla stay smooth. A slow swing keeps drawing after vanilla's own swing state
     * ends; the next swing simply restarts the timer.
     */
    private float swingPhase(EntityPlayerSP player) {
        long now = System.nanoTime();
        boolean inSwing = player.isSwingInProgress;

        if (inSwing && !swinging) {
            swinging = true;
            swingStartNanos = now;
            swingPhase = 0.0F;
        } else if (!inSwing) {
            swinging = false;
        }

        if (swingStartNanos == 0L) return 0.0F;

        double speed = Math.max(0.05D, animationSpeedSetting.getValue());
        double duration = 6.0D / 20.0D / speed;
        double elapsed = (now - swingStartNanos) / 1.0E9D;
        swingPhase = (float) Math.min(1.0D, elapsed / duration);

        if (swingPhase >= 1.0F && !inSwing) {
            swingStartNanos = 0L;
            swingPhase = 0.0F;
        }
        return swingPhase;
    }

    private void hurtCamera(float partialTicks) {
        Entity view = mc.getRenderViewEntity();
        if (!(view instanceof EntityLivingBase)) return;

        EntityLivingBase living = (EntityLivingBase) view;
        if (living.hurtTime <= 0 || living.maxHurtTime <= 0) return;

        float hurt = (float) living.hurtTime - partialTicks;
        hurt /= (float) living.maxHurtTime;
        hurt = MathHelper.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
        float yaw = living.attackedAtYaw;
        GlStateManager.rotate(-yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-hurt * 14.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
    }

    private void viewBobbing(float partialTicks) {
        if (!(mc.getRenderViewEntity() instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) mc.getRenderViewEntity();
        float walked = player.distanceWalkedModified - player.prevDistanceWalkedModified;
        float phase = -(player.distanceWalkedModified + walked * partialTicks);
        float sway = player.prevCameraYaw + (player.cameraYaw - player.prevCameraYaw) * partialTicks;
        float pitch = player.prevCameraPitch
                + (player.cameraPitch - player.prevCameraPitch) * partialTicks;

        GlStateManager.translate(MathHelper.sin(phase * (float) Math.PI) * sway * 0.5F,
                -Math.abs(MathHelper.cos(phase * (float) Math.PI) * sway), 0.0F);
        GlStateManager.rotate(MathHelper.sin(phase * (float) Math.PI) * sway * 3.0F,
                0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(Math.abs(MathHelper.cos(phase * (float) Math.PI - 0.2F) * sway)
                * 5.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(pitch, 1.0F, 0.0F, 0.0F);
    }

    @Override
    public void onEnable() {
        swingStartNanos = 0L;
        swinging = false;
        swingPhase = 0.0F;
    }

    @Override
    public void onDisable() {
        swingStartNanos = 0L;
        swinging = false;
        swingPhase = 0.0F;
    }
}
