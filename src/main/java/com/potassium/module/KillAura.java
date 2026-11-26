package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.RangeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.BlinkBlockQueue;
import com.potassium.util.ClickState;
import com.potassium.util.Friends;
import com.potassium.util.RotationSmoother;
import com.potassium.util.RotationUtil;
import com.potassium.util.SilentLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MouseHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Timer;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects visible targets in range, turns toward them and attacks them with LeftClicker-style
 * CPS pacing.
 *
 * Up to Targets entities are kept in the cycle; each successful hit moves the aim to the next
 * one, so several targets are fought at the configured CPS. Sword blocking can be layered
 * between hits through the Blocking settings group.
 */
public class KillAura extends Module {

    private static KillAura instance;

    public static final String ROTATION_LOCK_VIEW = "Lock View";
    public static final String ROTATION_SILENT = "Silent";

    public static final String PRIORITY_SMART = "Smart";
    public static final String PRIORITY_DAMAGE = "Damage";
    public static final String PRIORITY_HEALTH = "Health";
    public static final String PRIORITY_DISTANCE = "Distance";

    public static final String MOVE_FIX_NONE = "None";
    public static final String MOVE_FIX_SILENT = "Silent";
    public static final String MOVE_FIX_STRICT = "Strict";

    public static final String BLOCK_PACKET = "Packet";
    public static final String BLOCK_VANILLA = "Vanilla";
    public static final String BLOCK_FAKE = "Fake";
    public static final String BLOCK_BLINK = "Blink";

    private static final String ROTATION_SMOOTHING_GROUP = "Rotation Smoothing";
    private static final String RANGE_GROUP = "Range";
    private static final String TARGETS_GROUP = "Targets";
    private static final String CONDITIONS_GROUP = "Conditions";
    private static final String BLOCKING_GROUP = "Blocking";
    /** A Blink block is held this many ticks, then released for this many ticks. */
    private static final int BLINK_BLOCK_TICKS = 4;
    private static final int BLINK_GAP_TICKS = 2;
    /** How long the view may take easing back to the camera, in ticks. */
    private static final int MAX_RETURN_TICKS = 60;

    // Silent: the server receives the aim while the camera stays under the player's control.
    private final ModeSetting rotationSetting = new ModeSetting("Rotations",
            new String[]{ROTATION_LOCK_VIEW, ROTATION_SILENT}, 1);
    private final ModeSetting prioritySetting = new ModeSetting("Priority",
            new String[]{PRIORITY_SMART, PRIORITY_DAMAGE, PRIORITY_HEALTH, PRIORITY_DISTANCE}, 0);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final SliderSetting targetsSetting = new SliderSetting("Targets", 1, 1, 4, 1);
    private final SliderSetting reachSetting = new SliderSetting("Reach", 3.0, 1.0, 4.5, 0.1);
    private final SliderSetting fovSetting = new SliderSetting("FOV", 180, 1, 360, 1);
    private final RangeSetting cpsSetting = new RangeSetting("CPS", 12, 12, 1, 20, 1);
    private final ModeSetting smoothingSetting = new ModeSetting("Smoothing",
            new String[]{RotationUtil.SMOOTHING_LINEAR, RotationUtil.SMOOTHING_EXPONENTIAL,
                    RotationUtil.SMOOTHING_LEGIT}, 0);
    private final RangeSetting horizontalSpeedSetting =
            new RangeSetting("Horizontal Speed", 180, 180, 0, 180, 1);
    private final RangeSetting verticalSpeedSetting =
            new RangeSetting("Vertical Speed", 180, 180, 0, 180, 1);
    /** OpenAdjust's Organic parameters, used when Smoothing is Legit. */
    private final SliderSetting organicSpeedSetting =
            new SliderSetting("Max Angle", 60, 10, 180, 1);
    private final SliderSetting driftSetting =
            new SliderSetting("Drift Intensity", 1.2, 0.5, 2.0, 0.1);
    private final SliderSetting jitterSetting =
            new SliderSetting("Jitter Intensity", 0.12, 0.0, 0.3, 0.01);
    private final BooleanSetting playersSetting = new BooleanSetting("Players", true);
    private final BooleanSetting passiveSetting = new BooleanSetting("Passive", false);
    private final BooleanSetting hostileSetting = new BooleanSetting("Hostile", false);
    private final BooleanSetting teammatesSetting = new BooleanSetting("Teammates", false);
    private final BooleanSetting invisiblesSetting = new BooleanSetting("Invisibles", true);
    private final BooleanSetting wallCheckSetting = new BooleanSetting("Wall Check", true);
    private final BooleanSetting weaponOnlySetting = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting blockingEnabledSetting = new BooleanSetting("Blocking", false);
    private final ModeSetting blockingModeSetting = new ModeSetting("Block Mode",
            new String[]{BLOCK_PACKET, BLOCK_VANILLA, BLOCK_FAKE, BLOCK_BLINK}, 0);
    private final SliderSetting blockRangeSetting = new SliderSetting("Block Range", 6.0, 0.0, 8.0, 0.1);
    private final BooleanSetting onMouseDownSetting = new BooleanSetting("On Mouse Down", true);
    private final BooleanSetting stopOnBlockSetting = new BooleanSetting("Stop on Block", false);
    private final BooleanSetting holdAttackSetting = new BooleanSetting("Hold Attack", false);
    private final ModeSetting movementFixSetting = new ModeSetting("Movement Fix",
            new String[]{MOVE_FIX_NONE, MOVE_FIX_SILENT, MOVE_FIX_STRICT}, 1);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final List<EntityLivingBase> targets = new ArrayList<>();
    private EntityLivingBase target;
    private int targetIndex;
    private double clickBudget = 1.0D;
    private final RotationSmoother aim = new RotationSmoother();
    private boolean aimInitialised;
    /** Minecraft.timer is private; cached for the Lock View target interpolation. */
    private Timer clientTimer;
    /** The mouse helper that replaces vanilla's while Lock View holds a target. */
    private MouseHelper savedMouseHelper;
    private LockViewMouseHelper lockViewMouse;
    /** True while a silent aim is being eased back to the camera after the last target. */
    private boolean returning;
    private int returnTicks;
    private float sentYaw;
    private float sentPitch;
    private boolean sentInitialised;
    /** State of the sword block the aura started, so it can release exactly that block. */
    private boolean autoBlockActive;
    private String autoBlockMode;
    private ItemStack autoBlockStack;
    /** True while the use key is held down only because On Mouse Down is off. */
    private boolean spoofingUseKey;
    private int spoofedUseKeyCode;
    /** Client tick of the last C08, so one tick cannot send two block interactions. */
    private int lastBlockPacketTick = -1;
    /** Tick on which a block released for an attack may be started again. */
    private int restartAutoBlockTick = -1;
    /** Blink blocking: the server block is pulsed while C03 packets are held. */
    private boolean blinkBlockActive;
    private int blinkBlockStartedTick;
    private int blinkNextBlockTick;

