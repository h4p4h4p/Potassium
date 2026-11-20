package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Removes the horizontal movement penalties applied by vanilla.
 *
 * Item use is corrected at the input stage: the input is pre-scaled by the inverse of
 * vanilla's 0.2 multiplier, so EntityPlayerSP sees normal movement input while using an
 * item. Multiplying motion after the tick instead would compound every tick. Webs and
 * soul sand are still corrected after movement because vanilla applies those directly to
 * motion.
 */
public class NoSlow extends Module {

    private final BooleanSetting itemsSetting = new BooleanSetting("Items", true);
    private final BooleanSetting websSetting = new BooleanSetting("Webs", true);
    private final BooleanSetting soulSandSetting = new BooleanSetting("Soul Sand", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    public NoSlow() {
        super("NoSlow", Category.MOVEMENT);
        settings.add(itemsSetting);
        settings.add(websSetting);
        settings.add(soulSandSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        EntityPlayer player = event.player;
        if (player == null || player != mc.thePlayer) return;

        if (event.phase == TickEvent.Phase.START) {
            // EntityPlayerSP applies its item-use slowdown after updatePlayerMoveState,
            // so installing the wrapper now gives the wrapper a chance to pre-scale it.
            if (isEnabled() && itemsSetting.getValue() && player instanceof EntityPlayerSP) {
                installMovementInput((EntityPlayerSP) player);
            }
            return;
        }

        if (!isEnabled()) return;

        // Items are handled by NoSlowMovementInput. Correcting motion here would
        // multiply the previous tick's correction again and make speed grow without bound.
        if (websSetting.getValue() && isInWeb(player)) {
            player.motionX *= 4.0D;
            player.motionZ *= 4.0D;
        }

        if (soulSandSetting.getValue() && player.onGround && isOnSoulSand(player)) {
            player.motionX *= 2.5D;
            player.motionZ *= 2.5D;
        }
    }

    private void installMovementInput(EntityPlayerSP player) {
        if (player.movementInput instanceof NoSlowMovementInput) return;

        NoSlowMovementInput input = new NoSlowMovementInput(mc.gameSettings, this);
        if (player.movementInput != null) {
            // Preserve the one-tick flags EntityPlayerSP reads before updatePlayerMoveState.
            input.moveForward = player.movementInput.moveForward;
            input.moveStrafe = player.movementInput.moveStrafe;
            input.jump = player.movementInput.jump;
            input.sneak = player.movementInput.sneak;
        }
        player.movementInput = input;
    }

    private boolean isInWeb(EntityPlayer player) {
        AxisAlignedBB box = player.getEntityBoundingBox().expand(0.001D, 0.001D, 0.001D);
        int minX = MathHelper.floor_double(box.minX);
        int maxX = MathHelper.floor_double(box.maxX);
        int minY = MathHelper.floor_double(box.minY);
        int maxY = MathHelper.floor_double(box.maxY);
        int minZ = MathHelper.floor_double(box.minZ);
        int maxZ = MathHelper.floor_double(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.web) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isOnSoulSand(EntityPlayer player) {
        double y = player.getEntityBoundingBox().minY - 0.1D;
        BlockPos below = new BlockPos(player.posX, y, player.posZ);
        Block block = mc.theWorld.getBlockState(below).getBlock();
        return block == Blocks.soul_sand;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }

    /**
     * Pre-scales the raw key input so vanilla's item-use multiplier cancels out exactly.
     * Leaving the wrapper installed is harmless: it delegates all state updates to
     * MovementInputFromOptions and only applies the correction while NoSlow is enabled.
     */
    private static final class NoSlowMovementInput extends MovementInputFromOptions {

        private final NoSlow module;

        private NoSlowMovementInput(GameSettings gameSettings, NoSlow module) {
            super(gameSettings);
            this.module = module;
        }

        @Override
        public void updatePlayerMoveState() {
            super.updatePlayerMoveState();

            if (!module.isEnabled() || !module.itemsSetting.getValue()) return;

            EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
            if (player == null || !player.isUsingItem() || player.isRiding()) return;

            // EntityPlayerSP multiplies these by 0.2 after this method returns.
            this.moveForward *= 5.0F;
            this.moveStrafe *= 5.0F;
        }
    }
}
