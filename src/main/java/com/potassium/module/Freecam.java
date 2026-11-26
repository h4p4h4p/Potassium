package com.potassium.module;

import com.mojang.authlib.GameProfile;
import com.potassium.Potassium;
import com.potassium.settings.SliderSetting;
import com.potassium.util.MovementInputStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MouseHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;

/**
 * Detaches the camera from the body so it can fly through the loaded world.
 *
 * <p>Vanilla has one entity for both the body and the camera, so a free camera needs a second
 * one. The camera is a dummy {@link EntityOtherPlayerMP} that is never added to the world:
 * only its position and rotation are written, and {@code Minecraft.renderViewEntity} points
 * at it, which is what the renderer, the frustum and the fog ask for. Because the camera is
 * not the player, the real body keeps standing where it was and is still rendered.</p>
 *
 * <p>The player itself is not moved at all:</p>
 * <ul>
 *   <li>{@link #onPlayerTick} installs a frozen input layer on the shared movement stack,
 *   outside every other layer, so no key reaches the body while the camera is out.</li>
 *   <li>{@link #onEnable} swaps {@code mc.mouseHelper} for a silent helper. Vanilla still
 *   processes the mouse every frame, but sees a zero delta, so the body does not turn and no
 *   rotation packets change. The same raw deltas are read here through a second helper.</li>
 *   <li>{@link #onRenderTick} applies that look and moves the camera with frame time, then
 *   writes it into the dummy entity before the world is drawn.</li>
 * </ul>
 *
 * <p>No packets are touched. The server keeps seeing the player standing still, so no
 * teleport corrections are needed; the flip side is that the body is still subject to
 * gravity and knockback while the camera is away.</p>
 *
 * <p>Approach cross-checked against the MIT licensed {@code sndyx/freecam} (Forge 1.8.9):
 * the dummy player as render view entity, the silent mouse helper and camera relative
 * movement. Its packet suppression, fake-body entity and chunk-frustum mixin are not needed
 * here, because this module never moves the real player.</p>
 */
public class Freecam extends Module {

    private static final String SOURCE = "Freecam";
    /** A hitch must not fling the camera across the map; four ticks is one slow frame. */
    private static final double MAX_FRAME_TICKS = 4.0D;
    private static final float DIAGONAL = 0.70710677F;

    private final SliderSetting speedSetting = new SliderSetting("Speed", 1.0D, 0.1D, 5.0D, 0.1D);

    private final Minecraft mc = Minecraft.getMinecraft();
    /** Reads this frame's deltas while vanilla's helper is silent. */
    private final MouseHelper capture = new MouseHelper();

    private EntityPlayerSP subject;
    private CameraEntity camera;
    private MouseHelper savedMouseHelper;
    private int savedPerspective;
    private float frozenYaw;
    private float frozenPitch;

    private long lastFrameNanos;
    private double camX;
    private double camY;
    private double camZ;
    private float camYaw;
    private float camPitch;

    public Freecam() {
        super("Freecam", Category.RENDER);
        settings.add(speedSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void onEnable() {
        savedPerspective = mc.gameSettings.thirdPersonView;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // The two camera modules fight over the perspective, so keep only this one.
        disableFreeLook();

        subject = mc.thePlayer;
        frozenYaw = subject.rotationYaw;
        frozenPitch = subject.rotationPitch;
        camX = subject.posX;
        camY = subject.posY;
        camZ = subject.posZ;
        camYaw = frozenYaw;
        camPitch = frozenPitch;

        mc.gameSettings.thirdPersonView = 0;
        if (mc.renderGlobal != null) mc.renderGlobal.setDisplayListEntitiesDirty();

        camera = new CameraEntity(mc.theWorld, subject.getGameProfile());
        syncCamera();
        mc.setRenderViewEntity(camera);

        savedMouseHelper = mc.mouseHelper;
        mc.mouseHelper = new SilentMouseHelper();

        // Stop the walk that was in progress when the camera left the body.
        subject.motionX = 0.0D;
        subject.motionZ = 0.0D;
        lastFrameNanos = System.nanoTime();
    }

    @Override
    public void onDisable() {
        MovementInputStack.uninstall(SOURCE);

        if (savedMouseHelper != null && mc.mouseHelper instanceof SilentMouseHelper) {
            mc.mouseHelper = savedMouseHelper;
        }
        savedMouseHelper = null;

        if (camera != null && mc.getRenderViewEntity() == camera) {
            if (mc.thePlayer != null) {
                mc.setRenderViewEntity(mc.thePlayer);
            } else {
                // Left the world; the next world installs a fresh render view entity.
                mc.setRenderViewEntity(null);
            }
        }
        camera = null;
        if (subject != null) freezeBody(subject);
        subject = null;

        mc.gameSettings.thirdPersonView = savedPerspective;
        if (mc.renderGlobal != null) mc.renderGlobal.setDisplayListEntitiesDirty();
    }

    /** Drops the module when the player or the world it was opened on goes away. */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) return;

        if (mc.thePlayer == null || mc.theWorld == null
                || subject != mc.thePlayer || camera == null || camera.worldObj != mc.theWorld) {
            setEnabled(false);
            return;
        }

        // The previous frame borrowed the body angles for rendering; hand the frozen ones back
        // before this tick moves, aims or reports the player.
        freezeBody(mc.thePlayer);

        // Hold first person; the dummy entity has no third person arm to orbit.
        if (mc.gameSettings.thirdPersonView != 0) {
            mc.gameSettings.thirdPersonView = 0;
            if (mc.renderGlobal != null) mc.renderGlobal.setDisplayListEntitiesDirty();
        }
    }

