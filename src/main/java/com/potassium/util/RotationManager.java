package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The rotation the server holds, and the one place a silent aim is prepared from it.
 *
 * <p>KillAura and Scaffold each recorded the rotation the last movement packet carried, each
 * snapped its own aim onto the mouse grid, and each handed the result to SilentLook. The two
 * records were built differently (one read the player at tick end, the other read the aim
 * SilentLook had just sent) and could disagree by a tick, which is enough for a placement to
 * be judged against a rotation the server never had.</p>
 *
 * <p>Now the capture happens once, at the end of the client tick, from the aim SilentLook sent
 * if there was one and from the camera otherwise. Modules read {@link #serverYaw()} and
 * {@link #serverPitch()} for hit and placement checks, and call
 * {@link #request(String, int, float, float, SilentLook.MovementFix, boolean)} to aim, which
 * snaps from that same record.</p>
 */
public final class RotationManager {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static float serverYaw;
    private static float serverPitch;
    private static boolean serverKnown;
    private static boolean registered;

    private RotationManager() {
    }

    /** Registers the end-of-tick capture. Call once, from init. */
    public static void init() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(new Capture());
    }

    /** True once a movement packet has been observed on this connection. */
    public static boolean hasServerRotation() {
        return serverKnown;
    }

    public static float serverYaw() {
        return serverYaw;
    }

    public static float serverPitch() {
        return serverPitch;
    }

    /**
     * Asks SilentLook for this tick's server rotation, snapped to the mouse grid from the
     * rotation the server already holds so every look delta is a whole mouse count.
     *
     * @param snap false for callers that have already quantised the angle themselves
     * @return true when this aim is the one the tick's movement packet will carry
     */
    public static boolean request(String source, int priority, float yaw, float pitch,
                                  SilentLook.MovementFix fix, boolean snap) {
        float outYaw = yaw;
        float outPitch = pitch;
        if (snap) {
            float baseYaw = serverKnown ? serverYaw : yaw;
            float basePitch = serverKnown ? serverPitch : pitch;
            float[] snapped = RotationUtil.snapToMouse(yaw, pitch, baseYaw, basePitch);
            outYaw = snapped[0];
            outPitch = snapped[1];
        }
        return SilentLook.request(source, priority, outYaw, outPitch, fix);
    }

    /** Drops the record, e.g. after a respawn or a world change. */
    public static void reset() {
        serverKnown = false;
    }

    private static final class Capture {

        /**
         * Runs after SilentLook has put the camera back, so the aim it sent this tick can be
         * read without depending on handler order inside the aim window.
         */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onTickEnd(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            if (SilentLook.sentAimTick() >= 0) {
                serverYaw = SilentLook.sentAimYaw();
                serverPitch = SilentLook.sentAimPitch();
                serverKnown = true;
                return;
            }

            EntityPlayerSP player = mc.thePlayer;
            if (player == null) {
                serverKnown = false;
                return;
            }
            serverYaw = player.rotationYaw;
            serverPitch = player.rotationPitch;
            serverKnown = true;
        }
    }
}