    public KillAura() {
        super("KillAura", Category.COMBAT);
        instance = this;
        smoothingSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        horizontalSpeedSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        verticalSpeedSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        organicSpeedSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        driftSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        jitterSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        rangeSetting.setGroup(RANGE_GROUP);
        reachSetting.setGroup(RANGE_GROUP);
        fovSetting.setGroup(RANGE_GROUP);
        playersSetting.setGroup(TARGETS_GROUP);
        passiveSetting.setGroup(TARGETS_GROUP);
        hostileSetting.setGroup(TARGETS_GROUP);
        teammatesSetting.setGroup(TARGETS_GROUP);
        invisiblesSetting.setGroup(TARGETS_GROUP);
        wallCheckSetting.setGroup(CONDITIONS_GROUP);
        weaponOnlySetting.setGroup(CONDITIONS_GROUP);
        stopOnBlockSetting.setGroup(CONDITIONS_GROUP);
        holdAttackSetting.setGroup(CONDITIONS_GROUP);
        blockingEnabledSetting.setGroup(BLOCKING_GROUP);
        blockingModeSetting.setGroup(BLOCKING_GROUP);
        blockRangeSetting.setGroup(BLOCKING_GROUP);
        onMouseDownSetting.setGroup(BLOCKING_GROUP);
        settings.add(rotationSetting);
        settings.add(prioritySetting);
        settings.add(targetsSetting);
        settings.add(rangeSetting);
        settings.add(reachSetting);
        settings.add(fovSetting);
        settings.add(playersSetting);
        settings.add(passiveSetting);
        settings.add(hostileSetting);
        settings.add(teammatesSetting);
        settings.add(invisiblesSetting);
        settings.add(cpsSetting);
        settings.add(smoothingSetting);
        settings.add(horizontalSpeedSetting);
        settings.add(verticalSpeedSetting);
        settings.add(organicSpeedSetting);
        settings.add(driftSetting);
        settings.add(jitterSetting);
        settings.add(wallCheckSetting);
        settings.add(weaponOnlySetting);
        settings.add(stopOnBlockSetting);
        settings.add(holdAttackSetting);
        settings.add(blockingEnabledSetting);
        settings.add(blockingModeSetting);
        settings.add(blockRangeSetting);
        settings.add(onMouseDownSetting);
        settings.add(movementFixSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        // A silent rotation the server is still holding is unwound even with the module off,
        // so turning it off eases the view back instead of snapping it in one packet.
        if (!isEnabled()) {
            restoreLockViewMouse();
            stopBlinkBlock();
            tickReturn();
            return;
        }

        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null
                || mc.thePlayer.isDead) {
            reset();
            return;
        }

        if (weaponOnlySetting.getValue() && !holdingWeapon()) {
            reset();
            return;
        }

        // A Blink block must never outlive the setting that owns it, even when there is no
        // attack target and the normal maintain path is skipped below.
        if (!blockingEnabled() || !BLOCK_BLINK.equals(blockingMode())) stopBlinkBlock();

        List<TargetCandidate> candidates = findTargets();
        if (candidates.isEmpty()) {
            targets.clear();
            target = null;
            targetIndex = 0;
            clickBudget = 1.0D;
            restoreLockViewMouse();
            // Block Range can cover a threat before it reaches attack Range. Keep the block
            // up even with no attack target, so a player entering the aura is already guarded.
            if (blockingEnabled() && blockThreat() != null) {
                maintainAutoBlock();
            } else {
                stopAutoBlock();
                stopBlinkBlock();
            }
            // The server's rotation goes back to the camera on its own curve; the next target
            // then picks up from wherever the view really is.
            beginReturn();
            tickReturn();
            return;
        }

        returning = false;
        updateTargets(candidates);
        target = targets.get(targetIndex);
        syncAutoBlockMode();
        syncLockViewMouse();

        // Packet mode blocks before the hit as well as after it: sending C08 first means the
        // server is already blocking when the attack lands, not one packet later.
        if (isPacketMode(blockingMode())) maintainAutoBlock();

        // Attacking here puts the attack packet before this tick's movement packet, which is
        // the order a real click produces. Sent at the end of the tick instead, every hit
        // arrived after the flying packet and read as a post-movement attack.
        EntityLivingBase current = target;
        boolean attackNow = !stopOnBlock() && !usingOtherItem() && canAttack(current)
                && (!holdAttackSetting.getValue() || attackKeyHeld()) && clickDue();
        if (attackNow && HitSelect.suppressing(current)) {
            HitSelect.fakeSwing();
            attackNow = false;
        }
        if (attackNow) {
            HitSelect.consumeSecondHit(current);
            // Packet modes deliberately keep the server-side block through the hit; every
            // other mode releases it so the client attack matches the packet it sends.
            if (autoBlockActive && !isPacketMode(autoBlockMode)) {
                stopAutoBlock();
                // Keep the C07 release and the C08 restart out of the same tick. A block
                // interaction rate check treats a release plus a new use as two actions.
                restartAutoBlockTick = mc.thePlayer.ticksExisted + 1;
            }
            // Blink owns its own block state; release it before the attack packet so the
            // server does not see the hit thrown while a use-item flag is still active.
            if (blinkBlockActive) stopBlinkBlock();
            if (attack(current)) {
                HitSelect.noteHit(current);
                advanceTarget();
            }
        }
        maintainAutoBlock();

        // Lock View sets the camera at the same frame the mouse input is sampled; Silent keeps
        // its own aim for the packet. Either way the aim is chosen once for this tick.
        aimAt(current);
    }

