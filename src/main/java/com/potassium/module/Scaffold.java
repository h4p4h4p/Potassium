package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.ClickState;
import com.potassium.util.MovementFix;
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
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridges in the direction the player is moving. Placement targets are predicted ahead of the
 * player, checked for a reachable support face, and kept on one level while jumping unless
 * Tower is active.
 */
public class Scaffold extends Module {

    public static final String ROTATION_OFF = "Off";
    public static final String ROTATION_NORMAL = "Normal";
    public static final String ROTATION_SILENT = "Silent";

    public static final String TOWER_NONE = "None";
    public static final String TOWER_VANILLA = "Vanilla";
    public static final String TOWER_NCP = "NCP";

    public static final String SPRINT_NORMAL = "Normal";
    public static final String SPRINT_NONE = "None";
    public static final String SPRINT_BYPASS = "Bypass";
    public static final String SPRINT_LEGIT = "Legit";

    // Rise's NCP tower constants.
    private static final double NCP_TOWER_MOTION = 0.41998F;
    private static final double NCP_TOWER_SNAP = 0.00153598D;

    // LiquidBounce Scaffold RotationMode options.
    public static final String AIM_CENTER = "Center";
    public static final String AIM_RANDOM = "Random";
    public static final String AIM_STABILIZED = "Stabilized";
    public static final String AIM_NEAREST_ROTATION = "NearestRotation";

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