    /**
     * Freezes the body for the tick that moves and reports it. The layer is installed before
     * vanilla samples the keys and removed once the tick is over, the way NoSlowdown does, so it
     * never lingers on the stack.
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player == null || event.player != mc.thePlayer) return;

        if (event.phase == TickEvent.Phase.START) {
            if (isEnabled()) {
                MovementInputStack.install(SOURCE, MovementInputStack.PRIORITY_FREECAM,
                        FrozenInput::new);
            }
        } else {
            MovementInputStack.uninstall(SOURCE);
        }
    }

    /** Looks and moves the camera once per frame, then hands it to the renderer. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) return;
        if (camera == null || mc.thePlayer == null || mc.theWorld == null
                || camera.worldObj != mc.theWorld) {
            return;
        }

        long now = System.nanoTime();
        double deltaTicks = (now - lastFrameNanos) / 50_000_000.0D;
        lastFrameNanos = now;
        if (deltaTicks < 0.0D) deltaTicks = 0.0D;
        if (deltaTicks > MAX_FRAME_TICKS) deltaTicks = MAX_FRAME_TICKS;

        updateLook();
        moveCamera(deltaTicks);
        syncCamera();
        poseBodyForRender();

        if (mc.getRenderViewEntity() != camera) mc.setRenderViewEntity(camera);
    }

    /**
     * Vanilla billboards particles and a few overlays from the player's angles rather than from
     * the render view entity, so the body carries the camera angles for the frame. Only the aim
     * is borrowed; head and body yaw stay on the frozen values so the model does not spin.
     */
    private void poseBodyForRender() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;

