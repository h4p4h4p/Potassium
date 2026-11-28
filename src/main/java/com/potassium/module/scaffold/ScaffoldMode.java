package com.potassium.module.scaffold;

import com.potassium.module.Scaffold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;

/**
 * OpenAdjust's {@code ScaffoldMode}. A mode owns the parts of the module that differ between
 * servers: whether sprint is allowed while placing, whether a tower can be built and how its
 * jump is produced, and which rotation behavior the placement uses.
 */
public abstract class ScaffoldMode {

    public static final int CAP_SPRINT = 0x2;
    public static final int CAP_NO_ASCEND = 0x4;
    public static final int CAP_TOWER = 0x8;
    public static final int CAP_TOWER_MOVEMENT = 0x10;
    public static final int CAP_EXTEND = 0x20;

    protected final Minecraft mc = Minecraft.getMinecraft();
    private final String name;
    private final int capabilities;
    protected Scaffold module;
    public boolean towering;

    protected ScaffoldMode(String name, int capabilities) {
        this.name = name;
        this.capabilities = capabilities;
    }

    public String getName() {
        return name;
    }

    public void attach(Scaffold module) {
        this.module = module;
    }

    public boolean has(int capability) {
        return (capabilities & capability) != 0;
    }

    public boolean isActive() {
        return module != null && module.isEnabled() && module.currentMode() == this;
    }

    public void reset() {
        towering = false;
    }

    public boolean isTowerActive() {
        return towering;
    }

    public boolean isAboveBuildLimit(int y) {
        return mc.theWorld != null && y >= mc.theWorld.getHeight();
    }

    public void onPlace(PlacementTarget placement) {
    }

    public void onTickStart(Scaffold module, EntityPlayerSP player) {
    }

    public void onTickEnd(Scaffold module, EntityPlayerSP player) {
    }

    public boolean canSprint() {
        return true;
    }

    public void onPostRotation() {
    }

    public boolean canPlaceInGame() {
        return true;
    }

    public boolean canNoAscend() {
        return true;
    }

    public boolean canAutoJump() {
        EntityPlayerSP player = mc.thePlayer;
        return player != null && player.onGround;
    }

    public boolean allowForwardTurn() {
        return true;
    }

    public boolean canSnap() {
        return true;
    }

    public boolean useFastRotations() {
        return false;
    }

    public boolean forceRaycast() {
        return false;
    }

    public boolean isTowerLowOnly() {
        return false;
    }

    public boolean canTower() {
        return false;
    }

    public boolean shouldTower() {
        return false;
    }

    /** Plain scaffold: placements behind the player, a vanilla jump tower. */
    public static final class Normal extends ScaffoldMode {

        private boolean placedUp;

        Normal() {
            super(ScaffoldSettings.MODE_NORMAL,
                    CAP_SPRINT | CAP_NO_ASCEND | CAP_TOWER | CAP_TOWER_MOVEMENT | CAP_EXTEND);
        }

        @Override
        public void reset() {
            super.reset();
            placedUp = false;
        }

        private boolean canTowerNow() {
            ScaffoldSettings settings = module.getOptions();
            if (!settings.isTower()) return false;
            EntityPlayerSP player = mc.thePlayer;
            if (player == null || mc.theWorld == null) return false;
            if (player.isInWater() || player.isInLava()) return false;
            if (player.isPotionActive(Potion.jump)) return false;
            return settings.isTowerKeyDown();
        }

        @Override
        public void onPlace(PlacementTarget placement) {
            if (!canTowerNow()) return;
            if (placement.facing == EnumFacing.UP) placedUp = true;
        }

        @Override
        public void onTickEnd(Scaffold module, EntityPlayerSP player) {
            if (!canTowerNow()) {
                towering = false;
                placedUp = false;
                return;
            }
            if (placedUp) {
                double jump = module.jumpMotion(0.42D);
                AxisAlignedBB box = player.getEntityBoundingBox().offset(0.0D, jump, 0.0D);
                if (!module.collides(box)) player.motionY = jump;
                towering = true;
            }
            applyMovement(module, player);
            placedUp = false;
        }

        private void applyMovement(Scaffold module, EntityPlayerSP player) {
            String movement = module.getOptions().getTowerMovement();
            if (ScaffoldSettings.TOWER_STOP.equals(movement)) {
                module.stopMotion(player);
            } else if (ScaffoldSettings.TOWER_CENTER.equals(movement)) {
                module.centerMotion(player, 1.0D);
            }
        }

        @Override
        public boolean canSprint() {
            return module.getOptions().isAllowSprint();
        }

        @Override
        public boolean canTower() {
            return canTowerNow();
        }

