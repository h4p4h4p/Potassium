package com.potassium.module;

import com.potassium.settings.ModeSetting;
import com.potassium.util.SilentLook;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Shows where the server thinks the player is aiming, without touching the camera.
 *
 * <p>Modules such as Scaffold and KillAura in Silent mode only write their aim into outgoing
 * movement packets, so the camera stays where the player is looking while the server is told
 * something else. This turns the character's head and body onto the rotation the server was
 * actually given, for the render that follows the tick the packet went out on, and puts the
 * player's own angles back before the frame ends.</p>
 *
 * <p>The aim comes from the packet layer's own record of what each movement packet carried,
 * not from sniffing the pipeline: a packet compared against a camera read on another thread
 * can be a look the player made a frame earlier and would be shown as a silent aim. A tick
 * with no silent rotation in it shows nothing at all, so the model never sits on an aim the
 * server was not told about.</p>
 */
public class Rotations extends Module {

    public static final String PART_HEAD = "Head";
    public static final String PART_BODY = "Body";
    public static final String PART_BOTH = "Both";

    private final ModeSetting bodyPartSetting =
            new ModeSetting("Body Part", new String[]{PART_HEAD, PART_BODY, PART_BOTH}, 2);

    private final Minecraft mc = Minecraft.getMinecraft();

    private boolean active;
    private float serverYaw;
    private float serverPitch;

    private float savedYawHead;
    private float savedPrevYawHead;
    private float savedRenderYawOffset;
    private float savedPrevRenderYawOffset;
    private float savedPitch;
    private float savedPrevPitch;
    private boolean overriding;

    public Rotations() {
        super("Rotations", Category.RENDER);
        settings.add(bodyPartSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * Runs at the end of the tick, after the movement packet has been built, so the aim read
     * here is the one the server was just sent. No silent rotation this tick means no aim.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            reset();
            return;
        }

        if (SilentLook.sentAimTick() < 0) {
            active = false;
            return;
        }

        active = true;
        serverYaw = SilentLook.sentAimYaw();
        serverPitch = SilentLook.sentAimPitch();
    }

    /** Renders the player model on the server aim, then puts the real angles back. */
    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!isEnabled() || !active) return;
        EntityPlayer player = event.entityPlayer;
        if (player != mc.thePlayer) return;

        savedYawHead = player.rotationYawHead;
        savedPrevYawHead = player.prevRotationYawHead;
        savedRenderYawOffset = player.renderYawOffset;
        savedPrevRenderYawOffset = player.prevRenderYawOffset;
        savedPitch = player.rotationPitch;
        savedPrevPitch = player.prevRotationPitch;
        overriding = true;

        float yaw = serverYaw;
        float pitch = serverPitch;

        if (!PART_BODY.equals(bodyPartSetting.getValue())) {
            player.rotationYawHead = yaw;
            player.prevRotationYawHead = yaw;
            player.rotationPitch = pitch;
            player.prevRotationPitch = pitch;
        }
        if (!PART_HEAD.equals(bodyPartSetting.getValue())) {
            player.renderYawOffset = yaw;
            player.prevRenderYawOffset = yaw;
        }
    }

    @SubscribeEvent
    public void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (!overriding) return;
        EntityPlayer player = event.entityPlayer;
        if (player != mc.thePlayer) return;

        overriding = false;
        player.rotationYawHead = savedYawHead;
        player.prevRotationYawHead = savedPrevYawHead;
        player.renderYawOffset = savedRenderYawOffset;
        player.prevRenderYawOffset = savedPrevRenderYawOffset;
        player.rotationPitch = savedPitch;
        player.prevRotationPitch = savedPrevPitch;
    }

    private void reset() {
        active = false;
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }
}
