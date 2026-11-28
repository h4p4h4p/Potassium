package com.potassium.module.scaffold;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;

/**
 * OpenAdjust's {@code HypixelScaffoldMode}: the late-sample tower and the forward look that
 * walks the player out over the block being placed.
 *
 * <p>The original runs a full player simulation to decide when to look forward; this port uses
 * the same stage machine but tests the drop directly, since the ported client has no simulation
 * copy of the player.</p>
 */
public final class HypixelScaffoldMode extends ScaffoldMode {

    private static final float[] FORWARD_WEIGHTS = {0.5F, 1.0F};
    private static final float[] BACK_WEIGHTS = {0.3F, 0.5F, 1.0F};

    private enum Stage {
        NONE, GROUND, FORWARD, BACK, RETURN
    }

    private Stage stage = Stage.NONE;
    private int stageTicks;
    private int forwardTicks;

    @Override
    public void reset() {
        super.reset();
        stage = Stage.NONE;
        stageTicks = 0;
        forwardTicks = 0;
    }

    @Override
    public void onPostRotation() {
        stageTicks++;
        if (stage != Stage.BACK && stage != Stage.NONE) forwardTicks++;
    }

    @Override
    public boolean canNoAscend() {
        EntityPlayerSP player = mc.thePlayer;
        return player == null || !player.isPotionActive(Potion.moveSpeed);
    }

    @Override
    public boolean allowForwardTurn() {
        if (!module.getOptions().isAscendOnJump() && mc.gameSettings.keyBindJump.isKeyDown()) {
            return false;
        }
        if (!module.getOptions().isSnap()) return true;
        float direction = Math.abs(module.moveYaw() % 90.0F);
        return direction <= 5.0F || direction >= 85.0F;
    }

    @Override
    public boolean shouldLookForward() {
        if (!module.shouldRotateForward(false)) {
            stage = Stage.NONE;
            stageTicks = 0;
            forwardTicks = 0;
            return false;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return false;
        if (player.onGround) {
            if (stage != Stage.GROUND) {
                stage = Stage.GROUND;
                stageTicks = 0;
            }
            return false;
        }
        switch (stage) {
            case BACK:
                if (willLandNextTick(player)) {
                    stage = Stage.RETURN;
                    stageTicks = 0;
                    return true;
                }
                return false;
            case RETURN:
                if (!willLandNextTick(player)) {
                    stage = Stage.BACK;
                    stageTicks = 0;
                    forwardTicks = 0;
                    return false;
                }
                return true;
            case NONE:
            case GROUND:
            case FORWARD:
                if (module.isNoAscendActive() && !willLandNextTick(player)) {
                    stage = Stage.FORWARD;
                    return true;
                }
                stage = Stage.BACK;
                stageTicks = 0;
                forwardTicks = 0;
                return false;
            default:
                return false;
        }
    }

    /** The player is over air below the build height and close to landing. */
    private boolean willLandNextTick(EntityPlayerSP player) {
        double nextY = player.posY + player.motionY;
        int floor = MathHelper.floor_double(nextY);
        if (floor >= module.getBaseY()) return false;
        net.minecraft.util.BlockPos below = new net.minecraft.util.BlockPos(
                player.posX, floor - 1, player.posZ);
        return mc.theWorld.getBlockState(below).getBlock().isReplaceable(mc.theWorld, below);
    }

    @Override
    public RotationModel forwardRotationModel() {
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && player.onGround && willStepOffGround(player)) {
            return RotationModel.INSTANT;
        }
        int remaining = 2 - forwardTicks;
        if (remaining >= 2) return new RotationModel.Weighted(FORWARD_WEIGHTS, remaining);
        return RotationModel.INSTANT;
    }

    @Override
    public RotationModel placeRotationModel() {
        if (stage == Stage.BACK && stageTicks < 3 && mc.thePlayer != null && !mc.thePlayer.onGround) {
            return new RotationModel.Weighted(BACK_WEIGHTS, 3 - stageTicks);
        }
        return null;
    }

    private boolean willStepOffGround(EntityPlayerSP player) {
        net.minecraft.util.BlockPos below = new net.minecraft.util.BlockPos(
                player.posX + player.motionX * 2.0D,
                MathHelper.floor_double(player.posY) - 1,
                player.posZ + player.motionZ * 2.0D);
        return mc.theWorld.getBlockState(below).getBlock()
                .isReplaceable(mc.theWorld, below);
    }

    @Override
    public boolean canSnap() {
        return false;
    }

    @Override
    public boolean forceRaycast() {
        return true;
    }

    @Override
    public boolean isTowerLowOnly() {
        return true;
    }

    @Override
    public void onTickStart(EntityPlayerSP player) {
        if (!isActive()) return;
        if (towering && module.getOffGroundTicks() == 6 && !player.onGround) {
            player.motionY = Math.max(player.motionY - 0.25D, -0.6D);
        }
    }

    @Override
    public void onTickEnd(EntityPlayerSP player) {
        if (!isActive()) return;
        boolean keyDown = module.getOptions().isTowerKeyDown();
        if (towering) {
            if (!keyDown) {
                towering = false;
                module.releaseInput();
                return;
            }
            ScaffoldMovement.stop(player);
            return;
        }
        if (!keyDown || module.getOffGroundTicks() != 6 || player.onGround) return;
        net.minecraft.util.BlockPos below = new net.minecraft.util.BlockPos(
                player.posX, MathHelper.floor_double(player.posY) - 1, player.posZ);
        if (mc.theWorld.getBlockState(below).getBlock().isReplaceable(mc.theWorld, below)) return;
        towering = true;
        module.holdInput();
    }

    @Override
    public void onPlace(PlacementTarget placement) {
        if (!module.getOptions().isTower() || towering) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || module.isNoAscendActive()) return;
        if (placement.placedPos.getY() != MathHelper.floor_double(player.posY) - 1) return;
        towering = true;
        module.holdInput();
    }

    @Override
    public boolean canSprint() {
        return module.getOptions().isAllowSprint();
    }
}
