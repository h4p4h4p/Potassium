package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.RangeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.ClickState;
import com.potassium.util.MovementInputStack;
import com.potassium.util.RotationUtil;
import com.potassium.util.SilentLook;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Bridges in the direction the player is moving by filling the cell under their feet,
 * following Raven B4's layout: the target is the supporting cell of the player's own
 * column, the level stays at the one the player launched from while airborne, and the
 * clicked block is a neighbour of that cell. Every face is ray-traced from the eyes before
 * it is used, and the aim is the rotation that actually reaches that face, so the server
 * sees a placement it would accept from a hand rather than a click thrown at the sky.
 *
 * The look the packets carry is the rotation that reaches the clicked face, and a placement
 * waits until the server already holds that rotation, because the click leaves before the
 * tick's own look does. The camera itself never moves.
 */
public class Scaffold extends Module {

    private static final Logger LOGGER = LogManager.getLogger("Potassium");

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_LEGIT = "Legit";

    public static final String ROTATION_OFF = "Off";
    public static final String ROTATION_NORMAL = "Normal";

    public static final String TOWER_NONE = "None";
    public static final String TOWER_VANILLA = "Vanilla";
    public static final String TOWER_NCP = "NCP";
    public static final String TOWER_SAME_Y = "Same Y";

    public static final String SPRINT_NORMAL = "Normal";
    public static final String SPRINT_NONE = "None";

    public static final String MOVE_FIX_NONE = "None";
    public static final String MOVE_FIX_STRICT = "Strict";

    public static final String SMOOTHING_LINEAR = RotationUtil.SMOOTHING_LINEAR;
    public static final String SMOOTHING_EXPONENTIAL = RotationUtil.SMOOTHING_EXPONENTIAL;

    public static final String AIM_CENTER = "Center";
    public static final String AIM_RANDOM = "Random";
    public static final String AIM_STABILIZED = "Stabilized";
    public static final String AIM_NEAREST = "NearestRotation";
    public static final String AIM_REVERSE_YAW = "ReverseYaw";
    public static final String AIM_DIAGONAL_YAW = "DiagonalYaw";
    public static final String AIM_ANGLE_YAW = "AngleYaw";
    public static final String AIM_EDGE_POINT = "EdgePoint";

    private static final String ROTATION_SMOOTHING_GROUP = "Rotation Smoothing";
    private static final String PREDICTION_GROUP = "Prediction";
    private static final String SNEAK_GROUP = "Sneak";
    private static final String LEGIT_GROUP = "Legit";
    // Vanilla multiplies the movement input by this while the sneak key is down.
    private static final double VANILLA_SNEAK_SLOWDOWN = 0.3D;
    // One client tick is 50 ms, so the millisecond sliders convert by dividing by this.
    private static final double MS_PER_TICK = 50.0D;
    // A player who has just pressed a movement key has no motion yet; the edge probe uses
    // this much travel until the first moving tick gives it a real speed.
    private static final double LEGIT_MIN_STEP = 0.05D;
    // Rise releases about 50 ms after the feet are back over support; a random extra tick
    // keeps the toggle off the exact tick grid.
    private static final int LEGIT_RELEASE_TICKS = 1;
    // Faces are sampled inside this margin so a snapped mouse step still lands on the face.
    private static final double FACE_TRIM = 0.15D;
    // Yaw-offset modes keep their offset only when a point on the face is this close to it.
    private static final float YAW_TOLERANCE = 5.0F;
    private static final double[][] CENTER_POINTS = {{0.5D, 0.5D}};
    private static final double[][] EDGE_POINTS = {
            {0.2D, 0.2D}, {0.8D, 0.2D}, {0.2D, 0.8D}, {0.8D, 0.8D}
    };
    private static final double[] YAW_GRID = {0.2D, 0.35D, 0.5D, 0.65D, 0.8D};
    private static final Random RANDOM = new Random();

    // Rise's NCP tower constants.
    private static final double NCP_TOWER_MOTION = 0.41998F;
    private static final double NCP_TOWER_SNAP = 0.00153598D;

    // The silent aim turns toward the face instead of jumping to it. Each step is rounded to
    // whole mouse counts at the current sensitivity, the grid a real hand moves on, so the
    // deltas the server sees are turns a mouse could have produced.

    // The face disappears for a tick or two while the player steps onto the block just placed.
    // Holding the aim that long stops the view flicking back to the camera and out again; the
    // return only starts once the module has had nothing to aim at for longer than that.
    private static final int AIM_HOLD_TICKS = 3;