        @Override
        public boolean shouldTower() {
            return canTowerNow();
        }
    }

    /**
     * NCP's tower: jump from the ground, correct the early airborne fraction so the second tick
     * starts from a whole block, and reset the motion to a tiny downward step when the block
     * under the feet is placed.
     */
    public static final class Ncp extends ScaffoldMode {

        Ncp() {
            super(ScaffoldSettings.MODE_NCP,
                    CAP_SPRINT | CAP_NO_ASCEND | CAP_TOWER | CAP_TOWER_MOVEMENT | CAP_EXTEND);
        }

        @Override
        public void onTickStart(Scaffold module, EntityPlayerSP player) {
            if (!isActive()) return;
            if (!canTower()) {
                towering = false;
                return;
            }
            boolean keyDown = module.getOptions().isTowerKeyDown();
            if (!towering) {
                if (player.onGround && keyDown) {
                    module.setPreventsAscend(false);
                    player.motionY = module.jumpMotion(0.42D);
                    towering = true;
                }
            } else if (!keyDown) {
                towering = false;
                return;
            } else if (!player.onGround && !player.isCollidedVertically) {
                if (module.offGroundTicks() == 2) {
                    double fraction = player.posY - MathHelper.floor_double(player.posY);
                    if (fraction > 1.0D - fraction) player.motionY = 1.0D - fraction;
                }
            } else {
                towering = false;
                return;
            }
            if (!towering) return;

            String movement = module.getOptions().getTowerMovement();
            if (ScaffoldSettings.TOWER_NORMAL.equals(movement)) {
                if (module.offGroundTicks() < 2) {
                    module.setTowerSpeed(player, 0.2872D);
                }
            } else if (ScaffoldSettings.TOWER_STOP.equals(movement)) {
                module.stopMotion(player);
            } else if (ScaffoldSettings.TOWER_CENTER.equals(movement)) {
                if (module.offGroundTicks() >= 2) module.stopMotion(player);
                else module.centerMotion(player, 0.2872D);
            }
        }

        @Override
        public void onPlace(PlacementTarget placement) {
            if (!module.getOptions().isTower()) return;
            EntityPlayerSP player = mc.thePlayer;
            if (player == null || player.isInWater() || player.isInLava()) return;
            BlockPos placed = placement.pos.offset(placement.facing);
            if (placed.getY() != MathHelper.floor_double(player.posY) - 1) return;
            AxisAlignedBB feet = player.getEntityBoundingBox().addCoord(0.0D, -0.0625D, 0.0D);
            AxisAlignedBB blockBox = new AxisAlignedBB(
                    placed.getX(), placed.getY(), placed.getZ(),
                    placed.getX() + 1.0D, placed.getY() + 1.0D, placed.getZ() + 1.0D);
            if (!feet.intersectsWith(blockBox) || !shouldTower()) return;
            if (towering) {
                player.motionY = -0.0784D;
                player.onGround = true;
                module.resetOffGroundTicks();
                towering = false;
            }
        }

        @Override
        public boolean canSprint() {
            EntityPlayerSP player = mc.thePlayer;
            if (player == null) return false;
            if (!player.onGround) return false;
            if (module.getOptions().isAllowSprint()) return true;
            if (mc.gameSettings.keyBindJump.isKeyDown()) return true;
            if (player.movementInput != null && player.movementInput.jump) return true;

            float yaw = module.getAimYaw();
            float forward = (mc.gameSettings.keyBindForward.isKeyDown() ? 1.0F : 0.0F)
                    - (mc.gameSettings.keyBindBack.isKeyDown() ? 1.0F : 0.0F);
            float strafe = (mc.gameSettings.keyBindLeft.isKeyDown() ? 1.0F : 0.0F)
                    - (mc.gameSettings.keyBindRight.isKeyDown() ? 1.0F : 0.0F);
            float angle;
            if (forward < 0.0F) {
                yaw += 180.0F;
                angle = -45.0F;
            } else if (forward > 0.0F) {
                angle = 45.0F;
            } else {
                angle = 90.0F;
            }
            if (strafe > 0.0F) yaw -= angle;
            if (strafe < 0.0F) yaw += angle;
            float difference = MathHelper.wrapAngleTo180_float(player.rotationYaw - yaw);
            return Math.abs(difference) <= 45.0F;
        }

        @Override
        public boolean canTower() {
            ScaffoldSettings settings = module.getOptions();
            if (!settings.isTower()) return false;
            EntityPlayerSP player = mc.thePlayer;
            if (player == null || mc.theWorld == null) return false;
            if (MathHelper.floor_double(player.posY) >= 256) return false;
            if (player.isPotionActive(Potion.jump)) return false;
            if (module.isNoAscendActive()) return false;
            return true;
        }

