package com.potassium.module.scaffold;

import com.potassium.module.Scaffold;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.KeySetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.RangeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAdjust's {@code ScaffoldSettings}: the same options in the same groups, with the four
 * modes it ships. A mode's capability mask hides the options that would do nothing on it.
 */
public final class ScaffoldSettings {

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_HYPIXEL = "Hypixel";
    public static final String MODE_REWOUND = "Rewound";
    public static final String MODE_NCP = "NCP";

    public static final String ROTATION_STEP = "Step";
    public static final String ROTATION_LINEAR = "Linear";
    public static final String ROTATION_INSTANT = "Instant";

    public static final String NO_ASCEND_FLAT = "Flat";
    public static final String NO_ASCEND_BUFFER = "Buffer";

    public static final String TOWER_NORMAL = "Normal";
    public static final String TOWER_STOP = "Stop";
    public static final String TOWER_CENTER = "Center";

    private static final String ROTATIONS_GROUP = "Rotations";
    private static final String ADDONS_GROUP = "Addons";
    private static final String CLIENTSIDE_GROUP = "Clientside";
    private static final String RENDER_GROUP = "Render";
    private static final String SAFE_WALK_GROUP = "Safe walk";
    private static final String SPRINT_GROUP = "Allow sprint";
    private static final String NO_ASCEND_GROUP = "No ascend";
    private static final String TOWER_GROUP = "Tower";

    private final List<Setting> settings = new ArrayList<>();
    private final Scaffold module;

    public final ScaffoldMode normal;
    public final ScaffoldMode hypixel;
    public final ScaffoldMode rewound;
    public final ScaffoldMode ncp;

    public final ModeSetting mode;
    public final RangeSetting cps;
    public final BooleanSetting rotationsEnabled;
    public final ModeSetting rotationMode;
    public final RangeSetting horizontalSpeed;
    public final RangeSetting verticalSpeed;
    public final BooleanSetting snap;
    public final BooleanSetting stableYaw;
    public final BooleanSetting raycast;
    public final BooleanSetting swing;
    public final BooleanSetting clientsideSwap;
    public final BooleanSetting changeSlot;
    public final BooleanSetting waitForClick;
    public final SliderSetting extend;
    public final BooleanSetting safeWalkEnabled;
    public final BooleanSetting safeWalkSneak;
    public final SliderSetting unsneakDelay;
    public final SliderSetting earlySneak;
    public final BooleanSetting allowSprint;
    public final BooleanSetting noAscendEnabled;
    public final ModeSetting noAscendMode;
    public final KeySetting noAscendBind;
    public final BooleanSetting autoJump;
    public final BooleanSetting ascendOnJump;
    public final BooleanSetting towerEnabled;
    public final KeySetting towerBind;
    public final ModeSetting towerMovement;
    public final BooleanSetting blockPlacements;

