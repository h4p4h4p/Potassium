package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.Friends;
import com.potassium.util.MovementInputStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Orbits the current KillAura target while a movement key is held.
 *
 * <p>The orbit is written into the movement input rather than into motion or the camera.
 * Vanilla therefore resolves collisions and speed exactly as it does for a player holding
 * the keys, and a silent KillAura aim can stay off the camera. The wrapper sits above the
 * movement-fix layer in {@link MovementInputStack}, so a silent W/A/S/D correction cannot
 * fight the orbit.</p>
 *
 * <p>Without a KillAura target the module can fall back to the nearest player, but only when
 * Only With KillAura is off. Toggling the direction key or colliding with a wall reverses the
 * orbit so the player does not keep pushing into the same block.</p>
 */
public class TargetStrafe extends Module {

    public static final String DIRECTION_LEFT = "Left";
    public static final String DIRECTION_RIGHT = "Right";

    private static final String INPUT_SOURCE = "TargetStrafe";
    /** Above SilentLook's movement fix, below Freecam's frozen input. */
    private static final int INPUT_PRIORITY = MovementInputStack.PRIORITY_NO_SLOW + 50;

    private final SliderSetting radiusSetting = new SliderSetting("Radius", 1.0, 0.5, 4.0, 0.1);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final ModeSetting directionSetting = new ModeSetting("Direction",
            new String[]{DIRECTION_LEFT, DIRECTION_RIGHT}, 0);
    private final BooleanSetting holdJumpSetting = new BooleanSetting("Hold Jump", true);
    private final BooleanSetting movingSetting = new BooleanSetting("Only While Moving", true);
    private final BooleanSetting switchSetting = new BooleanSetting("Switch on Block", true);
    private final BooleanSetting killAuraSetting = new BooleanSetting("Only With KillAura", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    private boolean active;
    private int directionSign = 1;
    private int switchCooldown;
    private String lastDirection;

    public TargetStrafe() {
        super("TargetStrafe", Category.COMBAT);
        settings.add(radiusSetting);
        settings.add(rangeSetting);
        settings.add(directionSetting);
        settings.add(holdJumpSetting);
        settings.add(movingSetting);
        settings.add(switchSetting);
        settings.add(killAuraSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player != mc.thePlayer) return;
        if (event.phase == TickEvent.Phase.START) {
            syncDirection();
            active = isEnabled() && shouldStrafe();
            if (active) {
                MovementInputStack.install(INPUT_SOURCE, INPUT_PRIORITY,
                        inner -> new StrafeInput(inner, this));
            } else {
                MovementInputStack.uninstall(INPUT_SOURCE);
            }
            return;
        }
        MovementInputStack.uninstall(INPUT_SOURCE);
    }

    private boolean shouldStrafe() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || mc.currentScreen != null) return false;
        if (player.isDead || player.isRiding() || player.capabilities.isFlying) return false;
        if (player.isInWater() || player.isInLava()) return false;
        if (holdJumpSetting.getValue() && !mc.gameSettings.keyBindJump.isKeyDown()) return false;
        if (movingSetting.getValue() && !hasMovementKey()) return false;

        EntityLivingBase target = findTarget();
        if (target == null || !target.isEntityAlive()) return false;
        return player.getDistanceToEntity(target) <= rangeSetting.getValue();
    }

    private boolean hasMovementKey() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown();
    }

    private EntityLivingBase findTarget() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return null;

        KillAura aura = KillAura.instance();
        if (aura != null && aura.isEnabled() && aura.getTarget() != null
                && aura.getTarget().isEntityAlive()) {
            return aura.getTarget();
        }
        if (killAuraSetting.getValue()) return null;

        EntityLivingBase nearest = null;
        double best = rangeSetting.getValue();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityPlayer) || entity == player) continue;
            EntityPlayer other = (EntityPlayer) entity;
            if (other.isDead || !other.isEntityAlive() || other.isSpectator()) continue;
            if (!player.canEntityBeSeen(other) || Friends.isFriend(other)) continue;
            double distance = player.getDistanceToEntity(other);
            if (distance > best) continue;
            best = distance;
            nearest = other;
        }
        return nearest;
    }

    /** Called from the movement wrapper, after vanilla has sampled the keys. */
    private void applyOrbit(MovementInput input) {
        EntityPlayerSP player = mc.thePlayer;
        EntityLivingBase target = findTarget();
        if (!isEnabled() || !active || player == null || target == null) return;

        if (switchCooldown > 0) switchCooldown--;
        if (player.isCollidedHorizontally && switchSetting.getValue() && switchCooldown <= 0) {
            directionSign = -directionSign;
            switchCooldown = 10;
        }

        double dx = target.posX - player.posX;
        double dz = target.posZ - player.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-4D) return;

        double nx = dx / distance;
        double nz = dz / distance;
        // Unit tangent around the target, plus a radial push back onto the requested radius.
        double tangentX = -nz * directionSign;
        double tangentZ = nx * directionSign;
        double radial = (distance - radiusSetting.getValue()) * 1.5D;
        double desiredX = tangentX + nx * radial;
        double desiredZ = tangentZ + nz * radial;

        double length = Math.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        if (length < 1.0E-4D) return;
        desiredX /= length;
        desiredZ /= length;

        float yaw = player.rotationYaw * 0.017453292F;
        float forward = (float) (-desiredX * MathHelper.sin(yaw)
                + desiredZ * MathHelper.cos(yaw));
        float strafe = (float) (desiredX * MathHelper.cos(yaw)
                + desiredZ * MathHelper.sin(yaw));

        input.moveForward = Math.max(-1.0F, Math.min(1.0F, forward));
        input.moveStrafe = Math.max(-1.0F, Math.min(1.0F, strafe));
    }

    private boolean leftDirection() {
        return DIRECTION_LEFT.equals(directionSetting.getValue());
    }

    /** A direction picked in the menu takes effect immediately, not on the next enable. */
    private void syncDirection() {
        String mode = directionSetting.getValue();
        if (mode == null || mode.equals(lastDirection)) return;
        lastDirection = mode;
        directionSign = leftDirection() ? 1 : -1;
    }

    @Override
    public void onEnable() {
        lastDirection = directionSetting.getValue();
        directionSign = leftDirection() ? 1 : -1;
        switchCooldown = 0;
        active = false;
    }

    @Override
    public void onDisable() {
        active = false;
        MovementInputStack.uninstall(INPUT_SOURCE);
    }

    private static final class StrafeInput extends MovementInput {

        private final MovementInput delegate;
        private final TargetStrafe module;

        private StrafeInput(MovementInput delegate, TargetStrafe module) {
            this.delegate = delegate;
            this.module = module;
        }

        @Override
        public void updatePlayerMoveState() {
            delegate.updatePlayerMoveState();
            moveForward = delegate.moveForward;
            moveStrafe = delegate.moveStrafe;
            jump = delegate.jump;
            sneak = delegate.sneak;
            module.applyOrbit(this);
        }
    }
}
