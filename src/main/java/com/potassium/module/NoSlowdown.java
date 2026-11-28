package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.Setting;
import com.potassium.util.MovementInputStack;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
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
 *
 * NCP mode adds the sword-blocking bypass used by Augustus and Adjust. While the player is
 * moving with a sword in use, the use is released before the movement packet and started
 * again after it, so the server processes the movement while the item is not in use. Sprint
 * is re-asserted on the same tick, because vanilla refuses to start it while an item is in
 * use; the toggle makes that sprint legitimate to the server.
 */
public class NoSlowdown extends Module {

    public static final String MODE_VANILLA = "Vanilla";
    public static final String MODE_NCP = "NCP";

    /** Vanilla only starts sprinting once forward input reaches this much of full input. */
    private static final float SPRINT_FORWARD_THRESHOLD = 0.8F;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_VANILLA, MODE_NCP}, 0);
    private final BooleanSetting itemsSetting = new BooleanSetting("Items", true);
    private final BooleanSetting websSetting = new BooleanSetting("Webs", true);
    private final BooleanSetting soulSandSetting = new BooleanSetting("Soul Sand", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    /** Set between the two player-tick phases when NCP mode released the sword block. */
    private boolean releasedUseItem;

    public NoSlowdown() {
        super("NoSlowdown", Category.MOVEMENT);
        settings.add(modeSetting);
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
            releasedUseItem = false;
            if (!isEnabled() || !(player instanceof EntityPlayerSP)) return;

            EntityPlayerSP sp = (EntityPlayerSP) player;
            if (removesItemSlowdown()) {
                // EntityPlayerSP applies its item-use slowdown after updatePlayerMoveState,
                // so installing the wrapper now gives the wrapper a chance to pre-scale it.
                MovementInputStack.install("NoSlowdown", MovementInputStack.PRIORITY_NO_SLOW,
                        inner -> new NoSlowdownMovementInput(inner, this));
            }
            if (ncp() && shouldToggleBlock(sp)) {
                sendReleaseUseItem();
                releasedUseItem = true;
            }
            return;
        }

        // The pre-scale is only needed for the player update, so nothing stays wrapped.
        MovementInputStack.uninstall("NoSlowdown");

        if (!isEnabled()) return;

        // Items are handled by NoSlowdownMovementInput. Correcting motion here would
        // multiply the previous tick's correction again and make speed grow without bound.
        if (websSetting.getValue() && isInWeb(player)) {
            player.motionX *= 4.0D;
            player.motionZ *= 4.0D;
        }

        if (soulSandSetting.getValue() && player.onGround && isOnSoulSand(player)) {
            player.motionX *= 2.5D;
            player.motionZ *= 2.5D;
        }

        if (ncp() && player instanceof EntityPlayerSP) {
            maintainSprint((EntityPlayerSP) player);
        }
    }

    /**
     * PlayerTickEvent ends inside EntityPlayer#onUpdate, which EntityPlayerSP only follows
     * with the C03 movement packet. Restoring the block needs to happen after that packet,
     * so it waits for the end of the client tick.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !releasedUseItem) return;
        releasedUseItem = false;
        EntityPlayerSP player = mc.thePlayer;
        // Movement is irrelevant here: the release already happened, so the block must be
        // restored even if the player stopped moving during the tick.
        if (player != null && isUsingSword(player)) sendBlockPlacement(player);
    }

    public boolean ncp() {
        return MODE_NCP.equals(modeSetting.getValue());
    }

    /** NCP always removes the item penalty; Vanilla follows the Items toggle. */
    private boolean removesItemSlowdown() {
        return ncp() || itemsSetting.getValue();
    }

    private boolean isUsingSword(EntityPlayerSP player) {
        if (!player.isUsingItem() || player.isRiding()) return false;
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) return false;
        ItemStack held = player.getCurrentEquippedItem();
        return held != null && held.getItem() instanceof ItemSword;
    }

    /**
     * Whether the sword block is both active and being moved with. Standing still keeps the
     * block untouched, so damage reduction is not interrupted for no reason.
     */
    private boolean shouldToggleBlock(EntityPlayerSP player) {
        if (!isUsingSword(player)) return false;
        if (player.movementInput == null) return false;
        return player.movementInput.moveForward != 0.0F
                || player.movementInput.moveStrafe != 0.0F;
    }

    /**
     * Vanilla will not start sprinting while an item is in use, so NCP mode starts it after
     * the player update. With the item penalty pre-scaled away the forward input is still
     * above vanilla's threshold, so it stays on for the following ticks.
     */
    private void maintainSprint(EntityPlayerSP player) {
        if (!shouldToggleBlock(player)) return;
        if (Scaffold.isSprintSuppressed()) return;
        if (player.isSprinting() || player.isSneaking() || player.isRiding()) return;
        if (player.movementInput.moveForward < SPRINT_FORWARD_THRESHOLD) return;
        if (player.isCollidedHorizontally || player.isPotionActive(Potion.blindness)) return;
        if (player.getFoodStats().getFoodLevel() <= 6 && !player.capabilities.allowFlying) return;
        player.setSprinting(true);
    }

    private void sendReleaseUseItem() {
        if (mc.getNetHandler() == null) return;
        mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
    }

    private void sendBlockPlacement(EntityPlayer player) {
        if (mc.getNetHandler() == null) return;
        ItemStack held = player.getCurrentEquippedItem();
        if (held != null) mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(held));
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
    public boolean isSettingVisible(Setting setting) {
        // NCP mode always removes the item penalty, including sprinting, so the toggle has
        // no meaning there.
        if (setting == itemsSetting) return !ncp();
        return super.isSettingVisible(setting);
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        // A block released on this tick is restored by the client tick handler, after the
        // movement packet; sending it here would put the use back in front of that packet.
    }

    /**
     * Pre-scales the raw key input so vanilla's item-use multiplier cancels out exactly.
     * Leaving the wrapper installed is harmless: it delegates all state updates to
     * MovementInputFromOptions and only applies the correction while NoSlowdown is enabled.
     */
    private static final class NoSlowdownMovementInput extends MovementInput {

        private final MovementInput delegate;
        private final NoSlowdown module;

        private NoSlowdownMovementInput(MovementInput delegate, NoSlowdown module) {
            this.delegate = delegate;
            this.module = module;
        }

        @Override
        public void updatePlayerMoveState() {
            delegate.updatePlayerMoveState();
            moveForward = delegate.moveForward;
            moveStrafe = delegate.moveStrafe;
            jump = delegate.jump;
            sneak = delegate.sneak;

            if (!module.isEnabled() || !module.removesItemSlowdown()) return;

            EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
            if (player == null || !player.isUsingItem() || player.isRiding()) return;

            // EntityPlayerSP multiplies these by 0.2 after this method returns.
            this.moveForward *= 5.0F;
            this.moveStrafe *= 5.0F;
        }
    }
}
