package com.potassium.module;

import com.potassium.settings.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.potion.Potion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Rearms the sprint bonus so repeated hits keep dealing sprint knockback.
 *
 * A sprint hit makes the server clear the attacker's sprint flag, while the client still
 * believes it is sprinting. Vanilla therefore does not send another START_SPRINTING for
 * the next hit. Legit and LegitFast reset the client state for a short time so vanilla
 * sends a real STOP/START pair; Packet sends that pair itself without changing the client
 * sprint state. DoublePacket sends that pair twice, so the server's sprint flag is re-armed
 * even if the first pair is consumed while the hit that triggered it is still processing.
 *
 * Forge fires AttackEntityEvent after PlayerControllerMP has queued the C02 attack, so
 * packets sent from onAttack are ordered after the hit that triggered them.
 */
public class MoreKB extends Module {

    public static final String MODE_LEGIT = "Legit";
    public static final String MODE_LEGIT_FAST = "LegitFast";
    public static final String MODE_PACKET = "Packet";
    public static final String MODE_DOUBLE_PACKET = "DoublePacket";

    /** Ticks the client stays out of sprint for the normal, W-tap-like reset. */
    private static final int LEGIT_OFF_TICKS = 3;
    /** One tick is the shortest reset that still produces a real STOP before START. */
    private static final int LEGIT_FAST_OFF_TICKS = 1;
    /** STOP/START pairs DoublePacket sends per hit. */
    private static final int DOUBLE_PACKET_REPEATS = 2;

    private static final float SPRINT_FORWARD_THRESHOLD = 0.8F;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_LEGIT, MODE_LEGIT_FAST, MODE_PACKET, MODE_DOUBLE_PACKET}, 0);

    private final Minecraft mc = Minecraft.getMinecraft();

    private EntityPlayerSP trackedPlayer;
    private int offTicks;
    private boolean restartPending;

    public MoreKB() {
        super("MoreKB", Category.COMBAT);
        settings.add(modeSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private String mode() {
        String mode = modeSetting.getValue();
        return mode == null ? MODE_LEGIT : mode;
    }

    @Override
    public String getTag() {
        return mode();
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!isEnabled()) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.entityPlayer != player) return;

        Entity target = event.target;
        if (target == player || !(target instanceof EntityPlayer) || !target.isEntityAlive()) return;

        // Without a sprint hit there is no sprint bonus to refresh.
        if (!player.isSprinting()) return;

        trackedPlayer = player;

        if (isPacketMode()) {
            sendSprintReset(player, MODE_DOUBLE_PACKET.equals(mode()) ? DOUBLE_PACKET_REPEATS : 1);
            clearReset();
            return;
        }

        int ticks = MODE_LEGIT_FAST.equals(mode()) ? LEGIT_FAST_OFF_TICKS : LEGIT_OFF_TICKS;
        offTicks = ticks;
        restartPending = false;
    }

    /**
     * Runs at LOWEST priority so AutoSprint cannot put sprint back on after this handler
     * has deliberately released it for a reset tick.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;

        if (trackedPlayer != player) {
            trackedPlayer = player;
            clearReset();
        }

        if (!isEnabled()) return;

        // A mode change during a Legit reset should not leave the player stuck walking.
        if (isPacketMode()) {
            if (offTicks > 0 || restartPending) {
                if (canSprint(player)) player.setSprinting(true);
                clearReset();
            }
            return;
        }

        if (offTicks > 0) {
            if (player.isSprinting()) player.setSprinting(false);
            --offTicks;
            if (offTicks == 0) {
                // Keep the client out of sprint for this packet tick so the server receives
                // STOP. START is sent on the following tick.
                restartPending = true;
            }
            return;
        }

        if (restartPending) {
            if (canSprint(player)) player.setSprinting(true);
            restartPending = false;
        }
    }

    /** Packet modes leave the client sprint state alone and tell the server to drop and
     * re-arm sprint instead. */
    private boolean isPacketMode() {
        String mode = mode();
        return MODE_PACKET.equals(mode) || MODE_DOUBLE_PACKET.equals(mode);
    }

    private void sendSprintReset(EntityPlayerSP player, int repeats) {
        if (mc.getNetHandler() == null) return;

        for (int i = 0; i < repeats; i++) {
            mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(
                    player, C0BPacketEntityAction.Action.STOP_SPRINTING));
            mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(
                    player, C0BPacketEntityAction.Action.START_SPRINTING));
        }
    }

    /** Vanilla's sprint gate, used only when restarting the client after a reset. */
    private boolean canSprint(EntityPlayerSP player) {
        if (player.movementInput == null) return false;
        if (player.movementInput.moveForward < SPRINT_FORWARD_THRESHOLD) return false;
        if (player.isUsingItem() || player.isSneaking() || player.isRiding()) return false;
        if (player.isCollidedHorizontally || player.isPotionActive(Potion.blindness)) return false;
        return player.getFoodStats().getFoodLevel() > 6 || player.capabilities.allowFlying;
    }

    private void clearReset() {
        offTicks = 0;
        restartPending = false;
    }

    @Override
    public void onEnable() {
        trackedPlayer = mc.thePlayer;
        clearReset();
    }

    @Override
    public void onDisable() {
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && (offTicks > 0 || restartPending) && canSprint(player)) {
            player.setSprinting(true);
        }
        clearReset();
        trackedPlayer = null;
    }
}
