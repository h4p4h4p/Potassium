package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import com.potassium.util.MovementInputStack;
import com.potassium.util.SilentLook;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

public class Speed extends Module {

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_LEGIT_HOP = "LegitHop";
    public static final String MODE_NCP = "NCP";

    // OpenAdjust's NCP constants, from its NcpSpeedMode.
    private static final double NCP_BASE_SPEED = 0.2873D;
    private static final double NCP_BASE_POTION_STEP = 0.2D;
    private static final double NCP_INITIAL_SPEED = 0.329014D;
    private static final double NCP_INITIAL_POTION_FACTOR = 1.18D;
    private static final double NCP_INITIAL_POTION_BACKOFF = 0.01D;
    private static final double NCP_AIR_KEEP = 0.76D;
    private static final double NCP_AIR_KEEP_FAST = 0.66D;
    private static final double NCP_FRICTION_DIVISOR = 159.0D;
    private static final double NCP_GROUND_BOOST = 1.6D;
    private static final double NCP_CORRECTION_DIVISOR = 1.27D;
    // Its damage boost reads the server's knockback; this is the fall limit it accepts.
    private static final float NCP_DAMAGE_FALL_LIMIT = 2.5F;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_NORMAL, MODE_LEGIT_HOP, MODE_NCP}, 0);
    private final SliderSetting speedSetting = new SliderSetting("Speed", 1.2, 0.5, 5.0, 0.05);
    private final BooleanSetting autoJumpSetting = new BooleanSetting("Auto Jump", true);
    // The three settings its NcpSpeedMode exposes.
    private final BooleanSetting ncpFastSetting = new BooleanSetting("Fast", false);
    private final BooleanSetting ncpDamageBoostSetting = new BooleanSetting("Damage Boost", false);
    private final SliderSetting ncpJumpTicksSetting = new SliderSetting("Jump Ticks", 10, 8, 11, 1);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final NcpPacketHandler ncpHandler = new NcpPacketHandler();
    private int ncpPlayerId = -1;

    // VerusHop-style speed: one acceleration on the ground, then decay in the air.
    // Re-strafing to full speed every airborne tick is what the speed checks see.
    private double airSpeed;

    // NCP state, mirroring its NcpSpeedState.
    private int ncpOffGroundTicks;
    private double ncpLastDistance;
    private double ncpSpeed;
    private int ncpWaitTicks;
    private int ncpJumpTicks = 10;
    private boolean ncpDamaged;
    private boolean ncpJumped;
    private boolean ncpJumpRequested;
    private boolean ncpLowHopPinned;
    private volatile Double ncpPendingSpeed;
    // Ticks since the server last corrected our position; its movementPredictor equivalent.
    private volatile int ncpSinceCorrection;
    private Channel ncpChannel;

    public Speed() {
        super("Speed", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(speedSetting);
        settings.add(autoJumpSetting);
        settings.add(ncpFastSetting);
        settings.add(ncpDamageBoostSetting);
        settings.add(ncpJumpTicksSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == speedSetting) {
            return !legitHop() && !ncp();
        }
        if (setting == autoJumpSetting) {
            return !legitHop() && !ncp();
        }
        if (setting == ncpFastSetting || setting == ncpDamageBoostSetting
                || setting == ncpJumpTicksSetting) {
            return ncp();
        }
        return super.isSettingVisible(setting);
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean legitHop() {
        return MODE_LEGIT_HOP.equals(mode());
    }

    private boolean ncp() {
        return MODE_NCP.equals(mode());
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        // Every player entity fires this event; only the local player drives the module.
        EntityPlayer player = event.player;
        if (player == null || player != mc.thePlayer) return;

        if (!ncp()) {
            resetNcp();
        }
        if (player.capabilities.isFlying || player.isInWater() || player.isInLava()) {
            resetNcp();
            return;
        }

        if (legitHop()) {
            // Bunny hop off vanilla movement; no strafe override, so sprint jumps keep their momentum.
            if (player.onGround && PlayerUtil.isMoving(player) && !vanillaJumps()) {
                player.jump();
            }
            return;
        }

        if (ncp()) {
            tickNcp((EntityPlayerSP) player);
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

    /**
     * True when vanilla is about to jump on its own this tick. The player tick runs before the
     * player's update, so the key state read here is the one vanilla will use; a module jump
     * on the same tick would add the sprint boost a second time.
     */
    private boolean vanillaJumps() {
        if (mc.thePlayer == null) return false;
        if (mc.gameSettings != null && mc.gameSettings.keyBindJump.isKeyDown()) return true;
        return mc.thePlayer.movementInput != null && mc.thePlayer.movementInput.jump;
    }

    private static double horizontalSpeed(EntityPlayer player) {
        return Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
    }

    // ---- NCP: OpenAdjust's NcpSpeedMode -------------------------------------

    /**
     * One NCP tick, in the order its {@code update} runs it.
     *
     * <p>Its version is called from a post-{@code moveFlying} event, i.e. after vanilla has
     * folded the input into the motion and before the move. Forge 1.8.9 has no such event, so
     * the motion is written here, before the player update, and the input layer below keeps
     * vanilla from adding its own acceleration on top. The result is the same final motion,
     * with one caveat: the input direction is sampled from the keys rather than from the copy
     * vanilla has just computed.</p>
     */
    private void tickNcp(EntityPlayerSP player) {
        updateNcpState(player);
        installNcpInput();

        boolean inWeb = mc.theWorld != null && mc.theWorld.getBlockState(
                new BlockPos(player.posX, player.posY, player.posZ)).getBlock() == Blocks.web;
        if (inWeb || player.isOnLadder()) {
            resetNcp();
            return;
        }

        if (!ncpMoving()) {
            resetNcp();
            if (player.motionX != 0.0D || player.motionZ != 0.0D) {
                double multiplier = 0.0D;
                if (isOverVoid(player)) multiplier = 1.0D;
                else if (hasSupportAhead(player)) ncpJumped = true;
                player.motionX *= multiplier;
                player.motionZ *= multiplier;
            }
            return;
        }
        if (waitAfterCorrection(player)) return;
        updateNcpHop(player);
        if (ncpPendingSpeed != null) {
            ncpStrafe(player, ncpPendingSpeed);
            ncpPendingSpeed = null;
        }
        if (ncpJumped) return;
        if (ncpJumpTicks == 11) ncpLowHopPinned = true;
        applyNcpLowHop(player);
    }

    /** Its patched EntityPlayer fields, rebuilt from the position delta vanilla already keeps. */
    private void updateNcpState(EntityPlayerSP player) {
        if (player.onGround) {
            ncpOffGroundTicks = 0;
        } else {
            ncpOffGroundTicks++;
        }
        double dx = player.posX - player.prevPosX;
        double dz = player.posZ - player.prevPosZ;
        ncpLastDistance = Math.sqrt(dx * dx + dz * dz);
        ncpSinceCorrection++;
    }

    private boolean waitAfterCorrection(EntityPlayerSP player) {
        if (ncpSinceCorrection >= 4 || player.isCollidedHorizontally) {
            ncpWaitTicks = 0;
            return false;
        }
        if (!player.onGround) return true;
        if (ncpSinceCorrection < 3) return true;
        if (ncpWaitTicks++ < 2) {
            ncpStrafe(player, ncpBaseSpeed(player) / NCP_CORRECTION_DIVISOR);
            return true;
        }
        return false;
    }

    private void updateNcpHop(EntityPlayerSP player) {
        double base = ncpBaseSpeed(player);
        double initial = player.isPotionActive(Potion.moveSpeed)
                ? base * NCP_INITIAL_POTION_FACTOR - NCP_INITIAL_POTION_BACKOFF
                : NCP_INITIAL_SPEED;

        if (ncpOffGroundTicks == 0) {
            if (ncpMoving()) {
                if (!ncpDamaged) {
                    ncpDamaged = true;
                    ncpSpeed = initial;
                }
                ncpJumped = false;
                if (ncpJumpRequested) {
                    player.jump();
                    ncpJumpRequested = false;
                } else if (player.motionY <= 0.0D) {
                    player.jump();
                }
                ncpLowHopPinned = ncpJumped || ncpSinceCorrection < 41;
                ncpSpeed *= NCP_GROUND_BOOST;
                ncpJumpTicks = (int) Math.round(ncpJumpTicksSetting.getValue());
            }
        } else if (ncpOffGroundTicks == 1) {
            double keep = ncpFastSetting.getValue() ? NCP_AIR_KEEP_FAST : NCP_AIR_KEEP;
            ncpSpeed -= keep * (ncpSpeed - base);
            ncpDamaged = false;
        } else {
            // Its hop decays from the distance actually travelled last tick, not from the
            // speed it wrote, which is what keeps the curve inside NCP's envelope.
            ncpSpeed = ncpLastDistance - ncpLastDistance / NCP_FRICTION_DIVISOR;
        }
        ncpSpeed = Math.max(ncpSpeed, base);
        ncpStrafe(player, ncpSpeed);
    }

    private void applyNcpLowHop(EntityPlayerSP player) {
        if (ncpJumpRequested) {
            if (ncpOffGroundTicks >= 7) ncpLowHopPinned = false;
            return;
        }
        if (mc.theWorld != null) {
            BlockPos pos = new BlockPos(player.posX + 0.5D, player.posY + 0.5D, player.posZ + 0.5D);
            Block below = mc.theWorld.getBlockState(pos.down()).getBlock();
            if (mc.theWorld.getBlockState(pos).getBlock() instanceof BlockCarpet
                    && (below instanceof BlockFenceGate || below instanceof BlockFence)) {
                // A carpet over a fence is a full block to NCP, so the hop must not be cut.
                ncpLowHopPinned = true;
                return;
            }
        }
        double previousY = player.motionY;
        adjustNcpJumpMotion(player);
        if (player.motionY >= 0.0D) return;
        // Cutting the hop into a partial block would flag the descent; keep vanilla's motion
        // and pin the rest of the hop instead.
        if (collidesWithPartial(player.getEntityBoundingBox().addCoord(0.0D, player.motionY, 0.0D))) {
            player.motionY = previousY;
            ncpLowHopPinned = true;
        }
    }

    private void adjustNcpJumpMotion(EntityPlayerSP player) {
        if (ncpLowHopPinned) return;
        if (player.isPotionActive(Potion.jump)) {
            int halfLevel = (player.getActivePotionEffect(Potion.jump).getAmplifier() + 1) / 2;
            int tick = ncpOffGroundTicks - halfLevel;
            if (tick == 5) player.motionY -= 0.175D;
            else if (tick == 7 && halfLevel >= 2) player.motionY -= 0.15D;
            return;
        }
        switch (ncpJumpTicks) {
            case 8:
                if (ncpOffGroundTicks == 1) player.motionY += 0.0569D;
                else if (ncpOffGroundTicks == 3) player.motionY -= 0.1309D;
                else if (ncpOffGroundTicks == 4) player.motionY -= 0.2D;
                break;
            case 9:
                if (ncpOffGroundTicks == 5) player.motionY -= 0.175D;
                break;
            case 10:
                if (ncpOffGroundTicks == 5) player.motionY -= 0.075D;
                break;
            default:
                // 11 has no entry: the pin at the top of the tick keeps that hop level.
                break;
        }
    }

    private double ncpBaseSpeed(EntityPlayer player) {
        double base = NCP_BASE_SPEED;
        PotionEffect effect = player.getActivePotionEffect(Potion.moveSpeed);
        if (effect != null) {
            base *= 1.0D + NCP_BASE_POTION_STEP * (effect.getAmplifier() + 1);
        }
        return base;
    }

    /** Its strafe: absolute motion from the held keys, along the rotation NCP will see. */
    private void ncpStrafe(EntityPlayer player, double speed) {
        float forward = ncpForward();
        float strafe = ncpStrafeInput();
        if (forward == 0.0F && strafe == 0.0F) {
            player.motionX = 0.0D;
            player.motionZ = 0.0D;
            return;
        }
        // Its RenderRotationTracker yaw: the rotation the server will receive, so the motion
        // lines up with what NCP compares it to.
        float yaw = SilentLook.isRequested() ? SilentLook.sentAimYaw() : player.rotationYaw;
        if (forward < 0.0F) yaw += 180.0F;
        float offset = forward < 0.0F ? -45.0F : forward > 0.0F ? 45.0F : 90.0F;
        if (strafe > 0.0F) yaw -= offset;
        if (strafe < 0.0F) yaw += offset;
        double radians = Math.toRadians(yaw);
        player.motionX = -Math.sin(radians) * speed;
        player.motionZ = Math.cos(radians) * speed;
    }

    private boolean ncpMoving() {
        return ncpForward() != 0.0F || ncpStrafeInput() != 0.0F;
    }

    private float ncpForward() {
        return (mc.gameSettings.keyBindForward.isKeyDown() ? 1.0F : 0.0F)
                - (mc.gameSettings.keyBindBack.isKeyDown() ? 1.0F : 0.0F);
    }

    private float ncpStrafeInput() {
        return (mc.gameSettings.keyBindLeft.isKeyDown() ? 1.0F : 0.0F)
                - (mc.gameSettings.keyBindRight.isKeyDown() ? 1.0F : 0.0F);
    }

    /** Marks the input as ours for this tick so vanilla adds no movement of its own. */
    private void installNcpInput() {
        MovementInputStack.install("Speed", MovementInputStack.PRIORITY_SPEED, NcpInput::new);
    }

    private void uninstallNcpInput() {
        MovementInputStack.uninstall("Speed");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        uninstallNcpInput();
        // The zeroed layer left EntityPlayerSP's own fields at zero for this tick. Put the
        // real keys back so modules reading them at the next tick's start see the movement.
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && isEnabled() && ncp()) {
            player.moveForward = ncpForward();
            player.moveStrafing = ncpStrafeInput();
        }
    }

    private static final class NcpInput extends MovementInput {

        private final MovementInput delegate;

        private NcpInput(MovementInput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void updatePlayerMoveState() {
            delegate.updatePlayerMoveState();
            this.moveForward = 0.0F;
            this.moveStrafe = 0.0F;
            this.jump = delegate.jump;
            this.sneak = delegate.sneak;
        }
    }

    private boolean hasSupportAhead(EntityPlayer player) {
        if (mc.theWorld == null) return false;
        double distance = Math.max(0.5D, Math.hypot(player.motionX, player.motionZ));
        double radians = Math.atan2(-player.motionX, player.motionZ);
        double x = -Math.sin(radians) * distance + player.posX;
        double z = Math.cos(radians) * distance + player.posZ;
        AxisAlignedBB box = new AxisAlignedBB(x - 0.3D, player.posY - 0.5D, z - 0.3D,
                x + 0.3D, player.posY, z + 0.3D);
        return !mc.theWorld.getCollidingBoundingBoxes(player, box).isEmpty();
    }

    private boolean isOverVoid(EntityPlayer player) {
        if (mc.theWorld == null) return false;
        return mc.theWorld.getCollidingBoundingBoxes(player,
                player.getEntityBoundingBox().offset(0.0D, -4.0D, 0.0D)).isEmpty();
    }

    /** True when the swept box touches a stair, slab, snow layer or carpet. */
    private boolean collidesWithPartial(AxisAlignedBB box) {
        if (mc.theWorld == null) return false;
        BlockPos min = new BlockPos(box.minX, box.minY, box.minZ);
        BlockPos max = new BlockPos(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.getAllInBox(min, max)) {
            IBlockState state = mc.theWorld.getBlockState(pos);
            Block block = state.getBlock();
            boolean partial = block instanceof BlockStairs
                    || (block instanceof BlockSlab && !((BlockSlab) block).isDouble())
                    || block == Blocks.snow_layer || block == Blocks.carpet;
            if (partial && block.getCollisionBoundingBox(mc.theWorld, pos, state) != null) {
                return true;
            }
        }
        return false;
    }

    private void resetNcp() {
        ncpDamaged = false;
        ncpSpeed = 0.0D;
        ncpWaitTicks = 0;
        ncpPendingSpeed = null;
    }

    /**
     * Its damage boost: a knockback while already airborne is turned into the hop's next
     * speed instead of being applied as velocity. Its 11-tick fall simulation is not ported,
     * so only the conditions it can check up front are used here.
     */
    @ChannelHandler.Sharable
    private final class NcpPacketHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (isEnabled() && ncp() && msg instanceof Packet) {
                int id = ncpPlayerId;
                if (id < 0 && mc.thePlayer != null) {
                    id = mc.thePlayer.getEntityId();
                    ncpPlayerId = id;
                }
                if (msg instanceof S08PacketPlayerPosLook) {
                    ncpSinceCorrection = 0;
                } else if (msg instanceof S12PacketEntityVelocity) {
                    S12PacketEntityVelocity packet = (S12PacketEntityVelocity) msg;
                    if (ncpDamageBoostSetting.getValue() && id >= 0
                            && packet.getEntityID() == id && mc.thePlayer != null
                            && ncpOffGroundTicks >= 2
                            && mc.thePlayer.fallDistance <= NCP_DAMAGE_FALL_LIMIT
                            && ncpSinceCorrection >= 61 && ncpPendingSpeed == null) {
                        ncpPendingSpeed = Math.hypot(packet.getMotionX() / 8000.0D,
                                packet.getMotionZ() / 8000.0D);
                        msg = new S12PacketEntityVelocity(id, 0, 0, 0);
                    }
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    private void armNcpChannel() {
        if (mc.getNetHandler() == null || mc.getNetHandler().getNetworkManager() == null) return;
        Channel channel = mc.getNetHandler().getNetworkManager().channel();
        if (channel == null || !channel.isActive()) return;
        ncpChannel = channel;
        if (channel.pipeline().get("potassium_ncp") == null) {
            try {
                channel.pipeline().addBefore("packet_handler", "potassium_ncp", ncpHandler);
            } catch (Exception ignored) {
                // The channel can close between the check and the add.
            }
        }
    }

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (!isEnabled() || event.manager == null) return;
        armNcpChannel();
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
        airSpeed = 0.0D;
        ncpSinceCorrection = 0;
        resetNcp();
        armNcpChannel();
    }

    @Override
    public void onDisable() {
        airSpeed = 0.0D;
        resetNcp();
        uninstallNcpInput();
        ncpChannel = null;
    }

}
