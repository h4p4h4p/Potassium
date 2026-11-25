package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.potion.Potion;
import net.minecraft.util.Timer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Creative-style flight without touching the allowFlying capability.
 *
 * <p>Vanilla mode sets isFlying so vanilla uses its flying movement branch: jump ascends,
 * sneak descends, and the fly speed from the player's capabilities drives horizontal
 * movement. Vertical motion is cleared every tick so the player hovers when no key is held.
 * Re-applying it each tick matters because vanilla clears isFlying when a flying player
 * touches the ground.</p>
 *
 * <p>Packet mode never touches the capability. It replaces the tick's movement with its own
 * motion and lets vanilla send that position, so the server sees a normal player moving
 * without gravity. A ground-flagged position packet is mixed in so the server keeps seeing a
 * grounded player instead of a long airborne streak.</p>
 *
 * <p>Rewound mode is a port of Adjust's Rewound damage fly. On the ground it sends the same
 * fall-damage position packets, optionally waits for the hurt status, then runs Adjust's
 * speed stages: level off at the configured speed, the 0.42 jump-motion pop, and the
 * last-distance friction decay. Adjust's optional timer boost is included. There is no
 * jump/sneak handling and no vertical speed setting: the only vertical motion is the
 * scripted stage-two pop.</p>
 */
public class Flight extends Module {

    public static final String MODE_VANILLA = "Vanilla";
    public static final String MODE_PACKET = "Packet";
    public static final String MODE_REWOUND = "Rewound";

    public static final String REWOUND_DAMAGE_NORMAL = "Normal";
    public static final String REWOUND_DAMAGE_WAIT = "Wait";
    public static final String REWOUND_DAMAGE_NONE = "None";

    // Every fourth tick carries the grounded flag; every tick would look like a stuck
    // onGround value next to the real movement packet.
    private static final int SPOOF_INTERVAL = 4;
    private static final double START_CLIP = 0.5D;