    /**
     * Hold Attack gates every swing on the attack key, so the aura keeps aiming and blocking
     * but only hits while the player is holding the button themselves.
     */
    private boolean attackKeyHeld() {
        return mc.gameSettings != null && mc.gameSettings.keyBindAttack.isKeyDown();
    }

    /**
     * A hit only goes out when the rotation the server already holds really reaches the
     * target. Angles alone were not enough: a hit fired 10 degrees off the aim missed the
     * box the server traces, which is what an impossible-hit check reports.
     */
    private boolean canAttack(EntityLivingBase target) {
        if (!sentInitialised) return false;
        if (!ClickState.canAttack(mc.thePlayer.ticksExisted)) return false;
        return rayHits(target, sentYaw, sentPitch);
    }

    /** Sword right-click block; Stop on Block pauses attacks instead of releasing it. */
    private boolean isBlockingWithSword() {
        ItemStack held = mc.thePlayer.getHeldItem();
        return mc.thePlayer.isUsingItem() && held != null && held.getItem() instanceof ItemSword;
    }

    /**
     * The optional pause while the player holds a sword block. Auto-blocking manages its own
     * block, so Stop on Block only applies while the Blocking toggle is off.
     */
    private boolean stopOnBlock() {
        return stopOnBlockSetting.getValue() && !blockingEnabled() && isBlockingWithSword();
    }

    private boolean blockingEnabled() {
        return blockingEnabledSetting.getValue();
    }

    private String blockingMode() {
        String mode = blockingModeSetting.getValue();
        return mode == null ? BLOCK_PACKET : mode;
    }

    /**
     * Auto-blocking between attacks, after LiquidBounce and Rise.
     *
     * <p>Packet sends only C08/C07, so the server holds the block while the client keeps
     * attacking normally; nothing slows the camera or movement. Vanilla puts the sword in use,
     * releases it before each hit and starts it again on the next tick. Fake shows that
     * animation without telling the server, so it is only cosmetic. All of them only run while
     * a sword is held.</p>
     *
     * <p>With On Mouse Down on, this only assists a block the player is already holding;
     * with it off, it blocks whenever the aura has a target. The visible modes also hold the
     * use key down client-side so vanilla does not release the block on the next input pass.</p>
     */
    private void maintainAutoBlock() {
        String mode = blockingMode();
        if (!BLOCK_BLINK.equals(mode)) stopBlinkBlock();
        // Turning On Mouse Down back on must not leave the synthetic key held down, or the
        // module would keep blocking without the interact key.
        if (onMouseDownSetting.getValue()) releaseUseKey();

        if (BLOCK_BLINK.equals(mode)) {
            maintainBlinkBlock();
            return;
        }

        if (!blockingEnabled() || !autoBlockWanted()) {
            stopAutoBlock();
            return;
        }
        if (restartAutoBlockTick >= 0) {
            if (restartAutoBlockTick > mc.thePlayer.ticksExisted) return;
            restartAutoBlockTick = -1;
        }

        if (autoBlockActive && (!mode.equals(autoBlockMode)
                || autoBlockStack != mc.thePlayer.getHeldItem())) {
            stopAutoBlock();
        }
        if (!autoBlockActive) {
            startAutoBlock();
        } else if (!BLOCK_PACKET.equals(mode) && !mc.thePlayer.isUsingItem()) {
            // Vanilla drops item use if the use key goes up; reassert the visible block rather
            // than leaving autoBlockActive set with no client or server block.
            startAutoBlock();
        }
    }

