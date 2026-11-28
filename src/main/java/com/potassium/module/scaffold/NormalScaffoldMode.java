package com.potassium.module.scaffold;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumFacing;

/** OpenAdjust's {@code NormalScaffoldMode}: vanilla jump tower on an up-facing placement. */
public final class NormalScaffoldMode extends ScaffoldMode {

    private boolean placedUp;

    @Override
    public void reset() {
        super.reset();
        placedUp = false;
    }

    private boolean canTowerNow() {
        ScaffoldSettings settings = module.getOptions();
        if (!settings.isTower()) return false;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || player.isInWater() || player.isInLava()) return false;
        if (player.isPotionActive(Potion.jump)) return false;
        return settings.isTowerKeyDown();
    }

    @Override
    public void onTickEnd(EntityPlayerSP player) {
        if (!isActive() || !canTowerNow()) {
            towering = false;
            placedUp = false;
            return;
        }
        if (placedUp) {
            double jumpMotion = ScaffoldMovement.jumpMotion(player);
            AxisAlignedBB box = player.getEntityBoundingBox().offset(0.0D, jumpMotion, 0.0D);
            if (!ScaffoldMovement.isColliding(player, box)) {
                player.motionY = jumpMotion;
            }
            towering = true;
        }
        String movement = module.getOptions().getTowerMovement();
        if (ScaffoldSettings.TOWER_STOP.equals(movement)) {
            ScaffoldMovement.stop(player);
        } else if (ScaffoldSettings.TOWER_CENTER.equals(movement)) {
            ScaffoldMovement.center(player, 1.0D);
        }
        placedUp = false;
    }

    @Override
    public void onPlace(PlacementTarget placement) {
        if (!canTowerNow()) return;
        if (placement.facing == EnumFacing.UP) placedUp = true;
    }

    @Override
    public boolean canSprint() {
        return module.getOptions().isAllowSprint();
    }
}
