package com.potassium.module.scaffold;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

/** One solved placement: the block clicked, the face, the point on it and the rotation to it. */
public final class PlacementTarget {

    /** The block that is clicked. It is solid; the block being placed is {@link #placedPos}. */
    public final BlockPos pos;
    public final EnumFacing facing;
    public final Vec3 hitVec;
    public final float yaw;
    public final float pitch;
    /** The cell the new block lands in: the clicked block one step along its face. */
    public final BlockPos placedPos;

    public PlacementTarget(BlockPos pos, EnumFacing facing, Vec3 hitVec, float yaw, float pitch) {
        this.pos = pos;
        this.facing = facing;
        this.hitVec = hitVec;
        this.yaw = yaw;
        this.pitch = pitch;
        this.placedPos = pos.offset(facing);
    }
}
