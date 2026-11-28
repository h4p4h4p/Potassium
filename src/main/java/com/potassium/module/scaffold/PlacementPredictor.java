package com.potassium.module.scaffold;

import com.potassium.module.Scaffold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAdjust's {@code PlacementPredictor}.
 *
 * <p>The cell under the player today is not the cell to bridge: by the time a placement goes out
 * the feet are already over the next one. The predictor walks the player's own motion four ticks
 * ahead, looks for the first tick whose footprint has no support, and solves a placement for the
 * block that covers it. Supports are the solid blocks below the path and the free cells beside
 * them; the nearest one to the feet wins so the bridge stays under the player.</p>
 */
public final class PlacementPredictor {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MAX_SUPPORTS = 12;

    private final Scaffold module;

    /** The target this tick's prediction produced, or null when nothing needs placing. */
    public PlacementTarget lastResult;

    public PlacementPredictor(Scaffold module) {
        this.module = module;
    }

    public Integer predict(EntityPlayerSP player, float currentYaw, float currentPitch) {
        lastResult = null;
        ScaffoldSettings settings = module.getOptions();
        PlacementFinder geometry = module.getPlacementFinder();
        RotationSolver solver = geometry.solver();
        List<Scaffold.PathPoint> path = module.predictPath(player);

        for (int tick = 0; tick < path.size(); tick++) {
            Scaffold.PathPoint point = path.get(tick);
            ScaffoldMode mode = module.currentMode();
            boolean ground = tick == 0 ? player.onGround : point.onGround;

            double heightAbove = player.posY - module.getBaseY();
            boolean lowOnly = false;
            boolean buffering = false;
            boolean downward = false;

            if (mode.towering) {
                lowOnly = mode.isTowerLowOnly();
            } else if (module.isNoAscendActive()) {
                if (module.preventsAscend()) {
                    downward = true;
                } else if (!mode.isAboveBuildLimit(MathHelper.floor_double(player.posY))) {
                    boolean falling = player.fallDistance > 0.0F;
                    if (module.noAscendMode() == ScaffoldSettings.NO_ASCEND_BUFFER && falling) {
                        double next = module.getBaseY() + 1.0D;
                        lowOnly = next > player.posY + player.motionY && player.posY >= next;
                    }
                    buffering = falling;
                    downward = !falling;
                } else {
                    buffering = true;
                }
            }

            Vec3 position = new Vec3(point.x, downward ? module.getBaseY() : point.y, point.z);
            BlockPos playerBlock = new BlockPos(position);
            AxisAlignedBB playerBox = module.playerBoxAt(position);
            Set<BlockPos> supports = new LinkedHashSet<>();
            List<BlockPos> free = new ArrayList<>();
            List<BlockPos> preferred = new ArrayList<>();
            BlockPos referencePos = referencePos(path);

            for (int dx = -4; dx < 4; dx++) {
                for (int dy = -3; dy < 0; dy++) {
                    for (int dz = -4; dz < 4; dz++) {
                        BlockPos candidate = new BlockPos(
                                MathHelper.floor_double(position.xCoord + dx),
                                MathHelper.floor_double(position.yCoord + dy),
                                MathHelper.floor_double(position.zCoord + dz));
                        if (PlacementFinder.collisionBox(candidate) == null) continue;
                        free.add(candidate);
                        collectSupports(player, candidate, position, playerBlock, playerBox,
                                ground, lowOnly, supports, preferred, referencePos, path);
                    }
                }
            }
            if (supports.isEmpty()) continue;


            final Vec3 from = position;
            List<BlockPos> sorted = new ArrayList<>(supports);
            Collections.sort(sorted, new Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos first, BlockPos second) {
                    return Double.compare(preferredDistance(from, first, preferred),
                            preferredDistance(from, second, preferred));
                }
            });

            // OpenAdjust only places on the cluster around the nearest solid block, so a far
            // cell beside the bridge cannot win over the one the player is walking into.
            double minimumDistance = Double.MAX_VALUE;
            for (BlockPos solid : free) {
                minimumDistance = Math.min(minimumDistance, module.distanceTo(from, solid, false));
            }

            if (settings.isStableYaw() && settings.isRotationsEnabled() && ground
                    && !module.isJumping() && !mode.towering && !module.isNoAscendActive()) {
                PlacementTarget solved = solver.solveAlongPath(
                        player, sorted, path, currentYaw, currentPitch);
                if (solved != null) return accept(solved, tick);
            }

