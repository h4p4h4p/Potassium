package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SelectSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.settings.StringSetting;

import java.util.List;

public class HUD extends Module {

    public static final String PANEL_MODE_PANEL = "Panel";
    public static final String PANEL_MODE_REGULAR = "Regular";

    private final BooleanSetting watermarkSetting = new BooleanSetting("Watermark", true);
    private final StringSetting watermarkTextSetting =
            new StringSetting("Watermark Text", "").withPlaceholder("default");
    private final BooleanSetting moduleListSetting = new BooleanSetting("Module List", true);
    private final BooleanSetting hideTagsSetting = new BooleanSetting("Hide Tags", false);
    // Offers only enabled modules: a disabled module is already absent from the module list, so
    // hiding it is meaningless until it is switched on. Config values are still validated against
    // every module name, so a hidden module keeps its entry while it is off.
    private final SelectSetting hiddenModulesSetting =
            new SelectSetting("Hidden Modules", new String[0]) {
                @Override
                public String[] getOptions() {
                    return enabledModuleNames();
                }

                @Override
                protected boolean isValidOption(String option) {
                    return isModuleName(option);
                }
            };
    private final BooleanSetting keybindViewerSetting = new BooleanSetting("Keybind Viewer", false);
    private final BooleanSetting fpsCounterSetting = new BooleanSetting("FPS Counter", false);
    private final ModeSetting panelModeSetting = new ModeSetting("Background",
            new String[]{PANEL_MODE_PANEL, PANEL_MODE_REGULAR}, 0);
    private final SliderSetting bgDimSetting = new SliderSetting("BG Dim", 60, 0, 100, 5);

    public HUD() {
        super("HUD", Category.CLIENT);
        settings.add(watermarkSetting);
        settings.add(watermarkTextSetting);
        settings.add(moduleListSetting);
        settings.add(hideTagsSetting);
        settings.add(hiddenModulesSetting);
        settings.add(keybindViewerSetting);
        settings.add(fpsCounterSetting);
        settings.add(panelModeSetting);
        settings.add(bgDimSetting);
    }

    public boolean shouldShowWatermark() {
        return watermarkSetting.getValue();
    }

    /** Custom watermark text; empty means keep the default name and version line. */
    public String getWatermarkText() {
        String text = watermarkTextSetting.getValue();
        return text == null ? "" : text.trim();
    }

    public boolean shouldShowModuleList() {
        return moduleListSetting.getValue();
    }

    /** Drops the tag suffix from every module list row, leaving just the module names. */
    public boolean shouldHideTags() {
        return hideTagsSetting.getValue();
    }

    /** True for modules the user removed from the module list with the hider. */
    public boolean isModuleHidden(String name) {
        return hiddenModulesSetting.isSelected(name);
    }

    private static String[] enabledModuleNames() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) {
            return new String[0];
        }
        List<Module> enabled = Potassium.INSTANCE.moduleManager.getEnabledModules();
        String[] names = new String[enabled.size()];
        for (int i = 0; i < names.length; i++) names[i] = enabled.get(i).getName();
        return names;
    }

    private static boolean isModuleName(String name) {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return false;
        for (Module module : Potassium.INSTANCE.moduleManager.getModules()) {
            if (module.getName().equals(name)) return true;
        }
        return false;
    }

    /** The keybind list that used to be its own module. */
    public boolean shouldShowKeybinds() {
        return keybindViewerSetting.getValue();
    }

    public boolean shouldShowFps() {
        return fpsCounterSetting.getValue();
    }

    /**
     * Panel draws the soft shadow plate behind each HUD element, the default. Regular draws a
     * flat plate only behind the text itself, with none of that shadow's padding or fade.
     */
    public boolean usesShadowPanels() {
        return !PANEL_MODE_REGULAR.equals(panelModeSetting.getValue());
    }

    public int getBgDimAlpha() {
        return (int) (bgDimSetting.getValue() / 100.0 * 255);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == watermarkTextSetting) return watermarkSetting.getValue();
        if (setting == hideTagsSetting || setting == hiddenModulesSetting) {
            return moduleListSetting.getValue();
        }
        return super.isSettingVisible(setting);
    }

    @Override
    public boolean canBind() {
        return false;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}