    private final ModeSetting rotationSetting = new ModeSetting("Rotations",
            new String[]{ROTATION_OFF, ROTATION_NORMAL, ROTATION_SILENT}, 0);
    private final ModeSetting aimPointSetting = new ModeSetting("Aim Point",
            new String[]{AIM_CENTER, AIM_RANDOM, AIM_STABILIZED, AIM_NEAREST_ROTATION}, 0);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final SliderSetting bridgeReachSetting = new SliderSetting("Bridge Reach", 1.0, 0.0, 3.0, 0.5);
    private final BooleanSetting swingSetting = new BooleanSetting("Swing", true);
    private final BooleanSetting movementFixSetting = new BooleanSetting("Movement Fix", true);
    private final BooleanSetting autoSwitchSetting = new BooleanSetting("Auto Switch", true);
    private final BooleanSetting silentSwapSetting = new BooleanSetting("Silent Swap", false);
    private final BooleanSetting sameYSetting = new BooleanSetting("Same Y", true);
    private final ModeSetting towerSetting = new ModeSetting("Tower",
            new String[]{TOWER_NONE, TOWER_VANILLA, TOWER_NCP}, 1);
    private final ModeSetting sprintSetting = new ModeSetting("Sprint",
            new String[]{SPRINT_NORMAL, SPRINT_NONE, SPRINT_BYPASS, SPRINT_LEGIT}, 0);
    private final SliderSetting placeDelaySetting = new SliderSetting("Place Delay", 0, 0, 5, 1);
    private final BooleanSetting randomisePlaceDelaySetting =
            new BooleanSetting("Randomise Place Delay", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private int bridgeY = Integer.MIN_VALUE;
    private int silentSwapOriginalSlot = -1;
    private int ticksOnAir;
    private int normalPlacementTick = -1;
    private Field rightClickDelayField;
    private float lastOptimalYaw = Float.NaN;
    private float lastAimYaw = Float.NaN;
    private boolean jumpKeySuppressed;
    private boolean sprintKeySuppressed;
    private static boolean sprintSuppressed;

    public Scaffold() {
        super("Scaffold", Category.MOVEMENT);
        settings.add(rotationSetting);
        settings.add(aimPointSetting);
        settings.add(rangeSetting);
        settings.add(bridgeReachSetting);
        settings.add(swingSetting);
        settings.add(movementFixSetting);
        settings.add(autoSwitchSetting);
        settings.add(silentSwapSetting);
        settings.add(sameYSetting);
        settings.add(towerSetting);
        settings.add(sprintSetting);
        settings.add(placeDelaySetting);
        settings.add(randomisePlaceDelaySetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** AutoSprint must not immediately undo Scaffold's NCP sprint control. */
    public static boolean isSprintSuppressed() {
        return sprintSuppressed;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
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
            return;
        }

        sprintSuppressed = updateSprintControl(player);
        suppressVanillaUse();

        int tick = player.ticksExisted;
        if (!ClickState.canUse(tick)) return;
        if (normalPlacementTick == tick) return;

        // NCP tower rides the raw jump key and suppresses vanilla's jump so the exact
        // 0.41998 motion is the one applied to the player this tick.
        boolean ncpTower = TOWER_NCP.equals(towerMode())
                && !hasMovementInput(player) && isJumpHeld();
        updateJumpKey(ncpTower);
        if (ncpTower) applyNcpTower(player);

        int supportY = supportY(player);
        if (player.onGround || bridgeY == Integer.MIN_VALUE) bridgeY = supportY;
        updateAirTicks(player);

        // A straight-up jump is a tower. A jump with movement stays at bridge level so
        // jump-bridging no longer needs Tower enabled.
        boolean towerRise = ncpTower || (player.motionY > 0.0D && !hasMovementInput(player));
        if (towerRise && TOWER_NONE.equals(towerMode())) return;

        int targetY = sameYSetting.getValue() && !towerRise ? bridgeY : supportY;
        if (sameYSetting.getValue() && !towerRise
                && player.getEntityBoundingBox().minY < targetY) {
            targetY = supportY;
        }

        Vec3 direction = towerRise ? null : moveDirection(player);
        List<BlockPos> initialTargets = targetCandidates(player, targetY, direction);
        if (initialTargets.isEmpty()) return;

        if (ticksOnAir > 0 && ticksOnAir <= placementDelay()) return;

        if (!ensureBlock(player)) return;

        try {
            Placement firstPlacement = findPlacement(player, player.getHeldItem(), initialTargets,
                    targetY, direction);
            if (firstPlacement == null) return;

            ItemStack held = player.getHeldItem();
            if (!isPlaceableBlock(held)) return;
            if (!place(player, held, firstPlacement)) return;

            if (swingSetting.getValue()) player.swingItem();
            ClickState.markUse(tick);
            normalPlacementTick = tick;
        } finally {
            restoreSilentSwap(player);
        }
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
     * NCP's scaffold checks look for sprinting during a placement. Bypass only clears the
     * sprint state, while None/Legit also release the sprint key, matching Rise's modes.
     * AutoSprint is blocked through isSprintSuppressed() so it cannot re-enable sprint.
     */
    private boolean updateSprintControl(EntityPlayerSP player) {
        String mode = sprintSetting.getValue();
        boolean suppress = SPRINT_NONE.equals(mode) || SPRINT_BYPASS.equals(mode);
        if (SPRINT_LEGIT.equals(mode)) {
            suppress = !Float.isNaN(lastAimYaw)
                    && Math.abs(MathHelper.wrapAngleTo180_float(player.rotationYaw - lastAimYaw)) > 90.0F;
        }

        if (suppress) {
            if (!SPRINT_BYPASS.equals(mode)) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
                sprintKeySuppressed = true;
            }
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

    private void updateAirTicks(EntityPlayerSP player) {
        BlockPos below = new BlockPos(MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY - 1.0D),
                MathHelper.floor_double(player.posZ));
        if (isReplaceable(below)) {
            ticksOnAir++;
        } else {
            ticksOnAir = 0;
        }
    }

    /** Delay is only applied while over air, preserving the existing pre-edge placement. */
    private int placementDelay() {
        int delay = Math.max(0, (int) Math.round(placeDelaySetting.getValue()));
        if (randomisePlaceDelaySetting.getValue() && !mc.gameSettings.keyBindJump.isKeyDown()) {
            delay += (int) (Math.random() * 3.0D);
        }
        return delay;
    }

    private String towerMode() {
        return towerSetting.getValue();
    }

    /**
     * The block level under the player. Scans actual collision tops rather than assuming
     * every support is a full block, so slabs, stairs, carpets and similar shapes work.
     */
    private int supportY(EntityPlayerSP player) {
        AxisAlignedBB playerBox = player.getEntityBoundingBox();
        double feet = playerBox.minY;
        int minX = MathHelper.floor_double(playerBox.minX);
        int maxX = MathHelper.floor_double(playerBox.maxX);
        int minZ = MathHelper.floor_double(playerBox.minZ);
        int maxZ = MathHelper.floor_double(playerBox.maxZ);
        int minY = MathHelper.floor_double(feet) - 1;
        int maxY = MathHelper.floor_double(feet);

        int bestY = minY;
        double bestTop = Double.NEGATIVE_INFINITY;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    AxisAlignedBB box = blockBox(new BlockPos(x, y, z));
                    if (box == null || box.maxY > feet + 0.02D) continue;
                    if (box.maxX <= playerBox.minX || box.minX >= playerBox.maxX) continue;
                    if (box.maxZ <= playerBox.minZ || box.minZ >= playerBox.maxZ) continue;
                    if (box.maxY > bestTop) {
                        bestTop = box.maxY;
                        bestY = y;
                    }
                }
            }
        }
        return bestY;
    }

