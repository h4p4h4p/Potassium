package com.potassium.module.scaffold;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

/** The movement primitives OpenAdjust's modes use for their towers. */
final class ScaffoldMovement {

    private ScaffoldMovement() {
    }

    /** Vanilla's jump motion, including a jump boost potion. */
    static double jumpMotion(EntityPlayerSP player) {
        double motion = 0.42D;
        if (player.isPotionActive(Potion.jump)) {
            motion += (player.getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1D;
        }
        return motion;
    }

    /** Any solid block inside the box, which is what stops a tower jump. */
    static boolean isColliding(EntityPlayerSP player, AxisAlignedBB box) {
        for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX); x++) {
            for (int y = MathHelper.floor_double(box.minY); y <= MathHelper.floor_double(box.maxY); y++) {
                for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ); z++) {
                    if (PlacementFinder.collisionBox(new net.minecraft.util.BlockPos(x, y, z)) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static void stop(EntityPlayerSP player) {
        player.motionX = 0.0D;
        player.motionZ = 0.0D;
    }

    /** Pulls the player onto the middle of the block they are standing on. */
    static void center(EntityPlayerSP player, double speed) {
        double dx = MathHelper.floor_double(player.posX) + 0.5D - player.posX;
        double dz = MathHelper.floor_double(player.posZ) + 0.5D - player.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            stop(player);
            return;
        }
        double limit = Math.min(speed, length);
        player.motionX = dx / length * limit;
        player.motionZ = dz / length * limit;
    }

    /** Keeps the player's current direction and gives it a fixed speed. */
    static void setSpeed(EntityPlayerSP player, double speed) {
        double length = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
        if (length < 1.0E-4D) {
            float yaw = player.rotationYaw * 0.017453292F;
            player.motionX = -MathHelper.sin(yaw) * speed;
            player.motionZ = MathHelper.cos(yaw) * speed;
            return;
        }
        player.motionX = player.motionX / length * speed;
        player.motionZ = player.motionZ / length * speed;
    }

    static double speed(EntityPlayerSP player) {
        return Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
    }

    /** OpenAdjust's speed-potion scaling for the tower speeds. */
    static double withSpeedPotion(EntityPlayerSP player, double base) {
        if (!player.isPotionActive(Potion.moveSpeed)) return base;
        int level = player.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1;
        return base * (1.0D + 0.2D * level);
    }

    /** Small holder so this helper needs no static Minecraft field of its own. */
    private static final class MinecraftHolder {
        private static net.minecraft.client.Minecraft world() {
            return net.minecraft.client.Minecraft.getMinecraft();
        }
    }
}