    private boolean autoBlockWanted() {
        if (mc.thePlayer == null || mc.theWorld == null) return false;
        // On Mouse Down follows the interact key; without it the aura keeps blocking on its
        // own whenever a threat is in Block Range.
        if (onMouseDownSetting.getValue() && !mc.gameSettings.keyBindUseItem.isKeyDown()) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemSword)) return false;
        // Do not block over food, a potion or a bow the player is using.
        if (usingOtherItem()) return false;
        // Block Range is independent of attack Range: a threat can be close enough to guard
        // against before the aura is allowed to hit it.
        return blockThreat() != null;
    }

    /**
     * The nearest entity inside Block Range. The attack target is preferred when it is close
     * enough; otherwise a wider scan lets blocking start before the target enters Range.
     */
    private EntityLivingBase blockThreat() {
        if (mc.thePlayer == null || mc.theWorld == null) return null;
        double range = blockRangeSetting.getValue();
        if (range <= 0.0D) return null;
        if (target != null && canTarget(target) && hitDistance(target) <= range) return target;

        EntityLivingBase nearest = null;
        double nearestDistance = range;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) entity;
            if (!canTarget(living)) continue;

            double distance = hitDistance(living);
            if (distance > nearestDistance) continue;
            nearestDistance = distance;
            nearest = living;
        }
        return nearest;
    }

    private boolean isPacketMode(String mode) {
        return BLOCK_PACKET.equals(mode);
    }

    private void syncAutoBlockMode() {
        if (autoBlockActive && (!blockingEnabled() || !blockingMode().equals(autoBlockMode))) {
            stopAutoBlock();
        }
        if (blinkBlockActive && !BLOCK_BLINK.equals(blockingMode())) stopBlinkBlock();
    }

    /**
     * Blink blocking: the server is told to block for a few ticks, then released. C03 is held
     * for the whole pulse, so the server never sees the player moving while its use-item flag
     * is up. The release flushes the held movement after C07, which is what lets the player
     * attack and sprint away at full speed without a NoSlow movement packet.
     */
    private void maintainBlinkBlock() {
        if (mc.thePlayer == null || !blockingEnabled() || !autoBlockWanted()) {
            stopBlinkBlock();
            return;
        }

        int tick = mc.thePlayer.ticksExisted;
        if (blinkBlockActive) {
            if (tick - blinkBlockStartedTick >= BLINK_BLOCK_TICKS) stopBlinkBlock();
            return;
        }
        if (tick < blinkNextBlockTick || tick == lastBlockPacketTick) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemSword)) return;
        if (!BlinkBlockQueue.beginHold()) return;

        sendBlockPacket(held);
        blinkBlockActive = true;
        blinkBlockStartedTick = tick;
    }

    /** Ends the current Blink block, sends C07, then releases the held movement. */
    private void stopBlinkBlock() {
        if (!blinkBlockActive) return;
        blinkBlockActive = false;
        BlinkBlockQueue.stopHolding();
        sendReleasePacket();
        BlinkBlockQueue.flush();
        if (mc.thePlayer != null) {
            blinkNextBlockTick = mc.thePlayer.ticksExisted + BLINK_GAP_TICKS;
        }
    }

    private void startAutoBlock() {
        ItemStack held = mc.thePlayer.getHeldItem();
        String mode = blockingMode();
        if (held == null || !(held.getItem() instanceof ItemSword) || !blockingEnabled()) return;

        // The client-side use state gives Vanilla its animation and slowdown; Fake wants the
        // animation alone. Packet leaves the client untouched and only tells the server.
        if (!BLOCK_PACKET.equals(mode)) {
            mc.thePlayer.setItemInUse(held, held.getMaxItemUseDuration());
            holdUseKey();
        }
        if (!BLOCK_FAKE.equals(mode)) {
            sendBlockPacket(held);
        }

        autoBlockActive = true;
        autoBlockMode = mode;
        autoBlockStack = held;
        restartAutoBlockTick = -1;
    }

    private void stopAutoBlock() {
        if (!autoBlockActive) {
            restartAutoBlockTick = -1;
            releaseUseKey();
            return;
        }

        String mode = autoBlockMode;
        autoBlockActive = false;
        autoBlockMode = null;
        autoBlockStack = null;

        if (mc.thePlayer != null && !BLOCK_PACKET.equals(mode)) {
            ItemStack using = mc.thePlayer.getItemInUse();
            if (using != null && using.getItem() instanceof ItemSword) {
                mc.thePlayer.stopUsingItem();
            }
        }
        if (!BLOCK_FAKE.equals(mode)) sendReleasePacket();
        releaseUseKey();
        restartAutoBlockTick = -1;
    }

    /**
     * Keeps a visible auto-block alive when On Mouse Down is off. Vanilla releases an item as
     * soon as it is in use while the use key is up, sending a C07 that defeats the client-side
     * modes. Setting the key's held state, without adding a press to its pressTime, stops that
     * release without firing a normal right-click.
     */
    private void holdUseKey() {
        if (onMouseDownSetting.getValue()
                || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            return;
        }
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        if (keyCode == 0) return;
        if (spoofingUseKey && spoofedUseKeyCode != keyCode) releaseUseKey();
        KeyBinding.setKeyBindState(keyCode, true);
        spoofingUseKey = true;
        spoofedUseKeyCode = keyCode;
    }

    /**
     * The synthetic use key is held, not clicked. If vanilla's held-right-click path runs
     * anyway, cancellable right-click interactions stop it from sending a second C08.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // The integrated server fires this too; only cancel the local client's synthetic
        // right-click, never the server's processing of the C08 we sent on purpose.
        if (event.entityPlayer != mc.thePlayer) return;
        if (!autoBlockActive || !spoofingUseKey) return;
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR
                || event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            event.setCanceled(true);
        }
    }

    private void releaseUseKey() {
        if (!spoofingUseKey) return;
        spoofingUseKey = false;
        int keyCode = spoofedUseKeyCode;
        spoofedUseKeyCode = 0;
        if (keyCode != 0 && !isUseKeyPhysicallyDown(keyCode)) {
            KeyBinding.setKeyBindState(keyCode, false);
        }
    }

    private boolean isUseKeyPhysicallyDown(int keyCode) {
        if (keyCode < 0) {
            int button = keyCode + 100;
            return button >= 0 && Mouse.isButtonDown(button);
        }
        return keyCode > 0 && Keyboard.isKeyDown(keyCode);
    }

    private void sendBlockPacket(ItemStack stack) {
        if (mc.getNetHandler() == null || mc.thePlayer == null) return;
        int tick = mc.thePlayer.ticksExisted;
        if (tick == lastBlockPacketTick) return;
        lastBlockPacketTick = tick;
        mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(stack));
    }

    private void sendReleasePacket() {
        if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(
                    C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        }
    }

    /** Eating, drinking or drawing a bow; sword blocking is handled by Stop on Block. */
    private boolean usingOtherItem() {
        return mc.thePlayer.isUsingItem() && !isBlockingWithSword();
    }

    /**
     * Vanilla's hit test, run over the Reach slider: an eye trace against the box, grown by
     * the same 0.1 vanilla grows it. Range only decides which targets are considered.
     */
    private boolean rayHits(EntityLivingBase target, float yaw, float pitch) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRadians);
        Vec3 direction = new Vec3(-Math.sin(yawRadians) * cosPitch, -Math.sin(pitchRadians),
                Math.cos(yawRadians) * cosPitch);
        double reach = reachSetting.getValue();
        Vec3 end = eyes.addVector(direction.xCoord * reach,
                direction.yCoord * reach, direction.zCoord * reach);

        float border = target.getCollisionBorderSize();
        MovingObjectPosition hit = target.getEntityBoundingBox().expand(border, border, border)
                .calculateIntercept(eyes, end);
        if (hit == null) return false;

        // A wall between the eye and the box makes the hit impossible no matter how well
        // the angles line up, and vanilla's mouse-over drops the entity in that case too.
        MovingObjectPosition block = mc.theWorld.rayTraceBlocks(eyes, end, false, true, false);
        if (block == null) return true;
        return block.hitVec.squareDistanceTo(eyes) >= hit.hitVec.squareDistanceTo(eyes) - 1.0E-4D;
    }

    /** Records the rotation this tick's movement packet carried. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null) return;
        sentYaw = mc.thePlayer.rotationYaw;
        sentPitch = mc.thePlayer.rotationPitch;
        sentInitialised = true;
    }

    /** Minecraft.timer is private; accept both the MCP and SRG field names. */
    private float renderPartialTicks() {
        if (clientTimer == null) {
            clientTimer = ReflectionHelper.getPrivateValue(Minecraft.class, mc,
                    "timer", "field_71428_T");
        }
        return clientTimer == null ? 1.0F : clientTimer.renderPartialTicks;
    }

    private List<TargetCandidate> findTargets() {
        EntityPlayer player = mc.thePlayer;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;

        float currentYaw = player.rotationYaw;
        float currentPitch = player.rotationPitch;
        float fov = (float) fovSetting.getValue();

        double maxDistance = rangeSetting.getValue();
        List<TargetCandidate> candidates = new ArrayList<>();

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) entity;
            if (!canTarget(living)) continue;

            // Range is measured from the eyes to the closest point on the target's hitbox,
            // rather than to its centre; the centre made the reach depend on target height.
            double distance = hitDistance(living);
            if (distance > maxDistance) continue;

            double targetX = living.posX;
            double targetY = living.posY + living.getEyeHeight();
            double targetZ = living.posZ;
            double dx = targetX - eyeX;
            double dy = targetY - eyeY;
            double dz = targetZ - eyeZ;
            double horizontal = Math.sqrt(dx * dx + dz * dz);

            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
            float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(yaw - currentYaw));
            float pitchDifference = Math.abs(pitch - currentPitch);
            if (yawDifference > fov || pitchDifference > fov) continue;
            if (wallCheckSetting.getValue() && aimPoint(living) == null) continue;

            candidates.add(new TargetCandidate(living, scoreTarget(living, distance)));
        }

        candidates.sort((a, b) -> Double.compare(a.score, b.score));
        return candidates;
    }

    /** Whether a living entity passes the target filters, before range or FOV are checked. */
    private boolean canTarget(EntityLivingBase living) {
        if (mc.thePlayer == null || living == null || living == mc.thePlayer
                || living.isDead || !living.isEntityAlive()) {
            return false;
        }
        if (living.isInvisible() && !invisiblesSetting.getValue()) return false;

        if (living instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) living;
            if (player.isSpectator()) return false;
            if (!playersSetting.getValue()) return false;
            if (Friends.isFriend(player)) return false;
            if (!teammatesSetting.getValue() && mc.thePlayer.isOnSameTeam(player)) return false;
        } else {
            boolean hostile = living instanceof IMob || living instanceof EntityIronGolem;
            if (hostile ? !hostileSetting.getValue() : !passiveSetting.getValue()) return false;
        }
        return !AntiBot.isBot(living);
    }

    /** Target ordering: OpenAdjust's Smart/Damage/Health/Distance priority options. */
    private double scoreTarget(EntityLivingBase target, double distance) {
        String priority = prioritySetting.getValue();
        double distanceSq = distance * distance;
        if (PRIORITY_DISTANCE.equals(priority)) return distanceSq;

        double health = target.getHealth() + target.getAbsorptionAmount();
        if (PRIORITY_HEALTH.equals(priority)) return health * 100.0D;

        double damage = estimatedDamage(target);
        if (PRIORITY_DAMAGE.equals(priority)) {
            return health + (10000.0D - damage) * 1000.0D;
        }
        return health + (100.0D - damage) + distanceSq / 1000.0D;
    }

    /** A cheap damage estimate, following the base weapon and armour steps OpenAdjust uses. */
    private double estimatedDamage(EntityLivingBase target) {
        if (mc.thePlayer == null) return 0.0D;
        double damage = 1.0D;
        if (mc.thePlayer.getEntityAttribute(SharedMonsterAttributes.attackDamage) != null) {
            damage = mc.thePlayer.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                    .getAttributeValue();
        }
        ItemStack weapon = mc.thePlayer.getHeldItem();
        if (weapon != null) {
            for (AttributeModifier modifier : weapon.getAttributeModifiers()
                    .get("generic.attackDamage")) {
                damage += modifier.getAmount();
            }
        }
        damage *= (25.0D - Math.min(20, target.getTotalArmorValue())) / 25.0D;
        return Math.max(0.0D, damage);
    }

    /**
     * Keeps at most Targets entries in the cycle, preferring the ones already engaged so a
     * new best candidate cannot reset the rotation every tick. Empty slots are filled from
     * the score-ordered scan.
     */
    private void updateTargets(List<TargetCandidate> candidates) {
        int limit = targetsLimit();
        List<EntityLivingBase> next = new ArrayList<>(limit);
        if (limit == 1) {
            // With a single slot there is no cycle to keep, so priority always chooses the
            // current best instead of leaving the old target engaged forever.
            next.add(candidates.get(0).entity);
        } else {
            // The multi-target cycle keeps engaged targets so a hit can move to the next one;
            // priority order decides which new candidates fill the remaining slots.
            for (EntityLivingBase previous : targets) {
                if (next.size() >= limit) break;
                if (isCandidate(candidates, previous)) next.add(previous);
            }
            for (TargetCandidate candidate : candidates) {
                if (next.size() >= limit) break;
                if (!next.contains(candidate.entity)) next.add(candidate.entity);
            }
        }

        targets.clear();
        targets.addAll(next);
        if (targetIndex >= targets.size()) targetIndex = 0;
    }

    private boolean isCandidate(List<TargetCandidate> candidates, EntityLivingBase entity) {
        for (TargetCandidate candidate : candidates) {
            if (candidate.entity == entity) return true;
        }
        return false;
    }

    /** The next attack goes to the next target in the cycle. */
    private void advanceTarget() {
        if (targets.size() <= 1) return;
        targetIndex = (targetIndex + 1) % targets.size();
        target = targets.get(targetIndex);
    }

    private int targetsLimit() {
        return Math.max(1, Math.min(4, (int) Math.round(targetsSetting.getValue())));
    }

    /**
     * Chooses this tick's aim. Silent rotations keep a rotation of their own, so the target is
     * handed to the smoother and sampled here for the packet. Lock View rotations are the
     * camera: the target is applied at once, and the mouse helper keeps it locked every frame.
     */
    private void aimAt(EntityLivingBase target) {
        float[] rotations = rotationsTo(target);
        String smoothing = smoothingSetting.getValue();
        // A draw inside each range every tick, like LiquidBounce's speed ranges, so the turn
        // does not step at one flat rate the way a single number would.
        double yawStep = RotationUtil.speed(horizontalSpeedSetting.random());
        double pitchStep = RotationUtil.speed(verticalSpeedSetting.random());

        if (!isSilentRotations()) {
            // OpenAdjust's Lock on applies the whole target delta to the camera every input
            // sample, not a speed-limited turn. Do the same here; applying it at tick start as
            // well covers the first frame before the helper runs.
            aimInitialised = false;
            applyLockViewRotation(1.0F);
            return;
        }

        if (!aimInitialised) {
            // Start from the rotation the server already holds, so the first look is a turn
            // rather than a jump.
            aim.reset(sentInitialised ? sentYaw : mc.thePlayer.rotationYaw,
                    sentInitialised ? sentPitch : mc.thePlayer.rotationPitch);
            aimInitialised = true;
        }
        if (isLegitSmoothing()) {
            aim.setOrganic(organicSpeedSetting.getValue(), driftSetting.getValue(),
                    jitterSetting.getValue());
        }
        // One tick's turn, and the value this tick's packet samples below. A silent aim that is
        // still catching up when the packet is built is a look one tick behind the target.
        aim.tick(rotations[0], rotations[1], yawStep, pitchStep, smoothing);
        requestSilentAim();
    }

    /**
     * OpenAdjust's Lock on: replace the player's rotation with the target aim on every mouse
     * input sample. The angle is snapped to the sensitivity grid relative to the current
     * camera, and the previous rotation is moved with it so the renderer has no one-tick lag.
     */
    private boolean applyLockViewRotation(float partialTicks) {
        EntityPlayerSP player = mc.thePlayer;
        EntityLivingBase current = target;
        if (!isEnabled() || isSilentRotations() || player == null || current == null
                || mc.theWorld == null || mc.currentScreen != null
                || current.worldObj != mc.theWorld || !current.isEntityAlive()) {
            return false;
        }
        // Freecam has its own camera; never fight it for the player's rotation.
        if (mc.getRenderViewEntity() != player) return false;

        float[] rotations = rotationsTo(current, partialTicks);
        // Keep the delta on the short way around the yaw wrap; without this a target at
        // -179 seen from +179 becomes a nearly full extra turn instead of a two-degree one.
        float baseYaw = MathHelper.wrapAngleTo180_float(player.rotationYaw);
        float targetYaw = MathHelper.wrapAngleTo180_float(rotations[0]);
        float yawDelta = MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
        float[] snapped = RotationUtil.snapToMouse(baseYaw + yawDelta, rotations[1],
                baseYaw, player.rotationPitch);
        player.rotationYaw = MathHelper.wrapAngleTo180_float(snapped[0]);
        player.rotationPitch = snapped[1];
        player.prevRotationYaw = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
        return true;
    }

    /** Replaces vanilla's mouse helper while Lock View is actively holding a target. */
    private void syncLockViewMouse() {
        boolean wanted = isEnabled() && !isSilentRotations() && target != null
                && mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null
                && mc.getRenderViewEntity() == mc.thePlayer;
        if (!wanted) {
            restoreLockViewMouse();
            return;
        }
        installLockViewMouse();
    }

    private void installLockViewMouse() {
        if (lockViewMouse != null) return;
        MouseHelper current = mc.mouseHelper;
        if (current == null) return;
        savedMouseHelper = current;
        lockViewMouse = new LockViewMouseHelper(current);
        mc.mouseHelper = lockViewMouse;
    }

    private void restoreLockViewMouse() {
        if (lockViewMouse == null) return;
        if (mc.mouseHelper == lockViewMouse && savedMouseHelper != null) {
            mc.mouseHelper = savedMouseHelper;
        }
        lockViewMouse = null;
        savedMouseHelper = null;
    }

    /** A mouse helper that keeps the camera on the target while Lock View has one. */
    private final class LockViewMouseHelper extends MouseHelper {

        private final MouseHelper delegate;

        private LockViewMouseHelper(MouseHelper delegate) {
            this.delegate = delegate;
        }

        @Override
        public void mouseXYChange() {
            delegate.mouseXYChange();
            deltaX = delegate.deltaX;
            deltaY = delegate.deltaY;
            if (!applyLockViewRotation(renderPartialTicks())) {
                return;
            }
            // The camera was moved directly; letting vanilla apply the physical delta as well
            // would add the player's mouse movement back into the locked view.
            deltaX = 0;
            deltaY = 0;
        }

        @Override
        public void grabMouseCursor() {
            delegate.grabMouseCursor();
            deltaX = 0;
            deltaY = 0;
        }

        @Override
        public void ungrabMouseCursor() {
            delegate.ungrabMouseCursor();
        }
    }

    /**
     * Hands the aim to the packet layer as a whole mouse step from the last one sent. The aim
     * itself stays continuous; only what the server is told is rounded onto the grid a hand
     * moves on.
     */
    private void requestSilentAim() {
        float yaw = aim.yaw();
        float pitch = aim.pitch();
        float baseYaw = sentInitialised ? sentYaw : yaw;
        float basePitch = sentInitialised ? sentPitch : pitch;
        float[] snapped = RotationUtil.snapToMouse(yaw, pitch, baseYaw, basePitch);
        SilentLook.request(snapped[0], snapped[1], movementFix());
    }

    /**
     * Starts easing a silent aim back to the camera. On Lock View rotations the aim already is
     * the camera, so there is nothing to unwind; the view stays on the last target until the
     * player moves it.
     */
    private void beginReturn() {
        if (!aimInitialised) return;
        if (!isSilentRotations()) {
            endReturn();
            return;
        }
        if (returning) return;
        returning = true;
        returnTicks = 0;
    }

    /**
     * One tick's step back to the camera, at the same speed as an ordinary turn, so the server
     * sees the view come home instead of it being dropped the first tick the module has
     * nothing to aim at. Once the aim is within one mouse step of the camera the player's own
     * rotation is what goes out again.
     */
    private void tickReturn() {
        if (!returning) return;
        if (mc.thePlayer == null || mc.currentScreen != null || !isSilentRotations()) {
            endReturn();
            return;
        }
        if (++returnTicks > MAX_RETURN_TICKS) {
            endReturn();
            return;
        }

        float cameraYaw = SilentLook.cameraYaw();
        float cameraPitch = SilentLook.cameraPitch();
        // No target to chase: the low end of each range is the calmest turn home, the value
        // LiquidBounce uses when a rotation update has no target either.
        aim.tick(cameraYaw, cameraPitch,
                RotationUtil.speed(horizontalSpeedSetting.getLow()),
                RotationUtil.speed(verticalSpeedSetting.getLow()), smoothingSetting.getValue());

        double step = RotationUtil.mouseStep();
        if (step > 0.0D
                && Math.abs(MathHelper.wrapAngleTo180_float(cameraYaw - aim.yaw())) < step
                && Math.abs(cameraPitch - aim.pitch()) < step) {
            endReturn();
            return;
        }

        // Another module is aiming this tick; its rotation is the one that should go out.
        if (!SilentLook.isRequested()) requestSilentAim();
    }

    private void endReturn() {
        returning = false;
        returnTicks = 0;
        aimInitialised = false;
    }

    private String rotationMode() {
        String mode = rotationSetting.getValue();
        return mode == null ? ROTATION_LOCK_VIEW : mode;
    }

    private boolean isSilentRotations() {
        return ROTATION_SILENT.equals(rotationMode());
    }

    private boolean isLegitSmoothing() {
        return RotationUtil.SMOOTHING_LEGIT.equals(smoothingSetting.getValue());
    }

    /**
     * LiquidBounce's three movement corrections, with Silent as its default. None leaves the
     * walk on the camera under an aim the server believes; Silent keeps the camera direction
     * but turns the keys into the aim's frame so the movement matches the rotation it was sent
     * with; Strict simply walks along the aim.
     */
    private SilentLook.MovementFix movementFix() {
        String mode = movementFixSetting.getValue();
        if (MOVE_FIX_SILENT.equals(mode)) return SilentLook.MovementFix.SILENT;
        if (MOVE_FIX_STRICT.equals(mode)) return SilentLook.MovementFix.STRICT;
        return SilentLook.MovementFix.NONE;
    }

    private float[] rotationsTo(EntityLivingBase target) {
        return rotationsTo(target, 1.0F);
    }

    private float[] rotationsTo(EntityLivingBase target, float partialTicks) {
        Vec3 point = aimPoint(target, partialTicks);
        if (point == null) {
            Vec3 position = interpolatedPosition(target, partialTicks);
            point = new Vec3(position.xCoord, position.yCoord + target.getEyeHeight() * 0.9D,
                    position.zCoord);
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(partialTicks);
        return RotationUtil.getRotations(point, eyes);
    }

    /**
     * The point the aim goes to: the box centre at 82.5% height, the same point Raven aims
     * at, and only if that point is actually visible. A hit aimed through a wall is one the
     * server cannot reproduce, so the fallback is the closest point of the box the eye can
     * see, and null when nothing on the box is exposed.
     */
    private Vec3 aimPoint(EntityLivingBase target) {
        return aimPoint(target, 1.0F);
    }

    private Vec3 aimPoint(EntityLivingBase target, float partialTicks) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(partialTicks);
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = interpolatedBox(target, partialTicks)
                .expand(border, border, border);
        if (box.isVecInside(eyes)) return eyes;

        Vec3 centre = new Vec3((box.minX + box.maxX) / 2.0D,
                box.minY + (box.maxY - box.minY) * 0.825D, (box.minZ + box.maxZ) / 2.0D);
        if (clearLine(eyes, centre)) return centre;

        Vec3 closest = new Vec3(
                MathHelper.clamp_double(eyes.xCoord, box.minX + 0.03D, box.maxX - 0.03D),
                MathHelper.clamp_double(eyes.yCoord, box.minY + 0.03D, box.maxY - 0.03D),
                MathHelper.clamp_double(eyes.zCoord, box.minZ + 0.03D, box.maxZ - 0.03D));
        if (clearLine(eyes, closest)) return closest;
        return null;
    }

    /** The target's box at the rendered position, so a moving target is tracked smoothly. */
    private AxisAlignedBB interpolatedBox(EntityLivingBase target, float partialTicks) {
        AxisAlignedBB box = target.getEntityBoundingBox();
        Vec3 position = interpolatedPosition(target, partialTicks);
        double dx = position.xCoord - target.posX;
        double dy = position.yCoord - target.posY;
        double dz = position.zCoord - target.posZ;
        return new AxisAlignedBB(box.minX + dx, box.minY + dy, box.minZ + dz,
                box.maxX + dx, box.maxY + dy, box.maxZ + dz);
    }

    private Vec3 interpolatedPosition(EntityLivingBase target, float partialTicks) {
        return new Vec3(
                target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks,
                target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks,
                target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks);
    }

    private boolean clearLine(Vec3 from, Vec3 to) {
        return mc.theWorld.rayTraceBlocks(from, to, false, true, false) == null;
    }

    private boolean clickDue() {
        // The CPS range replaces the old Jitter percentage: every tick banks a fraction of a
        // click drawn from the range, so the pacing varies without a burst-prone chance roll.
        double cps = cpsSetting.random();
        if (cps <= 0.0D) return false;

        clickBudget += cps / 20.0D;
        if (clickBudget < 1.0D) return false;

        clickBudget = Math.min(1.0D, clickBudget - 1.0D);
        return true;
    }

    private boolean attack(EntityLivingBase target) {
        if (mc.playerController == null || !target.isEntityAlive()) return false;

        // The rotation this hit relies on is the one the previous tick's movement packet
        // carried; canAttack only fires when that ray already reaches the box.
        mc.thePlayer.swingItem();
        mc.playerController.attackEntity(mc.thePlayer, target);
        ClickState.markAttack(mc.thePlayer.ticksExisted);
        return true;
    }

    /** Distance from the player's eyes to the closest point on the target's hitbox. */
    private double hitDistance(EntityLivingBase target) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = target.getEntityBoundingBox();
        double x = MathHelper.clamp_double(eyes.xCoord, box.minX, box.maxX);
        double y = MathHelper.clamp_double(eyes.yCoord, box.minY, box.maxY);
        double z = MathHelper.clamp_double(eyes.zCoord, box.minZ, box.maxZ);
        double dx = eyes.xCoord - x;
        double dy = eyes.yCoord - y;
        double dz = eyes.zCoord - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean holdingWeapon() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return false;
        Item item = held.getItem();
        return item instanceof ItemSword || item instanceof ItemAxe;
    }

    private void reset() {
        restoreLockViewMouse();
        stopAutoBlock();
        stopBlinkBlock();
        target = null;
        targets.clear();
        targetIndex = 0;
        clickBudget = 1.0D;
        sentInitialised = false;
        if (aimInitialised && isSilentRotations()) {
            // Let the server's rotation return on its own step rather than dropping it.
            returning = true;
            returnTicks = 0;
        } else {
            endReturn();
        }
    }

    private static final class TargetCandidate {

        private final EntityLivingBase entity;
        private final double score;

        private TargetCandidate(EntityLivingBase entity, double score) {
            this.entity = entity;
            this.score = score;
        }
    }

    /** The blocking sub-settings only matter once blocking is enabled. */
    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == blockingModeSetting || setting == blockRangeSetting
                || setting == onMouseDownSetting) {
            return blockingEnabledSetting.getValue();
        }
        // Lock View uses OpenAdjust's instant camera lock; these only affect Silent.
        if (setting == smoothingSetting || setting == movementFixSetting) {
            return isSilentRotations();
        }
        // Legit (OpenAdjust's Organic) sets its own turn speed and hand motion.
        if (setting == horizontalSpeedSetting || setting == verticalSpeedSetting) {
            return isSilentRotations() && !isLegitSmoothing();
        }
        if (setting == organicSpeedSetting || setting == driftSetting
                || setting == jitterSetting) {
            return isSilentRotations() && isLegitSmoothing();
        }
        return true;
    }

    /** The aura instance, used by TargetHUD and TargetStrafe. */
    public static KillAura instance() {
        return instance;
    }

    /** The target the aura is aiming at, or null when it has none. */
    public EntityLivingBase getTarget() {
        return target;
    }

    /** How many targets the aura currently has in its cycle. */
    @Override
    public String getTag() {
        return Integer.toString(targets.size());
    }

    @Override
    public void onEnable() {
        reset();
        syncLockViewMouse();
    }

    @Override
    public void onDisable() {
        restoreLockViewMouse();
        reset();
    }
}
