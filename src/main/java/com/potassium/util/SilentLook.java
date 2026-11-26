package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The rotation the server is told to believe, shared by every module that aims somewhere
 * other than the camera.
 *
 * Modules used to hand the server a look packet of their own before an action and another
 * one to put the camera back afterwards. That is two extra position updates inside a single
 * tick, which packet rate checks read as a timer violation, and the immediate flip back made
 * the attack look like it was thrown facing away from the target.
 *
 * Instead the aim is written into the player's rotation for the one moment vanilla builds
 * its movement packet: Forge fires PlayerTickEvent.END at the end of EntityPlayer#onUpdate,
 * and EntityPlayerSP#onUpdate calls onUpdateWalkingPlayer right afterwards. The camera is put
 * back at the end of the client tick, before anything is rendered, and the packet that went
 * out carries the aim. Modules then act at the end of the tick, so their own packet follows
 * the rotation the server has just been given rather than the previous one.
 *
 * Nothing is injected at all. When vanilla sends a movement packet the aim rides along with
 * it, and when vanilla sends no look packet that is because the rotation is already exactly
 * the one the server holds, so there is nothing left to tell it.
 *
 * <p>An earlier version sent a position-and-look packet for those unchanged ticks. That put
 * two flying packets inside one tick, and Vulcan reads both of them: Timer (D) credits 50 ms
 * per flying packet, so the duplicate banked half a tick every tick, and Flight (A/B) predicts
 * the Y delta from the previous one, so the duplicate's unchanged Y read as a zero delta while
 * airborne. KillAura therefore flagged Timer and Flight without ever moving the player.</p>
 *
 * <p>Movement correction has the three modes LiquidBounce exposes. None walks along the
 * camera while the server holds the aim, Strict walks along the aim, and Silent walks along
 * the camera too but hands vanilla the keys that produce that direction in the aim's frame,
 * so the movement packet still looks like a legal input for the rotation it carries. Silent
 * does that by wrapping the tick's MovementInput: vanilla samples the keys on that object and
 * then moves, which is the one window a client without mixins can reach.</p>
 */
public final class SilentLook {

    /** LiquidBounce's MovementCorrection: how the walk is kept consistent with the aim. */
    public enum MovementFix {
        /** No correction: the walk stays on the camera, the packet carries the aim. */
        NONE,
        /** The walk stays on the camera, but the keys are turned into the aim's frame. */
        SILENT,
        /** The walk itself follows the aim, with the keys untouched. */
        STRICT
    }

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static SilentLook instance;

    private static volatile float aimYaw;
    private static volatile float aimPitch;
    private static volatile boolean requested;
    private static volatile MovementFix movementFix = MovementFix.NONE;
    /** True while the camera rotation has been replaced by the aim for the packet. */
    private static volatile boolean applied;
    /** True while movement is being computed on the aim instead of the camera. */
    private static volatile boolean strictApplied;

    private static volatile float savedYaw;
    private static volatile float savedPitch;
    private static volatile float savedPrevYaw;
    private static volatile float savedPrevPitch;
    private static volatile float savedHeadYaw;
    private static volatile float savedPrevHeadYaw;
    private static volatile float savedRenderYawOffset;
    private static volatile float savedPrevRenderYawOffset;
    /** The first-person arm smoothing vanilla updates inside the strict-aim window. */
    private static volatile float savedRenderArmYaw;
    private static volatile float savedRenderArmPitch;
    private static volatile float savedPrevRenderArmYaw;
    private static volatile float savedPrevRenderArmPitch;

    /** The aim the movement packet carried this tick, or -1 when no aim was requested. */
    private static volatile int sentAimTick = -1;
    private static volatile float sentAimYaw;
    private static volatile float sentAimPitch;

    private SilentLook() {
    }

    /**
     * Asks for this tick's server rotation. Renew it every tick that a module is aiming;
     * a tick without a request simply leaves the camera rotation in place.
     *
     * @param fix LiquidBounce's movement correction for this aim: Strict moves along the aim,
     *            Silent keeps the camera direction but turns the keys into the aim's frame,
     *            None sends the aim without changing how the player walks
     */
    public static void request(float yaw, float pitch, MovementFix fix) {
        register();
        aimYaw = yaw;
        aimPitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        movementFix = fix == null ? MovementFix.NONE : fix;
        requested = true;
    }

    /** Whether a module has already claimed this tick's server rotation. */
    public static boolean isRequested() {
        return requested;
    }

    /**
     * The tick the aim was written into the packet build, or -1 when no module aimed.
     *
     * Modules that display the server rotation read this instead of comparing outgoing
     * packets against the camera: an ordinary look and a silent aim look the same in the
     * pipeline, and the camera read on the Netty thread is not the one the packet was built
     * from. A tick with no entry here is a tick with no silent rotation, full stop.
     */
    public static int sentAimTick() {
        return sentAimTick;
    }

    public static float sentAimYaw() {
        return sentAimYaw;
    }

    public static float sentAimPitch() {
        return sentAimPitch;
    }

