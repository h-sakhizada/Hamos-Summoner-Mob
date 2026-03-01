package com.example.examplemod.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import net.minecraft.nbt.CompoundTag;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Bodyguard entity that protects a Summoner.
 *
 * Fixes included:
 * - Uses Summoner retaliate token to make ALL guards charge the attacker when Summoner takes 10 hearts of player damage.
 *
 * Version: 1.0.0
 * Comments:
 */
public class BodyguardEntity extends Zombie implements GeoEntity {

    // GeckoLib animation instance cache (stores per-entity animation state)
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Synced state for animation selection (server -> client)
    private static final EntityDataAccessor<Boolean> DATA_CHARGING =
            SynchedEntityData.defineId(BodyguardEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ATTACKING =
            SynchedEntityData.defineId(BodyguardEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_INDEX =
            SynchedEntityData.defineId(BodyguardEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MODE =
            SynchedEntityData.defineId(BodyguardEntity.class, EntityDataSerializers.INT);

    // Persistent NBT keys used to link this bodyguard to its summoner
    private static final String NBT_OWNER_UUID = "SummonerOwner";
    private static final String NBT_SIDE = "SummonerSide";

    // NBT keys used to persist combat state across world reloads
    private static final String NBT_MODE = "BodyguardMode";
    private static final String NBT_LAST_TARGET = "BodyguardLastTarget";
    private static final String NBT_RETALIATE_COOLDOWN = "BodyguardRetaliateCooldown";

    // Side values stored in NBT (left/right relative to summoner facing direction)
    private static final String SIDE_LEFT = "left";

    // Guard formation tuning
    private static final double GUARD_SIDE_OFFSET = 1.8;
    private static final double GUARD_STOP_DISTANCE = 1.2;

    // Aggro / detection tuning
    private static final double AGGRO_RADIUS = 10.0;

    // Retaliate cooldown (7 seconds at 20 TPS)
    private static final int RETALIATE_COOLDOWN_TICKS = 140;

    // Charge tuning
    private static final int CHARGE_DURATION_TICKS = 60;
    private static final double CHARGE_SPEED_MULTIPLIER = 1.4;
    private static final int CHARGE_STEER_UPDATE_EVERY_TICKS = 3;
    private static final double CHARGE_TURN_LERP = 0.10;

    // One-time impact damage tuning for charge
    private static final float CHARGE_IMPACT_DAMAGE = 20.0F;
    private static final double CHARGE_IMPACT_RANGE = 2.5;

    // Regular melee tuning
    private static final float MELEE_DAMAGE = 8.0F;
    private static final int MELEE_ATTACK_INTERVAL_TICKS = 20;
    private static final double MELEE_REACH = 2.75;

    // Melee animation timing: 0.33s at 20 TPS ≈ 7 ticks
    private static final int MELEE_HIT_DELAY_TICKS = 3;

    // True for 1 tick after starting an attack so the controller can reset ONCE
    private boolean attackAnimationJustStarted = false;


    // Movement modifier used during charge (2.5x => MULTIPLY_TOTAL +1.5)
    private static final UUID CHARGE_SPEED_UUID =
            UUID.fromString("2e6b1e49-0c0a-4c2c-8e32-fb7d9eaf2f4d");
    private static final AttributeModifier CHARGE_SPEED_MOD =
            new AttributeModifier(
                    CHARGE_SPEED_UUID,
                    "Bodyguard charge speed",
                    (CHARGE_SPEED_MULTIPLIER - 1.0),
                    AttributeModifier.Operation.MULTIPLY_TOTAL
            );

    /**
     * High-level behavior state for the bodyguard.
     *
     * GUARD  - Hold formation beside summoner.
     * CHARGE - Execute one-time charge burst.
     * MELEE  - Normal chasing + attacking.
     * RETURN - Ignore player and run back to summoner until formation is restored.
     *
     * Version: 1.0.0
     * Comments:
     */
    private enum GuardMode {
        GUARD,
        CHARGE,
        MELEE,
        RETURN
    }


    // Current mode (server-side)
    private GuardMode mode = GuardMode.GUARD;

    // Charge runtime state (server-side)
    private int chargeTicksLeft = 0;
    private Vec3 chargeDir = Vec3.ZERO;
    private int chargeSteerCooldown = 0;
    private boolean chargeImpactApplied = false;

    // Charge lockout rules
    private boolean chargeAvailable = true;
    private boolean playerWasInAggroRadius = false;

    // Attack animation cycling (server-side)
    private int attackAnimTicksLeft = 0;

    // Retaliate token tracking (server-side)
    private int lastSeenRetaliateToken = 0;

    // Charge token tracking (server-side)
    private int lastSeenChargeToken = 0;

    // Retaliate cooldown remaining ticks (prevents charge spam after retaliate)
    private int retaliateCooldownTicksLeft = 0;


    /**
     * Constructs a new BodyguardEntity instance.
     *
     * @param type EntityType<? extends Zombie> type - The entity type registered for this bodyguard.
     * @param level Level level - The world/level the entity exists in.
     * Version: 1.0.0
     * Comments:
     */
    public BodyguardEntity(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    /**
     * Returns the GeckoLib animation cache for this entity.
     *
     * @return AnimatableInstanceCache - The animation cache attached to this entity instance.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /**
     * Defines synchronized data fields for this entity.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();

        this.entityData.define(DATA_CHARGING, false);
        this.entityData.define(DATA_ATTACKING, false);
        this.entityData.define(DATA_ATTACK_INDEX, 0);

        // Sync mode so the client animation controller sees the real server state
        this.entityData.define(DATA_MODE, GuardMode.GUARD.ordinal());
    }

    /**
     * Returns the guard's synced mode value for client-side animation decisions.
     *
     * @return GuardMode - The current mode as seen by SynchedEntityData.
     * Version: 1.0.0
     * Comments:
     */
    private GuardMode getSyncedMode() {
        int idx = this.entityData.get(DATA_MODE);

        // Clamp to valid enum range (prevents crashes if bad data ever occurs)
        if (idx < 0 || idx >= GuardMode.values().length) {
            idx = GuardMode.GUARD.ordinal();
        }

        return GuardMode.values()[idx];
    }

    /**
     * Sets the guard's mode on the server AND syncs it to clients.
     *
     * IMPORTANT:
     * - Always use this instead of assigning this.mode directly.
     *
     * @param newMode GuardMode newMode - The new behavior mode to enter.
     * Version: 1.0.0
     * Comments:
     */
    private void setMode(GuardMode newMode) {
        this.mode = newMode;
        this.entityData.set(DATA_MODE, newMode.ordinal());
    }

    /**
     * Registers AI goals that define the entity’s behavior.
     *
     * IMPORTANT FIX:
     * - Do NOT call super.registerGoals() because Zombie default goals can override guard/return logic.
     * - Add a mode-gated target selector so the guard only targets players during CHARGE/MELEE.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected void registerGoals() {

        // RETURN must be first so it overrides everything else when active
        this.goalSelector.addGoal(0, new ReturnToSummonerGoal(this));

        // Charge movement while in CHARGE mode
        this.goalSelector.addGoal(1, new ChargeMovementGoal(this));

        // Melee chasing + attacking while in MELEE mode
        this.goalSelector.addGoal(2, new MeleeChaseAndAttackGoal(this));

        // Formation movement while in GUARD mode
        this.goalSelector.addGoal(3, new StayBesideSummonerGoal(this));

        // Visual awareness (cosmetic)
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 10.0F));

        // Target players ONLY when fighting (prevents "one hit then return" while in GUARD/RETURN)
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(this, Player.class, true) {

            /**
             * Allows targeting only during combat modes.
             *
             * @return boolean - True only when CHARGE or MELEE.
             * Version: 1.0.0
             * Comments:
             */
            @Override
            public boolean canUse() {
                return (BodyguardEntity.this.getSyncedMode() == GuardMode.CHARGE || BodyguardEntity.this.getSyncedMode() == GuardMode.MELEE)
                        && super.canUse();
            }

            /**
             * Continues targeting only during combat modes.
             *
             * @return boolean - True only when CHARGE or MELEE.
             * Version: 1.0.0
             * Comments:
             */
            @Override
            public boolean canContinueToUse() {
                return (BodyguardEntity.this.getSyncedMode() == GuardMode.CHARGE || BodyguardEntity.this.getSyncedMode() == GuardMode.MELEE)
                        && super.canContinueToUse();
            }
        });
    }

    /**
     * Registers GeckoLib animation controllers for this entity.
     *
     * Update:
     * - guarding_animation only plays when the guard is in GUARD mode and not moving.
     * - otherwise, idle_animation plays when not moving/attacking/charging.
     *
     * @param controllers AnimatableManager.ControllerRegistrar controllers - Controller registry used by GeckoLib.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {

        // Main controller that decides which animation should be active
        AnimationController<BodyguardEntity> controller =
                new AnimationController<>(this, "controller", 5, state -> {

                    AnimationController<BodyguardEntity> c = state.getController();
                    RawAnimation target;

                    // Charging animation has highest priority
                    if (this.isCharging()) {
                        target = RawAnimation.begin().thenLoop("charging_animation");

                        // Attack animation window plays next (PLAY_ONCE)
                    } else if (this.isAttacking()) {

                        String animName = switch (this.getAttackIndex()) {
                            case 1 -> "attacking_animation2";
                            case 2 -> "attacking_animation3";
                            default -> "attacking_animation1";
                        };

                        // IMPORTANT FIX:
                        // Only reset the animation ONCE when the attack begins.
                        // If we reset every tick, the animation restarts repeatedly and never finishes.
                        if (this.attackAnimationJustStarted) {
                            c.forceAnimationReset();
                            this.attackAnimationJustStarted = false;
                        }

                        target = RawAnimation.begin().then(animName, Animation.LoopType.PLAY_ONCE);

                        // Movement animation
                    } else if (state.isMoving()) {
                        target = RawAnimation.begin().thenLoop("walking_animation");

                        // Only show guarding stance when the guard is actually in GUARD mode
                    } else if (this.getSyncedMode() == GuardMode.GUARD) {
                        target = RawAnimation.begin().thenLoop("guarding_animation");
                        System.out.println("guarding");

                        System.out.println(this.getSyncedMode());

                        // Otherwise, default to idle when standing still (RETURN/MELEE/etc.)
                    } else {
                        target = RawAnimation.begin().thenLoop("idle_animation");
                        System.out.println("standing still");
                    }

                    // Avoid re-applying the same animation every tick (prevents constant resets)
                    if (c.getCurrentRawAnimation() == null || !c.getCurrentRawAnimation().equals(target)) {
                        c.setAnimation(target);
                    }

                    return PlayState.CONTINUE;
                });

        // Ensures an animation is set immediately for already-spawned entities on world load
        controller.setAnimation(RawAnimation.begin().thenLoop("idle_animation"));

        // Register the controller with GeckoLib
        controllers.add(controller);
    }


    /**
     * Creates the attribute set for the BodyguardEntity.
     *
     * Version: 1.0.0
     * Comments:
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 60.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.ATTACK_DAMAGE, MELEE_DAMAGE);
    }

    /**
     * Prevents the bodyguard from despawning due to distance.
     *
     * @param distanceToClosestPlayer double distanceToClosestPlayer - Distance to nearest player.
     * @return boolean - Always false to prevent despawn.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    /**
     * Prevents this entity from burning in sunlight.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected boolean isSunBurnTick() {
        return false;
    }


    /**
     * Saves persistent bodyguard state to NBT so combat can resume after reload.
     *
     * @param tag CompoundTag tag - The NBT tag being written for this entity.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        // Persist current mode so we don't default back to GUARD after reload
        tag.putInt(NBT_MODE, this.getSyncedMode().ordinal());

        // Persist last target so MELEE/CHARGE can resume after reload
        if (this.getTarget() != null) {
            tag.putUUID(NBT_LAST_TARGET, this.getTarget().getUUID());
        }

        // Persist retaliate cooldown so it survives reload
        tag.putInt(NBT_RETALIATE_COOLDOWN, this.retaliateCooldownTicksLeft);
    }

    /**
     * Loads persistent bodyguard state from NBT so combat can resume after reload.
     *
     * @param tag CompoundTag tag - The NBT tag being read for this entity.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        // Restore mode (clamp to safe range)
        if (tag.contains(NBT_MODE)) {
            int idx = tag.getInt(NBT_MODE);
            if (idx < 0 || idx >= GuardMode.values().length) {
                idx = GuardMode.GUARD.ordinal();
            }
            this.setMode(GuardMode.values()[idx]);
        }

        // Restore target if possible; if not available yet, tick() will reacquire
        if (tag.hasUUID(NBT_LAST_TARGET) && (this.level() instanceof ServerLevel serverLevel)) {
            Entity e = serverLevel.getEntity(tag.getUUID(NBT_LAST_TARGET));
            if (e instanceof Player p) {
                this.setTarget(p);
            }
        }

        // Restore retaliate cooldown
        if (tag.contains(NBT_RETALIATE_COOLDOWN)) {
            this.retaliateCooldownTicksLeft = tag.getInt(NBT_RETALIATE_COOLDOWN);
        }
    }

    /**
     * Reacquires a player target after reload if the guard is in a combat mode but has no target.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickReacquireTargetIfNeeded() {

        // Only server decides targets
        if (this.level().isClientSide) return;

        // If we're not fighting, don't auto-pick targets
        if (this.mode != GuardMode.MELEE && this.mode != GuardMode.CHARGE) return;

        // If target already exists, nothing to do
        if (this.getTarget() instanceof Player) return;

        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return;

        // Prefer "near summoner" logic so behavior stays consistent
        Player target = this.findClosestPlayerNearSummoner(summoner);
        if (target != null) {
            this.setTarget(target);
        }
    }

    /**
     * Main per-tick update for this entity.
     *
     * Key fixes:
     * - Recall now enters RETURN mode (ignores player and runs back to summoner).
     * - Charge trigger is read from the Summoner (shared token) so both guards charge together.
     * - GUARD is only entered once formation is restored.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void tick() {
        super.tick();

        // Server-only logic (clients should not simulate AI)
        if (this.level().isClientSide) return;

        // Decrement retaliate cooldown each tick
        if (this.retaliateCooldownTicksLeft > 0) {
            this.retaliateCooldownTicksLeft--;
        }

        // Shared retaliate event: charges the attacker when summoner takes enough player damage
        this.tickRetaliateTokenSync();

        // Shared charge event: starts charge for both guards together when Summoner triggers it
        this.tickChargeTokenSync();

        // Re-arm charge ONLY while properly formed in GUARD mode
        this.tickChargeRearmRules();

        // Attack animation window timer
        this.tickAttackAnimationWindow();

        // Reacquire target after reload if we are in combat mode
        this.tickReacquireTargetIfNeeded();

        // If in MELEE with no target, fall back to RETURN
        this.tickMeleeFallbackToReturn();
    }


    /**
     * If in MELEE mode with no target (player died or left), transition to RETURN.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickMeleeFallbackToReturn() {
        if (this.mode != GuardMode.MELEE) return;
        if (this.getTarget() instanceof Player) return;

        this.clearCombatState();
        this.setMode(GuardMode.RETURN);
    }

    /**
     * Checks the owning Summoner's retaliate token and charges the attacker when it changes.
     *
     * This ensures:
     * - Both guards react to the same shared event.
     * - Guards charge the player who damaged the summoner past the threshold.
     * - Guards already mid-charge are NOT interrupted.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickRetaliateTokenSync() {

        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return;

        int token = summoner.getBodyguardRetaliateToken();

        // No new retaliate signal
        if (token == this.lastSeenRetaliateToken) return;

        // Consume token immediately (unlike charge token which defers)
        this.lastSeenRetaliateToken = token;

        // Must be fully active (not rising)
        if (!this.isFullyActive()) return;

        // Do NOT interrupt an existing charge
        if (this.getSyncedMode() == GuardMode.CHARGE) return;

        // Must not be on cooldown
        if (this.retaliateCooldownTicksLeft > 0) return;

        // Resolve the attacker from summoner
        UUID attackerUUID = summoner.getRetaliateAttackerUUID();
        if (attackerUUID == null) return;
        if (!(this.level() instanceof ServerLevel serverLevel)) return;
        Entity attackerEntity = serverLevel.getEntity(attackerUUID);
        if (!(attackerEntity instanceof Player attackerPlayer)) return;

        // Clear current combat state and charge the attacker
        this.clearCombatState();
        this.startCharge(attackerPlayer);
    }

    /**
     * Reads the Summoner's shared "charge token" and starts a charge when it changes.
     *
     * IMPORTANT FIX:
     * - Do NOT "consume" (store) the new token until the guard is actually eligible to charge.
     * - This prevents missing the charge signal during rising / formation settling.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickChargeTokenSync() {

        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return;

        int token = summoner.getBodyguardChargeToken();

        // No new charge signal
        if (token == this.lastSeenChargeToken) return;

        // ---- Eligibility gates (do NOT consume token unless we pass) ----

        // Do not start a proximity charge while retaliate cooldown is active
        if (this.retaliateCooldownTicksLeft > 0) return;

        // Must be fully active (not rising)
        if (!this.isFullyActive()) return;

        // Must be in GUARD mode (RETURN/MELEE/CHARGE should not start a new charge)
        if (this.getSyncedMode() != GuardMode.GUARD) return;

        // Must be in formation beside summoner
        if (!this.isInFormation(summoner)) return;

        // Must have charge available (re-armed)
        if (!this.chargeAvailable) return;

        // Find the player near the summoner as the charge target
        Player target = this.findClosestPlayerNearSummoner(summoner);
        if (target == null) return;

        // ---- Consume token ONLY now (we are actually charging) ----
        this.lastSeenChargeToken = token;

        // Start the charge
        this.startCharge(target);
    }




    // -----------------------------
    // Everything below this point is your existing bodyguard logic
    // (no functional changes here beyond recall fix).
    // -----------------------------

    /**
     * Returns whether the bodyguard is currently charging.
     *
     * Version: 1.0.0
     * Comments:
     */
    public boolean isCharging() {
        return this.entityData.get(DATA_CHARGING);
    }

    /**
     * Sets the charging state and synchronizes it to clients.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void setCharging(boolean value) {
        this.entityData.set(DATA_CHARGING, value);
    }

    /**
     * Returns whether the bodyguard is currently playing an attack animation window.
     *
     * Version: 1.0.0
     * Comments:
     */
    public boolean isAttacking() {
        return this.entityData.get(DATA_ATTACKING);
    }

    /**
     * Sets the attack animation window flag.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void setAttacking(boolean value) {
        this.entityData.set(DATA_ATTACKING, value);
    }

    /**
     * Returns the current attack animation index (0..2).
     *
     * Version: 1.0.0
     * Comments:
     */
    public int getAttackIndex() {
        return this.entityData.get(DATA_ATTACK_INDEX);
    }

    /**
     * Sets the current attack animation index.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void setAttackIndex(int index) {
        this.entityData.set(DATA_ATTACK_INDEX, index);
    }

    /**
     * Advances the attack index (0 -> 1 -> 2 -> 0).
     *
     * Version: 1.0.0
     * Comments:
     */
    private void advanceAttackIndex() {
        int next = (this.getAttackIndex() + 1) % 3;
        this.setAttackIndex(next);
    }

    /**
     * Triggers a short window where attack animation plays.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void triggerAttackAnimation() {
        this.setAttacking(true);
        this.attackAnimTicksLeft = 10;
    }

    /**
     * Ticks the attack animation window timer.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickAttackAnimationWindow() {
        if (!this.isAttacking()) return;

        this.attackAnimTicksLeft--;
        if (this.attackAnimTicksLeft <= 0) {
            this.attackAnimTicksLeft = 0;
            this.setAttacking(false);
        }
    }

    /**
     * Returns whether this bodyguard is fully active (not in rising phase).
     *
     * Version: 1.0.0
     * Comments:
     */
    private boolean isFullyActive() {
        return !this.getTags().contains("summoned_rising");
    }

    /**
     * Attempts to locate and return the owning Summoner entity from stored NBT.
     *
     * Version: 1.0.0
     * Comments:
     */
    private SummonerEntity getOwningSummoner() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return null;
        if (!this.getPersistentData().hasUUID(NBT_OWNER_UUID)) return null;

        UUID ownerId = this.getPersistentData().getUUID(NBT_OWNER_UUID);
        Entity owner = serverLevel.getEntity(ownerId);

        if (owner instanceof SummonerEntity summoner) {
            return summoner;
        }

        return null;
    }

