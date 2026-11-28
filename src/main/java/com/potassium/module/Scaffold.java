package com.potassium.module;

import com.potassium.module.scaffold.PlacementFinder;
import com.potassium.module.scaffold.PlacementPredictor;
import com.potassium.module.scaffold.PlacementTarget;
import com.potassium.module.scaffold.RotationModel;
import com.potassium.module.scaffold.SafeWalk;
import com.potassium.module.scaffold.ScaffoldMode;
import com.potassium.module.scaffold.ScaffoldSettings;
import com.potassium.settings.Setting;
import com.potassium.util.ClickState;
import com.potassium.util.CombatArbiter;
import com.potassium.util.InventoryStateTracker;
import com.potassium.util.RotationManager;
import com.potassium.util.RotationUtil;
import com.potassium.util.RenderUtil;
import com.potassium.util.SilentLook;
import com.potassium.util.SlotSpoofer;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * OpenAdjust's scaffold, ported onto this client's rotation and click plumbing.
 *
 * <p>The module predicts where the feet are going to be, picks the support cell under that spot,
 * and solves the rotation that reaches a clickable face of the block beside it. The aim is sent
 * silently through {@link SilentLook}, so the camera stays where the player put it, and the
 * click only goes out once the rotation the server already holds is on the face: a placement is
 * never sent from a look the server has not received.</p>
 *
 * <p>The four modes are OpenAdjust's: Normal (vanilla jump tower), NCP and Rewound (the
 * ground-jump towers with the early-tick motion correction), and Hypixel (raycast only, no
 * snapping, low tower). The rotation is the same solver it uses: eight yaws around the current
 * aim, ordered by the walking direction, with the chosen offset kept so the body stays on one
 * shoulder between blocks.</p>
 */
public class Scaffold extends Module {

    // Blocks that must never be clicked for a bridge: a container opens instead, and a
    // gravity block or a web would not stay where it was put.
    private static final Set<Block> BLACKLIST = new HashSet<>(Arrays.asList(
            Blocks.chest,
            Blocks.trapped_chest,
            Blocks.ender_chest,
            Blocks.crafting_table,
            Blocks.furnace,
            Blocks.lit_furnace,
            Blocks.anvil,
            Blocks.brewing_stand,
            Blocks.enchanting_table,
            Blocks.dispenser,
            Blocks.dropper,
            Blocks.hopper,
            Blocks.beacon,
            Blocks.tnt,
            Blocks.web,
            Blocks.sand,
            Blocks.gravel
    ));

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ScaffoldSettings options = new ScaffoldSettings(this);
    private final PlacementFinder placementFinder = new PlacementFinder(this);
    private final PlacementPredictor placementScanner = new PlacementPredictor(this);
    private final SafeWalk safeWalk = new SafeWalk(this);
    private final Random random = new Random();

    private int baseY = Integer.MIN_VALUE;
    private boolean noAscendActive;
    private boolean preventsAscend;
    private PlacementTarget lastPlacement;
    private PlacementTarget placement;
    private boolean placeReady;
    private boolean rotating;
    private int validSlotTicks;
    /** Ticks since the predictor last found a target; OpenAdjust's rotation window. */
    public int ticksSincePlace = 100;
    private int offGroundTicks;
    private boolean towerKeyOnGround;
    public float targetYaw = Float.NaN;
    public float targetPitch = Float.NaN;
    private float aimYaw = Float.NaN;
    private float aimPitch = Float.NaN;
    private boolean firstTurn = true;
    private float rotationJitter;
    private int originalSlot = -1;
    private boolean slotChanged;
    private Field rightClickDelayField;
    private long nextClickTime;
    private boolean sprintKeySuppressed;
    /** OpenAdjust's render anchors: the cell placed into, and the block that was clicked. */
    private BlockPos lastPlaceBox;
    private BlockPos lastPlacePos;
    private static boolean sprintSuppressed;

    /** OpenAdjust's placement colours: the block placed, and the block that was clicked. */
    private static final int PLACED_COLOR = 0x3CC87820;
    private static final int CLICKED_COLOR = 0x3C0ADC20;

    public Scaffold() {
        super("Scaffold", Category.MOVEMENT);
        settings.addAll(options.getSettings());
        MinecraftForge.EVENT_BUS.register(this);
    }

    // ---- Accessors the modes and the geometry use --------------------------

    public ScaffoldSettings getOptions() {
        return options;
    }

