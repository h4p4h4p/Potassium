package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
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
 * cancelled only when a visual setting differs from vanilla or a blockhit is being drawn, and
 * the hand is then rendered with the same projection, hurt shake and view bobbing, plus this
 * module's size, offsets and rotations. When nothing needs changing, the event is left alone
 * and vanilla renders the hand.</p>
 */
public class ViewModel extends Module {

    /** Vanilla's first-person hand pivot, used so rotation turns the viewmodel in place. */
    private static final float HAND_PIVOT_X = 0.56F;
    private static final float HAND_PIVOT_Y = -0.52F;
    private static final float HAND_PIVOT_Z = -0.72F;

    private final SliderSetting sizeSetting =
            new SliderSetting("Size", 1.0, 0.5, 2.0, 0.05);
    private final BooleanSetting blockhittingSetting = new BooleanSetting("Blockhitting", true);
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

    public ViewModel() {
        super("ViewModel", Category.RENDER);
        settings.add(sizeSetting);
        settings.add(blockhittingSetting);
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
        if (!isEnabled() || !canRenderHand()) return;
        boolean blockhit = blockhittingActive();
        if (!hasVisualCustomization() && !blockhit) return;
        event.setCanceled(true);
        renderHand(event.partialTicks, event.renderPass, blockhit);
    }

    private boolean hasVisualCustomization() {
        return Math.abs(sizeSetting.getValue() - 1.0D) > 1.0E-4D
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

    private void renderHand(float partialTicks, int renderPass, boolean blockhit) {
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
            float size = (float) sizeSetting.getValue();
            GlStateManager.scale(size, size, size);
            GlStateManager.translate(-HAND_PIVOT_X, -HAND_PIVOT_Y, -HAND_PIVOT_Z);

            EntityPlayerSP player = mc.thePlayer;
            mc.entityRenderer.enableLightmap();
            try {
                if (blockhit) renderBlockhit(player, partialTicks);
                else renderVanillaHand(partialTicks);
            } finally {
                mc.entityRenderer.disableLightmap();
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

    private void renderVanillaHand(float partialTicks) {
        mc.getItemRenderer().renderItemInFirstPerson(partialTicks);
    }

    /**
     * 1.7-style blockhit. Vanilla's BLOCK branch passes 0.0F into transformFirstPersonItem,
     * which freezes the sword in the block pose even while the arm is swinging. Passing the live
     * swing progress instead, with doBlockTransformations still after it, keeps the sword in the
     * block position but lets it swing through that pose.
     *
     * <p>The rest of this method mirrors the camera, light map and equipping work vanilla does
     * in renderItemInFirstPerson before it reaches the BLOCK branch.</p>
     */
    private void renderBlockhit(EntityPlayerSP player, float partialTicks) {
        ItemStack stack = player.getHeldItem();
        if (stack == null) return;

        float pitch = player.prevRotationPitch
                + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw
                + (player.rotationYaw - player.prevRotationYaw) * partialTicks;

        GlStateManager.pushMatrix();
        GlStateManager.rotate(pitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.popMatrix();

        int light = mc.theWorld.getCombinedLight(new BlockPos(player.posX,
                player.posY + (double) player.getEyeHeight(), player.posZ), 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                (float) (light & 65535), (float) (light >> 16));

        float armPitch = player.prevRenderArmPitch
                + (player.renderArmPitch - player.prevRenderArmPitch) * partialTicks;
        float armYaw = player.prevRenderArmYaw
                + (player.renderArmYaw - player.prevRenderArmYaw) * partialTicks;
        GlStateManager.rotate((player.rotationPitch - armPitch) * 0.1F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate((player.rotationYaw - armYaw) * 0.1F, 0.0F, 1.0F, 0.0F);

        GlStateManager.enableRescaleNormal();
        GlStateManager.pushMatrix();
        try {
            // The sword is already raised, so the equip offset is zero. The live swing is the
            // value vanilla drops to 0.0F in its BLOCK case.
            transformFirstPersonItem(0.0F, player.getSwingProgress(partialTicks));
            doBlockTransformations();
            mc.getItemRenderer().renderItem(player, stack,
                    ItemCameraTransforms.TransformType.FIRST_PERSON);
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.disableRescaleNormal();
            RenderHelper.disableStandardItemLighting();
        }
    }

    private boolean blockhittingActive() {
        if (!blockhittingSetting.getValue() || mc.thePlayer == null) return false;
        EntityPlayerSP player = mc.thePlayer;
        if (!player.isUsingItem()) return false;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItemUseAction() != EnumAction.BLOCK) return false;
        return player.isSwingInProgress;
    }

    /** The transforms vanilla ItemRenderer applies before the held item, copied here. */
    private void transformFirstPersonItem(float equipProgress, float swingProgress) {
        GlStateManager.translate(0.56F, -0.52F, -0.72F);
        GlStateManager.translate(0.0F, equipProgress * -0.6F, 0.0F);
        GlStateManager.rotate(45.0F, 0.0F, 1.0F, 0.0F);
        float swing = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        float swingRoot = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        GlStateManager.rotate(swing * -20.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(swingRoot * -20.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(swingRoot * -80.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(0.4F, 0.4F, 0.4F);
    }

    /** The block pose vanilla applies after transformFirstPersonItem. */
    private void doBlockTransformations() {
        GlStateManager.translate(-0.5F, 0.2F, 0.0F);
        GlStateManager.rotate(30.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-80.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(60.0F, 0.0F, 1.0F, 0.0F);
    }

    private float handFov() {
        float fov = 70.0F;
        Entity view = mc.getRenderViewEntity();
        if (view instanceof AbstractClientPlayer) {
            fov *= ((AbstractClientPlayer) view).getFovModifier();
        }
        return fov;
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

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
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

}