    // Points sampled on a clickable face: the centre plus the four inner corners. Picking the
    // one that needs the least turn keeps the aim near the camera and gives the ray some
    // margin on every side, so a snapped mouse step still lands on the face.
    private static final double[][] FACE_POINTS = {
            {0.5D, 0.5D}, {0.3D, 0.3D}, {0.7D, 0.3D}, {0.3D, 0.7D}, {0.7D, 0.7D}
    };

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

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_NORMAL, MODE_LEGIT}, 0);
    private final ModeSetting rotationSetting = new ModeSetting("Rotations",
            new String[]{ROTATION_OFF, ROTATION_NORMAL}, 0);
    private final ModeSetting smoothingSetting = new ModeSetting("Smoothing",
            new String[]{SMOOTHING_LINEAR, SMOOTHING_EXPONENTIAL}, 0);
    private final RangeSetting yawSpeedSetting =
            new RangeSetting("Horizontal Speed", 180, 180, 0, 180, 1);
    private final RangeSetting pitchSpeedSetting =
            new RangeSetting("Vertical Speed", 180, 180, 0, 180, 1);
    private final ModeSetting rotationModeSetting = new ModeSetting("Rotation Mode",
            new String[]{AIM_CENTER, AIM_RANDOM, AIM_STABILIZED, AIM_NEAREST,
                    AIM_REVERSE_YAW, AIM_DIAGONAL_YAW, AIM_ANGLE_YAW, AIM_EDGE_POINT}, 2);
    private final ModeSetting movementFixSetting = new ModeSetting("Movement Fix",
            new String[]{MOVE_FIX_NONE, MOVE_FIX_STRICT}, 1);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final SliderSetting minDistSetting = new SliderSetting("Min Dist", 0.0, 0.0, 0.25, 0.01);
    private final BooleanSetting requiresSightSetting = new BooleanSetting("Requires Sight", true);
    private final BooleanSetting predictionSetting = new BooleanSetting("Prediction", true);
    private final SliderSetting bootstrapBackoffSetting =
            new SliderSetting("Bootstrap Backoff", 0.2, 0.0, 0.4, 0.05);
    private final SliderSetting predictionCutoffSetting =
            new SliderSetting("Prediction Cutoff Distance", 0.05, 0.0, 0.3, 0.01);
    private final SliderSetting warmupPlacementsSetting =
            new SliderSetting("Warmup Placements", 2, 0, 4, 1);
    private final BooleanSetting swingSetting = new BooleanSetting("Swing", true);
    private final BooleanSetting autoSwitchSetting = new BooleanSetting("Auto Switch", true);
    private final ModeSetting towerSetting = new ModeSetting("Tower",
            new String[]{TOWER_NONE, TOWER_VANILLA, TOWER_NCP, TOWER_SAME_Y}, 1);
    private final ModeSetting sprintSetting = new ModeSetting("Sprint",
            new String[]{SPRINT_NORMAL, SPRINT_NONE}, 0);
    private final RangeSetting delaySetting = new RangeSetting("Delay", 0, 0, 0, 40, 1);
    private final BooleanSetting sneakSetting = new BooleanSetting("Sneak", false);
    private final SliderSetting sneakEverySetting =
            new SliderSetting("Sneak Every X Blocks", 1, 1, 10, 1);
    private final SliderSetting startSneakingSetting =
            new SliderSetting("Start Sneaking", 100, 100, 1500, 10);
    private final SliderSetting stopSneakingSetting =
            new SliderSetting("Stop Sneaking", 100, 100, 1500, 10);
    private final SliderSetting sneakSpeedSetting =
            new SliderSetting("Sneak Speed", 0.2, 0.2, 1.0, 0.05);
    private final SliderSetting edgeLookaheadSetting =
            new SliderSetting("Edge Lookahead", 0.0, 0.0, 0.30, 0.01);
    private final BooleanSetting legitWhileSneakingSetting =
            new BooleanSetting("While Sneaking", false);
    private final BooleanSetting legitWhileInAirSetting =
            new BooleanSetting("While In Air", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private int launchY = Integer.MIN_VALUE;
    // The slot Auto Switch replaced; put back when Scaffold is turned off.
    private int autoSwitchOriginalSlot = -1;
    private int normalPlacementTick = -1;
    private int lastPlaceTick = -100;
    private Field rightClickDelayField;
    private float aimYaw = Float.NaN;
    private float aimPitch = Float.NaN;
    private boolean aimRequestedThisTick;
    private int lastAimTargetTick = -100;
    // The last silent aim requested, which this tick's movement packet carries. Candidates are
    // ranked against it so the next aim stays close to the rotation the server already has.
    private float sentAimYaw = Float.NaN;
    private float sentAimPitch = Float.NaN;
    private boolean jumpKeySuppressed;
    private boolean sprintKeySuppressed;
    private static boolean sprintSuppressed;
    private int sneakingTicks = -1;
    // Blocks actually placed since the last sneak cycle. Only a successful placement moves it.
    private int blocksSinceSneak;
    private boolean sneakKeyForced;
    private boolean legitSneaking;
    private int legitReleaseTicks;
    // TEMPORARY diagnostics: throttled reason for the last skipped placement.
    private String lastSkipReason;
    private int lastSkipLogTick = -100;
    private int placementsSinceEnable;

    public Scaffold() {
        super("Scaffold", Category.WORLD);
        rotationSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        smoothingSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        yawSpeedSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        pitchSpeedSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        predictionSetting.setGroup(PREDICTION_GROUP);
        bootstrapBackoffSetting.setGroup(PREDICTION_GROUP);
        predictionCutoffSetting.setGroup(PREDICTION_GROUP);
        warmupPlacementsSetting.setGroup(PREDICTION_GROUP);
        sneakSetting.setGroup(SNEAK_GROUP);
        sneakEverySetting.setGroup(SNEAK_GROUP);
        startSneakingSetting.setGroup(SNEAK_GROUP);
        stopSneakingSetting.setGroup(SNEAK_GROUP);
        sneakSpeedSetting.setGroup(SNEAK_GROUP);
        edgeLookaheadSetting.setGroup(LEGIT_GROUP);
        legitWhileSneakingSetting.setGroup(LEGIT_GROUP);
        legitWhileInAirSetting.setGroup(LEGIT_GROUP);
        settings.add(modeSetting);
        settings.add(rotationSetting);
        settings.add(smoothingSetting);
        settings.add(yawSpeedSetting);
        settings.add(pitchSpeedSetting);
        settings.add(rotationModeSetting);
        settings.add(movementFixSetting);
        settings.add(rangeSetting);
        settings.add(minDistSetting);
        settings.add(requiresSightSetting);
        settings.add(predictionSetting);
        settings.add(bootstrapBackoffSetting);
        settings.add(predictionCutoffSetting);
        settings.add(warmupPlacementsSetting);
        settings.add(swingSetting);
        settings.add(autoSwitchSetting);
        settings.add(towerSetting);
        settings.add(sprintSetting);
        settings.add(delaySetting);
        settings.add(sneakSetting);
        settings.add(sneakEverySetting);
        settings.add(startSneakingSetting);
        settings.add(stopSneakingSetting);
        settings.add(sneakSpeedSetting);
        settings.add(edgeLookaheadSetting);
        settings.add(legitWhileSneakingSetting);
        settings.add(legitWhileInAirSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Legit is Eagle's edge guard with Scaffold's aim and placement behind it. */
    private boolean isLegitMode() {
        return MODE_LEGIT.equals(modeSetting.getValue());
    }

    /** Prediction's sliders only earn their rows while prediction is on. */
    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == edgeLookaheadSetting || setting == legitWhileSneakingSetting
                || setting == legitWhileInAirSetting) {
            return isLegitMode();
        }
        if (setting == sneakSetting || setting == sneakEverySetting
                || setting == startSneakingSetting || setting == stopSneakingSetting
                || setting == sneakSpeedSetting) {
            // Legit holds sneak itself, so the timed sneak cycle has nothing to do there.
            if (isLegitMode()) return false;
            if (setting == sneakSetting) return true;
            return sneakSetting.getValue();
        }
        if (setting == bootstrapBackoffSetting || setting == predictionCutoffSetting
                || setting == warmupPlacementsSetting) {
            return predictionSetting.getValue();
        }
        return true;
    }

    /** LB's two-part tag: the rotation style, plus the tower when one is selected. */
    @Override
    public String getTag() {
        if (isLegitMode()) return MODE_LEGIT;
        String tower = towerMode();
        return TOWER_NONE.equals(tower)
                ? rotationSetting.getValue()
                : rotationSetting.getValue() + " | " + tower;
    }

    /** AutoSprint must not immediately undo Scaffold's NCP sprint control. */
    public static boolean isSprintSuppressed() {
        return sprintSuppressed;
    }

    private boolean isStrictMovementFix() {
        return MOVE_FIX_STRICT.equals(movementFixSetting.getValue());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        aimRequestedThisTick = false;
        if (!isEnabled()) {
            sprintSuppressed = false;
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || mc.currentScreen != null
                || mc.playerController == null || player.isDead || player.isSpectator()
                || player.isRiding()) {
            sprintSuppressed = false;
            restoreJumpKey();
            restoreSprintKey();
            resetSneak();
            return;
        }

        tickScaffold(player);
        // Nothing to aim at this tick: carry the server's view back to the camera instead of
        // dropping the request, which would snap it there in a single packet.
        if (!aimRequestedThisTick) returnAim(player);
    }

    private void tickScaffold(EntityPlayerSP player) {
        sprintSuppressed = updateSprintControl(player);
        suppressVanillaUse();

        int tick = player.ticksExisted;

        // NCP tower rides the raw jump key and suppresses vanilla's jump so the exact
        // 0.41998 motion is the one applied to the player this tick.
        boolean ncpTower = TOWER_NCP.equals(towerMode())
                && !hasMovementInput(player) && isJumpHeld();
        updateJumpKey(ncpTower);
        if (ncpTower) applyNcpTower(player);

        // The block a full-height player stands on, derived from the feet so slabs and other
        // half blocks resolve to their own cell. Raven and LiquidBounce use the same rule.
        int feetLevel = MathHelper.floor_double(player.getEntityBoundingBox().minY - 0.5D);
        if (player.onGround) launchY = feetLevel;
        if (launchY == Integer.MIN_VALUE) launchY = feetLevel;

        String tower = towerMode();
        boolean sameY = TOWER_SAME_Y.equals(tower);
        // A tower only builds up while the jump key is actually held and no direction is
        // pressed, exactly like Raven's Tower. Every other airborne tick keeps the bridge on
        // the level the player launched from instead of following the feet up into the air.
        boolean towering = !sameY && !TOWER_NONE.equals(tower) && isJumpHeld()
                && !player.onGround && !hasMovementInput(player);

        // Place Delay only holds the click back; the aim below still turns on every tick.
        int baseY;
        if (sameY) {
            baseY = launchY;
        } else if (towering) {
            baseY = feetLevel;
        } else if (!player.onGround) {
            // min() follows the player down if they fall below the launch level, so a clutch
            // still places under them, while a jump keeps the bridge flat.
            baseY = Math.min(feetLevel, launchY);
        } else {
            baseY = feetLevel;
        }

        double[] predicted = predictedPosition(player);
        BlockPos base = new BlockPos(MathHelper.floor_double(predicted[0]), baseY,
                MathHelper.floor_double(predicted[1]));
        // A prediction that lands on a wall or an existing block is not a reason to skip the
        // tick; the cell under the feet is still the one being bridged.
        BlockPos feet = new BlockPos(MathHelper.floor_double(player.posX), baseY,
                MathHelper.floor_double(player.posZ));
        if (!base.equals(feet) && !isReplaceable(base)) base = feet;
        if (isLegitMode()) {
            // Legit is Eagle with placement behind it: the edge scan both decides the sneak
            // and names the gap cell to fill, so the two can never disagree.
            BlockPos gap = updateLegitControl(player, base.getY());
            if (gap == null) return;
            base = gap;
        } else {
            // The sneak key follows the bridge: it goes down for the burst of placements and is
            // released once Stop Sneaking has passed since the last one.
            updateSneakControl(player, base);
        }
        if (!isReplaceable(base)) {
            skip(player, "base cell solid " + base);
            return;
        }
        // The server refuses a block in a cell the player's own box still covers and the
        // client is left with a ghost. That is what a tower looked like while it started.
        if (player.getEntityBoundingBox().intersectsWith(new AxisAlignedBB(
                base.getX(), base.getY(), base.getZ(),
                base.getX() + 1.0D, base.getY() + 1.0D, base.getZ() + 1.0D))) {
            skip(player, "base cell inside player " + base);
            return;
        }

        if (!ensureBlock(player)) {
            skip(player, "no placeable block in hand");
            return;
        }

        Placement placement = findPlacement(player, base);
        if (placement == null) {
            skip(player, "no reachable face on " + base);
            return;
        }

        // The aim turns toward the face on every tick it has a target, not only on the ticks
        // that end in a placement. Smoothing is a run of small moves spread over ticks, so
        // stepping once per placement and holding still in between is exactly the pattern the
        // curve exists to avoid; the server keeps seeing the view move the way a hand moves it.
        boolean aimArrived = turnAim(player, placement);

        if (!ClickState.canUse(tick)) {
            skip(player, "attack this tick");
            return;
        }
        if (normalPlacementTick == tick) return;
        if (tick - lastPlaceTick < placementDelay()) return;
        if (!aimArrived) {
            skip(player, "aim still turning");
            return;
        }
        // The click packet leaves before this tick's look reaches the server, so the
        // interaction is checked against the rotation the server already has. Placing while
        // that rotation is not on the face is what NCP's direction checks cancel, and a
        // cancelled placement leaves the client standing on a block the server never got.
        if (!serverAimReaches(player, placement)) {
            skip(player, "server aim not on face");
            return;
        }

        place(player, placement, tick);
    }

    /**
     * Places in the pre-motion window: the block-place packet leaves before this tick's
     * movement packet, the order a vanilla right click produces and Rise's "Pre" mode uses.
     * The silent aim for the tick is written into the movement packet that follows.
     */
    private void place(EntityPlayerSP player, Placement placement, int tick) {
        ItemStack held = player.getHeldItem();
        if (!isPlaceableBlock(held)) return;

        if (mc.playerController.onPlayerRightClick(player, mc.theWorld, held,
                placement.position, placement.side, placement.hit)) {
            if (swingSetting.getValue()) player.swingItem();
            ClickState.markUse(tick);
            normalPlacementTick = tick;
            lastPlaceTick = tick;
            placementsSinceEnable++;
            onSneakPlacement();
        } else {
            skip(player, "controller rejected " + placement.position + " " + placement.side);
        }
    }

    /**
     * Finds a clickable neighbour of the target cell and the point on its face the eyes can
     * actually see. The rotation mode decides where on the face that point sits; each candidate
     * is turned into the rotation a player would need and, unless sight checks are off,
     * ray-traced so only faces the ray really lands on are kept. Of those the one closest to the
     * current server aim wins (LiquidBounce's rule), so the next rotation stays close to the one
     * the server already has instead of swinging across the screen.
     */
    private Placement findPlacement(EntityPlayerSP player, BlockPos base) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        double maxRange = Math.min(rangeSetting.getValue(), mc.playerController.getBlockReachDistance());
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return null;
        ItemBlock itemBlock = (ItemBlock) held.getItem();

        String mode = rotationModeSetting.getValue();
        if (isYawMode(mode)) {
            Placement yawPlacement = findYawPlacement(player, base, eyes, maxRange, itemBlock, held, mode);
            if (yawPlacement != null) return yawPlacement;
        }

        float currentYaw = Float.isNaN(sentAimYaw) ? player.rotationYaw : sentAimYaw;
        float currentPitch = Float.isNaN(sentAimPitch) ? player.rotationPitch : sentAimPitch;

        Placement best = null;
        float bestDifference = Float.MAX_VALUE;
        for (EnumFacing face : getFacingsSorted(player)) {
            if (face == EnumFacing.UP) continue;
            BlockPos supportPos = base.offset(face);
            if (!isSupport(supportPos)) continue;
            EnumFacing clickedFace = face.getOpposite();
            if (!itemBlock.canPlaceBlockOnSide(mc.theWorld, supportPos, clickedFace, player, held)) {
                continue;
            }

            for (double[] sample : samplesFor(mode, supportPos, clickedFace, player)) {
                if (sample == null) continue;
                Vec3 hit = facePoint(supportPos, clickedFace, sample);
                if (eyes.distanceTo(hit) > maxRange) continue;
                if (!passesMinDist(eyes, clickedFace, hit)) continue;

                float[] rotations = RotationUtil.getRotations(hit);
                if (requiresSightSetting.getValue()
                        && !rayHits(player, rotations[0], rotations[1], supportPos, clickedFace)) {
                    continue;
                }

                float difference = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - currentYaw))
                        + Math.abs(rotations[1] - currentPitch);
                if (best == null || difference < bestDifference) {
                    best = new Placement(supportPos, clickedFace, hit, rotations);
                    bestDifference = difference;
                }
            }
        }
        return best;
    }

    /** Points sampled on the face by the selected rotation mode. */
    private double[][] samplesFor(String mode, BlockPos supportPos, EnumFacing clickedFace,
                                  EntityPlayerSP player) {
        if (AIM_CENTER.equals(mode)) return CENTER_POINTS;
        if (AIM_EDGE_POINT.equals(mode)) return edgePoint(supportPos, clickedFace, player);
        if (AIM_RANDOM.equals(mode)) return randomPoint(supportPos, clickedFace);
        if (AIM_NEAREST.equals(mode)) {
            double[] point = nearestPointOnFace(supportPos, clickedFace, player);
            if (point == null) return FACE_POINTS;
            // The projected point leads, with the ordinary samples behind it so a point that
            // grazes the face edge cannot leave the module with nothing to click.
            double[][] samples = new double[FACE_POINTS.length + 1][];
            samples[0] = point;
            System.arraycopy(FACE_POINTS, 0, samples, 1, FACE_POINTS.length);
            return samples;
        }
        return FACE_POINTS;
    }

    /**
     * One random point per cell and face, so the aim stays on that point while the block is
     * being placed instead of jumping between random points every tick.
     */
    private double[][] randomPoint(BlockPos supportPos, EnumFacing clickedFace) {
        long seed = supportPos.getX() * 0x9E3779B97F4A7C15L
                ^ supportPos.getY() * 0xC2B2AE3D27D4EB4FL
                ^ supportPos.getZ() * 0x165667B19E3779F9L
                ^ clickedFace.ordinal() * 0x27D4EB2F165667C5L;
        Random random = new Random(seed);
        double span = 1.0D - 2.0D * FACE_TRIM;
        return new double[][]{{FACE_TRIM + span * random.nextDouble(),
                FACE_TRIM + span * random.nextDouble()}};
    }

    /** The corner of the face furthest from the player, LiquidBounce's EdgePoint. */
    private double[][] edgePoint(BlockPos supportPos, EnumFacing clickedFace, EntityPlayerSP player) {
        double[] best = EDGE_POINTS[0];
        double bestDistance = -1.0D;
        for (double[] point : EDGE_POINTS) {
            Vec3 hit = facePoint(supportPos, clickedFace, point);
            double dx = hit.xCoord - player.posX;
            double dy = hit.yCoord - player.posY;
            double dz = hit.zCoord - player.posZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > bestDistance) {
                bestDistance = distance;
                best = point;
            }
        }
        return new double[][]{best};
    }

    /**
     * Nearest point of the face to the rotation the server currently holds: the aim ray is
     * projected onto the face plane and clamped into the trimmed face (LB's NearestRotation).
     */
    private double[] nearestPointOnFace(BlockPos supportPos, EnumFacing clickedFace,
                                        EntityPlayerSP player) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        float yaw = Float.isNaN(sentAimYaw) ? player.rotationYaw : sentAimYaw;
        float pitch = Float.isNaN(sentAimPitch) ? player.rotationPitch : sentAimPitch;
        Vec3 direction = lookVector(yaw, pitch);

        double plane;
        double origin;
        double along;
        switch (clickedFace) {
            case EAST:
                plane = supportPos.getX() + 1.0D;
                origin = eyes.xCoord;
                along = direction.xCoord;
                break;
            case WEST:
                plane = supportPos.getX();
                origin = eyes.xCoord;
                along = direction.xCoord;
                break;
            case SOUTH:
                plane = supportPos.getZ() + 1.0D;
                origin = eyes.zCoord;
                along = direction.zCoord;
                break;
            case NORTH:
                plane = supportPos.getZ();
                origin = eyes.zCoord;
                along = direction.zCoord;
                break;
            case UP:
                plane = supportPos.getY() + 1.0D;
                origin = eyes.yCoord;
                along = direction.yCoord;
                break;
            case DOWN:
                plane = supportPos.getY();
                origin = eyes.yCoord;
                along = direction.yCoord;
                break;
            default:
                return null;
        }
        if (Math.abs(along) < 1.0E-6D) return null;
        double distance = (plane - origin) / along;
        if (distance <= 0.0D) return null;

        return faceCoordinates(supportPos, clickedFace,
                eyes.xCoord + direction.xCoord * distance,
                eyes.yCoord + direction.yCoord * distance,
                eyes.zCoord + direction.zCoord * distance);
    }

    /** Converts a world point on the face plane back to the face's unit square, trimmed. */
    private double[] faceCoordinates(BlockPos pos, EnumFacing clickedFace,
                                     double x, double y, double z) {
        double u;
        double v;
        switch (clickedFace) {
            case UP:
            case DOWN:
                u = x - pos.getX();
                v = z - pos.getZ();
                break;
            case EAST:
            case WEST:
                u = z - pos.getZ();
                v = y - pos.getY();
                break;
            default:
                u = x - pos.getX();
                v = y - pos.getY();
                break;
        }
        double margin = 1.0D - FACE_TRIM;
        return new double[]{Math.max(FACE_TRIM, Math.min(margin, u)),
                Math.max(FACE_TRIM, Math.min(margin, v))};
    }

    /**
     * LB's MinDist: refuses a side face the eyes are standing almost flush against, which is the
     * aim that turns into a ghost block under the player.
     */
    private boolean passesMinDist(Vec3 eyes, EnumFacing clickedFace, Vec3 hit) {
        double minDist = minDistSetting.getValue();
        if (minDist <= 0.0D) return true;
        double dx = hit.xCoord - eyes.xCoord;
        double dz = hit.zCoord - eyes.zCoord;
        if (clickedFace == EnumFacing.EAST || clickedFace == EnumFacing.WEST) {
            return Math.abs(dx) >= minDist;
        }
        if (clickedFace == EnumFacing.NORTH || clickedFace == EnumFacing.SOUTH) {
            return Math.abs(dz) >= minDist;
        }
        return true;
    }

    private boolean isYawMode(String mode) {
        return AIM_REVERSE_YAW.equals(mode) || AIM_DIAGONAL_YAW.equals(mode)
                || AIM_ANGLE_YAW.equals(mode);
    }

    private float yawOffset(String mode) {
        if (AIM_REVERSE_YAW.equals(mode)) return 180.0F;
        if (AIM_DIAGONAL_YAW.equals(mode)) return 75.0F;
        return 45.0F;
    }

    /**
     * ReverseYaw, DiagonalYaw and AngleYaw aim at a point whose yaw is the camera's yaw plus or
     * minus the mode's angle, the way LB offsets the aim while bridging. If no point on any face
     * lands within a few degrees of that yaw, the nearest-rotation aim is used instead.
     */
    private Placement findYawPlacement(EntityPlayerSP player, BlockPos base, Vec3 eyes,
                                       double maxRange, ItemBlock itemBlock, ItemStack held,
                                       String mode) {
        float cameraYaw = player.rotationYaw;
        float targetHigh = MathHelper.wrapAngleTo180_float(cameraYaw + yawOffset(mode));
        float targetLow = MathHelper.wrapAngleTo180_float(cameraYaw - yawOffset(mode));

        Placement best = null;
        float bestDifference = Float.MAX_VALUE;
        for (EnumFacing face : getFacingsSorted(player)) {
            if (face == EnumFacing.UP) continue;
            BlockPos supportPos = base.offset(face);
            if (!isSupport(supportPos)) continue;
            EnumFacing clickedFace = face.getOpposite();
            if (!itemBlock.canPlaceBlockOnSide(mc.theWorld, supportPos, clickedFace, player, held)) {
                continue;
            }

            // Score the grid without tracing a ray for every point, then trace the winner.
            double[] bestSample = null;
            float bestSampleDifference = Float.MAX_VALUE;
            for (double u : YAW_GRID) {
                for (double v : YAW_GRID) {
                    Vec3 hit = facePoint(supportPos, clickedFace, new double[]{u, v});
                    if (eyes.distanceTo(hit) > maxRange) continue;
                    if (!passesMinDist(eyes, clickedFace, hit)) continue;
                    float[] rotations = RotationUtil.getRotations(hit);
                    float difference = Math.min(
                            Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - targetHigh)),
                            Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - targetLow)));
                    if (difference < bestSampleDifference) {
                        bestSampleDifference = difference;
                        bestSample = new double[]{u, v};
                    }
                }
            }
            if (bestSample == null || bestSampleDifference > YAW_TOLERANCE) continue;

            Vec3 hit = facePoint(supportPos, clickedFace, bestSample);
            float[] rotations = RotationUtil.getRotations(hit);
            if (requiresSightSetting.getValue()
                    && !rayHits(player, rotations[0], rotations[1], supportPos, clickedFace)) {
                continue;
            }
            if (bestSampleDifference < bestDifference) {
                bestDifference = bestSampleDifference;
                best = new Placement(supportPos, clickedFace, hit, rotations);
            }
        }
        return best;
    }

    /** Raven's facing order, anchored on the direction the player is moving. */
    private EnumFacing[] getFacingsSorted(EntityPlayerSP player) {
        Vec3 direction = moveDirection(player);
        float yaw = direction == null
                ? player.rotationYaw
                : (float) Math.toDegrees(Math.atan2(-direction.xCoord, direction.zCoord));
        EnumFacing forward = EnumFacing.getHorizontal(
                MathHelper.floor_double(yaw * 4.0F / 360.0F + 0.5D) & 3);
        return new EnumFacing[]{EnumFacing.DOWN, forward.getOpposite(), forward.rotateY(),
                forward.rotateYCCW(), forward, EnumFacing.UP};
    }

    /** Scalable support check: solid, not replaceable and not a container we never click. */
    private boolean isSupport(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (!block.getMaterial().isSolid() || block.getMaterial().isReplaceable()) return false;
        return !BLACKLIST.contains(block);
    }

    /**
     * Maps a sampled point of the face's unit square onto the face plane. The inner corners
     * give the aim margin, so a snapped mouse step still lands on the face instead of slipping
     * off an edge.
     */
    private Vec3 facePoint(BlockPos pos, EnumFacing clickedFace, double[] point) {
        double u = point[0];
        double v = point[1];
        switch (clickedFace) {
            case UP:
                return new Vec3(pos.getX() + u, pos.getY() + 1.0D, pos.getZ() + v);
            case DOWN:
                return new Vec3(pos.getX() + u, pos.getY(), pos.getZ() + v);
            case EAST:
                return new Vec3(pos.getX() + 1.0D, pos.getY() + v, pos.getZ() + u);
            case WEST:
                return new Vec3(pos.getX(), pos.getY() + v, pos.getZ() + u);
            case SOUTH:
                return new Vec3(pos.getX() + u, pos.getY() + v, pos.getZ() + 1.0D);
            case NORTH:
                return new Vec3(pos.getX() + u, pos.getY() + v, pos.getZ());
            default:
                return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        }
    }

    /**
     * Advances the invisible aim one tick along the smoothing curve toward the chosen face.
     * This runs on every tick a target exists, whether or not that tick ends in a placement,
     * so the turn is continuous instead of one jump per block. The aim is written into the
     * tick's movement packet, so the camera never moves. Returns whether the aim has arrived
     * at the face, which is what the placement waits for.
     */
    private boolean turnAim(EntityPlayerSP player, Placement placement) {
        if (ROTATION_OFF.equals(rotationSetting.getValue())) return true;
        lastAimTargetTick = player.ticksExisted;

        if (Float.isNaN(aimYaw)) {
            aimYaw = Float.isNaN(sentAimYaw) ? player.rotationYaw : sentAimYaw;
            aimPitch = Float.isNaN(sentAimPitch) ? player.rotationPitch : sentAimPitch;
        }

        // The aim rides the client's own movement packet instead of a look before the
        // placement and another one back after it; that pair put two extra position updates in
        // the tick, which packet rate checks count as timer abuse. The placement goes out in
        // the same pre-motion window Raven uses, and every step is rounded to whole mouse
        // counts so the deltas the server sees stay legal.
        float[] rotations = snapToSentAim(smoothingStep(placement.rotations[0], placement.rotations[1]));
        applyAim(rotations);

        // Clicking while the look has not arrived at the face yet is the shape a rotation
        // check drops, so the caller waits for the aim to reach the block. Without the sight
        // requirement there is no ray to wait for, so the aim reaching the rotation is enough.
        if (!requiresSightSetting.getValue()) return hasArrived(rotations, placement.rotations);
        return rayHits(player, rotations[0], rotations[1], placement.position, placement.side);
    }

    /** Whether the aim is within one mouse count of where it is turning to. */
    private boolean hasArrived(float[] rotations, float[] target) {
        double step = Math.max(0.01D, RotationUtil.mouseStep());
        return Math.abs(MathHelper.wrapAngleTo180_float(target[0] - rotations[0])) <= step
                && Math.abs(target[1] - rotations[1]) <= step;
    }

    /**
     * Hands the tick's rotation to the packet layer. sentAim is the rotation the server is
     * being given this tick, so the next face is picked against it, not against the camera.
     */
    private void applyAim(float[] rotations) {
        aimYaw = rotations[0];
        aimPitch = rotations[1];
        // Strict computes the tick's walk from this rotation. That only makes sense while the
        // aim is close to where the player is looking and walking; a face behind the player
        // would walk them backwards off the bridge, so past 45 degrees movement stays on the
        // camera and only the rotation the server receives turns.
        boolean strict = isStrictMovementFix()
                && Math.abs(MathHelper.wrapAngleTo180_float(
                        rotations[0] - SilentLook.cameraYaw())) <= 45.0F;
        SilentLook.request(rotations[0], rotations[1],
                strict ? SilentLook.MovementFix.STRICT : SilentLook.MovementFix.NONE);
        sentAimYaw = rotations[0];
        sentAimPitch = rotations[1];
        aimRequestedThisTick = true;
    }

    /**
     * Eases the server's view from the last aim back to the camera when the tick found no face
     * to aim at. Dropping the request instead would put the camera rotation in the next packet
     * in one jump, the same tell the smoothing exists to remove. The return follows the
     * selected curve and ends within one mouse count of the camera, after which the player's
     * own rotation is what goes out again.
     */
    private void returnAim(EntityPlayerSP player) {
        if (ROTATION_OFF.equals(rotationSetting.getValue())) {
            aimYaw = Float.NaN;
            aimPitch = Float.NaN;
            return;
        }
        if (Float.isNaN(aimYaw)) return;
        // Another module is aiming this tick; its rotation is the one that should go out, not
        // a return to the camera.
        if (SilentLook.isRequested()) return;

        // A target that blinks out for a tick, like the cell behind a block just placed, is
        // not a reason to start leaving. Hold the view there until it is really gone.
        if (player.ticksExisted - lastAimTargetTick <= AIM_HOLD_TICKS) {
            applyAim(new float[]{aimYaw, aimPitch});
            return;
        }

        float cameraYaw = SilentLook.cameraYaw();
        float cameraPitch = SilentLook.cameraPitch();
        double step = RotationUtil.mouseStep();
        if (step > 0.0D
                && Math.abs(MathHelper.wrapAngleTo180_float(cameraYaw - aimYaw)) < step
                && Math.abs(cameraPitch - aimPitch) < step) {
            aimYaw = Float.NaN;
            aimPitch = Float.NaN;
            // The camera rotation goes out from now on, so that is the server's current aim.
            sentAimYaw = cameraYaw;
            sentAimPitch = cameraPitch;
            return;
        }

        applyAim(snapToSentAim(smoothingStep(cameraYaw, cameraPitch)));
    }

    /**
     * One tick of the selected smoothing curve toward the face rotation. Each speed range is
     * that axis's turn limit in degrees per tick: 0 leaves the axis where it is and 180, a full
     * half turn, is effectively no limit, the snappy end of the range. Separating the axes lets
     * the vertical aim be smoothed without slowing the horizontal turn, and the value drawn
     * inside the range every tick keeps the turn from stepping at one flat rate.
     */
    private float[] smoothingStep(float targetYaw, float targetPitch) {
        String smoothing = smoothingSetting.getValue();
        double yawSpeed = RotationUtil.speed(yawSpeedSetting.random());
        double pitchSpeed = RotationUtil.speed(pitchSpeedSetting.random());
        float yaw = aimYaw + (float) RotationUtil.curveStep(
                MathHelper.wrapAngleTo180_float(targetYaw - aimYaw), yawSpeed, smoothing);
        float pitch = MathHelper.clamp_float(
                aimPitch + (float) RotationUtil.curveStep(targetPitch - aimPitch, pitchSpeed,
                        smoothing),
                -90.0F, 90.0F);

        // A step under one mouse count would round to nothing, so the aim settles on the face
        // as soon as it is that close.
        double step = RotationUtil.mouseStep();
        if (step > 0.0D) {
            if (Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - yaw)) < step) yaw = targetYaw;
            if (Math.abs(targetPitch - pitch) < step) pitch = targetPitch;
        }
        return new float[]{yaw, pitch};
    }

    /** Snaps an aim to the mouse grid measured from the rotation the server already has. */
    private float[] snapToSentAim(float[] target) {
        float baseYaw = Float.isNaN(sentAimYaw) ? target[0] : sentAimYaw;
        float basePitch = Float.isNaN(sentAimPitch) ? target[1] : sentAimPitch;
        return RotationUtil.snapToMouse(target[0], target[1], baseYaw, basePitch);
    }

    /** True when a look along these rotations lands on exactly this block face. */
    private boolean rayHits(EntityPlayerSP player, float yaw, float pitch,
                            BlockPos block, EnumFacing side) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 direction = lookVector(yaw, pitch);
        double reach = Math.min(rangeSetting.getValue(), mc.playerController.getBlockReachDistance());
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes,
                eyes.addVector(direction.xCoord * reach, direction.yCoord * reach,
                        direction.zCoord * reach), false, false, true);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && block.equals(hit.getBlockPos()) && hit.sideHit == side;
    }

    /**
     * True when the rotation the server currently holds already lands on the face about to be
     * clicked. The click packet leaves before this tick's look, so this, not the new aim, is
     * the rotation the server checks the interaction against.
     */
    private boolean serverAimReaches(EntityPlayerSP player, Placement placement) {
        float yaw = Float.isNaN(sentAimYaw) ? player.rotationYaw : sentAimYaw;
        float pitch = Float.isNaN(sentAimPitch) ? player.rotationPitch : sentAimPitch;
        return rayHits(player, yaw, pitch, placement.position, placement.side);
    }

    /** Vanilla's look vector for a pair of rotations. */
    private static Vec3 lookVector(float yaw, float pitch) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(f1 * f2, f3, f * f2);
    }

    /**
     * Vanilla treats a held right click as a second placement source. Cancel the mouse
     * press before it updates the use key, and pin the cooldown for a press that was
     * already held when Scaffold was enabled. Scaffold calls the controller directly,
     * so its own placement is unaffected.
     */
    private void suppressVanillaUse() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

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
            rightClickDelayField.setInt(mc, 4);
        } catch (Exception ignored) {
        }
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (!isEnabled() || mc.currentScreen != null) return;
        if (event.button != 1 || !event.buttonstate) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemBlock) {
            event.setCanceled(true);
        }
    }

    /**
     * NCP's scaffold checks look for sprinting during a placement, so None releases the sprint
     * key and clears the state. AutoSprint is blocked through isSprintSuppressed() so it cannot
     * re-enable sprint on the placement tick.
     */
    private boolean updateSprintControl(EntityPlayerSP player) {
        boolean suppress = SPRINT_NONE.equals(sprintSetting.getValue());

        if (suppress) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
            sprintKeySuppressed = true;
            player.setSprinting(false);
        } else {
            restoreSprintKey();
        }
        return suppress;
    }

    /** Runs after AutoSprint and vanilla so sprint cannot return on the placement tick. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;

        if (sprintSuppressed) {
            if (sprintKeySuppressed) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
            }
            player.setSprinting(false);
        }
    }

    private boolean isJumpHeld() {
        int key = mc.gameSettings.keyBindJump.getKeyCode();
        return key > 0 && key < 256 && Keyboard.isKeyDown(key);
    }

    private void updateJumpKey(boolean suppress) {
        if (suppress) {
            jumpKeySuppressed = true;
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        } else if (jumpKeySuppressed) {
            restoreJumpKey();
        }
    }

    private void restoreJumpKey() {
        if (!jumpKeySuppressed) return;
        jumpKeySuppressed = false;
        int key = mc.gameSettings.keyBindJump.getKeyCode();
        KeyBinding.setKeyBindState(key, key > 0 && key < 256 && Keyboard.isKeyDown(key));
    }

    private void restoreSprintKey() {
        if (!sprintKeySuppressed) return;
        sprintKeySuppressed = false;
        int key = mc.gameSettings.keyBindSprint.getKeyCode();
        KeyBinding.setKeyBindState(key, key > 0 && key < 256 && Keyboard.isKeyDown(key));
    }

    /**
     * Rise's sneak cycle: while bridging, the sneak key is held for a window around the
     * placements instead of sitting down the whole time. Sneak Every X Blocks counts blocks
     * that were really placed: once X-1 of them are down the next placement is the trigger, so
     * the window opens before it and the Xth block is placed while sneaking. Only that
     * placement resets the count, and every placement inside the window pushes its end back by
     * Stop Sneaking.
     */
    private void updateSneakControl(EntityPlayerSP player, BlockPos base) {
        if (!sneakSetting.getValue()) {
            resetSneak();
            return;
        }

        if (sneakingTicks >= 0) sneakingTicks--;
        if (sneakingTicks < 0 && blocksSinceSneak >= sneakEveryBlocks() - 1
                && isBridging(player, base)) {
            sneakingTicks = sneakWindowTicks();
        }

        if (sneakingTicks >= 0) holdSneak(player);
        else releaseSneak();
    }

    private int sneakEveryBlocks() {
        return Math.max(1, (int) Math.round(sneakEverySetting.getValue()));
    }

    private boolean isBridging(EntityPlayerSP player, BlockPos base) {
        if (!hasMovementInput(player)) return false;
        return isReplaceable(base) || gapAhead(player, base);
    }

    /** True when the next cell in the walk direction is air, the tick before a placement. */
    private boolean gapAhead(EntityPlayerSP player, BlockPos base) {
        Vec3 direction = moveDirection(player);
        if (direction == null) return false;
        double x = player.posX + direction.xCoord * 0.6D;
        double z = player.posZ + direction.zCoord * 0.6D;
        BlockPos ahead = new BlockPos(MathHelper.floor_double(x), base.getY(),
                MathHelper.floor_double(z));
        return !ahead.equals(base) && isReplaceable(ahead);
    }

    /**
     * Legit's edge guard, the edge Rise's LegitScaffold reads: the block under the player's
     * centre. The probe also looks one tick of travel ahead, so the key is already down for
     * the step that crosses the edge instead of the step after it. The gap it finds is the
     * cell placed into, which keeps the guard and the click on the same block.
     */
    private BlockPos updateLegitControl(EntityPlayerSP player, int bridgeY) {
        if (legitWhileSneakingSetting.getValue() && !isSneakKeyHeld()) {
            legitSneaking = false;
            legitReleaseTicks = 0;
            releaseSneak();
            return null;
        }

        // While In Air holds the key through a jump so the landing is already guarded, but
        // Legit only places at an edge with the feet on the ground.
        if (!player.onGround && !player.capabilities.isFlying && !player.isInWater()) {
            if (legitWhileInAirSetting.getValue() && legitSneaking) {
                holdSneak(player, false);
            } else {
                legitSneaking = false;
                legitReleaseTicks = 0;
                releaseSneak();
            }
            return null;
        }

        BlockPos gap = legitGapAhead(player, bridgeY);
        if (gap != null) {
            legitSneaking = true;
            legitReleaseTicks = LEGIT_RELEASE_TICKS + (Math.random() < 0.5D ? 1 : 0);
            holdSneak(player, false);
            return gap;
        }

        // Back over support: keep the key for the short release delay so the step onto the
        // block that was just placed finishes before the walk speed returns.
        if (legitSneaking) {
            if (legitReleaseTicks > 0) {
                legitReleaseTicks--;
                holdSneak(player, false);
                return null;
            }
            legitSneaking = false;
            releaseSneak();
        }
        return null;
    }

    /**
     * The support cell the feet are about to leave. A centre already over the gap counts, so
     * the key stays down while the player hangs on the lip with no input. Otherwise the cell
     * one tick of travel ahead is probed, which is the cell the step that leaves the block
     * would land on. Edge Lookahead adds to that probe when a bigger cushion before the edge
     * is wanted; 0 is Rise's edge exactly.
     */
    private BlockPos legitGapAhead(EntityPlayerSP player, int bridgeY) {
        if (player.capabilities.isFlying || player.isInWater()) return null;

        BlockPos under = new BlockPos(MathHelper.floor_double(player.posX), bridgeY,
                MathHelper.floor_double(player.posZ));
        if (isReplaceable(under)) return under;

        Vec3 direction = moveDirection(player);
        if (direction == null) return null;

        double speed = Math.sqrt(player.motionX * player.motionX
                + player.motionZ * player.motionZ);
        double reach = Math.max(speed, LEGIT_MIN_STEP) + edgeLookaheadSetting.getValue();
        BlockPos ahead = new BlockPos(
                MathHelper.floor_double(player.posX + direction.xCoord * reach), bridgeY,
                MathHelper.floor_double(player.posZ + direction.zCoord * reach));
        if (ahead.equals(under) || !isReplaceable(ahead)) return null;
        return ahead;
    }

    private boolean isSneakKeyHeld() {
        int code = mc.gameSettings.keyBindSneak.getKeyCode();
        return code > 0 && code < 256 && Keyboard.isKeyDown(code);
    }

    /** Start Sneaking is the time the key goes down early, Stop Sneaking the time it stays down. */
    private int sneakWindowTicks() {
        return Math.max(1, startSneakTicks() + stopSneakTicks());
    }

    private int startSneakTicks() {
        return (int) Math.round(startSneakingSetting.getValue() / MS_PER_TICK);
    }

    private int stopSneakTicks() {
        return (int) Math.round(stopSneakingSetting.getValue() / MS_PER_TICK);
    }

    /**
     * Counts the block that was just placed. Reaching Sneak Every X Blocks restarts the cycle
     * and the window stays up for Stop Sneaking after the placement, so the next block of the
     * burst is covered too.
     */
    private void onSneakPlacement() {
        if (!sneakSetting.getValue()) return;
        int every = sneakEveryBlocks();
        blocksSinceSneak = blocksSinceSneak + 1 >= every ? 0 : blocksSinceSneak + 1;
        if (sneakingTicks >= 0) sneakingTicks = Math.max(sneakingTicks, stopSneakTicks());
    }

    private void holdSneak(EntityPlayerSP player) {
        holdSneak(player, true);
    }

    private void holdSneak(EntityPlayerSP player, boolean applySneakSpeed) {
        sneakKeyForced = true;
        int code = mc.gameSettings.keyBindSneak.getKeyCode();
        if (code > 0 && code < 256 && !Keyboard.isKeyDown(code)) {
            KeyBinding.setKeyBindState(code, true);
        }
        // Legit keeps vanilla's 0.3 walk; only the timed sneak cycle uses Sneak Speed.
        if (applySneakSpeed) installSneakSpeedInput(player);
    }

    private void releaseSneak() {
        if (!sneakKeyForced) return;
        sneakKeyForced = false;
        uninstallSneakSpeedInput();
        // Never release a key the player is physically holding.
        int code = mc.gameSettings.keyBindSneak.getKeyCode();
        if (code > 0 && code < 256 && !Keyboard.isKeyDown(code)) {
            KeyBinding.setKeyBindState(code, false);
        }
    }

    private void resetSneak() {
        sneakingTicks = -1;
        blocksSinceSneak = 0;
        legitSneaking = false;
        legitReleaseTicks = 0;
        releaseSneak();
    }

    /**
     * Sneak Speed replaces vanilla's 0.3 movement multiplier while Scaffold holds the sneak
     * key. Vanilla applies that multiplier inside the movement input itself, so the input is
     * wrapped for the tick: the delegate samples the keys as usual, and the wrapper divides
     * the 0.3 back out and applies the configured fraction. 1 walks at full speed, 0.2 barely
     * creeps. Like NoSlowdown's wrapper, the delegate is kept so another module's input survives.
     */
    private void installSneakSpeedInput(EntityPlayerSP player) {
        MovementInputStack.install("Scaffold", MovementInputStack.PRIORITY_SNEAK_SPEED,
                inner -> new SneakSpeedInput(inner, this));
    }

    private void uninstallSneakSpeedInput() {
        MovementInputStack.uninstall("Scaffold");
    }

    /** The wrapper exists for the player update only, never across a tick boundary. */
    @SubscribeEvent
    public void onTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        uninstallSneakSpeedInput();
    }

    private static final class SneakSpeedInput extends MovementInput {

        private final MovementInput delegate;
        private final Scaffold module;

        private SneakSpeedInput(MovementInput delegate, Scaffold module) {
            this.delegate = delegate;
            this.module = module;
        }

        @Override
        public void updatePlayerMoveState() {
            delegate.updatePlayerMoveState();
            moveForward = delegate.moveForward;
            moveStrafe = delegate.moveStrafe;
            jump = delegate.jump;
            sneak = delegate.sneak;
            if (!sneak) return;

            float scale = (float) (module.sneakSpeedSetting.getValue()
                    / VANILLA_SNEAK_SLOWDOWN);
            moveForward *= scale;
            moveStrafe *= scale;
        }
    }

    /**
     * Rise's NCP tower: snap to the block floor at the boundary and apply 0.41998 with
     * only a sub-epsilon random reduction. The second branch drops an early airborne
     * fraction back to the floor without refreshing motion.
     */
    private void applyNcpTower(EntityPlayerSP player) {
        double fraction = player.posY % 1.0D;
        if (fraction <= NCP_TOWER_SNAP) {
            player.setPosition(player.posX, Math.floor(player.posY), player.posZ);
            player.motionY = NCP_TOWER_MOTION - Math.random() * 1.0E-9D;
        } else if (fraction < 0.1D && !player.onGround) {
            player.setPosition(player.posX, Math.floor(player.posY), player.posZ);
        }
    }

    /** TEMPORARY: one throttled log line per skip reason, for diagnosing a placement. */
    private void skip(EntityPlayerSP player, String reason) {
        int tick = player.ticksExisted;
        if (reason.equals(lastSkipReason) && tick - lastSkipLogTick < 20) return;
        lastSkipReason = reason;
        lastSkipLogTick = tick;
        LOGGER.info("[Scaffold] skip: {} pos={}/{}/{} yaw={} pitch={} onGround={} motionY={}",
                reason, player.posX, player.posY, player.posZ,
                player.rotationYaw, player.rotationPitch, player.onGround, player.motionY);
    }

    /** A random wait inside the Delay range, in ticks, like LB's integer range. */
    private int placementDelay() {
        int low = (int) Math.round(delaySetting.getLow());
        int high = (int) Math.round(delaySetting.getHigh());
        if (high < low) {
            int swap = low;
            low = high;
            high = swap;
        }
        low = Math.max(0, low);
        high = Math.max(low, high);
        return high == low ? low : low + RANDOM.nextInt(high - low + 1);
    }

    /**
     * Where the player will stand when this tick's placement goes out. LB predicts the position
     * so the bridge is built where the feet are about to be rather than behind them; the
     * Bootstrap Backoff and Warmup Placements settings keep the guess short until a few
     * placements have shown how fast the player is really moving.
     */
    private double[] predictedPosition(EntityPlayerSP player) {
        double stillX = player.posX;
        double stillZ = player.posZ;
        if (!predictionSetting.getValue()) return new double[]{stillX, stillZ};

        double motionX = player.motionX;
        double motionZ = player.motionZ;
        double speed = Math.sqrt(motionX * motionX + motionZ * motionZ);
        if (speed < 1.0E-4D) return new double[]{stillX, stillZ};

        double blend = warmupBlend();
        if (blend <= 0.0D) return new double[]{stillX, stillZ};

        double distance = speed * blend;
        double backoff = (1.0D - blend) * bootstrapBackoffSetting.getValue();
        double keep = distance - backoff;
        if (keep <= 1.0E-4D) return new double[]{stillX, stillZ};

        double dx = motionX / speed * keep;
        double dz = motionZ / speed * keep;
        // Standing right on a cell boundary, the block under the feet is the one to place. In
        // the air that rule does not apply: the arc is already committed and the cell the
        // player is going to come down on is the one ahead, which is the block a jump bridge
        // needs, so the cutoff only holds the guess back while walking.
        if (player.onGround) {
            double boundary = Math.min(boundaryDistance(stillX, dx), boundaryDistance(stillZ, dz));
            if (boundary < predictionCutoffSetting.getValue()) return new double[]{stillX, stillZ};
        }

        return new double[]{stillX + dx, stillZ + dz};
    }

    /** Prediction is trusted more with every placement, up to the Warmup Placements count. */
    private double warmupBlend() {
        double warmup = warmupPlacementsSetting.getValue();
        if (warmup <= 0.0D) return 1.0D;
        return Math.min(1.0D, placementsSinceEnable / warmup);
    }

    /** Distance from a coordinate to the next block boundary in the direction of travel. */
    private double boundaryDistance(double coordinate, double delta) {
        double fraction = coordinate - Math.floor(coordinate);
        if (delta > 1.0E-6D) return 1.0D - fraction;
        if (delta < -1.0E-6D) return fraction;
        return Double.MAX_VALUE;
    }

    private String towerMode() {
        return towerSetting.getValue();
    }

    private Vec3 normalize(double x, double z) {
        double length = Math.sqrt(x * x + z * z);
        if (length < 1.0E-4D) return null;
        return new Vec3(x / length, 0.0D, z / length);
    }

    private boolean hasMovementInput(EntityPlayerSP player) {
        return player.movementInput != null
                && (player.movementInput.moveForward != 0.0F || player.movementInput.moveStrafe != 0.0F);
    }

    private Vec3 moveDirection(EntityPlayerSP player) {
        // Velocity first: it is world-space, so a rotation applied for a placement cannot feed
        // back into the next direction the way a yaw-relative input vector would.
        if (player.motionX * player.motionX + player.motionZ * player.motionZ >= 0.0009D) {
            return normalize(player.motionX, player.motionZ);
        }
        if (!hasMovementInput(player)) return null;

        float forward = player.movementInput.moveForward;
        float strafe = player.movementInput.moveStrafe;
        float yaw = (float) Math.toRadians(player.rotationYaw);
        double x = forward * -MathHelper.sin(yaw) + strafe * MathHelper.cos(yaw);
        double z = forward * MathHelper.cos(yaw) + strafe * MathHelper.sin(yaw);
        return normalize(x, z);
    }

    private boolean isReplaceable(BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial().isReplaceable();
    }

    /**
     * Auto Switch moves the hotbar to a block slot and keeps it there for as long as the
     * module is on; turning Scaffold off puts the slot the player had before back.
     */
    private boolean ensureBlock(EntityPlayerSP player) {
        if (isPlaceableBlock(player.getHeldItem())) return true;
        if (!autoSwitchSetting.getValue()) return false;

        int slot = findBlockSlot(player);
        if (slot < 0) return false;

        if (autoSwitchOriginalSlot < 0) autoSwitchOriginalSlot = player.inventory.currentItem;
        player.inventory.currentItem = slot;
        sendSlot(slot);
        return true;
    }

    private void restoreAutoSwitch(EntityPlayerSP player) {
        if (autoSwitchOriginalSlot < 0) return;
        int originalSlot = autoSwitchOriginalSlot;
        autoSwitchOriginalSlot = -1;
        if (player != null) {
            player.inventory.currentItem = originalSlot;
            sendSlot(originalSlot);
        }
    }

    private int findBlockSlot(EntityPlayerSP player) {
        int bestSlot = -1;
        int bestSize = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (!isPlaceableBlock(stack) || stack.stackSize <= bestSize) continue;
            bestSlot = i;
            bestSize = stack.stackSize;
        }
        return bestSlot;
    }

    private boolean isPlaceableBlock(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return block.getMaterial().isSolid() && !BLACKLIST.contains(block);
    }

    private void sendSlot(int slot) {
        if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(slot));
        }
    }

    @Override
    public void onEnable() {
        launchY = Integer.MIN_VALUE;
        autoSwitchOriginalSlot = -1;
        normalPlacementTick = -1;
        lastPlaceTick = -100;
        aimYaw = Float.NaN;
        aimPitch = Float.NaN;
        sentAimYaw = Float.NaN;
        sentAimPitch = Float.NaN;
        sprintSuppressed = false;
        placementsSinceEnable = 0;
        resetSneak();
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null) restoreAutoSwitch(mc.thePlayer);
        restoreJumpKey();
        restoreSprintKey();
        sprintSuppressed = false;
        autoSwitchOriginalSlot = -1;
        normalPlacementTick = -1;
        lastPlaceTick = -100;
        launchY = Integer.MIN_VALUE;
        aimYaw = Float.NaN;
        aimPitch = Float.NaN;
        sentAimYaw = Float.NaN;
        sentAimPitch = Float.NaN;
        resetSneak();
    }

    private static class Placement {
        private final BlockPos position;
        private final EnumFacing side;
        private final Vec3 hit;
        private final float[] rotations;

        private Placement(BlockPos position, EnumFacing side, Vec3 hit, float[] rotations) {
            this.position = position;
            this.side = side;
            this.hit = hit;
            this.rotations = rotations;
        }
    }
}