    /** The rotation the player is really looking along, ignoring any silent aim. */
    public static float cameraYaw() {
        if (applied || strictApplied) return savedYaw;
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? 0.0F : player.rotationYaw;
    }

    public static float cameraPitch() {
        if (applied || strictApplied) return savedPitch;
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? 0.0F : player.rotationPitch;
    }

    private static void register() {
        if (instance != null) return;
        instance = new SilentLook();
        MinecraftForge.EVENT_BUS.register(instance);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        requested = false;
        sentAimTick = -1;
        movementFix = MovementFix.NONE;
        unwrapInput();
        // The player cannot be restored if it was replaced mid-tick, so never stay applied.
        if (applied) restore();
        else if (strictApplied) restoreStrict();
    }

    /**
     * LiquidBounce's Strict and Silent movement correction.
     *
     * LiquidBounce implements both by handing {@code Entity#moveRelative} the server rotation
     * instead of the camera yaw, so the velocity this tick matches the look packet the server
     * already received. It never writes the aim into the player's real rotation. 1.8.9 reads
     * that yaw from {@code Entity#rotationYaw} inside {@code moveFlying}, and there is no
     * mixin in this client, so the aim is put in place for exactly the window Forge opens
     * around a player update: {@code PlayerTickEvent.START} runs before
     * {@code EntityLivingBase#onUpdate} computes the walk, and {@code END} runs straight
     * after it, before {@code EntityPlayerSP#onUpdate} builds the packet.
     *
     * Silent installs the input wrapper here as well: the keys are sampled inside that same
     * window, after this handler, so the wrapper is the only place they can still be turned.
     *
     * Sprint is untouched: vanilla gates it on the raw key input, which this does not change.
     * Every rotation the swap can leak into is saved, and the camera is back in place before
     * the rest of the tick and before anything is rendered.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerTickStart(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!requested || movementFix == MovementFix.NONE || strictApplied) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player || player.movementInput == null) return;
        if (player.isRiding() || mc.currentScreen != null) return;

        saveRotation(player);
        player.rotationYaw = aimYaw;
        strictApplied = true;
        if (movementFix == MovementFix.SILENT) wrapInput();
    }

    /** Runs after the player's own update and before vanilla builds the movement packet. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTickEnd(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !requested || applied) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;
        // A riding player sends its look from a code path this cannot reach.
        if (player.isRiding() || mc.currentScreen != null) return;

        // Movement is done. Take the aim back out of the player so the rest of the tick and
        // the renderer only ever see the camera, then put it back for the packet alone.
        if (strictApplied) restoreStrict();
        saveRotation(player);
        player.rotationYaw = aimYaw;
        player.rotationPitch = aimPitch;
        applied = true;
        // This is the rotation the movement packet is about to be built with.
        sentAimTick = player.ticksExisted;
        sentAimYaw = aimYaw;
        sentAimPitch = aimPitch;
    }

    /** Puts the camera back before the frame is rendered. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTickEndRestore(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (applied) restore();
        else if (strictApplied) restoreStrict();
    }

    private static void saveRotation(EntityPlayerSP player) {
        savedYaw = player.rotationYaw;
        savedPitch = player.rotationPitch;
        savedPrevYaw = player.prevRotationYaw;
        savedPrevPitch = player.prevRotationPitch;
        savedHeadYaw = player.rotationYawHead;
        savedPrevHeadYaw = player.prevRotationYawHead;
        savedRenderYawOffset = player.renderYawOffset;
        savedPrevRenderYawOffset = player.prevRenderYawOffset;
        savedRenderArmYaw = player.renderArmYaw;
        savedRenderArmPitch = player.renderArmPitch;
        savedPrevRenderArmYaw = player.prevRenderArmYaw;
        savedPrevRenderArmPitch = player.prevRenderArmPitch;
    }

    private static void restore() {
        boolean strict = strictApplied;
        applied = false;
        strictApplied = false;
        EntityPlayerSP player = mc.thePlayer;
        unwrapInput();
        if (player == null) return;
        player.rotationYaw = savedYaw;
        player.rotationPitch = savedPitch;
        player.prevRotationYaw = savedPrevYaw;
        player.prevRotationPitch = savedPrevPitch;
        player.rotationYawHead = savedHeadYaw;
        player.prevRotationYawHead = savedPrevHeadYaw;
        player.renderYawOffset = savedRenderYawOffset;
        player.prevRenderYawOffset = savedPrevRenderYawOffset;
        restoreArmSway(player, strict);
    }

    /**
     * Ends the Strict window without touching the aim the packet still has to carry.
     *
     * The previous rotation is put back along with the current one on purpose. The player's
     * update copies rotationYaw into prevRotationYaw while the Strict aim is in place, and the
     * renderer interpolates the camera from prevRotationYaw to rotationYaw every frame. Left
     * alone, that interpolation starts at the aim and runs to the camera each tick: the view
     * is dragged onto the target and snapped back twenty times a second, which is exactly what
     * made Strict impossible to steer. Silent rotations never leaked this way because their
     * aim is written after the copy has already happened.
     */
    private static void restoreStrict() {
        strictApplied = false;
        EntityPlayerSP player = mc.thePlayer;
        unwrapInput();
        if (player == null) return;
        player.rotationYaw = savedYaw;
        player.rotationPitch = savedPitch;
        player.prevRotationYaw = savedPrevYaw;
        player.prevRotationPitch = savedPrevPitch;
        player.rotationYawHead = savedHeadYaw;
        player.prevRotationYawHead = savedPrevHeadYaw;
        player.renderYawOffset = savedRenderYawOffset;
        player.prevRenderYawOffset = savedPrevRenderYawOffset;
        restoreArmSway(player, true);
    }