    /**
     * Returns whether this bodyguard is assigned to the left side.
     *
     * Version: 1.0.0
     * Comments:
     */
    private boolean isLeftSide() {
        if (!this.getPersistentData().contains(NBT_SIDE)) return false;
        return SIDE_LEFT.equalsIgnoreCase(this.getPersistentData().getString(NBT_SIDE));
    }

    /**
     * Calculates desired guard position beside summoner.
     *
     * Version: 1.0.0
     * Comments:
     */
    private Vec3 getDesiredGuardPosition(SummonerEntity summoner) {

        double yawRad = Math.toRadians(summoner.getYRot());

        double rightX = -Math.sin(yawRad);
        double rightZ = Math.cos(yawRad);

        double sideSign = this.isLeftSide() ? -1.0 : 1.0;

        double targetX = summoner.getX() + (rightX * GUARD_SIDE_OFFSET * sideSign);
        double targetY = summoner.getY();
        double targetZ = summoner.getZ() + (rightZ * GUARD_SIDE_OFFSET * sideSign);

        return new Vec3(targetX, targetY, targetZ);
    }

    /**
     * Returns whether the bodyguard is currently in formation beside the Summoner.
     *
     * Version: 1.0.0
     * Comments:
     */
    private boolean isInFormation(SummonerEntity summoner) {

        Vec3 desired = this.getDesiredGuardPosition(summoner);
        double dist = this.distanceToSqr(desired.x, desired.y, desired.z);

        return dist <= (GUARD_STOP_DISTANCE * GUARD_STOP_DISTANCE);
    }