    public ScaffoldMode currentMode() {
        return options.mode();
    }

    public PlacementFinder getPlacementFinder() {
        return placementFinder;
    }

    public int getBaseY() {
        return baseY;
    }

    public void setBaseY(int value) {
        baseY = value;
    }

    public boolean isNoAscendActive() {
        return noAscendActive;
    }

    public boolean preventsAscend() {
        return preventsAscend;
    }

    public void setPreventsAscend(boolean value) {
        preventsAscend = value;
    }

    /** OpenAdjust's {@code field130}: a tower jump must not move the bridge's base level. */
    public boolean skipBaseYUpdate;

    private boolean inputHeld;

    public int getOffGroundTicks() {
        return offGroundTicks;
    }

    public void setOffGroundTicks(int value) {
        offGroundTicks = Math.max(0, value);
    }

    public boolean isJumpKeyDown() {
        return mc.gameSettings != null && mc.gameSettings.keyBindJump.isKeyDown();
    }

    public void holdInput() {
        inputHeld = true;
    }

    public void releaseInput() {
        inputHeld = false;
    }

    /** OpenAdjust's forward-look gate: only look ahead while a placement is being worked on. */
    public boolean shouldRotateForward(boolean force) {
        if (!options.isRotationsEnabled()) return false;
        if (!force && mc.thePlayer != null && mc.thePlayer.onGround) return false;
        return placeReady && currentMode().allowForwardTurn();
    }

    /** The angle between the way the player is walking and where the aim points. */
    public float facingMovement() {
        float movement = MathHelper.wrapAngleTo180_float(moveYaw());
        float aim = MathHelper.wrapAngleTo180_float(getAimYaw());
        return Math.abs(MathHelper.wrapAngleTo180_float(movement - aim));
    }

    public String noAscendMode() {
        return options.getNoAscendMode();
    }

    public boolean isAboveBuildLimit(int y) {
        return currentMode().isAboveBuildLimit(y);
    }

    public float getAimYaw() {
        if (!Float.isNaN(aimYaw)) return aimYaw;
        return mc.thePlayer == null ? 0.0F : mc.thePlayer.rotationYaw;
    }

    public float getAimPitch() {
        if (!Float.isNaN(aimPitch)) return aimPitch;
        return mc.thePlayer == null ? 0.0F : mc.thePlayer.rotationPitch;
    }

    public int offGroundTicks() {
        return offGroundTicks;
    }

    public void resetOffGroundTicks() {
        offGroundTicks = 0;
    }

    public boolean isTowerKeyDown() {
        return mc.gameSettings != null && mc.gameSettings.keyBindJump.isKeyDown();
    }