    private List<BlockPos> targetCandidates(EntityPlayerSP player, int y, Vec3 direction) {
        List<BlockPos> targets = new ArrayList<>();
        int baseX = MathHelper.floor_double(player.posX);
        int baseZ = MathHelper.floor_double(player.posZ);
        addTargetCandidates(targets, player, baseX, baseZ, y, direction, true);

        // On a bottom slab the feet sit half a block above the target level, so the next
        // same-level block would intersect the player's legs. If the normal scan is empty
        // at the ledge, try the same forward cells one level lower. Support, reach and the
        // held block's placement rules still apply; findPlacement handles the slab that
        // covers the lower support from the eyes.
        if (targets.isEmpty() && direction != null && isHalfBlockStanding(player, y)
                && isAtEdge(player, direction)) {
            addTargetCandidates(targets, player, baseX, baseZ, y - 1, direction, false);
        }
        return targets;
    }

    private void addTargetCandidates(List<BlockPos> targets, EntityPlayerSP player,
                                     int baseX, int baseZ, int y, Vec3 direction,
                                     boolean includeBase) {
        if (includeBase) {
            addTarget(targets, player, new BlockPos(baseX, y, baseZ));
        }

        double reach = Math.max(0.0D, Math.min(bridgeReachSetting.getMax(), bridgeReachSetting.getValue()));
        if (direction == null || reach <= 0.0D) return;
        // A positive reach below one block still has to search the adjacent cell.
        if (reach < 1.0D) reach = 1.0D;

        int steps = Math.max(1, (int) Math.round(reach / 0.5D));
        for (int i = 1; i <= steps; i++) {
            double distance = Math.min(reach, i * 0.5D);
            int x = MathHelper.floor_double(player.posX + direction.xCoord * distance);
            int z = MathHelper.floor_double(player.posZ + direction.zCoord * distance);
            addTarget(targets, player, new BlockPos(x, y, z));

            // A diagonal step needs one of the two orthogonal cells next to the player
            // before the diagonal cell can be supported from the current block.
            if (i == 1 && Math.abs(direction.xCoord) > 0.1D && Math.abs(direction.zCoord) > 0.1D) {
                int sideX = baseX + (direction.xCoord > 0.0D ? 1 : -1);
                int sideZ = baseZ + (direction.zCoord > 0.0D ? 1 : -1);
                addTarget(targets, player, new BlockPos(sideX, y, baseZ));
                addTarget(targets, player, new BlockPos(baseX, y, sideZ));
            }
        }
    }

    /** True when the player stands on a half-height collision top such as a bottom slab. */
    private boolean isHalfBlockStanding(EntityPlayerSP player, int y) {
        double feetOffset = player.getEntityBoundingBox().minY - y;
        return feetOffset >= 0.25D && feetOffset <= 0.75D;
    }

