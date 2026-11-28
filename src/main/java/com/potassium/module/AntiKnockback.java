package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.RangeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.PacketBus;
import com.potassium.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class AntiKnockback extends Module {

    public static final String MODE_MODIFY = "Modify";
    public static final String MODE_JUMP_RESET = "Jump Reset";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_MODIFY, MODE_JUMP_RESET}, 0);
    private final SliderSetting horizontalSetting = new SliderSetting("Horizontal", 0, -1.0, 1.0, 0.1);
    private final SliderSetting verticalSetting = new SliderSetting("Vertical", 0, -1.0, 1.0, 0.1);
    private final SliderSetting chanceSetting = new SliderSetting("Chance", 100, 0, 100, 1);
    // Chance is whether an attempt is made at all; Accuracy is how well that attempt is timed.
    // Each attempt draws its timing accuracy from this range; a miss is replayed a tick late.
    private final RangeSetting accuracySetting = new RangeSetting("Accuracy", 100, 100, 0, 100, 1);
    // Leaves the hit alone while the player gives no movement input, so a standing player keeps
    // vanilla knockback instead of every hit looking damped.
    private final BooleanSetting onlyWhileMovingSetting =
            new BooleanSetting("Only While Moving", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private volatile int playerId = -1;
    private volatile boolean active;

    // Snapshot settings for the Netty thread; Minecraft settings are not thread-safe.
    private volatile boolean jumpResetMode;
    private volatile double horizontal;
    private volatile double vertical;
    private volatile double chance;
    private volatile boolean jumpResetPending;
    private volatile boolean jumpResetLate;
    // Netty-thread snapshot: the settings and the player's input are only read on the client thread.
    private volatile boolean onlyWhileMoving;
    private volatile boolean moving;

    public AntiKnockback() {
        super("AntiKnockback", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(horizontalSetting);
        settings.add(verticalSetting);
        settings.add(chanceSetting);
        settings.add(accuracySetting);
        settings.add(onlyWhileMovingSetting);
        MinecraftForge.EVENT_BUS.register(this);
        PacketBus.subscribe("AntiKnockback", PacketBus.Direction.INBOUND, 0, this::onPacket);
    }

    @Override
    public String getTag() {
        if (MODE_JUMP_RESET.equals(mode())) return MODE_JUMP_RESET;
        return multiplier(horizontalSetting.getValue()) + "h "
                + multiplier(verticalSetting.getValue()) + "v";
    }

    /** The multiplier the slider shows, without trailing noise from the stored double. */
    private static String multiplier(double value) {
        return Double.toString(Math.round(value * 100.0D) / 100.0D);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == horizontalSetting || setting == verticalSetting) {
            return MODE_MODIFY.equals(mode());
        }
        if (setting == chanceSetting) return MODE_JUMP_RESET.equals(mode());
        if (setting == accuracySetting) return MODE_JUMP_RESET.equals(mode());
        return super.isSettingVisible(setting);
    }

    private String mode() {
        return modeSetting.getValue();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        syncSettings();
        if (!isEnabled()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player != null) playerId = player.getEntityId();

        // Rise's jump reset: jump on the tick after a hit that carried upward knockback, so the
        // jump's own motion answers the knockback. The packet itself goes through untouched.
        // Chance decides whether an attempt is made; Accuracy decides whether that attempt is
        // timed onto the hit tick. A mistimed attempt is replayed one tick late, where the
        // player tick rejects it once the knockback has already lifted the player off the floor.
        boolean jumped = false;
        if (jumpResetLate) {
            jumpResetLate = false;
            if (jumpResetMode && player != null) jumped = jump(player);
        }
        if (!jumped && jumpResetPending) {
            jumpResetPending = false;
            if (jumpResetMode && player != null && Math.random() * 100.0D < chance) {
                if (Math.random() * 100.0D < accuracySetting.random()) {
                    jump(player);
                } else {
                    jumpResetLate = true;
                }
            }
        }

        // Fallback for a pipeline that has not accepted the handler yet. The packet path is
        // primary; this still prevents the knockback if the channel was replaced or injection
        // failed.
        if (!jumpResetMode && !PacketBus.isInstalled() && player != null && player.velocityChanged
                && allowsNow()) {
            player.motionX *= horizontal;
            player.motionY *= vertical;
            player.motionZ *= horizontal;
            player.velocityChanged = false;
        }
    }

    /**
     * Vanilla's jump motion, including the sprint boost a real jump adds.
     *
     * @return false when the player cannot jump on this tick, so a late attempt can never
     * stack a second jump on an attempt that already landed.
     */
    private boolean jump(EntityPlayerSP player) {
        if (!player.onGround || player.isInWater() || player.isInLava()
                || player.isOnLadder() || player.isRiding()) {
            return false;
        }
        player.motionY = 0.42D;
        if (player.isSprinting()) {
            float yaw = player.rotationYaw * 0.017453292F;
            player.motionX -= MathHelper.sin(yaw) * 0.2F;
            player.motionZ += MathHelper.cos(yaw) * 0.2F;
        }
        player.isAirBorne = true;
        return true;
    }

    /** True unless the movement-only modifier is on and the player is standing still. */
    private boolean allowsNow() {
        return !onlyWhileMovingSetting.getValue()
                || (mc.thePlayer != null && PlayerUtil.isMoving(mc.thePlayer));
    }

    private void syncSettings() {
        jumpResetMode = MODE_JUMP_RESET.equals(mode());
        horizontal = horizontalSetting.getValue();
        vertical = verticalSetting.getValue();
        chance = chanceSetting.getValue();
        onlyWhileMoving = onlyWhileMovingSetting.getValue();
        moving = mc.thePlayer != null && PlayerUtil.isMoving(mc.thePlayer);
        active = isEnabled();
    }

    /**
     * A packet can arrive after the client tick but before the player tick. Catch the
     * residual velocity at player-tick start when the pipeline handler is unavailable.
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !active || PacketBus.isInstalled()
                || jumpResetMode) {
            return;
        }
        if (!allowsNow()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player || !player.velocityChanged) {
            return;
        }
        player.motionX *= horizontal;
        player.motionY *= vertical;
        player.motionZ *= horizontal;
        player.velocityChanged = false;
    }

    /**
     * Rewrites the player's own knockback before the client applies it.
     *
     * <p>The bus calls this on the channel's event loop with the module's settings snapshotted,
     * so nothing here reads a config value that could change mid-packet.</p>
     */
    private void onPacket(PacketBus.Event event) {
        if (!active || event.direction() != PacketBus.Direction.INBOUND) return;
        Packet packet = event.packet();

        int id = playerId;
        if (id < 0 && mc.thePlayer != null) {
            id = mc.thePlayer.getEntityId();
            playerId = id;
        }
        boolean allowed = !onlyWhileMoving || moving;

        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity p = (S12PacketEntityVelocity) packet;
            if (allowed && id >= 0 && p.getEntityID() == id) {
                if (jumpResetMode) {
                    // Only a hit with upward knockback is worth jumping against.
                    if (p.getMotionY() > 0) jumpResetPending = true;
                } else {
                    event.replace(new S12PacketEntityVelocity(id,
                            p.getMotionX() / 8000.0 * horizontal,
                            p.getMotionY() / 8000.0 * vertical,
                            p.getMotionZ() / 8000.0 * horizontal));
                }
            }
        } else if (packet instanceof S27PacketExplosion && !jumpResetMode && allowed) {
            S27PacketExplosion p = (S27PacketExplosion) packet;
            event.replace(new S27PacketExplosion(p.getX(), p.getY(), p.getZ(), p.getStrength(),
                    p.getAffectedBlockPositions(),
                    new Vec3(p.func_149149_c() * horizontal,
                            p.func_149144_d() * vertical,
                            p.func_149147_e() * horizontal)));
        }
    }

    @Override
    public void onEnable() {
        syncSettings();
        active = true;
        if (mc.thePlayer != null) playerId = mc.thePlayer.getEntityId();
    }

    @Override
    public void onDisable() {
        jumpResetPending = false;
        jumpResetLate = false;
        active = false;
    }
}