package com.potassium.module.scaffold;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

/** OpenAdjust's {@code RewoundScaffoldMode}: the same tower frame with the Rewound speeds. */
public final class RewoundScaffoldMode extends ScaffoldMode {

    public boolean shouldAutoJump() {
        return isActive() && canTower() && !towering && mc.thePlayer != null
                && mc.thePlayer.onGround && module.getOptions().isTowerKeyDown();
    }

    @Override
    public void onTickStart(EntityPlayerSP player) {
        if (!isActive()) return;
        if (towering && !player.onGround && module.getOffGroundTicks() == 3
                && player.posY % 1.0D == 0.0D) {
            player.onGround = true;
        }
    }

    @Override
    public void onTickEnd(EntityPlayerSP player) {
        if (!isActive()) return;
        if (!canTower()) {
            towering = false;
            return;
        }
        boolean keyDown = module.getOptions().isTowerKeyDown();
        if (!towering) {
            if (player.onGround && keyDown) {
                module.skipBaseYUpdate = true;
                player.motionY = ScaffoldMovement.jumpMotion(player);
                towering = true;
            }
        } else if (!keyDown) {
            towering = false;
            return;
        } else if (!player.onGround && !player.isCollidedVertically) {
            if (module.getOffGroundTicks() == 2) {
                double fraction = player.posY - MathHelper.floor_double(player.posY);
                if (fraction > 1.0D - fraction) player.motionY = 1.0D - fraction;
            }
        } else {
            towering = false;
            return;
        }
        if (!towering) return;

        double speed = ScaffoldMovement.withSpeedPotion(player, 0.2873D);
        String movement = module.getOptions().getTowerMovement();
        if (ScaffoldSettings.TOWER_NORMAL.equals(movement)) {
            ScaffoldMovement.setSpeed(player, speed);
        } else if (ScaffoldSettings.TOWER_STOP.equals(movement)) {
            ScaffoldMovement.stop(player);
        } else if (ScaffoldSettings.TOWER_CENTER.equals(movement)) {
            ScaffoldMovement.center(player, speed);
        }
    }

    @Override
    public void onPlace(PlacementTarget placement) {
        if (!module.getOptions().isTower()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || player.isInWater() || player.isInLava()) return;
        BlockPos placed = placement.placedPos;
        if (placed.getY() != MathHelper.floor_double(player.posY) - 1) return;
        AxisAlignedBB feet = player.getEntityBoundingBox().addCoord(0.0D, -0.0625D, 0.0D);
        AxisAlignedBB blockBox = new AxisAlignedBB(placed, placed.add(1, 1, 1));
        if (!feet.intersectsWith(blockBox) || !shouldTower()) return;
        if (towering) {
            player.motionY = -0.0784D;
            player.onGround = true;
            module.setOffGroundTicks(0);
            towering = false;
        }
    }

    @Override
    public boolean canSprint() {
        return true;
    }

    public boolean canTower() {
        if (!module.getOptions().isTower()) return false;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return false;
        if (MathHelper.floor_double(player.posY) >= 256) return false;
        if (player.isPotionActive(Potion.jump)) return false;
        if (module.isNoAscendActive()) return false;
        return true;
    }

    public boolean shouldTower() {
        return canTower() && module.getOptions().isTowerKeyDown();
    }
}
