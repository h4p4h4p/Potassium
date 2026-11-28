package com.potassium.module.scaffold;

import com.potassium.module.Scaffold;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * OpenAdjust's {@code PlacementFinder}: every face next to the target cell that can be clicked
 * is sampled, each sample becomes the rotation that looks at it, and the sample closest to the
 * rotation already in use wins. The winner is only accepted once a ray from the eyes along the
 * snapped rotation really lands on that face, so a click always has a face behind it.
 */
public final class PlacementFinder {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double HIT_TOLERANCE = 1.0E-3D;
    private static final int MAX_RAYS = 24;

    private final Scaffold module;
    private final RotationSolver solver;

    public PlacementFinder(Scaffold module) {
        this.module = module;
        this.solver = new RotationSolver(module, this);
    }

    public RotationSolver solver() {
        return solver;
    }

    public boolean useRaycast() {
        return module.getOptions().isRaycast() || module.currentMode().forceRaycast();
    }

    /**
     * Candidate rotations for filling {@code pos}. A neighbouring block is clickable only if it
     * has a collision box; the sampled face is the one pointing back at {@code pos}.
     */
    public List<RotationCandidate> findPlacementCandidates(EntityPlayer entity, BlockPos pos) {
        World world = mc.theWorld;
        if (world == null || entity == null) return Collections.emptyList();
        if (collisionBox(pos) != null) return Collections.emptyList();
        if (!world.checkNoEntityCollision(blockBox(pos), entity)) return Collections.emptyList();

        List<RotationCandidate> candidates = new ArrayList<>();
        for (EnumFacing facing : EnumFacing.VALUES) {
            BlockPos neighbour = pos.offset(facing);
            IBlockState state = world.getBlockState(neighbour);
            Block block = state.getBlock();
            if (block.getMaterial().isReplaceable()) continue;
            AxisAlignedBB box = block.getCollisionBoundingBox(world, neighbour, state);
            if (box == null) continue;
            candidates.addAll(sampleFace(entity, box, facing, neighbour));
        }
        return candidates;
    }

    /**
     * Samples the face of {@code box} that looks at the target cell. Points sit just inside the
     * perimeter, which leaves the ray margin on every side, and the middle of the face is
     * skipped because an aim at the centre is the one a direction check expects least.
     */
    private List<RotationCandidate> sampleFace(EntityPlayer entity, AxisAlignedBB box,
                                               EnumFacing facing, BlockPos blockPos) {
        EnumFacing side = facing.getOpposite();
        double random1 = Math.random() * 0.045D + 0.9D;
        double random2 = Math.random() * 0.045D + 0.9D;

        boolean xFixed = side == EnumFacing.EAST || side == EnumFacing.WEST;
        boolean yFixed = side == EnumFacing.UP || side == EnumFacing.DOWN;
        boolean zFixed = side == EnumFacing.SOUTH || side == EnumFacing.NORTH;

        double xHi = xFixed ? (side == EnumFacing.EAST ? box.maxX : box.minX)
                : (box.maxX - box.minX) * random1 + box.minX;
        double xLo = xFixed ? xHi : 1.0D - random1 + box.minX;
        double yHi = yFixed ? (side == EnumFacing.UP ? box.maxY : box.minY)
                : (box.maxY - box.minY) * random2 + box.minY;
        double yLo = yFixed ? yHi : 1.0D - random2 + box.minY;
        double zHi = zFixed ? (side == EnumFacing.SOUTH ? box.maxZ : box.minZ)
                : (box.maxZ - box.minZ) * random1 + box.minZ;
        double zLo = zFixed ? zHi : 1.0D - random1 + box.minZ;

        int freeAxes = (xFixed ? 0 : 1) + (yFixed ? 0 : 1) + (zFixed ? 0 : 1);
        int xSteps = xFixed ? 1 : 8;
        int ySteps = yFixed ? 1 : 8;
        int zSteps = zFixed ? 1 : 8;
        double centreX = (box.maxX + box.minX) * 0.5D;
        double centreY = (box.maxY + box.minY) * 0.5D;
        double centreZ = (box.maxZ + box.minZ) * 0.5D;

        Vec3 eyes = entity.getPositionEyes(1.0F);
        List<RotationCandidate> result = new ArrayList<>();
        for (int i = 0; i < xSteps; i++) {
            double x = xFixed ? xLo : (double) i * (xHi - xLo) / (double) (xSteps - 1) + xLo;
            for (int j = 0; j < ySteps; j++) {
                double y = yFixed ? yLo : (double) j * (yHi - yLo) / (double) (ySteps - 1) + yLo;
                for (int k = 0; k < zSteps; k++) {
                    double z = zFixed ? zLo : (zHi - zLo) * (double) k / (double) (zSteps - 1) + zLo;

                    if (freeAxes > 0) {
                        boolean nearCentre = true;
                        if (!xFixed) nearCentre = Math.abs(x - centreX) < 0.2D;
                        if (!yFixed) nearCentre &= Math.abs(y - centreY) < 0.2D;
                        if (!zFixed) nearCentre &= Math.abs(z - centreZ) < 0.2D;
                        if (nearCentre) continue;
                    }

                    double dx = x - eyes.xCoord;
                    double dy = y - eyes.yCoord;
                    double dz = z - eyes.zCoord;
                    double horizontal = MathHelper.sqrt_double(dx * dx + dz * dz);
                    float yaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
                    float pitch = (float) (-Math.atan2(dy, horizontal) * 180.0D / Math.PI);

                    result.add(new RotationCandidate(
                            new Vec3(x - blockPos.getX(), y - blockPos.getY(), z - blockPos.getZ()),
                            blockPos, yaw, pitch));
                }
            }
        }
        return result;
    }