    public ScaffoldSettings(Scaffold module) {
        this.module = module;

        normal = new NormalScaffoldMode();
        hypixel = new HypixelScaffoldMode();
        rewound = new RewoundScaffoldMode();
        ncp = new NcpScaffoldMode();
        normal.attach(module, MODE_NORMAL, 62);
        hypixel.attach(module, MODE_HYPIXEL, 14);
        rewound.attach(module, MODE_REWOUND, 62);
        ncp.attach(module, MODE_NCP, 62);

        mode = new ModeSetting("Mode",
                new String[]{MODE_NORMAL, MODE_HYPIXEL, MODE_REWOUND, MODE_NCP}, 0);
        add(mode);

        cps = new RangeSetting("CPS", 9, 13, 1, 20, 1);
        add(cps);

        rotationsEnabled = grouped(new BooleanSetting("Rotations", true), ROTATIONS_GROUP);
        rotationMode = grouped(new ModeSetting("Rotation Mode",
                new String[]{ROTATION_STEP, ROTATION_LINEAR, ROTATION_INSTANT}, 0), ROTATIONS_GROUP);
        horizontalSpeed = grouped(new RangeSetting("Horizontal Speed", 180, 180, 0, 180, 1),
                ROTATIONS_GROUP);
        verticalSpeed = grouped(new RangeSetting("Vertical Speed", 180, 180, 0, 180, 1),
                ROTATIONS_GROUP);
        snap = grouped(new BooleanSetting("Snap", false), ROTATIONS_GROUP);
        stableYaw = grouped(new BooleanSetting("Stable Yaw", true), ROTATIONS_GROUP);
        raycast = grouped(new BooleanSetting("Raycast", true), ROTATIONS_GROUP);

        swing = grouped(new BooleanSetting("Swing", true), CLIENTSIDE_GROUP);
        clientsideSwap = grouped(new BooleanSetting("Clientside Swap", false), CLIENTSIDE_GROUP);
        changeSlot = grouped(new BooleanSetting("Change Slot", true), ADDONS_GROUP);
        waitForClick = grouped(new BooleanSetting("Wait for Click", false), ADDONS_GROUP);
        extend = grouped(new SliderSetting("Extend", 0, 0, 3, 1), ADDONS_GROUP);

        safeWalkEnabled = grouped(new BooleanSetting("Safe Walk", false), SAFE_WALK_GROUP);
        safeWalkSneak = grouped(new BooleanSetting("Sneak", false), SAFE_WALK_GROUP);
        unsneakDelay = grouped(new SliderSetting("Un-sneak Delay", 0, 0, 5, 1), SAFE_WALK_GROUP);
        earlySneak = grouped(new SliderSetting("Early Sneak", 0, 0, 5, 1), SAFE_WALK_GROUP);

        allowSprint = grouped(new BooleanSetting("Allow Sprint", true), SPRINT_GROUP);

        noAscendEnabled = grouped(new BooleanSetting("No Ascend", false), NO_ASCEND_GROUP);
        noAscendMode = grouped(new ModeSetting("No Ascend Mode",
                new String[]{NO_ASCEND_FLAT, NO_ASCEND_BUFFER}, 0), NO_ASCEND_GROUP);
        noAscendBind = grouped(new KeySetting("No Ascend Bind", KeySetting.NONE), NO_ASCEND_GROUP);
        autoJump = grouped(new BooleanSetting("Auto Jump", true), NO_ASCEND_GROUP);
        ascendOnJump = grouped(new BooleanSetting("Ascend on Jump", true), NO_ASCEND_GROUP);

        towerEnabled = grouped(new BooleanSetting("Tower", false), TOWER_GROUP);
        towerBind = grouped(new KeySetting("Tower Bind", KeySetting.NONE), TOWER_GROUP);
        towerMovement = grouped(new ModeSetting("Tower Movement",
                new String[]{TOWER_NORMAL, TOWER_STOP, TOWER_CENTER}, 0), TOWER_GROUP);

        blockPlacements = grouped(new BooleanSetting("Block Placements", false), RENDER_GROUP);
    }

    private <T extends Setting> T grouped(T setting, String group) {
        setting.setGroup(group);
        add(setting);
        return setting;
    }

    private void add(Setting setting) {
        settings.add(setting);
    }

    public List<Setting> getSettings() {
        return settings;
    }

    public ScaffoldMode mode() {
        switch (mode.getIndex()) {
            case 1:
                return hypixel;
            case 2:
                return rewound;
            case 3:
                return ncp;
            default:
                return normal;
        }
    }

