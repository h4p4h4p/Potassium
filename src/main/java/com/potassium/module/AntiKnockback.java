package com.potassium.module;

import com.potassium.settings.ModeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

public class AntiKnockback extends Module {

    public static final String MODE_MODIFY = "Modify";
    public static final String MODE_LAG = "Lag";
    public static final String MODE_REVERSAL = "Reversal";

    private static final String HANDLER_NAME = "potassium_antiknockback";
    private static final int REVERSAL_MAX_WAIT_TICKS = 4;

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_MODIFY, MODE_LAG, MODE_REVERSAL}, 0);
    private final SliderSetting horizontalSetting = new SliderSetting("Horizontal", 0, -1.0, 1.0, 0.1);
    private final SliderSetting verticalSetting = new SliderSetting("Vertical", 0, -1.0, 1.0, 0.1);
    private final SliderSetting lagChanceSetting = new SliderSetting("Lag Chance", 0, 0, 100, 5);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final AntiKnockbackHandler handler = new AntiKnockbackHandler();
    private Channel boundChannel;
    private volatile int playerId = -1;
    private volatile boolean active;
    private volatile boolean injected;

    // Snapshot settings for the Netty thread; Minecraft settings are not thread-safe.
    private volatile boolean modifyMode;
    private volatile boolean lagMode;
    private volatile boolean reversalMode;
    private volatile double horizontal;
    private volatile double vertical;
    private volatile double lagChance;

    private volatile int clientTick;
    private volatile boolean reversalPending;
    private volatile int reversalApplyTick;
    private volatile double reversalX;
    private volatile double reversalY;
    private volatile double reversalZ;

    public AntiKnockback() {
        super("AntiKnockback", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(horizontalSetting);
        settings.add(verticalSetting);
        settings.add(lagChanceSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public String getTag() {
        if (modify()) {
            return Math.round(horizontalSetting.getValue() * 100) + "% "
                    + Math.round(verticalSetting.getValue() * 100) + "%";
        }
        if (lag()) {
            return Math.round(lagChanceSetting.getValue()) + "%";
        }
        return null;
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == horizontalSetting) return modify();
        if (setting == verticalSetting) return modify();
        if (setting == lagChanceSetting) return lag();
        return super.isSettingVisible(setting);
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean modify() {
        return MODE_MODIFY.equals(mode());
    }

    private boolean lag() {
        return MODE_LAG.equals(mode());
    }

    private boolean reversal() {
        return MODE_REVERSAL.equals(mode());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ++clientTick;
            syncSettings();
            if (!isEnabled()) return;
            if (!reversalMode) clearReversal();
            EntityPlayerSP player = mc.thePlayer;
            if (player != null) playerId = player.getEntityId();
            armChannel();

            // Fallback for a pipeline that has not accepted the handler yet. The
            // packet path is primary; this still prevents the knockback if the
            // channel was replaced or injection failed.
            if (!injected && modifyMode && player != null && player.velocityChanged) {
                player.motionX *= horizontal;
                player.motionY *= vertical;
                player.motionZ *= horizontal;
                player.velocityChanged = false;
            }
            return;
        }
        if (isEnabled() && reversalMode) handleReversal();
    }

    private void syncSettings() {
        modifyMode = modify();
        lagMode = lag();
        reversalMode = reversal();
        horizontal = horizontalSetting.getValue();
        vertical = verticalSetting.getValue();
        lagChance = lagChanceSetting.getValue();
        active = isEnabled();
    }

    /**
     * Records where the player was when the knockback arrived, then lets the packet through.
     * The next tick is spent measuring where the knockback actually moved them.
     */
    private void captureReversal() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        reversalX = player.posX;
        reversalY = player.posY;
        reversalZ = player.posZ;
        reversalApplyTick = clientTick + 1;
        reversalPending = true;
    }

    /**
     * Sends the player back with the velocity that covers the distance to the pre-knockback
     * position. Running at the end of the tick means the correction moves them on the next one.
     */
    private void handleReversal() {
        if (!reversalPending || clientTick < reversalApplyTick) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            clearReversal();
            return;
        }

        double dx = reversalX - player.posX;
        double dy = reversalY - player.posY;
        double dz = reversalZ - player.posZ;

        if (dx * dx + dy * dy + dz * dz < 1.0E-4D) {
            if (clientTick - reversalApplyTick >= REVERSAL_MAX_WAIT_TICKS) clearReversal();
            return;
        }

        player.motionX = dx;
        player.motionY = dy;
        player.motionZ = dz;
        clearReversal();
    }

    private void clearReversal() {
        reversalPending = false;
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        armChannel(manager.channel());
    }

    /**
     * A packet can arrive after the client tick but before the player tick. Catch the
     * residual velocity at player-tick start when the pipeline handler is unavailable.
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !active || injected) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player || !modifyMode || !player.velocityChanged) {
            return;
        }
        player.motionX *= horizontal;
        player.motionY *= vertical;
        player.motionZ *= horizontal;
        player.velocityChanged = false;
    }

    /**
     * The handler is sharable and stays installed while the module is disabled. That
     * avoids both the old "failed to attach once, never retry" path and the disable/
     * re-enable race that could remove a freshly attached handler.
     */
    private void armChannel(Channel channel) {
        if (channel == null || !channel.isActive()) return;
        boundChannel = channel;

        if (channel.pipeline().get(HANDLER_NAME) == null) {
            try {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                injected = true;
            } catch (Exception ignored) {
                injected = false;
            }
        } else {
            injected = channel.pipeline().get(HANDLER_NAME) == handler;
        }
    }

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (!isEnabled() || event.manager == null) return;
        syncSettings();
        armChannel(event.manager.channel());
    }

    @Override
    public void onEnable() {
        clearReversal();
        syncSettings();
        active = true;
        if (mc.thePlayer != null) playerId = mc.thePlayer.getEntityId();
        armChannel();
    }

    @Override
    public void onDisable() {
        clearReversal();
        active = false;
        injected = false;
        boundChannel = null;
    }

    @ChannelHandler.Sharable
    private class AntiKnockbackHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (active && msg instanceof Packet) {
                int id = playerId;
                if (id < 0 && mc.thePlayer != null) {
                    id = mc.thePlayer.getEntityId();
                    playerId = id;
                }
                if (msg instanceof S12PacketEntityVelocity) {
                    S12PacketEntityVelocity p = (S12PacketEntityVelocity) msg;
                    if (id >= 0 && p.getEntityID() == id) {
                        if (lagMode && Math.random() * 100.0D < lagChance) {
                            return;
                        }
                        if (modifyMode) {
                            msg = new S12PacketEntityVelocity(id,
                                    p.getMotionX() / 8000.0 * horizontal,
                                    p.getMotionY() / 8000.0 * vertical,
                                    p.getMotionZ() / 8000.0 * horizontal);
                        }
                        if (reversalMode) captureReversal();
                    }
                } else if (msg instanceof S27PacketExplosion) {
                    S27PacketExplosion p = (S27PacketExplosion) msg;
                    if (lagMode && Math.random() * 100.0D < lagChance) {
                        return;
                    }
                    if (modifyMode) {
                        msg = new S27PacketExplosion(p.getX(), p.getY(), p.getZ(), p.getStrength(),
                                p.getAffectedBlockPositions(),
                                new Vec3(p.func_149149_c() * horizontal,
                                        p.func_149144_d() * vertical,
                                        p.func_149147_e() * horizontal));
                    }
                    if (reversalMode) captureReversal();
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}