    private static final String HANDLER_NAME = "potassium_flight_rewound";
    private static final double REWOUND_BASE_SPEED = 0.2873D;
    private static final double REWOUND_SPEED_PER_LEVEL = 0.2D;
    private static final double REWOUND_STAGE_TWO_BOOST = 2.145D;
    private static final float REWOUND_JUMP_MOTION = 0.42F;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_VANILLA, MODE_PACKET, MODE_REWOUND}, 0);
    private final SliderSetting horizontalSpeedSetting = new SliderSetting("Horizontal Speed",
            0.24, 0.05, 1.0, 0.01);
    private final SliderSetting verticalSpeedSetting = new SliderSetting("Vertical Speed",
            0.062, 0.01, 0.5, 0.001);
    private final ModeSetting rewoundDamageSetting = new ModeSetting("Damage",
            new String[]{REWOUND_DAMAGE_NORMAL, REWOUND_DAMAGE_WAIT, REWOUND_DAMAGE_NONE}, 0);
    private final SliderSetting rewoundSpeedSetting = new SliderSetting("Speed",
            1.0, 0.5, 5.0, 0.05);
    private final BooleanSetting rewoundTimerSetting = new BooleanSetting("Timer", true);
    private final SliderSetting rewoundTimerSpeedSetting = new SliderSetting("Timer Speed",
            2.0, 0.5, 10.0, 0.05);
    private final SliderSetting rewoundTimerTimeSetting = new SliderSetting("Timer Time",
            500, 5, 2000, 5);
    private final BooleanSetting startClipSetting = new BooleanSetting("Start Clip", true);
    private final BooleanSetting spoofGroundSetting = new BooleanSetting("Spoof Ground", true);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final FlightPacketHandler packetHandler = new FlightPacketHandler();
    private Timer clientTimer;
    private String activeMode;
    private boolean wasFlying;

    // Packet and Rewound modes share the same anchored movement: remember where vanilla
    // started the tick and replace its movement with our own motion.
    private boolean packetFlying;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private int spoofTicks;

    // Rewound mode state, mirroring Adjust's DAMAGE state machine.
    private boolean rewoundActive;
    private int rewoundStage;
    private boolean rewoundWaiting;
    private double rewoundMoveSpeed;
    private double rewoundLastDistance;
    private double rewoundLastX;
    private double rewoundLastZ;
    private int rewoundGroundTicks;
    private int rewoundLastHurtTime;
    private boolean rewoundOnGround;
    private long rewoundTimerStart;
    private volatile boolean rewoundArmed;
    private volatile boolean rewoundFlagged;

    public Flight() {
        super("Flight", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(horizontalSpeedSetting);
        settings.add(verticalSpeedSetting);
        settings.add(rewoundDamageSetting);
        settings.add(rewoundSpeedSetting);
        settings.add(rewoundTimerSetting);
        settings.add(rewoundTimerSpeedSetting);
        settings.add(rewoundTimerTimeSetting);
        settings.add(startClipSetting);
        settings.add(spoofGroundSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == horizontalSpeedSetting || setting == verticalSpeedSetting
                || setting == startClipSetting || setting == spoofGroundSetting) {
            return packet();
        }
        if (setting == rewoundDamageSetting || setting == rewoundSpeedSetting
                || setting == rewoundTimerSetting) {
            return rewound();
        }
        if (setting == rewoundTimerSpeedSetting || setting == rewoundTimerTimeSetting) {
            return rewound() && rewoundTimerSetting.getValue();
        }
        return super.isSettingVisible(setting);
    }

    private boolean packet() {
        return MODE_PACKET.equals(modeSetting.getValue());
    }

    private boolean rewound() {
        return MODE_REWOUND.equals(modeSetting.getValue());
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player == null || event.player != mc.thePlayer) return;
        if (!isEnabled()) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;

        String mode = modeSetting.getValue();
        if (mode == null) mode = MODE_VANILLA;
        boolean custom = !MODE_VANILLA.equals(mode);

        // Hand the flight state over when the mode changes mid-air. Vanilla mode leaves
        // isFlying on, and the custom modes bail out while it is set, so without this the
        // switch would keep creative flight running and the new mode would never move.
        if (!mode.equals(activeMode)) {
            activeMode = mode;
            packetFlying = false;
            spoofTicks = 0;
            resetRewound();
            player.motionX = 0.0D;
            player.motionY = 0.0D;
            player.motionZ = 0.0D;
            player.capabilities.isFlying = !custom;
            if (custom && packet() && startClipSetting.getValue() && player.onGround) {
                player.setPosition(player.posX, player.posY + START_CLIP, player.posZ);
            }
            if (rewound()) {
                beginRewound(player);
                armChannel();
            }
        }

        if (rewoundFlagged) {
            rewoundFlagged = false;
            setEnabled(false);
            return;
        }

        if (!custom) {
            if (event.phase != TickEvent.Phase.START) return;
            player.capabilities.isFlying = true;
            player.motionY = 0.0D;
            return;
        }

        if (rewound()) {
            if (!rewoundActive) beginRewound(player);
            armChannel();
        }

        if (packet()) {
            if (event.phase == TickEvent.Phase.START) {
                packetStart(player);
            } else {
                packetEnd(player);
            }
        } else if (rewound()) {
            if (event.phase == TickEvent.Phase.START) {
                rewoundStart(player);
            } else {
                rewoundEnd(player);
            }
        }
    }

    /** Remembers where vanilla started this tick and cancels its motion. */
    private void packetStart(EntityPlayerSP player) {
        packetFlying = false;
        if (player.isDead || player.getHealth() <= 0.0F) return;
        if (player.capabilities.isFlying) return;
        if (player.isInWater() || player.isInLava() || player.isRiding() || player.isOnLadder()) return;
        if (player.onGround) return;

        anchor(player);
        packetFlying = true;
    }

    /**
     * Discards whatever vanilla moved the player, applies the packet motion instead, and
     * leaves the resulting position for vanilla's outgoing movement packet.
     */
    private void packetEnd(EntityPlayerSP player) {
        if (!packetFlying) return;

        double[] horizontal = inputMotion(player, horizontalSpeedSetting.getValue());
        double vertical = 0.0D;
        if (player.movementInput.jump) {
            vertical = verticalSpeedSetting.getValue();
        } else if (player.movementInput.sneak) {
            vertical = -verticalSpeedSetting.getValue();
        }

        player.setPosition(anchorX, anchorY, anchorZ);
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.moveEntity(horizontal[0], vertical, horizontal[1]);
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.fallDistance = 0.0F;

        if (spoofGroundSetting.getValue() && ++spoofTicks % SPOOF_INTERVAL == 0
                && mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                    player.posX, player.posY, player.posZ, true));
        }
    }

    /**
     * Adjust's Rewound stage zero: once the player has moved on the ground, send the
     * fall-damage packet ritual, then either start immediately or wait for the hurt status
     * carried by hurtTime. A failed or disabled launch falls through to the friction stage.
     */
    private void rewoundStart(EntityPlayerSP player) {
        if (rewoundStage == 0) {
            if (!rewoundWaiting && player.onGround && rewoundLastDistance > 0.0D) {
                if (!REWOUND_DAMAGE_NONE.equals(rewoundDamageSetting.getValue())
                        && sendSelfDamagePackets(player)) {
                    if (REWOUND_DAMAGE_WAIT.equals(rewoundDamageSetting.getValue())) {
                        rewoundWaiting = true;
                        // Wait for a hit after the ritual, not a hurtTime that was already active.
                        rewoundLastHurtTime = player.hurtTime;
                    } else {
                        rewoundStage = 1;
                    }
                    if (rewoundTimerSetting.getValue()) {
                        rewoundTimerStart = System.currentTimeMillis();
                    }
                } else {
                    rewoundMoveSpeed = 0.0D;
                    rewoundStage = 4;
                }
            }
        }

        if (rewoundWaiting) {
            int hurtTime = player.hurtTime;
            if (hurtTime > rewoundLastHurtTime) {
                rewoundWaiting = false;
                rewoundStage = 1;
            }
            rewoundLastHurtTime = hurtTime;
        }

        if (rewoundStage >= 1) {
            updateRewoundTimer();
            rewoundOnGround = player.onGround;
            if ((rewoundGroundTicks & 1) == 0) {
                double offset = 1.0E-9D - Math.random() * 9.9999E-10D;
                player.setPosition(player.posX, player.posY + offset, player.posZ);
            }
            rewoundGroundTicks++;
            anchor(player);
        }
    }

    /**
     * Adjust's Rewound movement stages. Horizontal motion is applied through the anchor so
     * the server sees the stage speed without vanilla gravity fighting it. Stage two's
     * 0.42 pop is the only vertical write.
     */
    private void rewoundEnd(EntityPlayerSP player) {
        if (!rewoundActive) return;
        if (rewoundStage < 1 || rewoundWaiting) {
            updateRewoundDistance(player);
            return;
        }

        double baseSpeed = speedWithPotion(player);
        boolean frictionStage = rewoundStage >= 4;
        double vertical = 0.0D;
        if (rewoundStage == 1) {
            rewoundMoveSpeed = rewoundSpeedSetting.getValue();
        } else if (rewoundStage == 2) {
            vertical = jumpMotion(player);
            rewoundMoveSpeed *= REWOUND_STAGE_TWO_BOOST;
        } else if (rewoundStage == 3) {
            rewoundMoveSpeed = rewoundLastDistance
                    - (rewoundLastDistance - baseSpeed) * 0.66D;
        } else {
            rewoundMoveSpeed = calculateFriction(rewoundLastDistance, baseSpeed) - 0.00001D;
        }

        if (frictionStage && rewoundOnGround && rewoundMoveSpeed > baseSpeed) {
            setEnabled(false);
            return;
        }

        double[] horizontal = rewoundMotion(player, rewoundMoveSpeed);
        player.setPosition(anchorX, anchorY, anchorZ);
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.moveEntity(horizontal[0], vertical, horizontal[1]);
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.fallDistance = 0.0F;

        updateRewoundDistance(player);
        updateRewoundTimer();
        rewoundStage++;
    }

    /**
     * The fall-damage packet loop from Adjust's Rewound. The server sees a climb and fall of
     * 3.1 blocks (plus Jump Boost levels) and applies the resulting fall damage.
     */
    private boolean sendSelfDamagePackets(EntityPlayerSP player) {
        if (mc.getNetHandler() == null) return false;

        double x = player.posX;
        double y = player.posY;
        double z = player.posZ;
        double fallDistance = 3.1D;
        if (player.isPotionActive(Potion.jump)) {
            fallDistance += (float) (player.getActivePotionEffect(Potion.jump).getAmplifier() + 1);
        }

        double step = 0.06249D;
        List<Packet<?>> packets = new ArrayList<>();
        for (double distance = 0.0D; distance < fallDistance; distance += step) {
            packets.add(new C03PacketPlayer.C04PacketPlayerPosition(x, y + step, z, false));
            packets.add(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
        }
        packets.add(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, true));

        for (Packet<?> packet : packets) {
            mc.getNetHandler().addToSendQueue(packet);
        }
        return true;
    }

    /** Adjust's MovementUtil.setSpeed direction, including its diagonal handling. */
    private double[] rewoundMotion(EntityPlayerSP player, double speed) {
        float forward = (mc.gameSettings.keyBindForward.isKeyDown() ? 1 : 0)
                - (mc.gameSettings.keyBindBack.isKeyDown() ? 1 : 0);
        float strafe = (mc.gameSettings.keyBindLeft.isKeyDown() ? 1 : 0)
                - (mc.gameSettings.keyBindRight.isKeyDown() ? 1 : 0);
        if (forward == 0.0F && strafe == 0.0F) {
            return new double[]{0.0D, 0.0D};
        }

        float yaw = player.rotationYaw;
        if (forward < 0.0F) yaw += 180.0F;
        float offset = forward < 0.0F ? -45.0F : forward > 0.0F ? 45.0F : 90.0F;
        if (strafe > 0.0F) yaw -= offset;
        if (strafe < 0.0F) yaw += offset;

        double radians = Math.toRadians(yaw);
        return new double[]{-Math.sin(radians) * speed, Math.cos(radians) * speed};
    }

    private static double speedWithPotion(EntityPlayerSP player) {
        double speed = REWOUND_BASE_SPEED;
        if (player.isPotionActive(Potion.moveSpeed)) {
            speed *= 1.0D + REWOUND_SPEED_PER_LEVEL
                    * (player.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1);
        }
        return speed;
    }

    private static double jumpMotion(EntityPlayerSP player) {
        double motion = REWOUND_JUMP_MOTION;
        if (player.isPotionActive(Potion.jump)) {
            motion += (player.getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1F;
        }
        return motion;
    }

    private static double calculateFriction(double lastDistance, double baseSpeed) {
        return Math.max(
                Math.max(lastDistance - lastDistance / 160.0D,
                        lastDistance - (lastDistance - baseSpeed) / 33.3D),
                lastDistance - (lastDistance - baseSpeed) * 0.02D);
    }

    private void updateRewoundDistance(EntityPlayerSP player) {
        rewoundLastDistance = Math.hypot(player.posX - rewoundLastX,
                player.posZ - rewoundLastZ);
        rewoundLastX = player.posX;
        rewoundLastZ = player.posZ;
    }

    private void updateRewoundTimer() {
        if (!rewoundTimerSetting.getValue()) {
            if (rewoundTimerStart != 0L) {
                setTimerSpeed(1.0F);
                rewoundTimerStart = 0L;
            }
            return;
        }
        if (rewoundTimerStart == 0L) return;
        if (System.currentTimeMillis() - rewoundTimerStart
                < (long) rewoundTimerTimeSetting.getValue()) {
            setTimerSpeed((float) rewoundTimerSpeedSetting.getValue());
        } else {
            setTimerSpeed(1.0F);
            rewoundTimerStart = 0L;
        }
    }

    /** Minecraft.timer is private; accept both the MCP and SRG field names. */
    private Timer getClientTimer() {
        if (clientTimer == null) {
            clientTimer = ReflectionHelper.getPrivateValue(Minecraft.class, mc,
                    "timer", "field_71428_T");
        }
        return clientTimer;
    }

    private void setTimerSpeed(float speed) {
        Timer timer = getClientTimer();
        if (timer != null) {
            timer.timerSpeed = speed;
        }
    }

    private void beginRewound(EntityPlayerSP player) {
        rewoundActive = true;
        rewoundStage = 0;
        rewoundWaiting = false;
        rewoundMoveSpeed = 0.0D;
        // EntityPlayer.lastDistance is the last tick's horizontal position delta.
        rewoundLastDistance = Math.hypot(player.posX - player.prevPosX,
                player.posZ - player.prevPosZ);
        rewoundLastX = player.posX;
        rewoundLastZ = player.posZ;
        rewoundGroundTicks = 0;
        rewoundLastHurtTime = player.hurtTime;
        rewoundOnGround = player.onGround;
        rewoundTimerStart = 0L;
        rewoundArmed = false;
        rewoundFlagged = false;
    }

    private void resetRewound() {
        rewoundActive = false;
        rewoundStage = 0;
        rewoundWaiting = false;
        rewoundMoveSpeed = 0.0D;
        rewoundLastDistance = 0.0D;
        rewoundLastX = mc.thePlayer == null ? 0.0D : mc.thePlayer.posX;
        rewoundLastZ = mc.thePlayer == null ? 0.0D : mc.thePlayer.posZ;
        rewoundGroundTicks = 0;
        rewoundLastHurtTime = mc.thePlayer == null ? 0 : mc.thePlayer.hurtTime;
        rewoundOnGround = mc.thePlayer != null && mc.thePlayer.onGround;
        rewoundArmed = false;
        rewoundFlagged = false;
        if (rewoundTimerStart != 0L) {
            setTimerSpeed(1.0F);
            rewoundTimerStart = 0L;
        }
    }

    private void armChannel() {
        rewoundArmed = false;
        if (!rewoundActive || !rewound()) return;
        if (mc.getNetHandler() == null) return;

        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (channel.pipeline().get(HANDLER_NAME) == null) {
            try {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, packetHandler);
            } catch (Exception ignored) {
                return;
            }
        }
        rewoundArmed = true;
    }

    private void anchor(EntityPlayerSP player) {
        anchorX = player.posX;
        anchorY = player.posY;
        anchorZ = player.posZ;
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.fallDistance = 0.0F;
    }

    /** Horizontal motion for Packet mode, the same way vanilla turns input into motion. */
    private static double[] inputMotion(EntityPlayerSP player, double speed) {
        float forward = player.movementInput.moveForward;
        float strafe = player.movementInput.moveStrafe;
        float yaw = player.rotationYaw;

        if (forward != 0.0F) {
            if (strafe > 0.0F) {
                yaw += forward > 0.0F ? -45.0F : 45.0F;
            } else if (strafe < 0.0F) {
                yaw += forward > 0.0F ? 45.0F : -45.0F;
            }
            strafe = 0.0F;
            forward = forward > 0.0F ? 1.0F : -1.0F;
        }

        double rad = Math.toRadians(yaw);
        double x = forward * speed * -Math.sin(rad) + strafe * speed * Math.cos(rad);
        double z = forward * speed * Math.cos(rad) - strafe * speed * -Math.sin(rad);
        return new double[]{x, z};
    }

    @Override
    public String getTag() {
        return modeSetting.getDisplayValue();
    }

    @Override
    public void onEnable() {
        wasFlying = mc.thePlayer != null && mc.thePlayer.capabilities.isFlying;
        packetFlying = false;
        spoofTicks = 0;
        resetRewound();

        activeMode = modeSetting.getValue();
        if (activeMode == null) activeMode = MODE_VANILLA;
        if (mc.thePlayer == null) return;

        if (MODE_VANILLA.equals(activeMode)) {
            mc.thePlayer.capabilities.isFlying = true;
            return;
        }

        if (rewound()) {
            // Rewound starts from the ground and creates its own damage, so creative
            // flight must not short-circuit the launch.
            mc.thePlayer.capabilities.isFlying = false;
            beginRewound(mc.thePlayer);
            armChannel();
        }

        if (packet() && startClipSetting.getValue() && mc.thePlayer.onGround) {
            // Packet mode needs air under the player; lift off the ground instead of
            // waiting for a jump the server may reject as flying.
            mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY + START_CLIP,
                    mc.thePlayer.posZ);
            mc.thePlayer.motionY = 0.0D;
        }
    }

    @Override
    public void onDisable() {
        packetFlying = false;
        resetRewound();
        if (mc.thePlayer == null) return;
        // Packet and Rewound are done with the flight state, so hand it back to what the
        // player had before enabling: creative and spectator keep it, everyone else drops.
        mc.thePlayer.capabilities.isFlying = mc.thePlayer.capabilities.allowFlying && wasFlying;
    }

    @ChannelHandler.Sharable
    private final class FlightPacketHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (rewoundArmed && msg instanceof S08PacketPlayerPosLook) {
                rewoundFlagged = true;
            }
            ctx.fireChannelRead(msg);
        }
    }
}
