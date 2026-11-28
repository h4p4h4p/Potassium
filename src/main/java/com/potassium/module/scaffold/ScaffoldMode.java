package com.potassium.module.scaffold;

import com.potassium.module.Scaffold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * OpenAdjust's {@code ScaffoldMode}: a named set of capabilities plus the per-mode ticks that
 * make the towers and the sprint gate differ.
 *
 * <p>Every mode carries the same capability mask the original does, so a mode that cannot
 * extend, move while towering or snap hides exactly the rows OpenAdjust hides.</p>
 */
public abstract class ScaffoldMode {

    public static final int CAP_SPRINT = 0x2;
    public static final int CAP_NO_ASCEND = 0x4;
    public static final int CAP_TOWER = 0x8;
    public static final int CAP_TOWER_MOVEMENT = 0x10;
    public static final int CAP_EXTEND = 0x20;

    protected static final Minecraft mc = Minecraft.getMinecraft();

    protected Scaffold module;
    protected String name;
    protected int capabilities;
    public boolean towering;

    /** Called once by {@link ScaffoldSettings} while the modes are built. */
    public final void attach(Scaffold module, String name, int capabilities) {
        this.module = module;
        this.name = name;
        this.capabilities = capabilities;
    }

    public String getName() {
        return name;
    }

    public int getCapabilities() {
        return capabilities;
    }

    public boolean has(int capability) {
        return (capabilities & capability) != 0;
    }

    public boolean isActive() {
        return module != null && module.isEnabled() && module.getOptions().mode() == this;
    }

    public void reset() {
        towering = false;
    }

    /** Player ticks: the mode's tower and motion work, in OpenAdjust's two phases. */
    public void onTickStart(EntityPlayerSP player) {
    }

    public void onTickEnd(EntityPlayerSP player) {
    }

    public void onPostRotation() {
    }

    public boolean isAboveBuildLimit(int y) {
        return mc.theWorld != null && y >= mc.theWorld.getHeight();
    }

    public abstract void onPlace(PlacementTarget placement);

    public abstract boolean canSprint();

    public boolean canPlaceInGame() {
        return true;
    }

    /** NCP ignores the block under the mouse when preparing a click. */
    public boolean ignoresMouseOver() {
        return false;
    }

    public boolean canNoAscend() {
        return true;
    }

    public boolean canAutoJump() {
        return mc.thePlayer == null || mc.thePlayer.onGround;
    }

    public boolean allowForwardTurn() {
        return true;
    }

    public boolean shouldLookForward() {
        return false;
    }

    public RotationModel forwardRotationModel() {
        return RotationModel.INSTANT;
    }

    public RotationModel placeRotationModel() {
        return null;
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
}
