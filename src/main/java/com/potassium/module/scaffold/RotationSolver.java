package com.potassium.module.scaffold;

import com.potassium.module.Scaffold;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * OpenAdjust's {@code RotationSolver}.
 *
 * <p>A bridge is placed behind the player, so the rotation that reaches the block is not the one
 * the camera looks along. The solver builds the eight yaws that are the current aim plus a
 * multiple of 45 degrees, orders them by how well they fit the direction the player is walking
 * (a diagonal walk wants the odd multiples, a straight one the even ones), and for each yaw
 * computes the pitch interval that lands a ray on a clickable face. The first ray that actually
 * hits wins, and the yaw used is remembered as an offset, so the next placement keeps the body
 * on the same shoulder instead of flipping sides between blocks.</p>
 */
public final class RotationSolver {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MAX_RAYS = 24;

    private final Scaffold module;
    private final PlacementFinder geometry;

    /** Offset between the yaw used for the last placement and the base yaw; null until solved. */
    Float yawOffset;

    public RotationSolver(Scaffold module, PlacementFinder geometry) {
        this.module = module;
        this.geometry = geometry;
    }

    public boolean isStableYawActive() {
        return module.getOptions().isStableYaw() && yawOffset != null;
    }

    public void reset() {
        yawOffset = null;
    }

    public PlacementTarget solve(EntityPlayer player, Collection<BlockPos> positions,
                                 float currentYaw, float currentPitch) {
        int belowY = MathHelper.floor_double(mc.thePlayer.posY) - 1;
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (pos.getY() != belowY) continue;
            if (PlacementFinder.collisionBox(pos) != null) continue;
            IBlockState state = mc.theWorld.getBlockState(pos);
            if (!state.getBlock().getMaterial().isReplaceable()) continue;
            if (mc.theWorld.checkNoEntityCollision(PlacementFinder.blockBox(pos), mc.thePlayer)) {
                candidates.add(pos);
            }
        }
        if (candidates.isEmpty()) return null;

