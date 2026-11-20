package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Turns the camera without turning the player.
 *
 * Vanilla only gives the camera one set of angles, the player's ones, so the free look is built
 * out of three pieces:
 *
 * - The player is frozen on the angles he had when the module was enabled. ClientTickEvent runs
 *   before the tick that moves and sends rotation, so movement and packets keep using the frozen
 *   angles.
 * - The mouse is read a second time from Mouse.getDX/getDY, which still holds this frame's delta,
 *   and accumulated into camera angles that are never written back to the player.
 * - EntityViewRenderEvent.CameraSetup replaces the camera rotation, which is what actually rotates
 *   the view.
 *
 * Third person is forced while looking, because the first person hand is oriented from
 * ActiveRenderInfo rather than from CameraSetup and would stay stuck on the frozen angles.
 *
 * Based on the event based approach of the MIT licensed FreeL00k mod, kept under
 * opensources/freel00k-mit: mouse capture from Mouse.getDX, the camera override in CameraSetup,
 * the frozen head/body yaw and the yaw limit. Perspective switching, the pitch lock and the
 * cleanup after leaving a world are our own.
 */
public class FreeLook extends Module {

    public static final String MODE_BACK = "Back";
    public static final String MODE_FRONT = "Front";

    private final ModeSetting perspectiveSetting =
            new ModeSetting("Perspective", new String[]{MODE_BACK, MODE_FRONT}, 0);
    private final SliderSetting maxYawSetting = new SliderSetting("Max Yaw", 360.0D, 90.0D, 360.0D, 90.0D);
    private final BooleanSetting invertPitchSetting = new BooleanSetting("Invert Pitch", false);
    private final BooleanSetting lockPitchSetting = new BooleanSetting("Lock Pitch", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    private EntityPlayerSP subject;
    private float cameraYaw;
    private float cameraPitch;
    private float frozenYaw;
    private float frozenPitch;
    private int savedPerspective;

    public FreeLook() {
        super("FreeLook", Category.RENDER);
        settings.add(perspectiveSetting);
        settings.add(maxYawSetting);
        settings.add(invertPitchSetting);
        settings.add(lockPitchSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Freezes the player and forces the perspective before the tick that moves and sends rotation. */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        if (mc.thePlayer == null || mc.theWorld == null) {
            // Left the world while looking; release the camera before the player reference is gone.
            setEnabled(false);
            return;
        }

        if (subject != mc.thePlayer) begin(mc.thePlayer);

        freeze(mc.thePlayer);
        if (mc.currentScreen == null) forcePerspective();
    }

    /** Reads this frame's mouse delta into the camera angles and leaves the model facing forward. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled() || subject == null || mc.thePlayer == null) return;

        captureMouse();
        applyCameraToPlayer(mc.thePlayer);
    }

    /** The camera angles vanilla renders with; this is the part that actually rotates the view. */
    @SubscribeEvent
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        if (!isEnabled() || !(event.entity instanceof EntityPlayerSP)) return;

        if (MODE_FRONT.equals(perspectiveSetting.getValue())) {
            // Vanilla mirrors the front perspective by turning the view around; match that.
            event.yaw = cameraYaw + 180.0F;
            event.pitch = -cameraPitch;
        } else {
            event.yaw = cameraYaw;
            event.pitch = cameraPitch;
        }
        event.roll = 0.0F;
    }

    private void captureMouse() {
        if (!mc.inGameHasFocus || !Display.isActive()) return;

        int deltaX = Mouse.getDX();
        int deltaY = Mouse.getDY();
        if (deltaX == 0 && deltaY == 0) return;

        // Same curve vanilla uses for the mouse, including the 0.15 turn scale.
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float speed = sensitivity * sensitivity * sensitivity * 8.0F * 0.15F;

        cameraYaw = MathHelper.wrapAngleTo180_float(cameraYaw + deltaX * speed);

        float pitchDelta = deltaY * speed;
        if (invertPitchSetting.getValue()) pitchDelta = -pitchDelta;
        cameraPitch += pitchDelta;
        if (lockPitchSetting.getValue()) {
            cameraPitch = MathHelper.clamp_float(cameraPitch, -90.0F, 90.0F);
        }

        clampYaw();
    }

    /** Keeps the camera within the configured degrees of the direction the player really faces. */
    private void clampYaw() {
        float maxYaw = (float) maxYawSetting.getValue();
        if (maxYaw >= 360.0F) return;

        float offset = MathHelper.wrapAngleTo180_float(cameraYaw - frozenYaw);
        if (offset > maxYaw) offset = maxYaw;
        if (offset < -maxYaw) offset = -maxYaw;
        cameraYaw = MathHelper.wrapAngleTo180_float(frozenYaw + offset);
    }

    private void begin(EntityPlayerSP player) {
        subject = player;
        frozenYaw = player.rotationYaw;
        frozenPitch = player.rotationPitch;
        cameraYaw = frozenYaw;
        cameraPitch = frozenPitch;
        savedPerspective = mc.gameSettings.thirdPersonView;
        forcePerspective();
    }

    private void forcePerspective() {
        int wanted = MODE_FRONT.equals(perspectiveSetting.getValue()) ? 2 : 1;
        if (mc.gameSettings.thirdPersonView == wanted) return;
        mc.gameSettings.thirdPersonView = wanted;
        if (mc.renderGlobal != null) mc.renderGlobal.setDisplayListEntitiesDirty();
    }

    /** Puts the real angles back, so the next tick moves, aims and reports where the player looks. */
    private void freeze(EntityPlayerSP player) {
        player.rotationYaw = frozenYaw;
        player.rotationPitch = frozenPitch;
        player.prevRotationYaw = frozenYaw;
        player.prevRotationPitch = frozenPitch;
        player.rotationYawHead = frozenYaw;
        player.prevRotationYawHead = frozenYaw;
        player.renderYawOffset = frozenYaw;
        player.prevRenderYawOffset = frozenYaw;
    }

    /** Camera angles on the player for rendering only; the head and body stay on the frozen yaw. */
    private void applyCameraToPlayer(EntityPlayerSP player) {
        player.rotationYaw = cameraYaw;
        player.rotationPitch = cameraPitch;
        player.prevRotationYaw = cameraYaw;
        player.prevRotationPitch = cameraPitch;
        player.rotationYawHead = frozenYaw;
        player.prevRotationYawHead = frozenYaw;
        player.renderYawOffset = frozenYaw;
        player.prevRenderYawOffset = frozenYaw;
    }

    @Override
    public void onEnable() {
        subject = null;
        if (mc.thePlayer != null && mc.theWorld != null) begin(mc.thePlayer);
    }

    @Override
    public void onDisable() {
        EntityPlayerSP player = mc.thePlayer;
        if (subject != null && player == subject) freeze(player);
        subject = null;
        mc.gameSettings.thirdPersonView = savedPerspective;
        if (mc.renderGlobal != null) mc.renderGlobal.setDisplayListEntitiesDirty();
    }

    @Override
    public String getTag() {
        return perspectiveSetting.getDisplayValue();
    }
}
