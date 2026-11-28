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

            for (int dx = -4; dx < 4; dx++) {
                for (int dy = -3; dy < 0; dy++) {
                    for (int dz = -4; dz < 4; dz++) {
                        BlockPos candidate = new BlockPos(
                                MathHelper.floor_double(position.xCoord + dx),
                                MathHelper.floor_double(position.yCoord + dy),
                                MathHelper.floor_double(position.zCoord + dz));
                        if (PlacementFinder.collisionBox(candidate) == null) continue;
                        free.add(candidate);

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
                            supports.add(neighbour);
                        }
                    }
                }
            }
            if (supports.isEmpty()) continue;

            final Vec3 from = position;
            List<BlockPos> sorted = new ArrayList<>(supports);
            Collections.sort(sorted, new Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos first, BlockPos second) {
                    return Double.compare(module.distanceTo(from, first, true),
                            module.distanceTo(from, second, true));
                }
            });

            if (settings.isStableYaw() && settings.isRotationsEnabled() && ground
                    && !module.isJumping() && !mode.towering && !module.isNoAscendActive()) {
                PlacementTarget solved = solver.solveAlongPath(
                        player, sorted, path, currentYaw, currentPitch);
                if (solved != null) return accept(solved, tick);
            }

            int attempted = 0;
            for (BlockPos pos : sorted) {
                if (++attempted > MAX_SUPPORTS) break;
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

    private Integer accept(PlacementTarget target, int tick) {
        module.ticksSincePlace = 0;
        module.targetYaw = target.yaw;
        module.targetPitch = target.pitch;
        this.lastResult = target;
        return tick;
    }
}
