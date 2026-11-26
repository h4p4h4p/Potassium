package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.Friends;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Nudges the camera toward the living entity nearest the crosshair.
 *
 * <p>The turn runs at RenderWorldLastEvent, after the frame's mouse delta has been applied
 * and before the next outgoing look packet is built. The mouse therefore still contributes
 * each frame, and the eased angle is what the server receives. AimAssist only turns the
 * camera: it never clicks and never uses a silent rotation, so it can be combined with the
 * clickers and with HitSelect.</p>
 *
 * <p>KillAura owns the aim while it is on, so AimAssist stands down rather than fighting the
 * aura for the camera. Freecam likewise has its own view and is left alone.</p>
 */
public class AimAssist extends Module {

    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final SliderSetting fovSetting = new SliderSetting("FOV", 90, 1, 360, 1);
    private final SliderSetting xSmoothSetting = new SliderSetting("X Smooth", 30, 0, 100, 1);
    private final SliderSetting ySmoothSetting = new SliderSetting("Y Smooth", 30, 0, 100, 1);
    private final SliderSetting sensitivitySetting =
            new SliderSetting("Sensitivity", 1.0, 0.1, 3.0, 0.05);
    private final BooleanSetting onClickSetting = new BooleanSetting("Only on Click", false);
    private final BooleanSetting playersSetting = new BooleanSetting("Players", true);
    private final BooleanSetting passiveSetting = new BooleanSetting("Passive", false);
    private final BooleanSetting hostileSetting = new BooleanSetting("Hostile", false);
    private final BooleanSetting invisiblesSetting = new BooleanSetting("Invisibles", true);
    private final BooleanSetting wallCheckSetting = new BooleanSetting("Wall Check", true);

    private final Minecraft mc = Minecraft.getMinecraft();
    private Module killAura;

    public AimAssist() {
        super("AimAssist", Category.COMBAT);
        settings.add(rangeSetting);
        settings.add(fovSetting);
        settings.add(xSmoothSetting);
        settings.add(ySmoothSetting);
        settings.add(sensitivitySetting);
        settings.add(onClickSetting);
        settings.add(playersSetting);
        settings.add(passiveSetting);
        settings.add(hostileSetting);
        settings.add(invisiblesSetting);
        settings.add(wallCheckSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (mc.thePlayer.isDead || mc.getRenderViewEntity() != mc.thePlayer) return;
        if (killAuraEnabled()) return;
        if (onClickSetting.getValue() && !mc.gameSettings.keyBindAttack.isKeyDown()) return;

        float curYaw = mc.thePlayer.rotationYaw;
        float curPitch = mc.thePlayer.rotationPitch;
        float fov = (float) fovSetting.getValue();
        double range = rangeSetting.getValue();

        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double pz = mc.thePlayer.posZ;

        float targetYaw = 0.0F;
        float targetPitch = 0.0F;
        double bestScore = Double.MAX_VALUE;
        boolean found = false;

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) entity;
            if (!canTarget(living)) continue;

            double dx = living.posX - px;
            double dy = (living.posY + living.getEyeHeight()) - py;
            double dz = living.posZ - pz;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < 0.01D && Math.abs(dy) < 0.01D) continue;
            if (Math.sqrt(horizontal * horizontal + dy * dy) > range) continue;

            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
            float dYaw = Math.abs(wrapDegrees(yaw - curYaw));
            float dPitch = Math.abs(pitch - curPitch);
            if (dYaw > fov || dPitch > fov) continue;

            if (wallCheckSetting.getValue()) {
                Vec3 eyes = new Vec3(px, py, pz);
                Vec3 aim = new Vec3(living.posX,
                        living.posY + living.getEyeHeight() * 0.9D, living.posZ);
                if (mc.theWorld.rayTraceBlocks(eyes, aim, false, true, false) != null) continue;
            }

            double score = dYaw + dPitch + horizontal * 0.01D;
            if (score < bestScore) {
                bestScore = score;
                targetYaw = yaw;
                targetPitch = pitch;
                found = true;
            }
        }

        if (!found) return;

        double sensitivity = sensitivitySetting.getValue();
        float yawFactor = (float) (xSmoothSetting.getValue() / 100.0D * sensitivity);
        float pitchFactor = (float) (ySmoothSetting.getValue() / 100.0D * sensitivity);

        mc.thePlayer.rotationYaw = curYaw + wrapDegrees(targetYaw - curYaw) * yawFactor;
        mc.thePlayer.rotationPitch = MathHelper.clamp_float(
                curPitch + (targetPitch - curPitch) * pitchFactor, -90.0F, 90.0F);
    }

    private boolean canTarget(EntityLivingBase living) {
        if (living == null || living == mc.thePlayer || living.isDead || !living.isEntityAlive()) {
            return false;
        }
        if (living.isInvisible() && !invisiblesSetting.getValue()) return false;

        if (living instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) living;
            if (player.isSpectator() || !playersSetting.getValue()) return false;
            if (Friends.isFriend(player)) return false;
            if (AntiBot.isBot(living)) return false;
        } else {
            boolean hostile = living instanceof IMob || living instanceof EntityIronGolem;
            if (hostile ? !hostileSetting.getValue() : !passiveSetting.getValue()) return false;
        }
        return true;
    }

    private boolean killAuraEnabled() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return false;
        if (killAura == null) killAura = Potassium.INSTANCE.moduleManager.getModule("KillAura");
        return killAura != null && killAura.isEnabled();
    }

    /** Normalises an angle to [-180, 180) so the aim takes the short way around. */
    private float wrapDegrees(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}