        float baseYaw = module.getAimYaw();
        List<Float> yaws = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) yaws.add(baseYaw + i * 45.0F);

        float moveYaw = module.moveYaw();
        boolean moveDiagonal = isDiagonalYaw(moveYaw);
        boolean baseDiagonal = isDiagonalYaw(baseYaw);
        boolean stable = module.getOptions().isStableYaw() && yawOffset != null;
        float preferredYaw = yawOffset != null ? baseYaw + yawOffset : currentYaw;

        float axisDeviation = MathHelper.wrapAngleTo180_float(
                moveYaw - Math.round(moveYaw / 90.0F) * 90.0F);
        float backward = moveYaw + 180.0F;
        float backwardDelta = MathHelper.wrapAngleTo180_float(backward - baseYaw);
        float absDeviation = Math.abs(axisDeviation);
        float diagonalTarget = absDeviation <= 1.0F
                ? preferredYaw
                : Math.round((backward - 45.0F) / 90.0F) * 90.0F + 45.0F;
        float nearest45 = Math.round(backwardDelta / 45.0F) * 45.0F + baseYaw;

        final float preferred = preferredYaw;
        final float nearest = nearest45;
        final float base = baseYaw;
        final float diagonal = diagonalTarget;
        final boolean movingDiagonally = moveDiagonal;
        final boolean baseIsDiagonal = baseDiagonal;
        final boolean keepOffset = stable;

        yaws.sort(new Comparator<Float>() {
            @Override
            public int compare(Float first, Float second) {
                if (keepOffset) {
                    int byPreferred = Double.compare(
                            Math.abs(MathHelper.wrapAngleTo180_float(preferred - first)),
                            Math.abs(MathHelper.wrapAngleTo180_float(preferred - second)));
                    if (byPreferred != 0) return byPreferred;
                }
                int byDiagonal = Integer.compare(rank(first), rank(second));
                if (byDiagonal != 0) return byDiagonal;
                int byTarget = Double.compare(
                        Math.abs(MathHelper.wrapAngleTo180_float(diagonal - first)),
                        Math.abs(MathHelper.wrapAngleTo180_float(diagonal - second)));
                if (byTarget != 0) return byTarget;
                return Double.compare(
                        Math.abs(MathHelper.wrapAngleTo180_float(preferred - first)),
                        Math.abs(MathHelper.wrapAngleTo180_float(preferred - second)));
            }

            private int rank(float yaw) {
                if (movingDiagonally && Math.abs(MathHelper.wrapAngleTo180_float(yaw - nearest)) < 0.01F) {
                    return -1;
                }
                float relative = (yaw - base) % 360.0F;
                if (relative < 0.0F) relative += 360.0F;
                boolean oddMultiple = angularDistance(relative, 45.0F) <= 22.5F
                        || angularDistance(relative, 135.0F) <= 22.5F
                        || angularDistance(relative, 225.0F) <= 22.5F
                        || angularDistance(relative, 315.0F) <= 22.5F;
                return oddMultiple == !baseIsDiagonal ? 0 : 1;
            }
        });

        List<FaceBox> faces = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            for (EnumFacing facing : EnumFacing.HORIZONTALS) {
                BlockPos neighbour = candidate.offset(facing);
                IBlockState state = mc.theWorld.getBlockState(neighbour);
                Block block = state.getBlock();
                if (block.getMaterial().isReplaceable()) continue;
                AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, neighbour, state);
                if (box == null) continue;
                faces.add(new FaceBox(box, facing.getOpposite()));
            }
        }

        if (absDeviation > 7.5F) {
            if (stable) {
                removeNear(yaws, nearest);
                yaws.add(0, nearest);
            }
        } else {
            removeNear(yaws, nearest);
        }

        float yawDelta = MathHelper.wrapAngleTo180_float(preferredYaw - baseYaw);
        if (yawOffset != null && !stable) {
            float chosen;
            if (!moveDiagonal) {
                float snapped = Math.round(yawDelta / 45.0F) * 45.0F + baseYaw;
                boolean useSnapped = false;
                if (absDeviation > 7.5F
                        || Math.abs(MathHelper.wrapAngleTo180_float(snapped - nearest45)) >= 0.01F) {
                    useSnapped = isDiagonalYaw(snapped - baseYaw) == !baseDiagonal;
                }
                chosen = useSnapped ? snapped : yaws.get(0);
            } else {
                chosen = nearest45;
            }
            yaws.clear();
            yaws.add(chosen);
        }

        PlacementTarget result = null;
        float chosenYaw = 0.0F;
        boolean fromCurrent = false;
        boolean tryCandidates = false;

        if (stable) {
            float difference = MathHelper.wrapAngleTo180_float(preferredYaw - currentYaw);
            if (Math.abs(difference) < 45.0F) {
                result = solvePitch(player, candidates, faces, currentPitch, currentYaw);
                if (result != null) {
                    fromCurrent = true;
                    chosenYaw = currentYaw;
                } else {
                    tryCandidates = true;
                }
            } else {
                tryCandidates = true;
            }
        }

        if (result == null && tryCandidates) {
            List<PlacementFinder.RotationCandidate> rotations = new ArrayList<>();
            for (BlockPos candidate : candidates) {
                rotations.addAll(geometry.findPlacementCandidates(player, candidate));
            }
            final float preferredYawCandidate = preferredYaw;
            for (Iterator<PlacementFinder.RotationCandidate> it = rotations.iterator(); it.hasNext(); ) {
                if (Math.abs(MathHelper.wrapAngleTo180_float(preferredYawCandidate - it.next().yaw)) >= 45.0F) {
                    it.remove();
                }
            }
            final float currentYawCandidate = currentYaw;
            final float currentPitchCandidate = currentPitch;
            rotations.sort(new Comparator<PlacementFinder.RotationCandidate>() {
                @Override
                public int compare(PlacementFinder.RotationCandidate first,
                                   PlacementFinder.RotationCandidate second) {
                    int byYaw = Double.compare(
                            Math.abs(MathHelper.wrapAngleTo180_float(currentYawCandidate - first.yaw)),
                            Math.abs(MathHelper.wrapAngleTo180_float(currentYawCandidate - second.yaw)));
                    if (byYaw != 0) return byYaw;
                    return Double.compare(Math.abs(currentPitchCandidate - first.pitch),
                            Math.abs(currentPitchCandidate - second.pitch));
                }
            });
            int rays = 0;
            for (PlacementFinder.RotationCandidate candidate : rotations) {
                if (++rays > MAX_RAYS) break;
                PlacementFinder.BlockPlacement ray = geometry.raycastPlacement(
                        player, candidate.yaw, candidate.pitch, candidates);
                if (ray != null && ray.facing != EnumFacing.UP && ray.facing != EnumFacing.DOWN) {
                    result = new PlacementTarget(ray.pos, ray.facing, ray.hitVec, ray.yaw, ray.pitch);
                    fromCurrent = true;
                    chosenYaw = candidate.yaw;
                    break;
                }
            }
        }

        if (result == null) {
            for (Float yaw : yaws) {
                result = solvePitch(player, candidates, faces, currentPitch, yaw);
                if (result != null) {
                    fromCurrent = false;
                    chosenYaw = yaw;
                    break;
                }
            }
        }
        if (result == null) return null;

        float difference = MathHelper.wrapAngleTo180_float(preferredYaw - chosenYaw);
        boolean farFromPreferred = Math.abs(difference) > 0.01F && Math.abs(difference) < 45.0F;
        if (yawOffset == null) farFromPreferred = false;
        if (farFromPreferred || fromCurrent) return result;
        if (!moveDiagonal && yawOffset != null
                && Math.abs(MathHelper.wrapAngleTo180_float(chosenYaw - nearest45)) < 0.01F) {
            return result;
        }
        yawOffset = MathHelper.wrapAngleTo180_float(chosenYaw - baseYaw);
        return result;
    }

    /**
     * Predicts where the player leaves the ground and solves a placement for the cell under that
     * spot, which is what lets a bridge start one tick before the feet cross the edge.
     */
    public PlacementTarget solveAlongPath(EntityPlayer player, List<BlockPos> blocks,
                                          List<Scaffold.PathPoint> path,
                                          float currentYaw, float currentPitch) {
        int belowY = MathHelper.floor_double(mc.thePlayer.posY) - 1;
        AxisAlignedBB gap = null;
        for (Scaffold.PathPoint point : path) {
            AxisAlignedBB footprint = new AxisAlignedBB(
                    point.x - 0.3D, point.y, point.z - 0.3D,
                    point.x + 0.3D, point.y + 1.8D, point.z + 0.3D);
            gap = new AxisAlignedBB(footprint.minX, belowY + 0.99D, footprint.minZ,
                    footprint.maxX, belowY + 1.0D, footprint.maxZ);
            if (PlacementFinder.collisionBox(new BlockPos(
                    MathHelper.floor_double(point.x), belowY, MathHelper.floor_double(point.z))) == null) {
                break;
            }
            gap = null;
        }
        if (gap == null) return null;

        List<BlockPos> nearby = new ArrayList<>();
        for (BlockPos pos : blocks) {
            if (gap.intersectsWith(PlacementFinder.blockBox(pos))) nearby.add(pos);
        }
        if (nearby.isEmpty()) {
            for (BlockPos pos : blocks) {
                if (pos.getY() != belowY) continue;
                if (PlacementFinder.collisionBox(pos) != null) continue;
                for (EnumFacing facing : EnumFacing.HORIZONTALS) {
                    BlockPos shifted = pos.offset(facing);
                    if (!gap.intersectsWith(PlacementFinder.blockBox(shifted))) continue;
                    if (PlacementFinder.collisionBox(shifted) == null) {
                        nearby.add(pos);
                        break;
                    }
                }
            }
        }
        if (nearby.isEmpty()) return null;
        return solve(player, nearby, currentYaw, currentPitch);
    }

    /**
     * Pitch solver for a fixed yaw: for each clickable face the interval of pitches whose ray
     * passes through that face is derived from the eye height and the face's height range, then
     * pitches inside the interval are tried closest to the current pitch first.
     */
    private PlacementTarget solvePitch(EntityPlayer player, Collection<BlockPos> positions,
                                       List<FaceBox> faces, float currentPitch, float yaw) {
        float yawDelta = MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw);
        float basePitch = MathHelper.clamp_float(currentPitch, 0.0F, 90.0F);
        double reach = mc.playerController.getCurrentGameType() == WorldSettings.GameType.CREATIVE
                ? 5.0D : 4.5D;
        float lookYaw = yawDelta + mc.thePlayer.rotationYaw;
        double playerX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double playerZ = player.posZ;
        Vec3 look = PlacementFinder.lookVector(lookYaw, 0.0F);
        double dx = reach * look.xCoord;
        double dy = reach * look.yCoord;
        double dz = reach * look.zCoord;

        List<Float> pitches = new ArrayList<>();
        pitches.add(basePitch);
        for (FaceBox face : faces) {
            AxisAlignedBB box = face.boundingBox;
            double centreY = (box.minY + box.maxY) * 0.5D;
            Vec3 start = new Vec3(playerX, centreY, playerZ);
            Vec3 end = new Vec3(playerX + dx, centreY + dy, playerZ + dz);
            MovingObjectPosition hit = box.calculateIntercept(start, end);
            if (hit == null || hit.sideHit != face.facing) continue;

            float distance = (float) Math.sqrt(
                    (hit.hitVec.xCoord - playerX) * (hit.hitVec.xCoord - playerX)
                            + (hit.hitVec.zCoord - playerZ) * (hit.hitVec.zCoord - playerZ));
            if (distance <= 0.0F || distance >= (float) reach) continue;

            float low = Math.max(0.0F, (float) (Math.atan2(eyeY - box.maxY, distance) * 57.29577951308232D));
            float upperByBox = (float) (Math.atan2(eyeY - box.minY, distance) * 57.29577951308232D);
            float upperByReach = (float) (Math.acos(distance / reach) * 57.29577951308232D);
            float high = Math.min(upperByBox, upperByReach);
            if (low >= high) continue;

            float margin = Math.min((high - low) * 0.1F, 0.05F);
            float highInner = high - margin;
            float lowInner = low + margin;
            float first;
            if (lowInner > basePitch) first = lowInner;
            else if (basePitch > highInner) first = highInner;
            else first = basePitch;
            pitches.add(first);

            float span = highInner - lowInner;
            for (int i = 0; i <= 8; i++) pitches.add(span * i / 8.0F + lowInner);
        }

        pitches.sort(new Comparator<Float>() {
            @Override
            public int compare(Float first, Float second) {
                return Float.compare(Math.abs(first - basePitch), Math.abs(second - basePitch));
            }
        });

        for (Float pitch : pitches) {
            PlacementFinder.BlockPlacement ray = geometry.raycastPlacement(
                    player, lookYaw, pitch, positions);
            if (ray != null && ray.facing != EnumFacing.UP && ray.facing != EnumFacing.DOWN) {
                return new PlacementTarget(ray.pos, ray.facing, ray.hitVec, ray.yaw, ray.pitch);
            }
        }
        return null;
    }

    private static void removeNear(List<Float> yaws, float target) {
        for (Iterator<Float> it = yaws.iterator(); it.hasNext(); ) {
            if (Math.abs(MathHelper.wrapAngleTo180_float(it.next() - target)) < 0.01F) it.remove();
        }
    }

    /** True when the yaw points along a diagonal of the block grid. */
    public static boolean isDiagonalYaw(float yaw) {
        float relative = ((yaw % 90.0F) + 90.0F) % 90.0F;
        return Math.abs(relative - 45.0F) <= 22.5F;
    }

    private static float angularDistance(float first, float second) {
        float distance = Math.abs(first - second);
        return Math.min(distance, 360.0F - distance);
    }

    /** One clickable face: the collision box of a block and the side pointing at the target. */
    static final class FaceBox {

        final AxisAlignedBB boundingBox;
        final EnumFacing facing;

        FaceBox(AxisAlignedBB boundingBox, EnumFacing facing) {
            this.boundingBox = boundingBox;
            this.facing = facing;
        }
    }
}
