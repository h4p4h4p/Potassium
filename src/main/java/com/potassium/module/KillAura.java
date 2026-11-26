package com.potassium.module;

import com.potassium.command.CommandManager;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.RangeSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.ClickState;
import com.potassium.util.RotationSmoother;
import com.potassium.util.RotationUtil;
import com.potassium.util.SilentLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
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
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selects visible targets in range, turns toward them and attacks them with LeftClicker-style
 * CPS pacing.
 *
 * Up to Targets entities are kept in the cycle; each successful hit moves the aim to the next
 * one, so several targets are fought at the configured CPS. Sword blocking can be layered
 * between hits through the Blocking mode.
 */
public class KillAura extends Module {

    public static final String ROTATION_NORMAL = "Normal";
    public static final String ROTATION_SILENT = "Silent";

    public static final String MOVE_FIX_NONE = "None";
    public static final String MOVE_FIX_SILENT = "Silent";
    public static final String MOVE_FIX_STRICT = "Strict";

    public static final String BLOCK_OFF = "Off";
    public static final String BLOCK_PACKET = "Packet";
    public static final String BLOCK_VANILLA = "Vanilla";
    public static final String BLOCK_FAKE = "Fake";

    private static final String ROTATION_SMOOTHING_GROUP = "Rotation Smoothing";
    private static final String CONDITIONS_GROUP = "Conditions";
    /** How long the view may take easing back to the camera, in ticks. */
    private static final int MAX_RETURN_TICKS = 60;

    // Silent: the server receives the aim while the camera stays under the player's control.
    private final ModeSetting rotationSetting = new ModeSetting("Rotations",
            new String[]{ROTATION_NORMAL, ROTATION_SILENT}, 1);
    private final BooleanSetting alertDistanceSetting = new BooleanSetting("Alert Distance", false);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final SliderSetting targetsSetting = new SliderSetting("Targets", 1, 1, 4, 1);
    private final SliderSetting reachSetting = new SliderSetting("Reach", 3.0, 1.0, 4.5, 0.1);
    private final SliderSetting fovSetting = new SliderSetting("FOV", 180, 1, 360, 1);
    private final RangeSetting cpsSetting = new RangeSetting("CPS", 12, 12, 1, 20, 1);
    private final ModeSetting smoothingSetting = new ModeSetting("Smoothing",
            new String[]{RotationUtil.SMOOTHING_LINEAR, RotationUtil.SMOOTHING_EXPONENTIAL}, 0);
    private final RangeSetting horizontalSpeedSetting =
            new RangeSetting("Horizontal Speed", 180, 180, 0, 180, 1);
    private final RangeSetting verticalSpeedSetting =
            new RangeSetting("Vertical Speed", 180, 180, 0, 180, 1);
    private final BooleanSetting playersOnlySetting = new BooleanSetting("Players Only", true);
    private final BooleanSetting wallCheckSetting = new BooleanSetting("Wall Check", true);
    private final BooleanSetting weaponOnlySetting = new BooleanSetting("Weapon Only", false);
    private final ModeSetting blockingSetting = new ModeSetting("Blocking",
            new String[]{BLOCK_OFF, BLOCK_PACKET, BLOCK_VANILLA, BLOCK_FAKE}, 0);
    private final BooleanSetting stopOnBlockSetting = new BooleanSetting("Stop on Block", false);
    private final ModeSetting movementFixSetting = new ModeSetting("Movement Fix",
            new String[]{MOVE_FIX_NONE, MOVE_FIX_SILENT, MOVE_FIX_STRICT}, 1);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final List<EntityLivingBase> targets = new ArrayList<>();
    private EntityLivingBase target;
    private int targetIndex;
    private double clickBudget = 1.0D;
    private final RotationSmoother aim = new RotationSmoother();
    private boolean aimInitialised;
    /** This tick's turn for Normal rotations, carried out across the tick's frames. */
    private float normalTurnYaw;
    private float normalTurnPitch;
    private boolean normalTurnSet;
    /** How much of that turn has been drawn already, and how long the whole turn may take. */
    private double normalTurnDrawn;
    private double normalTurnSeconds;
    private long normalTurnNanos;
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