    /**
     * Casts one rotation and returns the placement it lands on, if that placement fills one of
     * {@code targets}. The rotation is first snapped to whole mouse counts from the player's own
     * rotation, which is the rotation the server ends up holding.
     */
    public BlockPlacement raycastPlacement(EntityPlayer entity, float yaw, float pitch,
                                          Collection<BlockPos> targets) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return null;

        float playerYaw = player.rotationYaw;
        float playerPitch = player.rotationPitch;
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 8.0F;
        if (gcd < 1.0E-4F) gcd = 1.0E-4F;

        float yawDelta = MathHelper.wrapAngleTo180_float(yaw - playerYaw);
        float mouseYaw = yawDelta / 0.15F / gcd;
        float snappedYaw = playerYaw + mouseYaw * gcd * 0.15F;

        float mousePitch = (pitch - playerPitch) / 0.15F / gcd;
        float snappedPitch = MathHelper.clamp_float(
                playerPitch + mousePitch * gcd * 0.15F, -90.0F, 90.0F);

        double reach = mc.playerController.getBlockReachDistance();
        Vec3 eyes = entity.getPositionEyes(1.0F);
        Vec3 look = lookVector(snappedYaw, snappedPitch);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes, end, false, false, true);

        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        BlockPos hitPos = hit.getBlockPos();
        if (!targets.contains(hitPos.offset(hit.sideHit))) return null;

        IBlockState state = mc.theWorld.getBlockState(hitPos);
        if (state.getBlock().getMaterial().isReplaceable()) return null;
        if (collisionBox(hitPos) == null) return null;
        if (!validHit(hitPos, hit.hitVec)) return null;

        return new BlockPlacement(hitPos, hit.sideHit, hit.hitVec, snappedYaw, snappedPitch);
    }

    /**
     * Best placement for one target cell: candidates are ordered by how little they turn the
     * aim, and the first one whose ray lands is used. OpenAdjust's rule, which keeps the body
     * near the direction the player is already walking instead of swinging across the screen.
     */
    public BlockPlacement findBestPlacement(EntityPlayer entity, BlockPos target,
                                            float currentYaw, float currentPitch) {
        List<RotationCandidate> candidates = findPlacementCandidates(entity, target);
        if (candidates.isEmpty()) return null;
        final float yaw = currentYaw;
        final float pitch = currentPitch;
        candidates.sort(Comparator.comparingDouble(candidate -> {
            float yawDelta = Math.abs(MathHelper.wrapAngleTo180_float(yaw - candidate.yaw));
            float pitchDelta = Math.abs(pitch - candidate.pitch);
            return pitchDelta * pitchDelta + yawDelta * yawDelta;
        }));

        List<BlockPos> targets = Collections.singletonList(target);
        int rays = 0;
        for (RotationCandidate candidate : candidates) {
            if (++rays > MAX_RAYS) break;
            BlockPlacement placement = raycastPlacement(entity, candidate.yaw, candidate.pitch, targets);
            if (placement != null) return placement;
        }
        return null;
    }

    /** Vanilla's look vector for a pair of rotations. */
    static Vec3 lookVector(float yaw, float pitch) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(f1 * f2, f3, f * f2);
    }

    private boolean validHit(BlockPos pos, Vec3 hit) {
        if (hit == null) return false;
        if (Double.isNaN(hit.xCoord) || Double.isNaN(hit.yCoord) || Double.isNaN(hit.zCoord)) {
            return false;
        }
        return hit.xCoord >= pos.getX() - HIT_TOLERANCE && hit.xCoord <= pos.getX() + 1.0D + HIT_TOLERANCE
                && hit.yCoord >= pos.getY() - HIT_TOLERANCE && hit.yCoord <= pos.getY() + 1.0D + HIT_TOLERANCE
                && hit.zCoord >= pos.getZ() - HIT_TOLERANCE && hit.zCoord <= pos.getZ() + 1.0D + HIT_TOLERANCE;
    }

    /** The collision box of a block, or null when the cell is air or has no solid shape. */
    public static AxisAlignedBB collisionBox(BlockPos pos) {
        if (mc.theWorld == null) return null;
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block.getMaterial().isReplaceable()) return null;
        return block.getCollisionBoundingBox(mc.theWorld, pos, state);
    }

    public static AxisAlignedBB blockBox(BlockPos pos) {
        return new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D);
    }

    /** A sampled point on a face, and the rotation from the eyes that looks at it. */
    public static final class RotationCandidate {

        public final Vec3 hitVec;
        public final BlockPos pos;
        public final float yaw;
        public final float pitch;

        public RotationCandidate(Vec3 hitVec, BlockPos pos, float yaw, float pitch) {
            this.hitVec = hitVec;
            this.pos = pos;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /** A ray that landed on a clickable face. */
    public static final class BlockPlacement {

        public final BlockPos pos;
        public final EnumFacing facing;
        public final Vec3 hitVec;
        public final float yaw;
        public final float pitch;

        public BlockPlacement(BlockPos pos, EnumFacing facing, Vec3 hitVec, float yaw, float pitch) {
            this.pos = pos;
            this.facing = facing;
            this.hitVec = hitVec;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