        player.rotationYaw = camYaw;
        player.prevRotationYaw = camYaw;
        player.rotationPitch = camPitch;
        player.prevRotationPitch = camPitch;
        player.rotationYawHead = frozenYaw;
        player.prevRotationYawHead = frozenYaw;
        player.renderYawOffset = frozenYaw;
        player.prevRenderYawOffset = frozenYaw;
    }

    /** Puts the player's own angles back, so ticks move, aim and packet the original heading. */
    private void freezeBody(EntityPlayerSP player) {
        if (player == null) return;

        player.rotationYaw = frozenYaw;
        player.prevRotationYaw = frozenYaw;
        player.rotationPitch = frozenPitch;
        player.prevRotationPitch = frozenPitch;
        player.rotationYawHead = frozenYaw;
        player.prevRotationYawHead = frozenYaw;
        player.renderYawOffset = frozenYaw;
        player.prevRenderYawOffset = frozenYaw;
    }

    /** Same curve as vanilla, but accumulated into the camera instead of the player. */
    private void updateLook() {
        capture.mouseXYChange();
        int deltaX = capture.deltaX;
        int deltaY = capture.deltaY;
        if (deltaX == 0 && deltaY == 0) return;
        if (mc.currentScreen != null || !mc.inGameHasFocus || !Display.isActive()) return;

        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float speed = sensitivity * sensitivity * sensitivity * 8.0F * 0.15F;

        camYaw = MathHelper.wrapAngleTo180_float(camYaw + deltaX * speed);

        // Vanilla's setAngles subtracts the vertical delta: positive pitch looks down, so a
        // mouse push up has to lower it.
        float pitchDelta = deltaY * speed;
        if (mc.gameSettings.invertMouse) pitchDelta = -pitchDelta;
        camPitch = MathHelper.clamp_float(camPitch - pitchDelta, -90.0F, 90.0F);
    }

    /** WASD flies along the view, jump and sneak lift and drop, sprint is not special. */
    private void moveCamera(double deltaTicks) {
        if (mc.currentScreen != null) return;

        GameSettings settings = mc.gameSettings;
        float forward = 0.0F;
        float strafe = 0.0F;
        if (GameSettings.isKeyDown(settings.keyBindForward)) forward += 1.0F;
        if (GameSettings.isKeyDown(settings.keyBindBack)) forward -= 1.0F;
        if (GameSettings.isKeyDown(settings.keyBindLeft)) strafe += 1.0F;
        if (GameSettings.isKeyDown(settings.keyBindRight)) strafe -= 1.0F;

        double up = 0.0D;
        if (GameSettings.isKeyDown(settings.keyBindJump)) up += 1.0D;
        if (GameSettings.isKeyDown(settings.keyBindSneak)) up -= 1.0D;

        if (forward == 0.0F && strafe == 0.0F && up == 0.0D) return;
        if (forward != 0.0F && strafe != 0.0F) {
            forward *= DIAGONAL;
            strafe *= DIAGONAL;
        }

        double step = speedSetting.getValue() * deltaTicks;
        double yaw = Math.toRadians(camYaw);
        double pitch = Math.toRadians(camPitch);
        double cosPitch = Math.cos(pitch);

        // Minecraft yaw 0 faces +Z, yaw 90 faces -X; left of that is (cos, 0, sin).
        double lookX = -Math.sin(yaw) * cosPitch;
        double lookY = -Math.sin(pitch);
        double lookZ = Math.cos(yaw) * cosPitch;
        double leftX = Math.cos(yaw);
        double leftZ = Math.sin(yaw);

        camX += (lookX * forward + leftX * strafe) * step;
        camY += (lookY * forward + up) * step;
        camZ += (lookZ * forward + leftZ * strafe) * step;
    }

    /** Copies the camera state into the dummy entity, with no interpolation lag. */
    private void syncCamera() {
        camera.setPosition(camX, camY, camZ);
        camera.prevPosX = camX;
        camera.prevPosY = camY;
        camera.prevPosZ = camZ;
        camera.lastTickPosX = camX;
        camera.lastTickPosY = camY;
        camera.lastTickPosZ = camZ;

        camera.rotationYaw = camYaw;
        camera.prevRotationYaw = camYaw;
        camera.rotationPitch = camPitch;
        camera.prevRotationPitch = camPitch;
        camera.rotationYawHead = camYaw;
        camera.prevRotationYawHead = camYaw;
        camera.renderYawOffset = camYaw;
        camera.prevRenderYawOffset = camYaw;
    }

    /**
     * The wheel tunes the fly speed without opening a menu, and switching the held slot while
     * the camera is away would be surprising, so the scroll is swallowed while enabled.
     */
    @SubscribeEvent
    public void onMouseInput(MouseEvent event) {
        if (!isEnabled() || mc.currentScreen != null || event.dwheel == 0) return;
        speedSetting.setValue(speedSetting.getValue()
                + Math.signum(event.dwheel) * speedSetting.getStep());
        event.setCanceled(true);
    }

    /** The first person hand is still oriented from the body and would float in the view. */
    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (isEnabled()) event.setCanceled(true);
    }

    /** The body is visible while the camera is away; hide its own name tag like first person. */
    @SubscribeEvent
    public void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (!isEnabled() || event.entity != mc.thePlayer) return;
        mc.getRenderManager().livingPlayer = event.entity;
    }

    private void disableFreeLook() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return;
        Module freeLook = Potassium.INSTANCE.moduleManager.getModule("FreeLook");
        if (freeLook != null && freeLook.isEnabled()) freeLook.setEnabled(false);
    }

    /** The camera entity only ever provides a position and two angles. */
    private static final class CameraEntity extends EntityOtherPlayerMP {

        private CameraEntity(World world, GameProfile profile) {
            super(world, profile);
            this.noClip = true;
            this.setInvisible(true);
        }

        @Override
        public boolean isSpectator() {
            return true;
        }
    }

    /** Vanilla reads this every frame and gets no movement, so the body never turns. */
    private static final class SilentMouseHelper extends MouseHelper {

        @Override
        public void mouseXYChange() {
            deltaX = 0;
            deltaY = 0;
        }
    }

    /** Samples the keys as usual, then reports no movement to the player. */
    private static final class FrozenInput extends MovementInput {

        private final MovementInput delegate;

        private FrozenInput(MovementInput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void updatePlayerMoveState() {
            delegate.updatePlayerMoveState();
            moveForward = 0.0F;
            moveStrafe = 0.0F;
            jump = false;
            sneak = false;
        }
    }
}