    /**
     * Finds the closest player within AGGRO_RADIUS of the summoner (true radius check).
     *
     * Version: 1.0.0
     * Comments:
     */
    private Player findClosestPlayerNearSummoner(SummonerEntity summoner) {

        AABB box = summoner.getBoundingBox().inflate(AGGRO_RADIUS);
        List<Player> players = summoner.level().getEntitiesOfClass(Player.class, box);
        if (players.isEmpty()) return null;

        Player closest = null;
        double bestDist = Double.MAX_VALUE;

        double maxDistSqr = AGGRO_RADIUS * AGGRO_RADIUS;

        for (Player p : players) {
            double d = p.distanceToSqr(summoner);
            if (d > maxDistSqr) continue;

            if (d < bestDist) {
                bestDist = d;
                closest = p;
            }
        }

        return closest;
    }

    /**
     * Bundle for target state near summoner.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class LivingTargetState {

        private final Player targetPlayer;
        private final boolean anyPlayerInRadius;

        /**
         * Constructs a target state object.
         *
         * @param targetPlayer Player targetPlayer - Closest player, nullable.
         * @param anyPlayerInRadius boolean anyPlayerInRadius - True if any player is inside radius.
         * Version: 1.0.0
         * Comments:
         */
        private LivingTargetState(Player targetPlayer, boolean anyPlayerInRadius) {
            this.targetPlayer = targetPlayer;
            this.anyPlayerInRadius = anyPlayerInRadius;
        }
    }

    /**
     * Returns current player-in-radius state around summoner.
     *
     * Version: 1.0.0
     * Comments:
     */
    private LivingTargetState getCurrentTargetState() {

        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return new LivingTargetState(null, false);

        Player closest = this.findClosestPlayerNearSummoner(summoner);
        boolean any = (closest != null);

        return new LivingTargetState(closest, any);
    }

    /**
     * Re-arms charge availability while the guard is safely in GUARD mode and in formation.
     *
     * Charge should only become available again when the guard has returned to formation.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickChargeRearmRules() {

        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return;

        // Do not re-arm while retaliate cooldown is active
        if (this.retaliateCooldownTicksLeft > 0) return;

        // Only re-arm while in GUARD and properly formed beside the summoner
        if (!this.isFullyActive()) return;
        if (this.getSyncedMode() != GuardMode.GUARD) return;
        if (!this.isInFormation(summoner)) return;

        // Only re-arm if no player is currently in aggro radius around the summoner
        Player p = this.findClosestPlayerNearSummoner(summoner);
        if (p == null) {
            this.chargeAvailable = true;
        }
    }

    /**
     * Clears any active combat state (charge speed, charge flags, target, navigation).
     *
     * Used when switching into RETURN mode so the guard does not jitter between attack and return.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void clearCombatState() {

        // Clear target and stop movement immediately
        this.setTarget(null);
        this.getNavigation().stop();

        // Cancel charge flags and remove charge speed modifier if present
        this.setCharging(false);
        this.chargeTicksLeft = 0;
        this.chargeSteerCooldown = 0;
        this.chargeImpactApplied = false;

        var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null && speedAttr.hasModifier(CHARGE_SPEED_MOD)) {
            speedAttr.removeModifier(CHARGE_SPEED_MOD);
        }

        // Cancel pending melee animation windows
        this.setAttacking(false);
        this.attackAnimTicksLeft = 0;
    }



    /**
     * Starts a charge toward a target.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void startCharge(Player player) {

        this.chargeImpactApplied = false;

        this.setCharging(true);
        this.setMode(GuardMode.CHARGE);
        this.chargeTicksLeft = CHARGE_DURATION_TICKS;
        this.chargeSteerCooldown = 0;

        Vec3 toTarget = player.position().subtract(this.position());
        this.chargeDir = toTarget.lengthSqr() < 0.0001 ? this.getLookAngle() : toTarget.normalize();

        var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null && !speedAttr.hasModifier(CHARGE_SPEED_MOD)) {
            speedAttr.addTransientModifier(CHARGE_SPEED_MOD);
        }

        this.setTarget(player);
    }

    /**
     * Ends charge and transitions into melee.
     *
     * IMPORTANT FIX:
     * - After charge completes, guards remain in MELEE unless recall triggers RETURN.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void endCharge() {

        this.chargeImpactApplied = false;

        this.setCharging(false);
        this.chargeTicksLeft = 0;
        this.chargeSteerCooldown = 0;

        var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null && speedAttr.hasModifier(CHARGE_SPEED_MOD)) {
            speedAttr.removeModifier(CHARGE_SPEED_MOD);
        }

        // Charge cannot be used again until the guard fully reforms in GUARD mode
        this.chargeAvailable = false;

        // Start retaliate cooldown to prevent charge spam
        this.retaliateCooldownTicksLeft = RETALIATE_COOLDOWN_TICKS;

        // Always go into melee after a charge (unless retaliate later forces another charge)
        this.setMode(GuardMode.MELEE);
    }


    /**
     * Applies one-time charge impact damage.
     *
     * Version: 1.0.0
     * Comments:
     */
    private boolean tryApplyChargeImpact(Player target) {

        if (this.chargeImpactApplied) return false;

        double impactRangeSqr = CHARGE_IMPACT_RANGE * CHARGE_IMPACT_RANGE;
        if (this.distanceToSqr(target) > impactRangeSqr) return false;

        target.hurt(this.damageSources().mobAttack(this), CHARGE_IMPACT_DAMAGE);

        this.chargeImpactApplied = true;
        this.endCharge();

        return true;
    }

    /**
     * Starts melee attack animation only.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void startMeleeAttackAnimation() {

        // Existing logic you already have (setting attacking flag, index, ticks, etc.)
        this.setAttacking(true);

        // IMPORTANT: controller should only reset once per attack start
        this.attackAnimationJustStarted = true;

        this.advanceAttackIndex();
        this.triggerAttackAnimation();
    }

    /**
     * Applies melee hit damage.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void applyMeleeHitDamage(Player target) {
        target.hurt(this.damageSources().mobAttack(this), MELEE_DAMAGE);
    }

    /**
     * Charge movement goal while in CHARGE mode.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class ChargeMovementGoal extends Goal {

        private final BodyguardEntity guard;

        /**
         * Constructs charge goal.
         *
         * @param guard BodyguardEntity guard - Guard running the goal.
         * Version: 1.0.0
         * Comments:
         */
        public ChargeMovementGoal(BodyguardEntity guard) {
            this.guard = guard;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /**
         * Determines whether the goal can run.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return this.guard.mode == GuardMode.CHARGE && this.guard.getTarget() instanceof Player;
        }

        /**
         * Continues while charge conditions hold.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        /**
         * Tick handler for charge movement.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void tick() {

            if (!(this.guard.getTarget() instanceof Player target)) return;

            this.guard.chargeTicksLeft--;
            if (this.guard.chargeTicksLeft <= 0) {
                this.guard.endCharge();
                return;
            }

            if (this.guard.tryApplyChargeImpact(target)) {
                return;
            }

            this.guard.chargeSteerCooldown--;
            if (this.guard.chargeSteerCooldown <= 0) {

                this.guard.chargeSteerCooldown = CHARGE_STEER_UPDATE_EVERY_TICKS;

                Vec3 desired = target.position().subtract(this.guard.position());
                if (desired.lengthSqr() > 0.0001) {
                    desired = desired.normalize();
                    this.guard.chargeDir = this.guard.chargeDir.lerp(desired, CHARGE_TURN_LERP).normalize();
                }
            }

            Vec3 ahead = this.guard.position().add(this.guard.chargeDir.scale(10.0));
            this.guard.getNavigation().moveTo(ahead.x, ahead.y, ahead.z, 1.25D);
            this.guard.getLookControl().setLookAt(ahead.x, ahead.y + 1.0, ahead.z);
        }

        /**
         * Cleanup if charge ends unexpectedly.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void stop() {
            if (this.guard.isCharging()) {
                this.guard.endCharge();
            }
        }
    }

    /**
     * Melee chase + delayed-hit attack goal while in MELEE mode.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class MeleeChaseAndAttackGoal extends Goal {

        private final BodyguardEntity guard;

        private int attackCooldown = 0;
        private int pendingHitTicksLeft = 0;
        private Player pendingHitTarget = null;

        /**
         * Constructs melee goal.
         *
         * @param guard BodyguardEntity guard - Guard running the goal.
         * Version: 1.0.0
         * Comments:
         */
        public MeleeChaseAndAttackGoal(BodyguardEntity guard) {
            this.guard = guard;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /**
         * Determines whether melee can run.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return this.guard.mode == GuardMode.MELEE && this.guard.getTarget() instanceof Player;
        }

        /**
         * Continues while melee conditions hold.
         *
         * IMPORTANT FIX:
         * - Do NOT require getOwningSummoner() != null here, because temporary lookup failures
         *   can prematurely end the melee goal and interrupt attacks.
         *
         * @return boolean - True while mode is MELEE and target is a player.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }


        /**
         * Initializes timers when melee starts.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void start() {
            this.attackCooldown = 0;
            this.pendingHitTicksLeft = 0;
            this.pendingHitTarget = null;
        }

        /**
         * Tick handler for melee chase and delayed hit timing.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void tick() {

            if (!(this.guard.getTarget() instanceof Player target)) return;

//            if(this.guard.mode == GuardMode.MELEE)
//            {
//                System.out.println("guardmode MELEE");
//            }else
//            {
//                System.out.println("guardmode NOT MELEE");
//            }

            if (this.pendingHitTicksLeft > 0) {

                //this.guard.getNavigation().stop();
                this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);

                this.pendingHitTicksLeft--;

                if (this.pendingHitTicksLeft <= 0) {

                    this.pendingHitTicksLeft = 0;

                    if (this.pendingHitTarget != null && this.pendingHitTarget.isAlive()) {

                        double reachSqr = MELEE_REACH * MELEE_REACH;

                        if (this.guard.distanceToSqr(this.pendingHitTarget) <= reachSqr) {
                            this.guard.applyMeleeHitDamage(this.pendingHitTarget);
                        }
                    }

                    this.pendingHitTarget = null;
                    this.attackCooldown = MELEE_ATTACK_INTERVAL_TICKS;
                }

                System.out.println("attacking");
                return;
            }

            this.guard.getNavigation().moveTo(target, 1.15D);
            this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);

            if (this.attackCooldown > 0) {
                this.attackCooldown--;
            }

            double reachSqr = MELEE_REACH * MELEE_REACH;
            if (this.attackCooldown <= 0 && this.guard.distanceToSqr(target) <= reachSqr) {

                //this.guard.getNavigation().stop();
                this.guard.startMeleeAttackAnimation();

                this.pendingHitTarget = target;
                this.pendingHitTicksLeft = MELEE_HIT_DELAY_TICKS;
            }
        }

        /**
         * Cleanup on melee stop.
         *
         * IMPORTANT FIX:
         * - Do NOT change mode here.
         *   Mode must remain MELEE unless recall triggers RETURN or other explicit state change occurs.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void stop() {

            // Stop navigation so we don't slide after combat ends
            //this.guard.getNavigation().stop();

            // Clear any pending delayed hit
            this.pendingHitTicksLeft = 0;
            this.pendingHitTarget = null;
        }

    }

    /**
     * Formation goal while in GUARD mode.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class StayBesideSummonerGoal extends Goal {

        private final BodyguardEntity guard;

        /**
         * Constructs formation goal.
         *
         * @param guard BodyguardEntity guard - Guard running the goal.
         * Version: 1.0.0
         * Comments:
         */
        public StayBesideSummonerGoal(BodyguardEntity guard) {
            this.guard = guard;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /**
         * Determines whether formation can run.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return this.guard.getOwningSummoner() != null && this.guard.mode == GuardMode.GUARD;
        }

        /**
         * Continues while formation conditions hold.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.guard.getOwningSummoner() != null && this.guard.mode == GuardMode.GUARD;
        }

        /**
         * Tick handler for formation movement.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void tick() {

            SummonerEntity summoner = this.guard.getOwningSummoner();
            if (summoner == null) return;

            Vec3 target = this.guard.getDesiredGuardPosition(summoner);

            double dist = this.guard.distanceToSqr(target.x, target.y, target.z);

            if (dist > (GUARD_STOP_DISTANCE * GUARD_STOP_DISTANCE)) {
                this.guard.getNavigation().moveTo(target.x, target.y, target.z, 1.15D);
            } else {
                this.guard.getNavigation().stop();
            }

            //this.guard.setYRot(summoner.getYRot());
            //this.guard.setYHeadRot(summoner.getYHeadRot());
        }
    }

    /**
     * Goal that forces the bodyguard to return to the summoner and ignore players.
     *
     * When formation is restored, the guard switches to GUARD mode.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class ReturnToSummonerGoal extends Goal {

        private final BodyguardEntity guard;

        /**
         * Constructs the return goal.
         *
         * @param guard BodyguardEntity guard - Guard running the goal.
         * Version: 1.0.0
         * Comments:
         */
        public ReturnToSummonerGoal(BodyguardEntity guard) {
            this.guard = guard;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /**
         * Determines whether the goal can run.
         *
         * @return boolean - True when mode is RETURN and summoner exists.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return this.guard.mode == GuardMode.RETURN && this.guard.getOwningSummoner() != null;
        }

        /**
         * Continues while return conditions hold.
         *
         * @return boolean - True while mode is RETURN and summoner exists.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        /**
         * Tick handler for returning to formation.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void tick() {

            SummonerEntity summoner = this.guard.getOwningSummoner();
            if (summoner == null) return;

            // Ignore player entirely: clear target and focus on summoner
            this.guard.setTarget(null);

            // Hard-stop any leftover attack animation flags while returning
            this.guard.setCharging(false);
            this.guard.setAttacking(false);

            Vec3 desired = this.guard.getDesiredGuardPosition(summoner);

            double distSqr = this.guard.distanceToSqr(desired.x, desired.y, desired.z);

            // Move back quickly until close enough
            if (distSqr > (GUARD_STOP_DISTANCE * GUARD_STOP_DISTANCE)) {
                this.guard.getNavigation().moveTo(desired.x, desired.y, desired.z, 1.35D);
            } else {
                // Formation restored: enter GUARD mode
                this.guard.getNavigation().stop();
                this.guard.setMode(GuardMode.GUARD);
                System.out.println("Setting mode to GUARD here");

                // Reset aggro memory so charge won't instantly fire unless Summoner triggers again
                this.guard.playerWasInAggroRadius = false;
            }

            // Face the same direction as summoner while returning
            //this.guard.setYRot(summoner.getYRot());
            //this.guard.setYHeadRot(summoner.getYHeadRot());
        }
    }

}