    public KillAura() {
        super("KillAura", Category.COMBAT);
        smoothingSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        horizontalSpeedSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        verticalSpeedSetting.setGroup(ROTATION_SMOOTHING_GROUP);
        playersOnlySetting.setGroup(CONDITIONS_GROUP);
        wallCheckSetting.setGroup(CONDITIONS_GROUP);
        weaponOnlySetting.setGroup(CONDITIONS_GROUP);
        stopOnBlockSetting.setGroup(CONDITIONS_GROUP);
        settings.add(rotationSetting);
        settings.add(alertDistanceSetting);
        settings.add(rangeSetting);
        settings.add(targetsSetting);
        settings.add(reachSetting);
        settings.add(fovSetting);
        settings.add(blockingSetting);
        settings.add(cpsSetting);
        settings.add(smoothingSetting);
        settings.add(horizontalSpeedSetting);
        settings.add(verticalSpeedSetting);
        settings.add(playersOnlySetting);
        settings.add(wallCheckSetting);
        settings.add(weaponOnlySetting);
        settings.add(stopOnBlockSetting);
        settings.add(movementFixSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        // A silent rotation the server is still holding is unwound even with the module off,
        // so turning it off eases the view back instead of snapping it in one packet.
        if (!isEnabled()) {
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

        List<TargetCandidate> candidates = findTargets();
        if (candidates.isEmpty()) {
            targets.clear();
            target = null;
            targetIndex = 0;
            clickBudget = 1.0D;
            stopAutoBlock();
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

        // Packet mode blocks before the hit as well as after it: sending C08 first means the
        // server is already blocking when the attack lands, not one packet later.
        if (BLOCK_PACKET.equals(blockingMode())) maintainAutoBlock();

        // Attacking here puts the attack packet before this tick's movement packet, which is
        // the order a real click produces. Sent at the end of the tick instead, every hit
        // arrived after the flying packet and read as a post-movement attack.
        EntityLivingBase current = target;
        boolean attackNow = !stopOnBlock() && !usingOtherItem() && canAttack(current)
                && clickDue();
        if (attackNow) {
            // Packet mode deliberately keeps the server-side block through the hit; every
            // other mode releases it so the client attack matches the packet it sends.
            if (autoBlockActive && !BLOCK_PACKET.equals(autoBlockMode)) stopAutoBlock();
            if (attack(current)) advanceTarget();
        }
        maintainAutoBlock();

        // The aim is chosen once for this tick; the smoother carries it across the frames in
        // between, and Silent samples it here so this tick's movement packet carries it.
        aimAt(current);
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
     * block, so Stop on Block only applies when the Blocking setting is Off.
     */
    private boolean stopOnBlock() {
        return stopOnBlockSetting.getValue() && BLOCK_OFF.equals(blockingMode())
                && isBlockingWithSword();
    }

    private String blockingMode() {
        String mode = blockingSetting.getValue();
        return mode == null ? BLOCK_OFF : mode;
    }

    /**
     * Auto-blocking between attacks, after LiquidBounce and Rise.
     *
     * <p>Packet sends only C08/C07, so the server holds the block while the client keeps
     * attacking normally; nothing slows the camera or movement. Vanilla also puts the sword
     * in use client-side, giving the real block animation and slowdown, and releases it
     * before each hit. Fake shows that animation without telling the server, so it is only
     * cosmetic. All of them only run while a sword is held.</p>
     */
    private void maintainAutoBlock() {
        String mode = blockingMode();
        if (BLOCK_OFF.equals(mode) || !autoBlockWanted()) {
            stopAutoBlock();
            return;
        }

        if (autoBlockActive && (!mode.equals(autoBlockMode)
                || autoBlockStack != mc.thePlayer.getHeldItem())) {
            stopAutoBlock();
        }
        if (!autoBlockActive) startAutoBlock();
    }

    private boolean autoBlockWanted() {
        if (mc.thePlayer == null || target == null) return false;
        // Auto-block only assists a block the player is already asking for; letting go of
        // the interact key releases it on the next tick.
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemSword)) return false;
        // Do not block over food, a potion or a bow the player is using.
        return !usingOtherItem();
    }

    private void syncAutoBlockMode() {
        if (autoBlockActive && !blockingMode().equals(autoBlockMode)) stopAutoBlock();
    }

    private void startAutoBlock() {
        ItemStack held = mc.thePlayer.getHeldItem();
        String mode = blockingMode();
        if (held == null || !(held.getItem() instanceof ItemSword) || BLOCK_OFF.equals(mode)) return;

        // The client-side use state gives Vanilla its animation and slowdown; Fake wants the
        // animation alone. Packet leaves the client untouched and only tells the server.
        if (!BLOCK_PACKET.equals(mode)) {
            mc.thePlayer.setItemInUse(held, held.getMaxItemUseDuration());
        }
        if (!BLOCK_FAKE.equals(mode)) {
            sendBlockPacket(held);
        }

        autoBlockActive = true;
        autoBlockMode = mode;
        autoBlockStack = held;
    }

    private void stopAutoBlock() {
        if (!autoBlockActive) return;

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
    }

    private void sendBlockPacket(ItemStack stack) {
        if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(stack));
        }
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

    /**
     * Puts the tick's Normal rotation on the mouse grid before vanilla builds the movement
     * packet.
     *
     * <p>Normal rotations are the camera, and the module turns it frame by frame, so the delta
     * between two movement packets is a fractional angle. A hand cannot produce one: vanilla
     * only ever adds whole mouse counts, and a server dividing the delta by the count step
     * finds no sensitivity that fits it. Rounding this tick's camera onto whole counts, the
     * same snap Silent rotations and Scaffold already apply, leaves every rotation the server
     * sees on the grid a real mouse moves on. It is written for the packet alone; the camera
     * is put back at the end of the tick.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onNormalAimPacket(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!isEnabled() || isSilentRotations() || !normalTurnSet) return;
        // Another module is already aiming this tick; its rotation is the one that goes out.
        if (SilentLook.isRequested()) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;
        if (player.isRiding() || mc.currentScreen != null) return;

        float baseYaw = sentInitialised ? sentYaw : player.rotationYaw;
        float basePitch = sentInitialised ? sentPitch : player.rotationPitch;
        float[] snapped = RotationUtil.snapToMouse(player.rotationYaw, player.rotationPitch,
                baseYaw, basePitch);
        SilentLook.request(snapped[0], snapped[1], SilentLook.MovementFix.NONE);
    }

    /** Records the rotation this tick's movement packet carried, before the camera is restored. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null) return;
        sentYaw = mc.thePlayer.rotationYaw;
        sentPitch = mc.thePlayer.rotationPitch;
        sentInitialised = true;
    }

    private boolean isAlertDistance() {
        return alertDistanceSetting.getValue();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (isSilentRotations() || !normalTurnSet) return;

        // Normal rotations are the camera, so this tick's turn is added as it is drawn rather
        // than pulled out of a stored target: whatever the mouse has moved since the turn was
        // chosen stays, and the turn finishes inside its own tick. Drawn across the frames of
        // the tick, a turn is no slower than the game's own look and no longer steps at 20 Hz.
        long now = System.nanoTime();
        double share = normalTurnSeconds <= 1.0e-4D ? 1.0D
                : Math.min(1.0D, (now - normalTurnNanos) / 1.0e9D / normalTurnSeconds);
        double step = share - normalTurnDrawn;
        if (step <= 0.0D) return;
        normalTurnDrawn = share;

        mc.thePlayer.rotationYaw += (float) (normalTurnYaw * step);
        mc.thePlayer.rotationPitch = MathHelper.clamp_float(
                mc.thePlayer.rotationPitch + (float) (normalTurnPitch * step), -90.0F, 90.0F);
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
        double maxDistanceSq = maxDistance * maxDistance;
        List<TargetCandidate> candidates = new ArrayList<>();

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity == player || entity.isDead || !(entity instanceof EntityLivingBase)) continue;
            if (!entity.isEntityAlive()) continue;
            if (playersOnlySetting.getValue() && !(entity instanceof EntityPlayer)) continue;
            if (entity instanceof EntityPlayer && ((EntityPlayer) entity).isSpectator()) continue;
            if (AntiBot.isBot(entity)) continue;

            EntityLivingBase living = (EntityLivingBase) entity;
            double targetX = living.posX;
            double targetY = living.posY + living.getEyeHeight();
            double targetZ = living.posZ;

            double dx = targetX - eyeX;
            double dy = targetY - eyeY;
            double dz = targetZ - eyeZ;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double distanceSq = horizontal * horizontal + dy * dy;
            if (distanceSq > maxDistanceSq) continue;

            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
            float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(yaw - currentYaw));
            float pitchDifference = Math.abs(pitch - currentPitch);
            if (yawDifference > fov || pitchDifference > fov) continue;
            if (wallCheckSetting.getValue() && aimPoint(living) == null) continue;

            double score = yawDifference + pitchDifference + Math.sqrt(distanceSq) * 0.05D;
            candidates.add(new TargetCandidate(living, score));
        }

        candidates.sort((a, b) -> Double.compare(a.score, b.score));
        return candidates;
    }

    /**
     * Keeps at most Targets entries in the cycle, preferring the ones already engaged so a
     * new best candidate cannot reset the rotation every tick. Empty slots are filled from
     * the score-ordered scan.
     */
    private void updateTargets(List<TargetCandidate> candidates) {
        int limit = targetsLimit();
        List<EntityLivingBase> next = new ArrayList<>(limit);
        for (EntityLivingBase previous : targets) {
            if (next.size() >= limit) break;
            if (isCandidate(candidates, previous)) next.add(previous);
        }
        for (TargetCandidate candidate : candidates) {
            if (next.size() >= limit) break;
            if (!next.contains(candidate.entity)) next.add(candidate.entity);
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
     * handed to the smoother, which eases the real aim to it at frame rate and is sampled here
     * for the packet. Normal rotations are the camera, so the target is stored and the view is
     * eased toward it every frame from wherever the mouse has left it.
     */
    private void aimAt(EntityLivingBase target) {
        float[] rotations = rotationsTo(target);
        String smoothing = smoothingSetting.getValue();
        // A draw inside each range every tick, like LiquidBounce's speed ranges, so the turn
        // does not step at one flat rate the way a single number would.
        double yawStep = RotationUtil.speed(horizontalSpeedSetting.random());
        double pitchStep = RotationUtil.speed(verticalSpeedSetting.random());

        if (!isSilentRotations()) {
            // The camera is the aim. The turn is generated as a flick and rounded onto the
            // mouse grid, from where the mouse has just left the view, so the server sees a
            // hand's flicks rather than one perfect step onto the target.
            float cameraYaw = mc.thePlayer.rotationYaw;
            float cameraPitch = mc.thePlayer.rotationPitch;
            normalTurnYaw = aim.cameraYawTurn(
                    MathHelper.wrapAngleTo180_float(rotations[0] - cameraYaw), yawStep);
            normalTurnPitch = aim.cameraPitchTurn(rotations[1] - cameraPitch, pitchStep);
            normalTurnSet = true;
            normalTurnDrawn = 0.0D;
            normalTurnNanos = System.nanoTime();
            normalTurnSeconds = tickSeconds();
            aimInitialised = true;
            return;
        }

        if (!aimInitialised) {
            // Start from the rotation the server already holds, so the first look is a turn
            // rather than a jump.
            aim.reset(sentInitialised ? sentYaw : mc.thePlayer.rotationYaw,
                    sentInitialised ? sentPitch : mc.thePlayer.rotationPitch);
            aimInitialised = true;
        }
        // One tick's turn, and the value this tick's packet samples below. A silent aim that is
        // still catching up when the packet is built is a look one tick behind the target.
        aim.tick(rotations[0], rotations[1], yawStep, pitchStep, smoothing);
        requestSilentAim();
    }

    /** The client's tick length; 1.8.9 runs twenty of them a second. */
    private double tickSeconds() {
        return 1.0D / 20.0D;
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
     * Starts easing a silent aim back to the camera. On Normal rotations the aim already is the
     * camera, so there is nothing to unwind and the view simply stays where the player left it.
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
        normalTurnSet = false;
    }

    private String rotationMode() {
        String mode = rotationSetting.getValue();
        return mode == null ? ROTATION_NORMAL : mode;
    }

    private boolean isSilentRotations() {
        return ROTATION_SILENT.equals(rotationMode());
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
        Vec3 point = aimPoint(target);
        if (point == null) {
            point = new Vec3(target.posX, target.posY + target.getEyeHeight() * 0.9D, target.posZ);
        }
        return RotationUtil.getRotations(point);
    }

    /**
     * The point the aim goes to: the box centre at 82.5% height, the same point Raven aims
     * at, and only if that point is actually visible. A hit aimed through a wall is one the
     * server cannot reproduce, so the fallback is the closest point of the box the eye can
     * see, and null when nothing on the box is exposed.
     */
    private Vec3 aimPoint(EntityLivingBase target) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
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

        boolean alert = isAlertDistance();
        double distance = alert ? hitDistance(target) : 0.0D;
        float healthBefore = target.getHealth();
        // The rotation this hit relies on is the one the previous tick's movement packet
        // carried; canAttack only fires when that ray already reaches the box.
        mc.thePlayer.swingItem();
        mc.playerController.attackEntity(mc.thePlayer, target);
        ClickState.markAttack(mc.thePlayer.ticksExisted);
        // Only a hit that actually took health counts: a click inside the target's hurt
        // immunity window sends the packet but lands no damage, and alerting on those
        // made the message fire several times per real hit.
        if (alert && target.getHealth() < healthBefore) {
            CommandManager.sendMessage("\u00a77KillAura \u00a7fAlert Distance: \u00a7a"
                    + String.format(Locale.ROOT, "%.2f", distance) + " \u00a77blocks");
        }
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
        stopAutoBlock();
        target = null;
        targets.clear();
        targetIndex = 0;
        clickBudget = 1.0D;
        normalTurnSet = false;
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

    /** How many targets the aura currently has in its cycle. */
    @Override
    public String getTag() {
        return Integer.toString(targets.size());
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }
}