    private void addTarget(List<BlockPos> targets, EntityPlayerSP player, BlockPos pos) {
        if (targets.contains(pos) || !isReplaceable(pos)) return;
        AxisAlignedBB targetBox = AxisAlignedBB.fromBounds(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D);
        if (player.getEntityBoundingBox().intersectsWith(targetBox)) return;
        targets.add(pos);
    }

    private Vec3 moveDirection(EntityPlayerSP player) {
        if (hasMovementInput(player)) {
            float forward = player.movementInput.moveForward;
            float strafe = player.movementInput.moveStrafe;
            float yaw = (float) Math.toRadians(player.rotationYaw);
            double x = forward * -MathHelper.sin(yaw) + strafe * MathHelper.cos(yaw);
            double z = forward * MathHelper.cos(yaw) + strafe * MathHelper.sin(yaw);
            return normalize(x, z);
        }
        if (player.motionX * player.motionX + player.motionZ * player.motionZ < 0.0009D) return null;
        return normalize(player.motionX, player.motionZ);
    }

    private boolean isAtEdge(EntityPlayerSP player, Vec3 direction) {
        int groundY = MathHelper.floor_double(player.getEntityBoundingBox().minY - 0.05D);
        double aheadX = player.posX + direction.xCoord * 0.75D;
        double aheadZ = player.posZ + direction.zCoord * 0.75D;

        BlockPos current = new BlockPos(MathHelper.floor_double(player.posX), groundY,
                MathHelper.floor_double(player.posZ));
        BlockPos ahead = new BlockPos(MathHelper.floor_double(aheadX), groundY,
                MathHelper.floor_double(aheadZ));

        return !mc.theWorld.getBlockState(current).getBlock().getMaterial().isReplaceable()
                && mc.theWorld.getBlockState(ahead).getBlock().getMaterial().isReplaceable();
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

    private boolean isReplaceable(BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial().isReplaceable();
    }

    /**
     * Finds a support face for one of the candidate targets. The clicked face must be close
     * enough, visible from the eyes, and accepted by the held ItemBlock.
     */
    private Placement findPlacement(EntityPlayerSP player, ItemStack held, List<BlockPos> targets,
                                    int targetY, Vec3 direction) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        double maxRange = Math.min(rangeSetting.getValue(), mc.playerController.getBlockReachDistance());
        ItemBlock itemBlock = (ItemBlock) held.getItem();

        for (BlockPos target : targets) {
            boolean lowerFallback = target.getY() < targetY;
            Placement placement = findPlacement(player, itemBlock, held, target, eyes, maxRange,
                    lowerFallback, direction);
            if (placement != null) return placement;
        }
        return null;
    }

    private Placement findPlacement(EntityPlayerSP player, ItemBlock itemBlock, ItemStack held,
                                    BlockPos target, Vec3 eyes, double maxRange,
                                    boolean lowerFallback, Vec3 direction) {
        double bestDistance = maxRange;
        Placement best = null;

        for (EnumFacing face : EnumFacing.values()) {
            BlockPos supportPos = target.offset(face);
            IBlockState supportState = mc.theWorld.getBlockState(supportPos);
            Block support = supportState.getBlock();
            if (!support.getMaterial().isSolid() || support.getMaterial().isReplaceable()) continue;

            AxisAlignedBB box = blockBox(supportPos);
            if (box == null) continue;

            Vec3 hit = faceHit(box, face, player, direction);
            double distance = eyes.distanceTo(hit);
            if (distance > maxRange || distance >= bestDistance) continue;

            EnumFacing clickedFace = face.getOpposite();
            if (!hasLineOfSight(eyes, hit, supportPos, lowerFallback)) continue;
            if (!itemBlock.canPlaceBlockOnSide(mc.theWorld, supportPos, clickedFace, player, held)) continue;

            bestDistance = distance;
            best = new Placement(supportPos, clickedFace, hit);
        }
        return best;
    }

    /**
     * Real collision bounds for a block. Calling setBlockBoundsBasedOnState first is
     * important for slabs because their top/bottom bounds depend on the block state.
     */
    private AxisAlignedBB blockBox(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block.getMaterial().isReplaceable()) return null;

