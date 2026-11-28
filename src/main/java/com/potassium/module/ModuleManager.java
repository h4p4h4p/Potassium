package com.potassium.module;

import java.util.ArrayList;
import java.util.List;

public class ModuleManager {

    private final List<Module> modules = new ArrayList<>();

    public ModuleManager() {
        modules.add(new Speed());
        modules.add(new Timer());
        modules.add(new Disabler());
        modules.add(new AutoSprint());
        modules.add(new MoreKB());
        modules.add(new NoSlowdown());
        modules.add(new Scaffold());
        modules.add(new FastBreak());
        modules.add(new Blink());
        modules.add(new FakeLag());
        modules.add(new AimAssist());
        modules.add(new HitSelect());
        modules.add(new KillAura());
        modules.add(new TargetStrafe());
        modules.add(new LeftClicker());
        modules.add(new AntiBot());
        modules.add(new Criticals());
        modules.add(new RightClicker());
        modules.add(new FastPlace());
        modules.add(new ChestStealer());
        modules.add(new InvManager());
        modules.add(new AutoArmor());
        modules.add(new AntiKnockback());
        modules.add(new Backtrack());
        modules.add(new Esp());
        modules.add(new Tracers());
        modules.add(new TargetHud());
        modules.add(new StorageESP());
        modules.add(new Scoreboard());
        modules.add(new Fullbright());
        modules.add(new NoFOV());
        modules.add(new FreeLook());
        modules.add(new Freecam());
        modules.add(new ViewModel());
        modules.add(new Rotations());
        modules.add(new HUD());
        modules.add(new MotionBlur());
        modules.add(new ClientSpoofer());
        modules.add(new Flight());
        modules.add(new AntiVoid());
        modules.add(new NoFall());
        modules.add(new LocalConfigs());
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<Module> getModulesInCategory(Category category) {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.getCategory() == category) result.add(module);
        }
        return result;
    }

    public Module getModule(String name) {
        for (Module module : modules) {
            if (module.getName().equalsIgnoreCase(name)) return module;
        }
        return null;
    }

    public List<Module> getEnabledModules() {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.isEnabled()) result.add(module);
        }
        return result;
    }
}