    /**
     * Rows that would do nothing on the selected mode are hidden, the way OpenAdjust's expand
     * settings grey themselves out for a mode without the matching capability.
     */
    public boolean isVisible(Setting setting) {
        if (setting == rotationMode || setting == horizontalSpeed || setting == verticalSpeed
                || setting == snap || setting == stableYaw || setting == raycast) {
            if (!rotationsEnabled.getValue()) return false;
            if (setting == horizontalSpeed || setting == verticalSpeed) {
                return !ROTATION_INSTANT.equals(rotationMode.getValue());
            }
            if (setting == snap) return mode().canSnap();
            return true;
        }
        if (setting == safeWalkSneak || setting == unsneakDelay || setting == earlySneak) {
            if (!safeWalkEnabled.getValue()) return false;
            if (setting == safeWalkSneak) return true;
            return safeWalkSneak.getValue();
        }
        if (setting == allowSprint) return mode().has(ScaffoldMode.CAP_SPRINT);
        if (setting == noAscendEnabled) return mode().has(ScaffoldMode.CAP_NO_ASCEND);
        if (setting == noAscendMode || setting == noAscendBind || setting == autoJump
                || setting == ascendOnJump) {
            return noAscendEnabled.getValue() && mode().has(ScaffoldMode.CAP_NO_ASCEND);
        }
        if (setting == towerEnabled) return mode().has(ScaffoldMode.CAP_TOWER);
        if (setting == towerBind) return towerEnabled.getValue();
        if (setting == towerMovement) {
            return towerEnabled.getValue() && mode().has(ScaffoldMode.CAP_TOWER_MOVEMENT);
        }
        if (setting == extend) return mode().has(ScaffoldMode.CAP_EXTEND);
        if (setting == clientsideSwap) return changeSlot.getValue();
        return true;
    }

    public boolean isRotationsEnabled() {
        return rotationsEnabled.getValue();
    }

    public String getRotationMode() {
        return rotationMode.getValue();
    }

    public boolean isSnap() {
        return snap.getValue();
    }

    public boolean isStableYaw() {
        return stableYaw.getValue();
    }

    public boolean isRaycast() {
        return raycast.getValue();
    }

    /** OpenAdjust's clientside swing: the client does the swing, not the placement packet. */
    public boolean isSwing() {
        return swing.getValue();
    }

    public boolean isClientsideSwap() {
        return changeSlot.getValue() && clientsideSwap.getValue();
    }

    public boolean isChangeSlot() {
        return changeSlot.getValue();
    }

    /** Kept for the placement code's older name for the clientside swap. */
    public boolean isSilentSwitch() {
        return isClientsideSwap();
    }

    public boolean isWaitForClick() {
        return waitForClick.getValue();
    }

    public boolean isBlockPlacements() {
        return blockPlacements.getValue();
    }

    public int getCpsLow() {
        return (int) Math.round(cps.getLow());
    }

    public int getCpsHigh() {
        return (int) Math.round(cps.getHigh());
    }

    public double getExtend() {
        if (!mode().has(ScaffoldMode.CAP_EXTEND)) return 0.0D;
        if (mode() instanceof RewoundScaffoldMode) {
            return isTower() && isTowerKeyDown() ? 2.0D : 1.0D;
        }
        return extend.getValue();
    }

    public boolean isSafeWalk() {
        return safeWalkEnabled.getValue();
    }

    public boolean isSafeWalkSneak() {
        return safeWalkSneak.getValue();
    }

    public double getUnsneakDelay() {
        return unsneakDelay.getValue();
    }

    public double getEarlySneak() {
        return earlySneak.getValue();
    }

    public boolean isAllowSprint() {
        return allowSprint.getValue() && mode().has(ScaffoldMode.CAP_SPRINT);
    }

    public boolean isNoAscend() {
        return noAscendEnabled.getValue() && mode().has(ScaffoldMode.CAP_NO_ASCEND);
    }

    public String getNoAscendMode() {
        return noAscendMode.getValue();
    }

    public boolean isNoAscendKeyDown() {
        return noAscendBind.isDown();
    }

    public boolean isAutoJump() {
        return autoJump.getValue();
    }

    public boolean isAscendOnJump() {
        return ascendOnJump.getValue();
    }

    public boolean isTower() {
        return towerEnabled.getValue() && mode().has(ScaffoldMode.CAP_TOWER);
    }

    public boolean isTowerKeyDown() {
        if (towerBind.isBound()) return towerBind.isDown();
        return module.isJumpKeyDown();
    }

    public String getTowerMovement() {
        return towerMovement.getValue();
    }
}