    /**
     * Vanilla updates the first-person arm toward the player's current rotation inside the same
     * player update the strict aim is installed for. Restoring only the rotation would leave
     * {@code renderArmYaw}/{@code renderArmPitch} pulled toward the aim, making the viewmodel
     * drift on screen while the camera itself never moves. When the strict aim was installed,
     * recompute the arm smoothing vanilla would have produced from the camera; when it was not,
     * put back the values saved before the packet-only aim.
     */
    private static void restoreArmSway(EntityPlayerSP player, boolean strict) {
        boolean armUpdated = strict && mc.getRenderViewEntity() == player
                && !player.isPlayerSleeping() && player.getHealth() > 0.0F;
        if (armUpdated) {
            player.prevRenderArmYaw = savedRenderArmYaw;
            player.renderArmYaw = savedRenderArmYaw
                    + (savedYaw - savedRenderArmYaw) * 0.5F;
            player.prevRenderArmPitch = savedRenderArmPitch;
            player.renderArmPitch = savedRenderArmPitch
                    + (savedPitch - savedRenderArmPitch) * 0.5F;
            return;
        }

        player.prevRenderArmYaw = savedPrevRenderArmYaw;
        player.renderArmYaw = savedRenderArmYaw;
        player.prevRenderArmPitch = savedPrevRenderArmPitch;
        player.renderArmPitch = savedRenderArmPitch;
    }

    /**
     * Swaps in the input that samples the keys and then turns them onto the aim. Vanilla calls
     * {@code updatePlayerMoveState} on this object after the tick's keys have been read, so the
     * turned values are what the player moves with; the player's own input goes back at the end
     * of the window, before the packet is built and before any other module reads it.
     */
    /**
     * Registers the Silent key rotation on the shared movement-input stack. The stack rebuilds
     * the chain in priority order, so NoSlowdown's pre-scale and Scaffold's sneak scaling compose
     * the same way regardless of which module installed its layer first.
     */
    private static void wrapInput() {
        MovementInputStack.install("SilentLook", MovementInputStack.PRIORITY_MOVEMENT_FIX,
                SilentInput::new);
    }

    private static void unwrapInput() {
        MovementInputStack.uninstall("SilentLook");
    }

    /**
     * One tick's input with LiquidBounce's Silent correction applied.
     *
     * <p>The delegate samples the keys against the camera, then vanilla moves the player along
     * {@code rotationYaw}, which the strict window has set to the aim. Turning the key vector
     * by {@code camera - aim} before that move keeps the direction the player asked for while
     * making the keys match the rotation the server was sent. That is what separates Silent
     * from None: None walks the camera direction under an aim the server believes, and Strict
     * walks along the aim itself.</p>
     */
    private static final class SilentInput extends MovementInput {

        private final MovementInput delegate;

        private SilentInput(MovementInput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void updatePlayerMoveState() {
            delegate.updatePlayerMoveState();
            moveStrafe = delegate.moveStrafe;
            moveForward = delegate.moveForward;
            jump = delegate.jump;
            sneak = delegate.sneak;
            if (requested && movementFix == MovementFix.SILENT) turnKeysOntoAim();
        }

        private void turnKeysOntoAim() {
            float strafe = moveStrafe;
            float forward = moveForward;
            if (strafe == 0.0F && forward == 0.0F) return;

            float delta = (float) Math.toRadians(
                    MathHelper.wrapAngleTo180_float(savedYaw - aimYaw));
            float sin = MathHelper.sin(delta);
            float cos = MathHelper.cos(delta);
            float turnedStrafe = strafe * cos - forward * sin;
            float turnedForward = strafe * sin + forward * cos;
            if (Math.abs(turnedStrafe) < 1.0E-4F && Math.abs(turnedForward) < 1.0E-4F) return;

            // A keyboard only produces the eight directions, so the nearest one is the key
            // combination whose movement in the aim's frame is closest to the camera's input.
            // The length is kept: sneaking scales both keys by 0.3 and must stay that way.
            double snapped = Math.round(Math.atan2(turnedForward, turnedStrafe)
                    / (Math.PI / 4.0D)) * (Math.PI / 4.0D);
            float length = MathHelper.sqrt_float(strafe * strafe + forward * forward);
            moveStrafe = (float) (Math.cos(snapped) * length);
            moveForward = (float) (Math.sin(snapped) * length);
        }
    }

}