        block.setBlockBoundsBasedOnState(mc.theWorld, pos);
        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, state);
        if (box == null) return null;
        if (box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ) return null;
        return box;
    }

    private static final double FACE_TRIM = 0.15D;
    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    /**
     * A point on the support face that touches the target cell. This lets half blocks
     * and other non-full shapes provide a correctly placed hit vector.
     *
     * The Aim Point options are ports of LiquidBounce's Scaffold RotationMode target
     * position factories.
     */
    private Vec3 faceHit(AxisAlignedBB box, EnumFacing face, EntityPlayerSP player, Vec3 direction) {
        Vec3 point = aimPoint(box, face, player, direction);

        // Move just outside the support so the line-of-sight trace ends on its face
        // instead of inside the collision box.
        double nudge = 0.001D;
        return new Vec3(point.xCoord - face.getFrontOffsetX() * nudge,
                point.yCoord - face.getFrontOffsetY() * nudge,
                point.zCoord - face.getFrontOffsetZ() * nudge);
    }

    private Vec3 aimPoint(AxisAlignedBB box, EnumFacing face, EntityPlayerSP player,
                          Vec3 direction) {
        FaceRect fullFace = FaceRect.from(box, face);
        FaceRect trimmedFace = fullFace.trim(FACE_TRIM);
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 look = player.getLook(1.0F);
        String mode = aimPointSetting.getValue();
        boolean moving = hasMovementInput(player) && direction != null;

        if (AIM_RANDOM.equals(mode)) {
            return trimmedFace.randomPoint();
        }
        if (AIM_NEAREST_ROTATION.equals(mode)) {
            return trimmedFace.nearestPointToLine(eyes, look);
        }
        if (AIM_STABILIZED.equals(mode)) {
            if (!moving) return trimmedFace.nearestPointToLine(eyes, look);

            Vec3 optimalDirection = optimalLineDirection(direction);
            Vec3 collision = trimmedFace.rayPlaneIntersection(eyes, optimalDirection);
            if (collision == null) return trimmedFace.nearestPointToLine(eyes, look);

            Vec3 playerPosition = playerFootPosition(player);
            FaceRect pathFace = trimmedFace.cropBetween(collision, playerPosition,
                    playerPosition.yCoord - 2.0D, playerPosition.yCoord + 1.0D);
            if (pathFace == null) return trimmedFace.nearestPointToLine(eyes, look);
            return pathFace.nearestPointToLine(eyes, look);
        }

        return fullFace.center();
    }

    private Vec3 playerFootPosition(EntityPlayerSP player) {
        return new Vec3(player.posX, player.posY, player.posZ);
    }

    /**
     * LiquidBounce's movement planner snaps the optimal path to one of eight directions
     * and keeps it until the input angle moves more than 30 degrees from it.
     */
    private Vec3 optimalLineDirection(Vec3 direction) {
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.xCoord, direction.zCoord));
        float snapped;
        if (!Float.isNaN(lastOptimalYaw)
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - lastOptimalYaw)) <= 30.0F) {
            snapped = lastOptimalYaw;
        } else {
            snapped = Math.round(yaw / 45.0F) * 45.0F;
            lastOptimalYaw = snapped;
        }

        double radians = Math.toRadians(snapped);
        return new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
    }

    /** Axis-aligned rectangle on one face of a support block. */
    private static final class FaceRect {
        private final int planeAxis;
        private final double plane;
        private final int axisA;
        private final int axisB;
        private final double minA;
        private final double maxA;
        private final double minB;
        private final double maxB;

        private FaceRect(int planeAxis, double plane, int axisA, int axisB,
                         double minA, double maxA, double minB, double maxB) {
            this.planeAxis = planeAxis;
            this.plane = plane;
            this.axisA = axisA;
            this.axisB = axisB;
            this.minA = minA;
            this.maxA = maxA;
            this.minB = minB;
            this.maxB = maxB;
        }

        private static FaceRect from(AxisAlignedBB box, EnumFacing face) {
            switch (face) {
                case DOWN:
                    return new FaceRect(AXIS_Y, box.maxY, AXIS_X, AXIS_Z,
                            box.minX, box.maxX, box.minZ, box.maxZ);
                case UP:
                    return new FaceRect(AXIS_Y, box.minY, AXIS_X, AXIS_Z,
                            box.minX, box.maxX, box.minZ, box.maxZ);
                case NORTH:
                    return new FaceRect(AXIS_Z, box.maxZ, AXIS_X, AXIS_Y,
                            box.minX, box.maxX, box.minY, box.maxY);
                case SOUTH:
                    return new FaceRect(AXIS_Z, box.minZ, AXIS_X, AXIS_Y,
                            box.minX, box.maxX, box.minY, box.maxY);
                case WEST:
                    return new FaceRect(AXIS_X, box.maxX, AXIS_Z, AXIS_Y,
                            box.minZ, box.maxZ, box.minY, box.maxY);
                case EAST:
                    return new FaceRect(AXIS_X, box.minX, AXIS_Z, AXIS_Y,
                            box.minZ, box.maxZ, box.minY, box.maxY);
                default:
                    return new FaceRect(AXIS_Y, box.maxY, AXIS_X, AXIS_Z,
                            box.minX, box.maxX, box.minZ, box.maxZ);
            }
        }

        private FaceRect trim(double scale) {
            double insetA = (maxA - minA) * scale;
            double insetB = (maxB - minB) * scale;
            double a0 = minA + insetA;
            double a1 = maxA - insetA;
            double b0 = minB + insetB;
            double b1 = maxB - insetB;

            if (a0 >= a1 || b0 >= b1) {
                double centerA = (minA + maxA) * 0.5D;
                double centerB = (minB + maxB) * 0.5D;
                return new FaceRect(planeAxis, plane, axisA, axisB,
                        centerA, centerA, centerB, centerB);
            }
            return new FaceRect(planeAxis, plane, axisA, axisB, a0, a1, b0, b1);
        }

        private double area() {
            return (maxA - minA) * (maxB - minB);
        }

        private FaceRect cropBetween(Vec3 first, Vec3 second, double yMin, double yMax) {
            double a0 = minA;
            double a1 = maxA;
            double b0 = minB;
            double b1 = maxB;

            if (axisA == AXIS_Y) {
                a0 = Math.max(a0, yMin);
                a1 = Math.min(a1, yMax);
            } else {
                a0 = Math.max(a0, Math.min(coord(first, axisA), coord(second, axisA)));
                a1 = Math.min(a1, Math.max(coord(first, axisA), coord(second, axisA)));
            }

            if (axisB == AXIS_Y) {
                b0 = Math.max(b0, yMin);
                b1 = Math.min(b1, yMax);
            } else {
                b0 = Math.max(b0, Math.min(coord(first, axisB), coord(second, axisB)));
                b1 = Math.min(b1, Math.max(coord(first, axisB), coord(second, axisB)));
            }

            FaceRect cropped = clamp(a0, a1, b0, b1);
            return cropped == null || cropped.area() < 0.0001D ? null : cropped;
        }

        private FaceRect clamp(double a0, double a1, double b0, double b1) {
            a0 = Math.max(a0, minA);
            a1 = Math.min(a1, maxA);
            b0 = Math.max(b0, minB);
            b1 = Math.min(b1, maxB);
            if (a0 >= a1 || b0 >= b1) return null;
            return new FaceRect(planeAxis, plane, axisA, axisB, a0, a1, b0, b1);
        }

        private Vec3 point(double a, double b) {
            double[] coordinates = new double[3];
            coordinates[planeAxis] = plane;
            coordinates[axisA] = a;
            coordinates[axisB] = b;
            return new Vec3(coordinates[AXIS_X], coordinates[AXIS_Y], coordinates[AXIS_Z]);
        }

        private Vec3 center() {
            return point((minA + maxA) * 0.5D, (minB + maxB) * 0.5D);
        }

        private Vec3 randomPoint() {
            return point(minA + Math.random() * (maxA - minA),
                    minB + Math.random() * (maxB - minB));
        }

        /**
         * LiquidBounce's nearestPointTo: use the line/plane intersection when it lies
         * inside the face, otherwise the nearest point on the face border.
         */
        private Vec3 nearestPointToLine(Vec3 origin, Vec3 direction) {
            double directionLength = direction.lengthVector();
            if (directionLength < 1.0E-6D) return center();
            double inverseLength = 1.0D / directionLength;
            Vec3 normalized = new Vec3(direction.xCoord * inverseLength,
                    direction.yCoord * inverseLength, direction.zCoord * inverseLength);

            double denominator = coord(normalized, planeAxis);
            if (Math.abs(denominator) > 1.0E-9D) {
                double t = (plane - coord(origin, planeAxis)) / denominator;
                if (Double.isFinite(t)) {
                    Vec3 intersection = origin.addVector(normalized.xCoord * t,
                            normalized.yCoord * t, normalized.zCoord * t);
                    if (contains(intersection)) return intersection;
                }
            }

            Vec3 borderPoint = nearestBorderPointToLine(origin, normalized);
            return borderPoint == null ? center() : borderPoint;
        }

        private boolean contains(Vec3 point) {
            double coordinateA = coord(point, axisA);
            double coordinateB = coord(point, axisB);
            return coordinateA >= minA - 1.0E-7D && coordinateA <= maxA + 1.0E-7D
                    && coordinateB >= minB - 1.0E-7D && coordinateB <= maxB + 1.0E-7D;
        }

        private Vec3 nearestBorderPointToLine(Vec3 origin, Vec3 direction) {
            // Same edge order as AlignedFace.getEdges(): A at minB, A at maxB,
            // B at minA, B at maxA.
            Vec3[] edges = new Vec3[]{
                    point(minA, minB), point(maxA, minB),
                    point(minA, maxB), point(maxA, maxB),
                    point(minA, minB), point(minA, maxB),
                    point(maxA, minB), point(maxA, maxB)
            };

            Vec3 best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < edges.length; i += 2) {
                Vec3 candidate = closestPointOnSegment(edges[i], edges[i + 1], origin, direction);
                double distance = lineDistanceSq(candidate, origin, direction);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best;
        }

        private static Vec3 closestPointOnSegment(Vec3 start, Vec3 end, Vec3 origin,
                                                  Vec3 direction) {
            Vec3 edge = end.subtract(start);
            Vec3 offset = start.subtract(origin);
            double edgeAlong = edge.dotProduct(direction);
            double offsetAlong = offset.dotProduct(direction);
            double quadratic = edge.dotProduct(edge) - edgeAlong * edgeAlong;
            double linear = edge.dotProduct(offset) - edgeAlong * offsetAlong;
            double t = quadratic > 1.0E-9D ? -linear / quadratic : 0.0D;
            t = MathHelper.clamp_double(t, 0.0D, 1.0D);
            return start.addVector(edge.xCoord * t, edge.yCoord * t, edge.zCoord * t);
        }

        private Vec3 rayPlaneIntersection(Vec3 origin, Vec3 direction) {
            double denominator = coord(direction, planeAxis);
            if (Math.abs(denominator) < 1.0E-9D) return null;
            double t = (plane - coord(origin, planeAxis)) / denominator;
            if (!Double.isFinite(t)) return null;
            return origin.addVector(direction.xCoord * t, direction.yCoord * t,
                    direction.zCoord * t);
        }

        private static double coord(Vec3 vector, int axis) {
            if (axis == AXIS_X) return vector.xCoord;
            if (axis == AXIS_Y) return vector.yCoord;
            return vector.zCoord;
        }

        private static double lineDistanceSq(Vec3 point, Vec3 origin, Vec3 direction) {
            Vec3 offset = point.subtract(origin);
            double projection = offset.dotProduct(direction);
            Vec3 perpendicular = offset.subtract(new Vec3(direction.xCoord * projection,
                    direction.yCoord * projection, direction.zCoord * projection));
            return perpendicular.dotProduct(perpendicular);
        }
    }

    /** Applies Normal or Silent placement rotations around the chosen face point. */
    private boolean place(EntityPlayerSP player, ItemStack held, Placement placement) {
        boolean normal = ROTATION_NORMAL.equals(rotationSetting.getValue());
        boolean silent = ROTATION_SILENT.equals(rotationSetting.getValue());
        float originalYaw = player.rotationYaw;
        float[] rotations = RotationUtil.getRotations(placement.hit);
        lastAimYaw = normal || silent ? rotations[0] : player.rotationYaw;

        if (normal) {
            player.rotationYaw = rotations[0];
            player.rotationPitch = rotations[1];
            // The placement rotation must not also redirect the bridge the player is walking.
            if (movementFixSetting.getValue()) MovementFix.apply(originalYaw);
            sendLook(player, rotations[0], rotations[1]);
        } else if (silent) {
            // Silent aims through the client's own movement packet instead of sending a look
            // before the placement and another one back after it; the pair put two extra
            // position updates in the tick, which packet rate checks count as timer abuse.
            SilentLook.request(rotations[0], rotations[1]);
        }

        return mc.playerController.onPlayerRightClick(player, mc.theWorld, held,
                placement.position, placement.side, placement.hit);
    }

    /**
     * Sends the look together with the player's position, for the Normal mode where the
     * camera itself is turned. Silent looks never use this: they ride on the client's own
     * movement packet, so no extra position update is added to the tick.
     */
    private void sendLook(EntityPlayerSP player, float yaw, float pitch) {
        if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C06PacketPlayerPosLook(
                    player.posX, player.posY, player.posZ, yaw, pitch, player.onGround));
        }
    }

    private boolean hasLineOfSight(Vec3 eyes, Vec3 target, BlockPos support,
                                   boolean lowerFallback) {
        MovingObjectPosition trace = mc.theWorld.rayTraceBlocks(eyes, target, false, true, false);
        if (trace == null || trace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        return support.equals(trace.getBlockPos())
                // A bottom slab hides the lower support under it from the eyes. Allow only
                // the block directly above that support to cover the fallback placement.
                || (lowerFallback && support.up().equals(trace.getBlockPos()));
    }

    private boolean ensureBlock(EntityPlayerSP player) {
        if (isPlaceableBlock(player.getHeldItem())) return true;
        if (!autoSwitchSetting.getValue() && !silentSwapSetting.getValue()) return false;

        int slot = findBlockSlot(player);
        if (slot < 0) return false;

        int originalSlot = player.inventory.currentItem;
        player.inventory.currentItem = slot;
        sendSlot(slot);
        if (silentSwapSetting.getValue()) {
            silentSwapOriginalSlot = originalSlot;
        }
        return true;
    }

    private void restoreSilentSwap(EntityPlayerSP player) {
        if (silentSwapOriginalSlot < 0) return;
        int originalSlot = silentSwapOriginalSlot;
        silentSwapOriginalSlot = -1;
        player.inventory.currentItem = originalSlot;
        sendSlot(originalSlot);
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
        bridgeY = Integer.MIN_VALUE;
        silentSwapOriginalSlot = -1;
        ticksOnAir = 0;
        normalPlacementTick = -1;
        lastOptimalYaw = Float.NaN;
        lastAimYaw = Float.NaN;
        sprintSuppressed = false;
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null) restoreSilentSwap(mc.thePlayer);
        restoreJumpKey();
        restoreSprintKey();
        sprintSuppressed = false;
        silentSwapOriginalSlot = -1;
        ticksOnAir = 0;
        normalPlacementTick = -1;
        bridgeY = Integer.MIN_VALUE;
        lastOptimalYaw = Float.NaN;
        lastAimYaw = Float.NaN;
    }

    private static class Placement {
        private final BlockPos position;
        private final EnumFacing side;
        private final Vec3 hit;

        private Placement(BlockPos position, EnumFacing side, Vec3 hit) {
            this.position = position;
            this.side = side;
            this.hit = hit;
        }
    }
}
