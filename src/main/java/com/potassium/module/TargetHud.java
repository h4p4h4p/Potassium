package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.hud.TargetHudElement;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.Friends;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
 * TargetHUD working with the HUD module switched off. Size scales the whole panel and its
 * editor hitbox together.</p>
 */
public class TargetHud extends Module {

    public static final float WIDTH = 150f;
    public static final float BASE_HEIGHT = 46f;

    private static final int LINGER_TICKS = 40;
    private static final int COMBO_TIMEOUT = 60;
    private static final float ROW = 12f;
    private static TargetHud instance;

    private final BooleanSetting healthBarSetting = new BooleanSetting("Health Bar", true);
    private final BooleanSetting armorSetting = new BooleanSetting("Armor", true);
    private final BooleanSetting distanceSetting = new BooleanSetting("Distance", true);
    private final BooleanSetting backgroundSetting = new BooleanSetting("Background", true);
    private final BooleanSetting pingSetting = new BooleanSetting("Ping", true);
    private final BooleanSetting comboSetting = new BooleanSetting("Combo", true);
    private final BooleanSetting heldItemSetting = new BooleanSetting("Held Item", true);
    private final BooleanSetting potionsSetting = new BooleanSetting("Potions", true);
    private final SliderSetting sizeSetting = new SliderSetting("Size", 1.0, 0.5, 2.0, 0.05);

    private final Minecraft mc = Minecraft.getMinecraft();
    private EntityLivingBase lastTarget;
    private int lastSeenTick;
    private int combo;
    private int lastHitTick;
    /** Health + absorption from the previous tick, so incoming damage breaks the combo. */
    private float lastHealth = -1.0F;