        @Override
        public boolean shouldTower() {
            return canTower() && module.getOptions().isTowerKeyDown();
        }
    }

    /** Rewound's tower: the same jump, with the block edge pushed to a whole block sooner. */
    public static final class Rewound extends ScaffoldMode {

        Rewound() {
            super(ScaffoldSettings.MODE_REWOUND,
                    CAP_SPRINT | CAP_NO_ASCEND | CAP_TOWER | CAP_TOWER_MOVEMENT | CAP_EXTEND);
        }

        @Override
        public void onTickStart(Scaffold module, EntityPlayerSP player) {
            if (!isActive()) return;
            if (!canTower()) {
                towering = false;
                return;
            }
            boolean keyDown = module.getOptions().isTowerKeyDown();
            if (!towering) {
                if (player.onGround && keyDown) {
                    module.setPreventsAscend(false);
                    player.motionY = module.jumpMotion(0.42D);
                    towering = true;
                }
            } else if (!keyDown) {
                towering = false;
                return;
            } else if (!player.onGround && !player.isCollidedVertically) {
                if (module.offGroundTicks() == 2) {
                    double fraction = player.posY - MathHelper.floor_double(player.posY);
                    if (fraction > 1.0D - fraction) player.motionY = 1.0D - fraction;
                }
            } else {
                towering = false;
                return;
            }
            if (!towering) return;

            String movement = module.getOptions().getTowerMovement();
            if (ScaffoldSettings.TOWER_NORMAL.equals(movement)) {
                module.setTowerSpeed(player, 0.2873D);
            } else if (ScaffoldSettings.TOWER_STOP.equals(movement)) {
                module.stopMotion(player);
            } else if (ScaffoldSettings.TOWER_CENTER.equals(movement)) {
                module.centerMotion(player, 0.2873D);
            }
        }

        @Override
        public void onPlace(PlacementTarget placement) {
            if (!module.getOptions().isTower()) return;
            EntityPlayerSP player = mc.thePlayer;
            if (player == null || player.isInWater() || player.isInLava()) return;
            BlockPos placed = placement.pos.offset(placement.facing);
            if (placed.getY() != MathHelper.floor_double(player.posY) - 1) return;
            AxisAlignedBB feet = player.getEntityBoundingBox().addCoord(0.0D, -0.0625D, 0.0D);
            AxisAlignedBB blockBox = new AxisAlignedBB(
                    placed.getX(), placed.getY(), placed.getZ(),
                    placed.getX() + 1.0D, placed.getY() + 1.0D, placed.getZ() + 1.0D);
            if (!feet.intersectsWith(blockBox) || !shouldTower()) return;
            if (towering) {
                player.motionY = -0.0784D;
                player.onGround = true;
                module.resetOffGroundTicks();
                towering = false;
            }
        }

        @Override
        public boolean canSprint() {
            return true;
        }

        @Override
        public boolean canTower() {
            if (!module.getOptions().isTower()) return false;
            EntityPlayerSP player = mc.thePlayer;
            if (player == null || mc.theWorld == null) return false;
            if (MathHelper.floor_double(player.posY) >= 256) return false;
            if (player.isPotionActive(Potion.jump)) return false;
            return !module.isNoAscendActive();
        }

        @Override
        public boolean shouldTower() {
            return canTower() && module.getOptions().isTowerKeyDown();
        }
    }

    /**
     * Hypixel's mode. It places only with a raycast, never snaps, and towers only on the low
     * edge of the block. Its server-specific gates (placement limiter, tab timing) have no
     * counterpart in this client.
     */
    public static final class Hypixel extends ScaffoldMode {

        Hypixel() {
            super(ScaffoldSettings.MODE_HYPIXEL, CAP_SPRINT | CAP_NO_ASCEND | CAP_TOWER);
        }

        @Override
        public boolean canSprint() {
            return module.getOptions().isAllowSprint();
        }

        @Override
        public boolean canNoAscend() {
            EntityPlayerSP player = mc.thePlayer;
            return player == null || !player.isPotionActive(Potion.moveSpeed);
        }

        @Override
        public boolean canAutoJump() {
            EntityPlayerSP player = mc.thePlayer;
            return player != null && player.onGround;
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
        public boolean canSnap() {
            return false;
        }

        @Override
        public boolean useFastRotations() {
            return true;
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
        public boolean canTower() {
            if (!module.getOptions().isTower()) return false;
            EntityPlayerSP player = mc.thePlayer;
            return player != null && MathHelper.floor_double(player.posY) < 256;
        }
    }
}
