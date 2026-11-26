package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.hud.TargetHudElement;
import com.potassium.util.Friends;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Locale;

/**
 * A compact readout for the entity currently being fought.
 *
 * <p>KillAura's target is preferred because a silent aura can be aimed at someone who is not
 * under the crosshair at all. Without an active aura the entity under the crosshair is used,
 * and it stays on screen for a short grace period after the crosshair leaves so the panel does
 * not flicker while tracking a moving player.</p>
 *
 * <p>The panel's position lives in the HUD editor element, so it is dragged in chat and
 * persisted like the other HUD rows. The module still draws the panel itself, which keeps
 * TargetHUD working with the HUD module switched off.</p>
 */
public class TargetHud extends Module {

    public static final float WIDTH = 150f;
    public static final float HEIGHT = 46f;

    private static final int LINGER_TICKS = 40;
    private static TargetHud instance;

    private final Minecraft mc = Minecraft.getMinecraft();
    private EntityLivingBase lastTarget;
    private int lastSeenTick;

    public TargetHud() {
        super("TargetHUD", Category.RENDER);
        settings.add(new com.potassium.settings.BooleanSetting("Health Bar", true));
        settings.add(new com.potassium.settings.BooleanSetting("Armor", true));
        settings.add(new com.potassium.settings.BooleanSetting("Distance", true));
        settings.add(new com.potassium.settings.BooleanSetting("Background", true));
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static TargetHud instance() {
        return instance;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            lastTarget = null;
            return;
        }
        EntityLivingBase target = findTarget();
        if (target != null) {
            lastTarget = target;
            lastSeenTick = mc.thePlayer.ticksExisted;
        } else if (lastTarget != null
                && mc.thePlayer.ticksExisted - lastSeenTick > LINGER_TICKS) {
            lastTarget = null;
        }
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !isEnabled()) return;
        if (currentTarget() == null) return;

        float x = 0f;
        float y = 0f;
        boolean positioned = false;
        if (Potassium.INSTANCE != null && Potassium.INSTANCE.hudManager != null) {
            TargetHudElement element = Potassium.INSTANCE.hudManager.getTargetHud();
            if (element != null) {
                x = element.getX();
                y = element.getY();
                positioned = true;
            }
        }
        if (!positioned) {
            net.minecraft.client.gui.ScaledResolution sr =
                    new net.minecraft.client.gui.ScaledResolution(mc);
            x = sr.getScaledWidth() / 2f - WIDTH / 2f;
            y = sr.getScaledHeight() - 96f;
        }
        renderAt(x, y);
    }

    /**
     * Draws the panel at an arbitrary point. The HUD editor calls this directly while chat is
     * open, including with no target so the row still has a visible, draggable body.
     */
    public void renderAt(float x, float y) {
        EntityLivingBase target = currentTarget();
        if (target == null || mc.thePlayer == null) {
            if (setting("Background")) {
                RenderUtil.drawRect(x, y, WIDTH, HEIGHT, 0xB0121418);
                RenderUtil.drawRect(x, y, 2f, HEIGHT, Theme.ACCENT);
            }
            RenderUtil.drawGuiStringShadow("TargetHUD", x + 8f, y + 7f, Theme.TEXT_GRAY);
            RenderUtil.drawGuiStringShadow("No target", x + 8f, y + 22f, Theme.TEXT_FAINT);
            return;
        }

        if (setting("Background")) {
            RenderUtil.drawRect(x, y, WIDTH, HEIGHT, 0xB0121418);
            RenderUtil.drawRect(x, y, 2f, HEIGHT, Theme.ACCENT);
        }

        RenderUtil.drawGuiStringShadow(target.getName(), x + 8f, y + 7f, 0xFFFFFFFF);

        float health = target.getHealth();
        float absorption = target.getAbsorptionAmount();
        float shown = health + absorption;
        String healthText = String.format(Locale.ROOT, "%.1f", shown);
        RenderUtil.drawGuiStringShadow(healthText,
                x + WIDTH - 8f - RenderUtil.getGuiTextWidth(healthText), y + 7f,
                healthColor(health, target.getMaxHealth()));

        if (setting("Health Bar")) {
            float barX = x + 8f;
            float barY = y + 22f;
            float barW = WIDTH - 16f;
            float barH = 4f;
            RenderUtil.drawRect(barX, barY, barW, barH, 0x66000000);
            float ratio = target.getMaxHealth() <= 0f
                    ? 0f : Math.min(1f, shown / target.getMaxHealth());
            RenderUtil.drawRect(barX, barY, barW * ratio, barH,
                    healthColor(health, target.getMaxHealth()));
        }

        float lineY = y + 32f;
        if (setting("Armor")) {
            RenderUtil.drawGuiStringShadow("Armor " + target.getTotalArmorValue(),
                    x + 8f, lineY, Theme.TEXT_GRAY);
        }
        if (setting("Distance")) {
            String distance = String.format(Locale.ROOT, "%.1f m",
                    mc.thePlayer.getDistanceToEntity(target));
            RenderUtil.drawGuiStringShadow(distance,
                    x + WIDTH - 8f - RenderUtil.getGuiTextWidth(distance), lineY,
                    Theme.TEXT_GRAY);
        }

        if (target instanceof net.minecraft.entity.player.EntityPlayer
                && Friends.isFriend((net.minecraft.entity.player.EntityPlayer) target)) {
            RenderUtil.drawGuiStringShadow("FRIEND",
                    x + WIDTH - 8f - RenderUtil.getGuiTextWidth("FRIEND"), y + HEIGHT - 12f,
                    0xFF55FF55);
        }
    }

    private boolean setting(String name) {
        for (com.potassium.settings.Setting setting : settings) {
            if (!setting.getName().equals(name)) continue;
            return ((com.potassium.settings.BooleanSetting) setting).getValue();
        }
        return false;
    }

    private EntityLivingBase currentTarget() {
        if (mc.thePlayer == null) return null;
        EntityLivingBase target = findTarget();
        if (target != null) return target;
        if (lastTarget != null && lastTarget.isEntityAlive()
                && mc.thePlayer.ticksExisted - lastSeenTick <= LINGER_TICKS) {
            return lastTarget;
        }
        return null;
    }

    private EntityLivingBase findTarget() {
        KillAura aura = KillAura.instance();
        if (aura != null && aura.isEnabled() && aura.getTarget() != null
                && aura.getTarget().isEntityAlive()) {
            return aura.getTarget();
        }
        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null
                && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && over.entityHit instanceof EntityLivingBase) {
            return (EntityLivingBase) over.entityHit;
        }
        return null;
    }

    private int healthColor(float health, float maxHealth) {
        float ratio = maxHealth <= 0f ? 0f : Math.max(0f, Math.min(1f, health / maxHealth));
        int red = (int) (255f * (1f - ratio) + 60f * ratio);
        int green = (int) (60f * (1f - ratio) + 255f * ratio);
        return 0xFF000000 | (red << 16) | (green << 8) | 0x30;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        lastTarget = null;
    }
}