    public double jumpMotion(double base) {
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && player.isPotionActive(Potion.jump)) {
            int level = player.getActivePotionEffect(Potion.jump).getAmplifier() + 1;
            return base + (level + 1) * 0.1D;
        }
        return base;
    }

    public void setTowerSpeed(EntityPlayerSP player, double speed) {
        float forward = (mc.gameSettings.keyBindForward.isKeyDown() ? 1.0F : 0.0F)
                - (mc.gameSettings.keyBindBack.isKeyDown() ? 1.0F : 0.0F);
        float strafe = (mc.gameSettings.keyBindLeft.isKeyDown() ? 1.0F : 0.0F)
                - (mc.gameSettings.keyBindRight.isKeyDown() ? 1.0F : 0.0F);
        if (forward == 0.0F && strafe == 0.0F) return;
        float yaw = player.rotationYaw;
        float offset;
        if (forward < 0.0F) {
            yaw += 180.0F;
            offset = -45.0F;
        } else if (forward > 0.0F) {
            offset = 45.0F;
        } else {
            offset = 90.0F;
        }
        if (strafe > 0.0F) yaw -= offset;
        if (strafe < 0.0F) yaw += offset;
        double radians = Math.toRadians(yaw);
        player.motionX = -Math.sin(radians) * speed;
        player.motionZ = Math.cos(radians) * speed;
    }

    public void stopMotion(EntityPlayerSP player) {
        player.motionX = 0.0D;
        player.motionZ = 0.0D;
    }

    public void centerMotion(EntityPlayerSP player, double speed) {
        double centreX = MathHelper.floor_double(player.posX) + 0.5D;
        double centreZ = MathHelper.floor_double(player.posZ) + 0.5D;
        double dx = centreX - player.posX;
        double dz = centreZ - player.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            stopMotion(player);
            return;
        }
        player.motionX = dx / length * speed;
        player.motionZ = dz / length * speed;
    }

    public boolean collides(AxisAlignedBB box) {
        return mc.theWorld != null && !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box).isEmpty();
    }

    public boolean isMoving() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return false;
        if (player.movementInput != null
                && (player.movementInput.moveForward != 0.0F || player.movementInput.moveStrafe != 0.0F)) {
            return true;
        }
        return mc.gameSettings != null && (mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown());
    }

    public boolean isJumping() {
        return mc.thePlayer != null && !mc.thePlayer.onGround;
    }

    public double horizontalSpeed() {
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? 0.0D : Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
    }

    /** Direction of travel in degrees, the anchor the rotation solver orders its yaws around. */
    public float moveYaw() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return 0.0F;
        double dx = player.motionX;
        double dz = player.motionZ;
        if (dx * dx + dz * dz < 0.0009D) {
            Vec3 input = inputDirection(player);
            if (input == null) return player.rotationYaw;
            dx = input.xCoord;
            dz = input.zCoord;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public Vec3 inputDirection(EntityPlayerSP player) {
        float forward = (mc.gameSettings.keyBindForward.isKeyDown() ? 1.0F : 0.0F)
                - (mc.gameSettings.keyBindBack.isKeyDown() ? 1.0F : 0.0F);
        float strafe = (mc.gameSettings.keyBindLeft.isKeyDown() ? 1.0F : 0.0F)
                - (mc.gameSettings.keyBindRight.isKeyDown() ? 1.0F : 0.0F);
        if (forward == 0.0F && strafe == 0.0F) return null;
        float radians = (float) Math.toRadians(player.rotationYaw);
        double x = forward * -MathHelper.sin(radians) + strafe * MathHelper.cos(radians);
        double z = forward * MathHelper.cos(radians) + strafe * MathHelper.sin(radians);
        double length = Math.sqrt(x * x + z * z);
        if (length < 1.0E-4D) return null;
        return new Vec3(x / length, 0.0D, z / length);
    }

    /** Distance from a point to a cell centre, nudged along the walking direction. */
    public double distanceTo(Vec3 from, BlockPos pos, boolean horizontalOnly) {
        Vec3 centre = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        double radians = Math.toRadians(moveYaw());
        Vec3 shifted = centre.addVector(-Math.sin(radians) * 0.2D, 0.0D, Math.cos(radians) * 0.2D);
        if (!horizontalOnly) return shifted.distanceTo(from);
        double dx = from.xCoord - shifted.xCoord;
        double dz = from.zCoord - shifted.zCoord;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public AxisAlignedBB playerBoxAt(Vec3 position) {
        return new AxisAlignedBB(position.xCoord - 0.3D, position.yCoord, position.zCoord - 0.3D,
                position.xCoord + 0.3D, position.yCoord + 1.8D, position.zCoord + 0.3D);
    }

    /**
     * The path the predictor scans: the player's own motion carried forward, with the friction
     * and gravity vanilla applies and the key input folded in while the feet are down.
     */
    public List<PathPoint> predictPath(EntityPlayerSP player) {
        List<PathPoint> path = new ArrayList<>(5);
        double x = player.posX;
        double y = player.posY;
        double z = player.posZ;
        double motionX = player.motionX;
        double motionY = player.motionY;
        double motionZ = player.motionZ;
        boolean onGround = player.onGround;
        Vec3 input = inputDirection(player);
        path.add(new PathPoint(x, y, z, onGround));

        for (int tick = 0; tick < 4; tick++) {
            if (onGround) {
                motionX *= 0.546D;
                motionZ *= 0.546D;
                if (input != null) {
                    motionX += input.xCoord * 0.1D;
                    motionZ += input.zCoord * 0.1D;
                }
                motionY = 0.0D;
            } else {
                motionX *= 0.91D;
                motionZ *= 0.91D;
                if (input != null) {
                    motionX += input.xCoord * 0.02D;
                    motionZ += input.zCoord * 0.02D;
                }
                motionY = (motionY - 0.08D) * 0.98D;
            }
            x += motionX;
            y += motionY;
            z += motionZ;
            onGround = onGroundAt(player, x, y, z);
            path.add(new PathPoint(x, y, z, onGround));
        }
        return path;
    }

    private boolean onGroundAt(EntityPlayerSP player, double x, double y, double z) {
        if (mc.theWorld == null) return false;
        AxisAlignedBB box = new AxisAlignedBB(x - 0.3D, y - 0.05D, z - 0.3D,
                x + 0.3D, y + 1.8D, z + 0.3D);
        return !mc.theWorld.getCollidingBoundingBoxes(player, box).isEmpty();
    }

    // ---- Settings and module hooks -----------------------------------------

    @Override
    public boolean isSettingVisible(Setting setting) {
        return options.isVisible(setting);
    }

    @Override
    public String getTag() {
        return currentMode().getName();
    }

    /** AutoSprint must not re-enable sprint on a tick where the mode forbids it. */
    public static boolean isSprintSuppressed() {
        return sprintSuppressed;
    }

    // ---- Tick ---------------------------------------------------------------

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        placement = null;
        if (!isEnabled()) {
            sprintSuppressed = false;
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || mc.currentScreen != null
                || mc.playerController == null || player.isDead || player.isSpectator()
                || player.isRiding()) {
            sprintSuppressed = false;
            safeWalk.reset();
            return;
        }

        WorldSettings.GameType type = mc.playerController.getCurrentGameType();
        if (type == WorldSettings.GameType.ADVENTURE || type == WorldSettings.GameType.SPECTATOR) {
            setEnabled(false);
            return;
        }

        tickScaffold(player);
    }

    private void tickScaffold(EntityPlayerSP player) {
        int tick = player.ticksExisted;
        // Vanilla's held right click must not place a second block beside this module's own
        // click; the cooldown is pinned, and the click goes straight to the controller.
        suppressVanillaUse();
        if (player.onGround) {
            offGroundTicks = 0;
            towerKeyOnGround = isTowerKeyDown();
            firstTurn = true;
        } else {
            offGroundTicks++;
        }

        updateBaseY(player);
        noAscendActive = computeNoAscend(true);

        // The predictor is anchored on the last target, not on the aim as it steps toward it,
        // so the face it picks does not change while the body is still turning.
        float referenceYaw = Float.isNaN(targetYaw) ? getAimYaw() : targetYaw;
        float referencePitch = Float.isNaN(targetPitch) ? getAimPitch() : targetPitch;
        Integer ground = placementScanner.predict(player, referenceYaw, referencePitch);
        if (ground == null) ticksSincePlace++;

        boolean nearGround = ground != null && ground < 5;
        boolean hasBlock = selectBlock(nearGround);
        validSlotTicks = hasBlock ? validSlotTicks + 1 : 0;
        // OpenAdjust waits two ticks on the block slot before placing, so a scroll or a
        // hotbar press that just landed has the slot it needs by the time the click goes.
        placeReady = hasBlock && validSlotTicks >= 2;

        // OpenAdjust latches this: once the module has started placing, the rotation window is
        // what ends the aim, not a single tick without a block in hand.
        if (placeReady) rotating = true;
        if (placeReady) {
            safeWalk.update(ground);
            if (canPlaceNow() && ground != null && ground <= options.getExtend()) {
                placement = placementScanner.lastResult;
                if (placement != null) {
                    lastPlacement = placement;
                    lastPlacePos = placement.pos;
                    lastPlaceBox = placement.placedPos;
                }
            }
        } else {
            safeWalk.update(null);
        }

        // The window stays open for a few ticks after the last placement, and the aim is kept
        // through it even when this tick has nothing to click, so the view is never pulled back
        // to the camera between two blocks.
        if (isRotationWindowActive() && options.isRotationsEnabled()) {
            rotate(player);
        }

        // Raycast is the safety gate: with it on, the rotation the server already holds must
        // land on the face before a click goes out. Off, the click is trusted to the solved
        // placement alone, which is faster and can leave a ghost block on a strict server.
        if (placement != null) {
            boolean aimVerified = !placementFinder.useRaycast()
                    || serverAimReaches(player, placement);
            if (canPlaceNow() && ClickState.canUse(tick) && arrived() && aimVerified
                    && (!options.isWaitForClick() || canClick())) {
                executePlacement(player, placement, tick);
            }
        }
    }

    /** OpenAdjust's rotation window: rotations stay active for a few ticks after a target. */
    private boolean isRotationWindowActive() {
        if (!rotating) return false;
        return Math.abs(usernameHash()) % 3 + 20 > ticksSincePlace;
    }

    private int usernameHash() {
        String name = mc.getSession() == null ? null : mc.getSession().getUsername();
        return name == null ? 0 : name.hashCode();
    }

    private void updateBaseY(EntityPlayerSP player) {
        if (player.onGround || baseY > player.posY || currentMode().towering) {
            if (skipBaseYUpdate) {
                skipBaseYUpdate = false;
            } else if (preventsAscend) {
                preventsAscend = false;
            } else {
                baseY = MathHelper.floor_double(player.posY);
            }
        }
        if (baseY == Integer.MIN_VALUE) baseY = MathHelper.floor_double(player.posY);
    }

    /**
     * No ascend: keep the bridge flat by refusing the upward faces once a jump has taken the
     * feet a block above the level the bridge is on.
     */
    private boolean computeNoAscend(boolean retainAirborne) {
        if (!options.isNoAscend()) return false;
        if (!currentMode().canNoAscend()) return false;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return false;
        boolean force = retainAirborne && noAscendActive && !player.onGround;
        if (isJumpKeyDown()) return false;
        // OpenAdjust: a bound key has to be held, an unbound one keeps no-ascend on for as
        // long as the player is not jumping.
        return options.isNoAscendKeyDown() || force || !options.noAscendBind.isBound();
    }

    private boolean canPlaceNow() {
        if (!currentMode().canPlaceInGame()) return false;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return false;
        if (player.isUsingItem()) return false;
        if (player.ridingEntity != null) return false;
        return !mc.gameSettings.keyBindAttack.isKeyDown();
    }

    // ---- Rotation -----------------------------------------------------------

    private void rotate(EntityPlayerSP player) {
        PlacementTarget aimTarget = placement != null ? placement : placementScanner.lastResult;
        if (Float.isNaN(aimYaw) || Float.isNaN(aimPitch)) {
            aimYaw = SilentLook.cameraYaw();
            aimPitch = SilentLook.cameraPitch();
        }

        // The aim only ever moves to a placement. When nothing new has been solved this tick,
        // the last solved placement stays the target, so the body keeps looking at the spot the
        // next block will go instead of turning back to wherever the player is looking.
        if (aimTarget != null) {
            targetYaw = aimTarget.yaw;
            targetPitch = aimTarget.pitch;
        } else if (Float.isNaN(targetYaw) || Float.isNaN(targetPitch)) {
            // Nothing has been solved yet in this window: start the turn from the camera once.
            targetYaw = SilentLook.cameraYaw();
            targetPitch = SilentLook.cameraPitch();
        }
        if (Float.isNaN(targetYaw) || Float.isNaN(targetPitch)) return;

        RotationModel model = currentMode().placeRotationModel();
        if (model == null) model = rotationModel();
        float[] stepped = model.apply(aimYaw, aimPitch, targetYaw, targetPitch);
        // Every look the server receives has to be a whole number of mouse counts from the
        // camera, or a rotation check reads the delta as synthetic. OpenAdjust snaps its
        // stepped rotation the same way before handing it to the packet.
        float[] snapped = RotationUtil.snapToMouse(stepped[0], stepped[1],
                SilentLook.cameraYaw(), SilentLook.cameraPitch());
        aimYaw = snapped[0];
        aimPitch = MathHelper.clamp_float(snapped[1], -90.0F, 90.0F);
        if (aimTarget == null) {
            if (Math.abs(MathHelper.wrapAngleTo180_float(aimYaw - targetYaw)) < 0.5F) aimYaw = targetYaw;
            if (Math.abs(aimPitch - targetPitch) < 0.5F) aimPitch = targetPitch;
        }

        RotationManager.request("Scaffold", CombatArbiter.PRIORITY_MOVEMENT,
                aimYaw, aimPitch, SilentLook.MovementFix.NONE, false);
        currentMode().onPostRotation();
    }

    private RotationModel rotationModel() {
        ScaffoldMode mode = currentMode();
        if (options.isSnap() && mode.canSnap()) return RotationModel.INSTANT;
        if (ScaffoldSettings.ROTATION_INSTANT.equals(options.getRotationMode())) {
            return RotationModel.INSTANT;
        }
        if (ScaffoldSettings.ROTATION_STEP.equals(options.getRotationMode()) || mode.useFastRotations()) {
            float limit = firstTurn ? 73.0F : 43.0F;
            if (!firstTurn && Math.abs(MathHelper.wrapAngleTo180_float(
                    SilentLook.cameraYaw() - targetYaw)) >= 1.0F) {
                limit -= rotationJitter;
            }
            rotationJitter = updateJitter();
            firstTurn = false;
            return new RotationModel.Linear(limit - (0.1F + random.nextFloat() * 0.9F));
        }
        return new RotationModel.Axis(options.horizontalSpeed.random(),
                options.verticalSpeed.random(), false);
    }

    private float updateJitter() {
        float difference = MathHelper.wrapAngleTo180_float(SilentLook.cameraYaw() - targetYaw);
        if (Math.abs(difference) < 1.0F) return 0.0F;
        if (rotationJitter < 10.0F) return rotationJitter + 3.0F + random.nextFloat();
        return rotationJitter;
    }

    private boolean arrived() {
        if (Float.isNaN(targetYaw) || Float.isNaN(targetPitch)) return false;
        if (Float.isNaN(aimYaw) || Float.isNaN(aimPitch)) return false;
        return Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - aimYaw)) < 1.0F
                && Math.abs(targetPitch - aimPitch) < 1.0F;
    }

    /**
     * The click leaves before this tick's look, so the rotation the server already has is the one
     * its placement check uses. A ray from that rotation must land on the face being clicked.
     */
    private boolean serverAimReaches(EntityPlayerSP player, PlacementTarget target) {
        if (player == null || target == null || mc.theWorld == null || mc.playerController == null) {
            return false;
        }
        float yaw = RotationManager.hasServerRotation()
                ? RotationManager.serverYaw() : player.rotationYaw;
        float pitch = RotationManager.hasServerRotation()
                ? RotationManager.serverPitch() : player.rotationPitch;
        return rayHits(player, yaw, pitch, target.pos, target.facing);
    }

    private boolean rayHits(EntityPlayerSP player, float yaw, float pitch,
                            BlockPos block, EnumFacing side) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 direction = lookVector(yaw, pitch);
        double reach = mc.playerController.getBlockReachDistance();
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes,
                eyes.addVector(direction.xCoord * reach, direction.yCoord * reach,
                        direction.zCoord * reach), false, false, true);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && block.equals(hit.getBlockPos()) && hit.sideHit == side;
    }

    private static Vec3 lookVector(float yaw, float pitch) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(f1 * f2, f3, f * f2);
    }

    /** OpenAdjust's CPS gate, used when Wait for Click is on. */
    private boolean canClick() {
        long now = System.currentTimeMillis();
        if (now < nextClickTime) return false;
        int low = Math.max(1, options.getCpsLow());
        int high = Math.max(low, options.getCpsHigh());
        int delay = high == low ? low : low + random.nextInt(high - low + 1);
        nextClickTime = now + delay * 50L;
        return true;
    }

    // ---- Placement ----------------------------------------------------------

    private void executePlacement(EntityPlayerSP player, PlacementTarget target, int tick) {
        // A silent switch leaves the server on the block slot and the client on the item the
        // player is holding. The client has to build this click with the block, so it is put
        // on the server's slot for exactly this call and taken back afterwards, with no C09
        // either way.
        boolean swap = SlotSpoofer.active();
        if (swap) SlotSpoofer.swapClientToServer();
        try {
            ItemStack held = player.getHeldItem();
            if (!isPlaceable(held)) return;

            mc.objectMouseOver = new MovingObjectPosition(target.hitVec, target.facing, target.pos);
            if (mc.playerController.onPlayerRightClick(player, mc.theWorld, held,
                    target.pos, target.facing, target.hitVec)) {
                if (options.isSwing()) player.swingItem();
                ClickState.markUse(tick);
                ticksSincePlace = 0;
                lastPlacement = target;
                currentMode().onPlace(target);
                if (!currentMode().canSprint()) {
                    player.setSprinting(false);
                    player.sprintingTicksLeft = 0;
                }
            }
        } finally {
            if (swap) SlotSpoofer.restoreClient();
            placement = null;
        }
    }

    /**
     * The stack placement will use. With a silent switch the server holds the block slot while
     * the client still shows the player's own item, so the server's slot is what decides
     * whether there is anything to place.
     */
    private ItemStack heldForPlacement(EntityPlayerSP player) {
        return SlotSpoofer.active() ? InventoryStateTracker.heldStack() : player.getHeldItem();
    }

    /**
     * Vanilla treats a held right click as a second placement source. The cooldown is pinned
     * above zero every tick while a block is held, so vanilla's own right-click path never runs
     * beside this module's. The mouse press is cancelled for the same reason.
     */
    private void suppressVanillaUse() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !isPlaceable(heldForPlacement(player))) return;
        setRightClickDelay(4);
    }

    private void setRightClickDelay(int delay) {
        if (rightClickDelayField == null) {
            try {
                rightClickDelayField = ReflectionHelper.findField(Minecraft.class,
                        "rightClickDelayTimer", "field_71467_ac");
                rightClickDelayField.setAccessible(true);
            } catch (Exception ignored) {
                return;
            }
        }
        try {
            rightClickDelayField.setInt(mc, delay);
        } catch (Exception ignored) {
        }
    }

    // ---- Render -------------------------------------------------------------

    /**
     * OpenAdjust's block placement render: the cell the last block went into, and the block
     * that was clicked to place it, each drawn as a filled unit cube with an outline.
     */
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled() || !options.isBlockPlacements()) return;
        if (lastPlaceBox == null || lastPlacePos == null) return;
        if (mc.theWorld == null || mc.getRenderManager() == null) return;

        double viewX = mc.getRenderManager().viewerPosX;
        double viewY = mc.getRenderManager().viewerPosY;
        double viewZ = mc.getRenderManager().viewerPosZ;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        drawCell(tessellator, lastPlaceBox, viewX, viewY, viewZ, PLACED_COLOR);
        drawCell(tessellator, lastPlacePos, viewX, viewY, viewZ, CLICKED_COLOR);

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    private void drawCell(Tessellator tessellator, BlockPos pos,
                          double viewX, double viewY, double viewZ, int color) {
        double x = pos.getX() - viewX;
        double y = pos.getY() - viewY;
        double z = pos.getZ() - viewZ;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (color >>> 24) & 0xFF;
        RenderUtil.drawFilledBox(tessellator, x, y, z, x + 1.0D, y + 1.0D, z + 1.0D, r, g, b, a);
        RenderUtil.drawOutlinedBox(tessellator,
                new AxisAlignedBB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D), r, g, b, a);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (!isEnabled() || mc.currentScreen != null) return;
        if (event.button != 1 || !event.buttonstate) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        if (isPlaceable(heldForPlacement(player))) event.setCanceled(true);
    }

    // ---- Hotbar -------------------------------------------------------------

    private boolean selectBlock(boolean force) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return false;
        // A hotbar key sends its own C09, which ends the silent switch from the server's side.
        SlotSpoofer.syncWithServer();
        boolean usable = isPlaceable(heldForPlacement(player));
        if (usable) return true;
        if (!options.isChangeSlot()) return false;

        int slot = findBlockSlot(player);
        if (slot < 0) return false;
        if (!force && !slotChanged) return false;

        if (options.isClientsideSwap()) {
            // The server is told about the block slot while the client keeps showing the
            // item the player picked; the swap happens per placement, in executePlacement.
            if (!SlotSpoofer.active() || SlotSpoofer.serverSlot() != slot) SlotSpoofer.enable(slot);
            slotChanged = true;
            return true;
        }

        if (originalSlot < 0) originalSlot = player.inventory.currentItem;
        player.inventory.currentItem = slot;
        sendSlot(slot);
        slotChanged = true;
        return true;
    }

    private int findBlockSlot(EntityPlayerSP player) {
        int bestSlot = -1;
        int bestSize = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (!isPlaceable(stack) || stack.stackSize <= bestSize) continue;
            bestSlot = i;
            bestSize = stack.stackSize;
        }
        return bestSlot;
    }

    private boolean isPlaceable(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        if (BLACKLIST.contains(block)) return false;
        if (!block.getMaterial().isSolid()) return false;
        return block.getBlockBoundsMaxX() - block.getBlockBoundsMinX() == 1.0D
                && block.getBlockBoundsMaxY() - block.getBlockBoundsMinY() == 1.0D
                && block.getBlockBoundsMaxZ() - block.getBlockBoundsMinZ() == 1.0D;
    }

    private void sendSlot(int slot) {
        InventoryStateTracker.noteSlot(slot);
        if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(slot));
        }
    }

    private void restoreSlot(EntityPlayerSP player) {
        if (SlotSpoofer.active()) {
            SlotSpoofer.disable();
            originalSlot = -1;
            slotChanged = false;
            return;
        }
        if (originalSlot < 0) return;
        int slot = originalSlot;
        originalSlot = -1;
        slotChanged = false;
        if (player != null) {
            player.inventory.currentItem = slot;
            sendSlot(slot);
        }
    }

    // ---- Player tick: tower, no-ascend jump, sprint -------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTickStart(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;

        // Sprint eligibility is decided before the walk is computed, so a mode that forbids
        // sprint while placing has to clear it here and not only after the move.
        if (placeReady && !currentMode().canSprint()) {
            int key = mc.gameSettings.keyBindSprint.getKeyCode();
            if (!sprintKeySuppressed && key > 0 && key < 256) {
                KeyBinding.setKeyBindState(key, false);
                sprintKeySuppressed = true;
            }
            player.setSprinting(false);
            player.sprintingTicksLeft = 0;
        }

        if (!player.onGround) {
            offGroundTicks++;
        } else {
            offGroundTicks = 0;
            inputHeld = false;
        }
        if (inputHeld && player.movementInput != null) {
            player.movementInput.moveForward = 0.0F;
            player.movementInput.moveStrafe = 0.0F;
        }

        currentMode().onTickStart(player);

        if (noAscendActive && options.isAutoJump() && currentMode().canAutoJump()
                && player.onGround && !isTowerKeyDown() && isMoving()
                && !player.isSneaking()) {
            player.jump();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTickEnd(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;

        currentMode().onTickEnd(player);

        boolean block = placeReady && !currentMode().canSprint();
        sprintSuppressed = block;
        if (block) {
            if (!sprintKeySuppressed) {
                int key = mc.gameSettings.keyBindSprint.getKeyCode();
                if (key > 0 && key < 256) {
                    KeyBinding.setKeyBindState(key, false);
                    sprintKeySuppressed = true;
                }
            }
            player.setSprinting(false);
            player.sprintingTicksLeft = 0;
        } else if (sprintKeySuppressed) {
            sprintKeySuppressed = false;
            int key = mc.gameSettings.keyBindSprint.getKeyCode();
            KeyBinding.setKeyBindState(key, key > 0 && key < 256 && Keyboard.isKeyDown(key));
        }
    }

    // ---- Lifetime -----------------------------------------------------------

    @Override
    public void onEnable() {
        EntityPlayerSP player = mc.thePlayer;
        baseY = player == null ? Integer.MIN_VALUE : MathHelper.floor_double(player.posY);
        noAscendActive = false;
        preventsAscend = false;
        placement = null;
        lastPlacement = null;
        placeReady = false;
        rotating = false;
        validSlotTicks = 0;
        ticksSincePlace = 100;
        offGroundTicks = 0;
        inputHeld = false;
        skipBaseYUpdate = false;
        towerKeyOnGround = false;
        targetYaw = Float.NaN;
        targetPitch = Float.NaN;
        aimYaw = Float.NaN;
        aimPitch = Float.NaN;
        firstTurn = true;
        rotationJitter = 0.0F;
        if (SlotSpoofer.active()) SlotSpoofer.disable();
        lastPlaceBox = null;
        lastPlacePos = null;
        originalSlot = -1;
        slotChanged = false;
        nextClickTime = 0L;
        sprintSuppressed = false;
        sprintKeySuppressed = false;
        safeWalk.reset();
        placementFinder.solver().reset();
        placementScanner.lastResult = null;
        currentMode().reset();
    }

    @Override
    public void onDisable() {
        restoreSlot(mc.thePlayer);
        safeWalk.reset();
        sprintSuppressed = false;
        if (sprintKeySuppressed) {
            sprintKeySuppressed = false;
            int key = mc.gameSettings.keyBindSprint.getKeyCode();
            KeyBinding.setKeyBindState(key, key > 0 && key < 256 && Keyboard.isKeyDown(key));
        }
        placement = null;
        lastPlacement = null;
        placeReady = false;
        rotating = false;
        validSlotTicks = 0;
        placementFinder.solver().reset();
        placementScanner.lastResult = null;
        currentMode().reset();
    }

    /** One predicted position of the player, with the ground state it was computed for. */
    public static final class PathPoint {

        public final double x;
        public final double y;
        public final double z;
        public final boolean onGround;

        public PathPoint(double x, double y, double z, boolean onGround) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.onGround = onGround;
        }
    }
}