    public TargetHud() {
        super("TargetHUD", Category.RENDER);
        settings.add(healthBarSetting);
        settings.add(armorSetting);
        settings.add(distanceSetting);
        settings.add(pingSetting);
        settings.add(comboSetting);
        settings.add(heldItemSetting);
        settings.add(potionsSetting);
        settings.add(backgroundSetting);
        settings.add(sizeSetting);
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static TargetHud instance() {
        return instance;
    }

    public float getScale() {
        return (float) sizeSetting.getValue();
    }

    public float getPanelHeight() {
        float height = BASE_HEIGHT;
        if (pingSetting.getValue() || comboSetting.getValue()) height += ROW;
        if (heldItemSetting.getValue()) height += ROW;
        if (potionsSetting.getValue()) height += ROW;
        return height;
    }

    public float getScaledWidth() {
        return WIDTH * getScale();
    }

    public float getScaledHeight() {
        return getPanelHeight() * getScale();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            lastTarget = null;
            combo = 0;
            lastHealth = -1.0F;
            return;
        }

        // Taking a hit ends the combo even if the target has not changed. Absorption is
        // included so a hit soaked by it still resets the counter.
        float health = mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount();
        if (lastHealth >= 0.0F && health < lastHealth - 1.0E-4F) {
            combo = 0;
        }
        lastHealth = health;

        EntityLivingBase target = findTarget();
        if (target != null) {
            if (target != lastTarget) combo = 0;
            lastTarget = target;
            lastSeenTick = mc.thePlayer.ticksExisted;
        } else if (lastTarget != null
                && mc.thePlayer.ticksExisted - lastSeenTick > LINGER_TICKS) {
            lastTarget = null;
            combo = 0;
        }
        if (lastTarget != null && mc.thePlayer.ticksExisted - lastHitTick > COMBO_TIMEOUT) {
            combo = 0;
        }
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (event.entityPlayer != mc.thePlayer) return;
        if (!(event.target instanceof EntityLivingBase)) return;
        if (event.target != currentTarget()) return;

        int tick = mc.thePlayer.ticksExisted;
        if (tick - lastHitTick > COMBO_TIMEOUT) combo = 0;
        combo++;
        lastHitTick = tick;
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
            x = sr.getScaledWidth() / 2f - getScaledWidth() / 2f;
            y = sr.getScaledHeight() - 96f;
        }
        renderAt(x, y);
    }

    /**
     * Draws the panel at an arbitrary point. The HUD editor calls this directly while chat is
     * open, including with no target so the row still has a visible, draggable body.
     */
    public void renderAt(float x, float y) {
        float scale = getScale();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
        drawPanel(currentTarget());
        GlStateManager.popMatrix();
    }

    private void drawPanel(EntityLivingBase target) {
        float height = getPanelHeight();
        if (backgroundSetting.getValue()) {
            RenderUtil.drawRect(0f, 0f, WIDTH, height, 0xB0121418);
            RenderUtil.drawRect(0f, 0f, 2f, height, Theme.ACCENT);
        }

        if (target == null || mc.thePlayer == null) {
            RenderUtil.drawGuiStringShadow("TargetHUD", 8f, 7f, Theme.TEXT_GRAY);
            RenderUtil.drawGuiStringShadow("No target", 8f, 22f, Theme.TEXT_FAINT);
            RenderUtil.drawGuiStringShadow("Size " + String.format(Locale.ROOT, "%.2f", getScale()),
                    8f, 37f, Theme.TEXT_FAINT);
            return;
        }

        boolean friend = target instanceof EntityPlayer && Friends.isFriend((EntityPlayer) target);
        RenderUtil.drawGuiStringShadow(target.getName(), 8f, 7f,
                friend ? 0xFF55FF55 : 0xFFFFFFFF);

        float health = target.getHealth();
        float absorption = target.getAbsorptionAmount();
        float shown = health + absorption;
        String healthText = String.format(Locale.ROOT, "%.1f", shown);
        RenderUtil.drawGuiStringShadow(healthText,
                WIDTH - 8f - RenderUtil.getGuiTextWidth(healthText), 7f,
                healthColor(health, target.getMaxHealth()));

        if (healthBarSetting.getValue()) {
            float barY = 22f;
            float barW = WIDTH - 16f;
            float barH = 4f;
            RenderUtil.drawRect(8f, barY, barW, barH, 0x66000000);
            float ratio = target.getMaxHealth() <= 0f
                    ? 0f : Math.min(1f, shown / target.getMaxHealth());
            RenderUtil.drawRect(8f, barY, barW * ratio, barH,
                    healthColor(health, target.getMaxHealth()));
        }

        float lineY = 32f;
        if (armorSetting.getValue()) {
            RenderUtil.drawGuiStringShadow("Armor " + target.getTotalArmorValue(),
                    8f, lineY, Theme.TEXT_GRAY);
        }
        if (distanceSetting.getValue()) {
            String distance = String.format(Locale.ROOT, "%.1f m",
                    mc.thePlayer.getDistanceToEntity(target));
            RenderUtil.drawGuiStringShadow(distance,
                    WIDTH - 8f - RenderUtil.getGuiTextWidth(distance), lineY, Theme.TEXT_GRAY);
        }

        if (pingSetting.getValue() || comboSetting.getValue()) {
            lineY += ROW;
            if (pingSetting.getValue()) {
                int ping = ping(target);
                String pingText = ping < 0 ? "Ping --" : "Ping " + ping;
                RenderUtil.drawGuiStringShadow(pingText, 8f, lineY, Theme.TEXT_GRAY);
            }
            if (comboSetting.getValue()) {
                String comboText = "Combo " + combo;
                RenderUtil.drawGuiStringShadow(comboText,
                        WIDTH - 8f - RenderUtil.getGuiTextWidth(comboText), lineY,
                        combo > 0 ? Theme.ACCENT : Theme.TEXT_GRAY);
            }
        }

        if (heldItemSetting.getValue()) {
            lineY += ROW;
            ItemStack held = target.getHeldItem();
            String heldText = held == null ? "Held Fists" : "Held " + held.getDisplayName();
            RenderUtil.drawGuiStringShadow(fit(heldText, WIDTH - 16f), 8f, lineY,
                    Theme.TEXT_GRAY);
        }

        if (potionsSetting.getValue()) {
            lineY += ROW;
            String potions = potionSummary(target);
            RenderUtil.drawGuiStringShadow(fit(potions, WIDTH - 16f), 8f, lineY,
                    Theme.TEXT_GRAY);
        }
    }

    private int ping(EntityLivingBase target) {
        if (!(target instanceof EntityPlayer)) return -1;
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null) return -1;
        NetworkPlayerInfo info = handler.getPlayerInfo(((EntityPlayer) target).getUniqueID());
        return info == null ? -1 : info.getResponseTime();
    }

    private String potionSummary(EntityLivingBase target) {
        Collection<PotionEffect> effects = target.getActivePotionEffects();
        if (effects == null || effects.isEmpty()) return "No effects";

        List<String> names = new ArrayList<>();
        for (PotionEffect effect : effects) {
            if (names.size() >= 3) break;
            Potion potion = Potion.potionTypes[effect.getPotionID()];
            String name = potion == null ? "Effect"
                    : StatCollector.translateToLocal(effect.getEffectName());
            int amplifier = effect.getAmplifier();
            names.add(amplifier > 0 ? name + " " + (amplifier + 1) : name);
        }
        String joined = String.join(", ", names);
        int extra = effects.size() - names.size();
        return extra > 0 ? joined + " +" + extra : joined;
    }

    private String fit(String text, float width) {
        if (RenderUtil.getGuiTextWidth(text) <= width) return text;
        String ellipsis = "...";
        while (text.length() > 0
                && RenderUtil.getGuiTextWidth(text + ellipsis) > width) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
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
    public String getTag() {
        return "x" + String.format(Locale.ROOT, "%.2f", getScale());
    }

    @Override
    public void onEnable() {
        lastHealth = -1.0F;
    }

    @Override
    public void onDisable() {
        lastTarget = null;
        combo = 0;
        lastHealth = -1.0F;
    }
}
