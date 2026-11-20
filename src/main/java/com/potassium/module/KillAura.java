package com.potassium.module;

import com.potassium.command.CommandManager;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.ClickState;
import com.potassium.util.MovementFix;
import com.potassium.util.RotationUtil;
import com.potassium.util.SilentLook;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selects a visible target in range, turns toward it and attacks it with LeftClicker-style
 * CPS pacing.
 *
 * Single keeps attacking the best target. Switch moves to the next valid target after each
 * successful attack, so multiple targets in range are cycled at the configured CPS.
 */
public class KillAura extends Module {

    public static final String MODE_SINGLE = "Single";
    public static final String MODE_SWITCH = "Switch";
    public static final String MODE_ALERT_DISTANCE = "Alert Distance";

    public static final String ROTATION_OFF = "Off";
    public static final String ROTATION_NORMAL = "Normal";
    public static final String ROTATION_SILENT = "Silent";

    private static final float AIM_TOLERANCE = 10.0F;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_SINGLE, MODE_SWITCH, MODE_ALERT_DISTANCE}, 0);
    private final ModeSetting rotationSetting = new ModeSetting("Rotations",
            new String[]{ROTATION_OFF, ROTATION_NORMAL, ROTATION_SILENT}, 1);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final SliderSetting fovSetting = new SliderSetting("FOV", 180, 1, 360, 1);
    private final SliderSetting cpsSetting = new SliderSetting("CPS", 12, 1, 20, 1);
    private final SliderSetting jitterSetting = new SliderSetting("Jitter", 0, 0, 100, 5);
    private final SliderSetting yawSpeedSetting = new SliderSetting("Yaw Speed", 30, 0, 30, 1);
    private final SliderSetting pitchSpeedSetting = new SliderSetting("Pitch Speed", 30, 0, 30, 1);
    private final SliderSetting interpolationSetting = new SliderSetting("Interpolation", 50, 0, 95, 1);
    private final BooleanSetting playersOnlySetting = new BooleanSetting("Players Only", true);
    private final BooleanSetting wallCheckSetting = new BooleanSetting("Wall Check", true);
    private final BooleanSetting weaponOnlySetting = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting silentSwapSetting = new BooleanSetting("Silent Swap", false);
    private final BooleanSetting movementFixSetting = new BooleanSetting("Movement Fix", true);

    private final Minecraft mc = Minecraft.getMinecraft();
    private EntityLivingBase target;
    private EntityLivingBase lastSwitchTarget;
    private double clickBudget = 1.0D;
    private long lastAimNanos;
    private int silentSwapOriginalSlot = -1;
    private float aimYaw;
    private float aimPitch;
    private boolean aimInitialised;

    public KillAura() {
        super("KillAura", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(rotationSetting);
        settings.add(rangeSetting);
        settings.add(fovSetting);
        settings.add(cpsSetting);
        settings.add(jitterSetting);
        settings.add(yawSpeedSetting);
        settings.add(pitchSpeedSetting);
        settings.add(interpolationSetting);
        settings.add(playersOnlySetting);
        settings.add(wallCheckSetting);
        settings.add(weaponOnlySetting);
        settings.add(silentSwapSetting);
        settings.add(movementFixSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null
                || mc.thePlayer.isDead) {
            reset();
            return;
        }

        if (weaponOnlySetting.getValue() && !canUseWeapon()) {
            reset();
            return;
        }

        List<TargetCandidate> candidates = findTargets();
        if (candidates.isEmpty()) {
            target = null;
            lastAimNanos = 0L;
            // The next aim starts from the camera, the way AimAssist does, rather than
            // sweeping in from wherever the last target left it.
            aimInitialised = false;
            return;
        }

        target = selectTarget(candidates);

        // Rotation is updated on the render thread so it runs at frame rate, not 20 TPS.
        // The aim is renewed here so this tick's own movement packet carries it.
        if (isSilentRotations() && aimInitialised) SilentLook.request(aimYaw, aimPitch);
    }

    /**
     * Attacking only after the tick's movement packet has been queued is what keeps a silent
     * rotation from reading as a fight direction violation. The server validates a hit against
     * the last rotation it was sent, so an attack queued before the aim is checked against the
     * old direction, and the instant flip back afterwards made it look like the hit came from
     * behind the player.
     */
    @SubscribeEvent
    public void onTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null
                || mc.thePlayer.isDead) {
            return;
        }

        EntityLivingBase current = target;
        if (current == null || current.isDead || !current.isEntityAlive()
                || current.worldObj != mc.theWorld) {
            return;
        }

        if (!isAligned(current)) return;
        if (!ClickState.canAttack(mc.thePlayer.ticksExisted)) return;
        if (!clickDue()) return;

        if (attack(current) && isSwitchMode()) {
            lastSwitchTarget = current;
            target = null;
        }
    }

    private boolean isSwitchMode() {
        return MODE_SWITCH.equals(modeSetting.getValue());
    }

    private boolean isAlertDistanceMode() {
        return MODE_ALERT_DISTANCE.equals(modeSetting.getValue());
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;

        EntityLivingBase current = target;
        if (current == null || current.isDead || !current.isEntityAlive()
                || current.worldObj != mc.theWorld) {
            return;
        }

        long now = System.nanoTime();
        double deltaSeconds = lastAimNanos == 0L ? 1.0D / 60.0D
                : (now - lastAimNanos) / 1.0e9D;
        lastAimNanos = now;
        updateAim(current, deltaSeconds);
    }

    private List<TargetCandidate> findTargets() {
        EntityPlayer player = mc.thePlayer;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;

        float currentYaw = player.rotationYaw;
        float currentPitch = player.rotationPitch;
        float fov = (float) fovSetting.getValue();

        double maxDistance = rangeSetting.getValue();
        double maxDistanceSq = maxDistance * maxDistance;
        List<TargetCandidate> candidates = new ArrayList<>();

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity == player || entity.isDead || !(entity instanceof EntityLivingBase)) continue;
            if (!entity.isEntityAlive()) continue;
            if (playersOnlySetting.getValue() && !(entity instanceof EntityPlayer)) continue;
            if (entity instanceof EntityPlayer && ((EntityPlayer) entity).isSpectator()) continue;
            if (AntiBot.isBot(entity)) continue;

            EntityLivingBase living = (EntityLivingBase) entity;
            double targetX = living.posX;
            double targetY = living.posY + living.getEyeHeight();
            double targetZ = living.posZ;

            double dx = targetX - eyeX;
            double dy = targetY - eyeY;
            double dz = targetZ - eyeZ;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double distanceSq = horizontal * horizontal + dy * dy;
            if (distanceSq > maxDistanceSq) continue;

            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
            float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(yaw - currentYaw));
            float pitchDifference = Math.abs(pitch - currentPitch);
            if (yawDifference > fov || pitchDifference > fov) continue;
            if (wallCheckSetting.getValue() && !hasLineOfSight(living, targetY)) continue;

            double score = yawDifference + pitchDifference + Math.sqrt(distanceSq) * 0.05D;
            candidates.add(new TargetCandidate(living, score));
        }

        candidates.sort((a, b) -> Double.compare(a.score, b.score));
        return candidates;
    }

    /**
     * Single always uses the best candidate. Switch keeps the current target until it is
     * hit or leaves the valid set, then advances to the next one in score order.
     */
    private EntityLivingBase selectTarget(List<TargetCandidate> candidates) {
        if (!isSwitchMode()) return candidates.get(0).entity;

        for (TargetCandidate candidate : candidates) {
            if (candidate.entity == target) return target;
        }

        int index = 0;
        if (lastSwitchTarget != null) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).entity == lastSwitchTarget) {
                    index = (i + 1) % candidates.size();
                    break;
                }
            }
        }
        return candidates.get(index).entity;
    }

    /**
     * Moves the aim toward the target by at most the interpolated step for this frame. Normal
     * writes the player's view; Silent keeps a separate aim that is only ever sent in look
     * packets, so the view itself never moves. Off does not turn at all.
     */
    private void updateAim(EntityLivingBase target, double deltaSeconds) {
        String mode = rotationMode();
        if (ROTATION_OFF.equals(mode)) return;

        EntityPlayer player = mc.thePlayer;
        boolean silent = ROTATION_SILENT.equals(mode);
        if (!silent || !aimInitialised) {
            aimYaw = player.rotationYaw;
            aimPitch = player.rotationPitch;
            aimInitialised = true;
        }

        float baseYaw = silent ? aimYaw : player.rotationYaw;
        float basePitch = silent ? aimPitch : player.rotationPitch;
        float[] rotations = rotationsTo(target);

        // Shared with AimAssist so the same sliders produce the same motion: the speed
        // settings scale the remaining angle, then Interpolation caps the step per frame.
        float[] interpolated = RotationUtil.step(baseYaw, basePitch, rotations[0], rotations[1],
                yawSpeedSetting.getValue(), pitchSpeedSetting.getValue(),
                interpolationSetting.getValue(), deltaSeconds);

        if (silent) {
            aimYaw = interpolated[0];
            aimPitch = interpolated[1];
        } else {
            player.rotationYaw = interpolated[0];
            player.rotationPitch = interpolated[1];
            if (movementFixSetting.getValue()) MovementFix.apply(baseYaw);
        }
    }

    /** Silent checks the aim it sends, the other modes check the real view. */
    private boolean isAligned(EntityLivingBase target) {
        float[] rotations = rotationsTo(target);
        boolean silent = ROTATION_SILENT.equals(rotationMode());
        float yaw = silent ? aimYaw : mc.thePlayer.rotationYaw;
        float pitch = silent ? aimPitch : mc.thePlayer.rotationPitch;
        return Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - yaw)) <= AIM_TOLERANCE
                && Math.abs(rotations[1] - pitch) <= AIM_TOLERANCE;
    }

    private String rotationMode() {
        String mode = rotationSetting.getValue();
        return mode == null ? ROTATION_NORMAL : mode;
    }

    private boolean isSilentRotations() {
        return ROTATION_SILENT.equals(rotationMode());
    }

    private float[] rotationsTo(EntityLivingBase target) {
        EntityPlayer player = mc.thePlayer;
        double dx = target.posX - player.posX;
        double dy = target.posY + target.getEyeHeight()
                - (player.posY + player.getEyeHeight());
        double dz = target.posZ - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    private boolean hasLineOfSight(Entity target, double targetY) {
        Vec3 eyes = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);
        Vec3 targetEyes = new Vec3(target.posX, targetY, target.posZ);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes, targetEyes, false, true, false);
        return hit == null;
    }

    private boolean clickDue() {
        double cps = cpsSetting.getValue();
        if (cps <= 0.0D) return false;

        clickBudget += (cps / 20.0D) * jitterFactor();
        if (clickBudget < 1.0D) return false;

        clickBudget = Math.min(1.0D, clickBudget - 1.0D);
        return true;
    }

    private double jitterFactor() {
        return 1.0D + (Math.random() * 2.0D - 1.0D) * (jitterSetting.getValue() / 100.0D);
    }

    private boolean attack(EntityLivingBase target) {
        if (mc.playerController == null || !target.isEntityAlive()) return false;

        double distance = isAlertDistanceMode() ? hitDistance(target) : 0.0D;
        swapToWeapon();
        try {
            // Silent aim reached the server with this tick's movement packet, before this
            // attack is queued, so no look packet is needed here.
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, target);
            ClickState.markAttack(mc.thePlayer.ticksExisted);
            if (isAlertDistanceMode()) {
                CommandManager.sendMessage("\u00a77KillAura \u00a7fAlert Distance: \u00a7a"
                        + String.format(Locale.ROOT, "%.2f", distance) + " \u00a77blocks");
            }
            return true;
        } finally {
            restoreSilentSwap();
        }
    }

    /** Distance from the player's eyes to the closest point on the target's hitbox. */
    private double hitDistance(EntityLivingBase target) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = target.getEntityBoundingBox();
        double x = MathHelper.clamp_double(eyes.xCoord, box.minX, box.maxX);
        double y = MathHelper.clamp_double(eyes.yCoord, box.minY, box.maxY);
        double z = MathHelper.clamp_double(eyes.zCoord, box.minZ, box.maxZ);
        double dx = eyes.xCoord - x;
        double dy = eyes.yCoord - y;
        double dz = eyes.zCoord - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean canUseWeapon() {
        return holdingWeapon()
                || (silentSwapSetting.getValue() && findWeaponSlot(mc.thePlayer) >= 0);
    }

    private boolean holdingWeapon() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return false;
        Item item = held.getItem();
        return item instanceof ItemSword || item instanceof ItemAxe;
    }

    private void swapToWeapon() {
        if (!silentSwapSetting.getValue() || holdingWeapon()) return;

        int slot = findWeaponSlot(mc.thePlayer);
        if (slot < 0) return;

        silentSwapOriginalSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = slot;
        sendSlot(slot);
    }

    private void restoreSilentSwap() {
        if (silentSwapOriginalSlot < 0) return;
        int originalSlot = silentSwapOriginalSlot;
        silentSwapOriginalSlot = -1;
        mc.thePlayer.inventory.currentItem = originalSlot;
        sendSlot(originalSlot);
    }

    /** First hotbar sword, otherwise the first axe. */
    private int findWeaponSlot(EntityPlayer player) {
        int axeSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack == null) continue;

            Item item = stack.getItem();
            if (item instanceof ItemSword) return i;
            if (item instanceof ItemAxe && axeSlot < 0) axeSlot = i;
        }
        return axeSlot;
    }

    private void sendSlot(int slot) {
        if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(slot));
        }
    }

    private void reset() {
        target = null;
        lastSwitchTarget = null;
        clickBudget = 1.0D;
        lastAimNanos = 0L;
        aimInitialised = false;
    }

    private static final class TargetCandidate {

        private final EntityLivingBase entity;
        private final double score;

        private TargetCandidate(EntityLivingBase entity, double score) {
            this.entity = entity;
            this.score = score;
        }
    }

    @Override
    public String getTag() {
        return Math.round(cpsSetting.getValue()) + " CPS";
    }

    @Override
    public void onEnable() {
        silentSwapOriginalSlot = -1;
        reset();
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null) restoreSilentSwap();
        silentSwapOriginalSlot = -1;
        reset();
    }
}
