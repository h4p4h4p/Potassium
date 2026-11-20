package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Timer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Speed extends Module {

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_LEGIT_HOP = "LegitHop";
    public static final String MODE_LOW_HOP = "LowHop";
    public static final String MODE_NCP = "NCP";

    // Same ground speed Normal starts from, so the default multiplier lands around 0.33.
    private static final double LOW_HOP_BASE_SPEED = 0.28D;

    // LiquidBounce NCP constants.
    private static final double NCP_GROUND_SPEED = 0.281D;
    private static final double NCP_AIR_SPEED = 0.2D;
    private static final double NCP_SPEED_EFFECT = 0.199999999D;
    private static final double NCP_AIR_STRAFE_STRENGTH = 0.7D;
    private static final double NCP_BOOST = 0.00718D;
    private static final double NCP_PULL_DOWN = 0.1523351824467155D;
    private static final double NCP_PULL_HURT = 0.1D;
    private static final double NCP_DAMAGE_BOOST_SPEED = 0.5D;
    private static final double NCP_LOW_HOP_MOTION = 0.4D;
    private static final float NCP_TIMER_SPEED = 1.08F;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_NORMAL, MODE_LEGIT_HOP, MODE_LOW_HOP, MODE_NCP}, 0);
    private final SliderSetting speedSetting = new SliderSetting("Speed", 1.2, 0.5, 5.0, 0.05);
    private final BooleanSetting autoJumpSetting = new BooleanSetting("Auto Jump", true);
    private final BooleanSetting glideSetting = new BooleanSetting("Glide", false);
    private final BooleanSetting ncpPullDownSetting = new BooleanSetting("Pull Down", true);
    private final SliderSetting ncpPullMotionSetting = new SliderSetting("Motion Multiplier",
            1.0, 0.01, 10.0, 0.01);
    private final SliderSetting ncpPullTickSetting = new SliderSetting("On Tick",
            5, 1, 9, 1);
    private final BooleanSetting ncpPullOnHurtSetting = new BooleanSetting("On Hurt", true);
    private final BooleanSetting ncpBoostSetting = new BooleanSetting("Boost", true);
    private final SliderSetting ncpBoostMultiplierSetting = new SliderSetting("Initial Boost Multiplier",
            1.0, 0.01, 10.0, 0.01);
    private final BooleanSetting ncpTimerSetting = new BooleanSetting("Timer", true);
    private final BooleanSetting ncpDamageBoostSetting = new BooleanSetting("Damage Boost", true);
    private final BooleanSetting ncpLowHopSetting = new BooleanSetting("Low Hop", true);
    private final BooleanSetting ncpAirStrafeSetting = new BooleanSetting("Air Strafe", true);

    private final Minecraft mc = Minecraft.getMinecraft();
    private Timer clientTimer;

    // VerusHop-style speed: one acceleration on the ground, then decay in the air.
    // Re-strafing to full speed every airborne tick is what the speed checks see.
    private double airSpeed;

    // LowHop: hold the horizontal speed flat through the hop and cut the jump
    // short on the first air tick so the bounce stays near the ground.
    private double lowHopSpeed;
    private int lowHopAirTicks;
    private boolean lowHopJumped;

    private boolean ncpTimerApplied;
    private int ncpAirTicks;

    public Speed() {
        super("Speed", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(speedSetting);
        settings.add(autoJumpSetting);
        settings.add(glideSetting);
        settings.add(ncpPullDownSetting);
        settings.add(ncpPullMotionSetting);
        settings.add(ncpPullTickSetting);
        settings.add(ncpPullOnHurtSetting);
        settings.add(ncpBoostSetting);
        settings.add(ncpBoostMultiplierSetting);
        settings.add(ncpTimerSetting);
        settings.add(ncpDamageBoostSetting);
        settings.add(ncpLowHopSetting);
        settings.add(ncpAirStrafeSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == speedSetting) return !legitHop() && !ncp();
        if (setting == autoJumpSetting) return !legitHop() && !lowHop() && !ncp();
        if (setting == glideSetting) return lowHop();
        if (setting == ncpPullDownSetting || setting == ncpBoostSetting
                || setting == ncpTimerSetting || setting == ncpDamageBoostSetting
                || setting == ncpLowHopSetting || setting == ncpAirStrafeSetting) {
            return ncp();
        }
        if (setting == ncpPullMotionSetting || setting == ncpPullTickSetting
                || setting == ncpPullOnHurtSetting) {
            return ncp() && ncpPullDownSetting.getValue();
        }
        if (setting == ncpBoostMultiplierSetting) {
            return ncp() && ncpBoostSetting.getValue();
        }
        return super.isSettingVisible(setting);
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean legitHop() {
        return MODE_LEGIT_HOP.equals(mode());
    }

    private boolean lowHop() {
        return MODE_LOW_HOP.equals(mode());
    }

    private boolean ncp() {
        return MODE_NCP.equals(mode());
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        if (!ncp()) {
            resetNcpState();
            ncpAirTicks = 0;
        }

        EntityPlayer player = event.player;
        if (player == null || player != mc.thePlayer) {
            if (ncp()) {
                resetNcpState();
                ncpAirTicks = 0;
            }
            return;
        }
        if (player.capabilities.isFlying || player.isInWater() || player.isInLava()) {
            if (ncp()) resetNcpState();
            return;
        }

        if (legitHop()) {
            // Bunny hop off vanilla movement; no strafe override, so sprint jumps keep their momentum.
            if (player.onGround && PlayerUtil.isMoving(player)) {
                player.jump();
            }
            return;
        }

        if (lowHop()) {
            boolean moving = PlayerUtil.isMoving(player) && !player.isSneaking();
            if (player.onGround) {
                if (!moving) return;
                lowHopAirTicks = 0;
                lowHopJumped = true;
                player.jump();
                lowHopSpeed = LOW_HOP_BASE_SPEED * speedSetting.getValue();
            } else {
                // Cutting the rise on the first air tick is the "low" in LowHop: the jump
                // peaks almost immediately, so the next hop starts a few ticks after take-off.
                // The tick counter keeps running while no key is held, so releasing them
                // mid-jump cannot re-trigger the cut later in the same hop.
                if (lowHopJumped && ++lowHopAirTicks == 1) {
                    player.motionY = -0.098D;
                }
                if (isGroundExempt(player)) {
                    // Ground exemption: while skimming the floor the anti-cheat treats the
                    // movement as grounded, so hold the air speed instead of bleeding it off.
                    if (glideSetting.getValue()) {
                        player.motionY = 0.0D;
                    }
                } else {
                    lowHopSpeed *= 0.99D;
                }
                if (!moving) return;
            }
            // Enabling mid-air leaves the speed at zero until the first hop; strafing with it
            // would zero the player's momentum.
            if (lowHopSpeed > 0.0D) {
                PlayerUtil.strafe(player, lowHopSpeed);
            }
            return;
        }

        if (ncp()) {
            boolean moving = PlayerUtil.isMoving(player);
            PotionEffect speedEffect = player.getActivePotionEffect(Potion.moveSpeed);
            int speedAmplifier = speedEffect == null ? 0 : speedEffect.getAmplifier();

            if (player.onGround) {
                ncpAirTicks = 0;
            }

            // LiquidBounce's NCP mode is a bhop: jump while moving and hold the
            // ground minimum, then blend toward the air minimum while airborne.
            if (moving && player.onGround) {
                player.jump();
                if (ncpLowHopSetting.getValue()) {
                    player.motionY = NCP_LOW_HOP_MOTION;
                }
                double groundSpeed = NCP_GROUND_SPEED + NCP_SPEED_EFFECT * speedAmplifier;
                ncpStrafe(player, Math.max(horizontalSpeed(player), groundSpeed), 1.0D);
            } else if (moving && ncpAirStrafeSetting.getValue()) {
                double airSpeed = NCP_AIR_SPEED + NCP_SPEED_EFFECT * speedAmplifier;
                ncpStrafe(player, Math.max(horizontalSpeed(player), airSpeed),
                        NCP_AIR_STRAFE_STRENGTH);
            }

            // NCP PullDown: one downward impulse per jump at the configured air tick.
            if (ncpPullDownSetting.getValue() && !player.onGround) {
                ncpAirTicks++;
                if (ncpAirTicks == (int) ncpPullTickSetting.getValue()) {
                    ncpStrafe(player, horizontalSpeed(player), 1.0D);
                    player.motionY -= NCP_PULL_DOWN * ncpPullMotionSetting.getValue();
                }
                if (ncpPullOnHurtSetting.getValue() && player.hurtTime >= 5
                        && player.motionY >= 0.0D) {
                    player.motionY -= NCP_PULL_HURT;
                }
            }

            // NCP Boost: apply a small horizontal boost every tick while moving.
            if (ncpBoostSetting.getValue() && moving) {
                double factor = 1.0D + NCP_BOOST * ncpBoostMultiplierSetting.getValue();
                player.motionX *= factor;
                player.motionZ *= factor;
            }

            setTimerSpeed(ncpTimerSetting.getValue() ? NCP_TIMER_SPEED : 1.0F);
            ncpTimerApplied = true;

            // NCP DamageBoost: minimum horizontal speed while hurt.
            if (ncpDamageBoostSetting.getValue() && player.hurtTime >= 1) {
                ncpStrafe(player, Math.max(horizontalSpeed(player), NCP_DAMAGE_BOOST_SPEED),
                        1.0D);
            }
            return;
        }

        if (!PlayerUtil.isMoving(player) || player.isSneaking()) return;

        if (player.onGround) {
            airSpeed = 0.28D * speedSetting.getValue();
            if (autoJumpSetting.getValue()) {
                player.jump();
            }
        } else {
            airSpeed *= 0.98D;
        }

        if (airSpeed > 0.0D) {
            PlayerUtil.strafe(player, airSpeed);
        }
    }

    private static double horizontalSpeed(EntityPlayer player) {
        return Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
    }

    /** LiquidBounce's withStrafe: no input zeroes horizontal movement. */
    private static void ncpStrafe(EntityPlayer player, double speed, double strength) {
        if (!PlayerUtil.isMoving(player)) {
            player.motionX = 0.0D;
            player.motionZ = 0.0D;
            return;
        }
        PlayerUtil.strafe(player, speed, strength);
    }

    /** Minecraft.timer is private; accept both the MCP and SRG field names. */
    private Timer getClientTimer() {
        if (clientTimer == null) {
            clientTimer = ReflectionHelper.getPrivateValue(Minecraft.class, mc, "timer", "field_71428_T");
        }
        return clientTimer;
    }

    private void setTimerSpeed(float speed) {
        Timer timer = getClientTimer();
        if (timer != null) {
            timer.timerSpeed = speed;
        }
    }

    /** True while descending with a block within 0.66 blocks below the player. */
    private boolean isGroundExempt(EntityPlayer player) {
        if (player.motionY >= 0.0D) return false;
        AxisAlignedBB below = player.getEntityBoundingBox().offset(0.0D, -0.66D, 0.0D);
        return !mc.theWorld.getCollidingBoundingBoxes(player, below).isEmpty();
    }

    /** Restores the timer when leaving NCP. */
    private void resetNcpState() {
        if (ncpTimerApplied) {
            setTimerSpeed(1.0F);
            ncpTimerApplied = false;
        }
    }

    @Override
    public String getTag() {
        if (ncp()) return MODE_NCP;
        if (legitHop()) return MODE_LEGIT_HOP;
        double value = speedSetting.getValue();
        return (Math.round(value * 100.0) / 100.0) + "x";
    }

    @Override
    public void onEnable() {
        resetNcpState();
        airSpeed = 0.0D;
        lowHopSpeed = 0.0D;
        lowHopAirTicks = 0;
        lowHopJumped = false;
        ncpAirTicks = 0;
    }

    @Override
    public void onDisable() {
        resetNcpState();
        airSpeed = 0.0D;
        lowHopSpeed = 0.0D;
        lowHopAirTicks = 0;
        lowHopJumped = false;
        ncpAirTicks = 0;
    }
}