            int attempted = 0;
            for (BlockPos pos : sorted) {
                if (++attempted > MAX_SUPPORTS) break;
                if (module.distanceTo(from, pos, false) > minimumDistance) continue;
                PlacementFinder.BlockPlacement placement = geometry.findBestPlacement(
                        player, pos, currentYaw, currentPitch);
                if (placement == null) continue;

                PlacementTarget target = new PlacementTarget(
                        placement.pos, placement.facing, placement.hitVec, placement.yaw, placement.pitch);
                EnumFacing facing = target.facing;

                if (facing == EnumFacing.UP) {
                    if (!buffering && mode.isAboveBuildLimit(pos.getY())) continue;
                    if (module.isNoAscendActive() && !lowOnly) {
                        if (heightAbove < 1.0D || heightAbove >= 2.0D) continue;
                        if (module.preventsAscend()) continue;
                        if (!buffering) continue;
                    }
                } else if (module.isNoAscendActive() && !downward) {
                    BlockPos below = new BlockPos(player.posX, module.getBaseY() - 1.0D, player.posZ);
                    if (PlacementFinder.collisionBox(below) != null) break;
                }
                return accept(target, tick);
            }
        }
        return null;
    }

    /**
     * Every free neighbour of one solid block, kept out of the predictor's scan loop: Java
     * 8u51's C2 compiler crashes on the original single-method form of that loop.
     */
    private void collectSupports(EntityPlayerSP player, BlockPos candidate, Vec3 position,
                                 BlockPos playerBlock, AxisAlignedBB playerBox, boolean ground,
                                 boolean lowOnly, Set<BlockPos> supports, List<BlockPos> preferred,
                                 BlockPos referencePos, List<Scaffold.PathPoint> path) {
        for (EnumFacing facing : EnumFacing.VALUES) {
            BlockPos neighbour = candidate.offset(facing);
            if (lowOnly && neighbour.getY() != MathHelper.floor_double(position.yCoord) - 1) {
                continue;
            }
            if (facing == EnumFacing.UP) {
                if (ground) continue;
                if (neighbour.getX() != playerBlock.getX()
                        || neighbour.getZ() != playerBlock.getZ()) {
                    continue;
                }
            } else if (module.isJumping() && !module.isMoving()
                    && module.horizontalSpeed() < 0.035D && player.motionY > -0.2D) {
                continue;
            }
            if (PlacementFinder.collisionBox(neighbour) != null) continue;
            if (playerBox.intersectsWith(PlacementFinder.blockBox(neighbour))) continue;
            if (module.isAboveBuildLimit(neighbour.getY())) continue;
            if (!aboveExpectedPath(neighbour, position, path, referencePos, candidate)) continue;
            supports.add(neighbour);

            if (candidate.equals(referencePos)) {
                preferred.add(candidate);
            } else if (module.lastPlacement != null && module.lastPlacement.facing != EnumFacing.UP
                    && facing == EnumFacing.UP && candidate.equals(module.lastPlacement.placedPos)) {
                preferred.add(candidate);
            }
        }
    }

    /**
     * A cell higher than the path is only allowed when it continues the block that was just
     * started; otherwise the module would climb a wall beside the bridge instead of bridging
     * forward.
     */
    private boolean aboveExpectedPath(BlockPos neighbour, Vec3 position,
                                      List<Scaffold.PathPoint> path, BlockPos referencePos,
                                      BlockPos candidate) {
        if (path.size() < 2) return true;
        double cx = neighbour.getX() + 0.5D;
        double cz = neighbour.getZ() + 0.5D;
        double horizontal = MathHelper.sqrt_double(
                (cx - position.xCoord) * (cx - position.xCoord)
                        + (cz - position.zCoord) * (cz - position.zCoord));
        float angle = (float) (Math.atan2(position.zCoord - cz, position.xCoord - cx)
                * 180.0D / Math.PI) - 90.0F;
        double factor = RotationSolver.isDiagonalYaw(angle) ? 1.5D : 1.0D;
        int index = (int) (horizontal * factor + 0.7D);
        index = Math.min(index, path.size() - 1);
        Scaffold.PathPoint expected = path.get(index);
        if (neighbour.getY() <= expected.y) return true;
        return referencePos != null && candidate.equals(referencePos);
    }

    /** The block just placed, while the path is still descending past it. */
    private BlockPos referencePos(List<Scaffold.PathPoint> path) {
        if (path.size() < 2) return null;
        if (path.get(1).y - path.get(0).y >= -0.5D) return null;
        PlacementTarget pending = module.lastPlacement;
        if (pending == null || pending.placedPos == null) return null;
        return pending.placedPos.getY() > path.get(0).y ? null : pending.placedPos;
    }

    private double preferredDistance(Vec3 from, BlockPos pos, List<BlockPos> preferred) {
        double distance = module.distanceTo(from, pos, true);
        return preferred.contains(pos) ? distance * 0.01D : distance;
    }

    private Integer accept(PlacementTarget target, int tick) {
        module.ticksSincePlace = 0;
        module.targetYaw = target.yaw;
        module.targetPitch = target.pitch;
        this.lastResult = target;
        return tick;
    }
}
