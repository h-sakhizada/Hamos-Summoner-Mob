package com.example.examplemod.entity;

import net.minecraft.nbt.CompoundTag;
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

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Bodyguard entity that protects a Summoner.
 *
 * Core behavior:
 * - GUARD: stays beside the summoner in guard stance
 * - CHARGE: rushes the player with a short burst attack
 * - MELEE: continues normal chase + melee after charge
 * - RETURN: ignores player and runs back to the summoner
 *
 * Special behavior:
 * - If the summoner takes enough damage while the player is still within the 10-block radius,
 *   the guards immediately re-charge the player instead of just returning.
 * - If there is no owning summoner, the bodyguard becomes a permanent melee attacker.
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

    // NBT keys used to persist combat state across reloads
    private static final String NBT_MODE = "BodyguardMode";
    private static final String NBT_LAST_TARGET = "BodyguardLastTarget";

    // Side values stored in NBT (left/right relative to the summoner)
    private static final String SIDE_LEFT = "left";

    // Guard formation tuning
    private static final double GUARD_SIDE_OFFSET = 1.8;
    private static final double GUARD_READY_DISTANCE = 0.45;
    // Matches the summoning circle radius — a guard is "in formation" when it is
    // this close to the summoner and has finished navigating.
    private static final double FORMATION_RADIUS = 5.0;

    // Aggro / detection tuning
    private static final double AGGRO_RADIUS = 10.0;

    // Charge tuning
    private static final int CHARGE_DURATION_TICKS = 60;
    private static final double CHARGE_SPEED_MULTIPLIER = 1.4;
    private static final int CHARGE_STEER_UPDATE_EVERY_TICKS = 6;
    private static final double CHARGE_TURN_LERP = 0.10;

    // One-time impact damage tuning for charge
    private static final float CHARGE_IMPACT_DAMAGE = 20.0F;
    private static final double CHARGE_IMPACT_RANGE = 2.5;

    // Regular melee tuning
    private static final float MELEE_DAMAGE = 8.0F;
    private static final int MELEE_ATTACK_INTERVAL_TICKS = 20;
    private static final double MELEE_REACH = 2.75;

    // Melee animation timing
    private static final int MELEE_HIT_DELAY_TICKS = 3;

    // True for 1 tick after starting an attack so the controller can reset once
    private boolean attackAnimationJustStarted = false;

    // Movement modifier used during charge
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

    // Attack animation timing
    private int attackAnimTicksLeft = 0;

    // Shared token tracking
    private int lastSeenRecallToken = 0;
    private int lastSeenChargeToken = 0;

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
        this.entityData.define(DATA_MODE, GuardMode.GUARD.ordinal());
    }

    /**
     * Registers AI goals that define the entity’s behavior.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected void registerGoals() {

        // RETURN must override all combat behavior
        this.goalSelector.addGoal(0, new ReturnToSummonerGoal(this));

        // Charge movement while in CHARGE mode
        this.goalSelector.addGoal(1, new ChargeMovementGoal(this));

        // Melee chasing + attacking while in MELEE mode
        this.goalSelector.addGoal(2, new MeleeChaseAndAttackGoal(this));

        // Formation movement while in GUARD mode
        this.goalSelector.addGoal(3, new StayBesideSummonerGoal(this));

        // Visual awareness
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 10.0F));

        // Target players only during combat modes
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
                return (BodyguardEntity.this.mode == GuardMode.CHARGE || BodyguardEntity.this.mode == GuardMode.MELEE)
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
                return (BodyguardEntity.this.mode == GuardMode.CHARGE || BodyguardEntity.this.mode == GuardMode.MELEE)
                        && super.canContinueToUse();
            }
        });
    }

    /**
     * Registers GeckoLib animation controllers for this entity.
     *
     * @param controllers AnimatableManager.ControllerRegistrar controllers - Controller registry used by GeckoLib.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {

        AnimationController<BodyguardEntity> controller =
                new AnimationController<>(this, "controller", 5, state -> {

                    AnimationController<BodyguardEntity> c = state.getController();
                    RawAnimation target;

                    // Charging animation has highest priority
                    if (this.isCharging()) {
                        target = RawAnimation.begin().thenLoop("charging_animation");

                        // Attack animation window plays next
                    } else if (this.isAttacking()) {

                        String animName = switch (this.getAttackIndex()) {
                            case 1 -> "attacking_animation2";
                            case 2 -> "attacking_animation3";
                            default -> "attacking_animation1";
                        };

                        if (this.attackAnimationJustStarted) {
                            c.forceAnimationReset();
                            this.attackAnimationJustStarted = false;
                        }

                        target = RawAnimation.begin().then(animName, Animation.LoopType.PLAY_ONCE);

                        // Movement animation
                    } else if (state.isMoving()) {
                        target = RawAnimation.begin().thenLoop("walking_animation");

                        // Guarding animation only in actual GUARD mode
                    } else if (this.getSyncedMode() == GuardMode.GUARD) {
                        target = RawAnimation.begin().thenLoop("guarding_animation");

                        // Otherwise use idle when standing still in other modes
                    } else {
                        target = RawAnimation.begin().thenLoop("idle_animation");
                    }

                    if (c.getCurrentRawAnimation() == null || !c.getCurrentRawAnimation().equals(target)) {
                        c.setAnimation(target);
                    }

                    return PlayState.CONTINUE;
                });

        controller.setAnimation(RawAnimation.begin().thenLoop("idle_animation"));
        controllers.add(controller);
    }

    /**
     * Creates the attribute set for the BodyguardEntity.
     *
     * @return AttributeSupplier.Builder - Builder containing the entity's attributes to be registered.
     * Version: 1.0.0
     * Comments:
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 60.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.ATTACK_DAMAGE, MELEE_DAMAGE)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    /**
     * Prevents this entity from burning in sunlight.
     *
     * @return boolean - Always false to indicate the entity should not burn this tick.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected boolean isSunBurnTick() {
        return false;
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
     * Saves persistent bodyguard state to NBT so combat can resume after reload.
     *
     * @param tag CompoundTag tag - The NBT tag being written for this entity.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.putInt(NBT_MODE, this.getSyncedMode().ordinal());

        if (this.getTarget() != null) {
            tag.putUUID(NBT_LAST_TARGET, this.getTarget().getUUID());
        }
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

        if (tag.contains(NBT_MODE)) {
            int idx = tag.getInt(NBT_MODE);
            if (idx < 0 || idx >= GuardMode.values().length) {
                idx = GuardMode.GUARD.ordinal();
            }
            this.setMode(GuardMode.values()[idx]);
        }

        if (tag.hasUUID(NBT_LAST_TARGET) && (this.level() instanceof ServerLevel serverLevel)) {
            Entity e = serverLevel.getEntity(tag.getUUID(NBT_LAST_TARGET));
            if (e instanceof Player p) {
                this.setTarget(p);
            }
        }
    }

    /**
     * Main per-tick update for this entity.
     *
     * Updated behavior:
     * - If there is no owning Summoner, the bodyguard permanently enters melee mode
     *   and attacks the nearest player on its own.
     * - If it does have a Summoner, it continues to use shared recall/charge logic.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) return;

        SummonerEntity summoner = this.getOwningSummoner();

        // If there is no owning summoner, this guard becomes a permanent melee attacker
        if (summoner == null) {

            if (this.mode != GuardMode.MELEE) {
                this.setMode(GuardMode.MELEE);
            }

            if (!(this.getTarget() instanceof Player)) {
                Player nearest = this.findClosestPlayerToSelf();
                if (nearest != null) {
                    this.setTarget(nearest);
                }
            }

            this.tickAttackAnimationWindow();
            return;
        }

        // Shared recall event: either punish nearby attacker with a re-charge,
        // or return to the Summoner if no one is nearby
        this.tickRecallTokenSync();

        // Shared charge event: starts charge for both guards together when Summoner triggers it
        this.tickChargeTokenSync();

        // Re-arm charge only while properly formed in GUARD mode
        this.tickChargeRearmRules();

        // Reacquire target after reload if we are in combat mode
        this.tickReacquireTargetIfNeeded();

        // Attack animation window timer
        this.tickAttackAnimationWindow();
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

        if (idx < 0 || idx >= GuardMode.values().length) {
            idx = GuardMode.GUARD.ordinal();
        }

        return GuardMode.values()[idx];
    }

    /**
     * Returns whether this bodyguard is currently in GUARD mode.
     *
     * @return boolean - True if the guard is in GUARD mode.
     * Version: 1.0.0
     * Comments:
     */
    public boolean isGuardModeActive() {
        return this.getSyncedMode() == GuardMode.GUARD;
    }

    /**
     * Returns whether this bodyguard is currently in formation beside its owning summoner.
     *
     * @return boolean - True if the guard is in formation for its owner.
     * Version: 1.0.0
     * Comments:
     */
    public boolean isInFormationForOwner() {
        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return false;

        return this.isInFormation(summoner);
    }

    /**
     * Sets the guard's mode on the server AND syncs it to clients.
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
     * Checks the owning Summoner's recall token and reacts based on whether the player
     * is still inside the Summoner's 10-block protection radius.
     *
     * Updated behavior:
     * - If recall triggers AND a player is still within the Summoner's aggro radius,
     *   the guards immediately charge that player again and then continue melee.
     * - If recall triggers AND no player is within that radius,
     *   the guards return to the Summoner as normal.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickRecallTokenSync() {

        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return;

        int token = summoner.getBodyguardRecallToken();

        if (token == this.lastSeenRecallToken) return;

        this.lastSeenRecallToken = token;

        Player nearbyThreat = this.findClosestPlayerNearSummoner(summoner);

        if (nearbyThreat != null) {

            // If the attacker is still close to the Summoner, punish them immediately
            this.clearCombatState();
            this.chargeAvailable = false;
            this.playerWasInAggroRadius = true;
            this.startCharge(nearbyThreat);
            return;
        }

        // If no player is near the Summoner, return to formation normally
        this.clearCombatState();
        this.setMode(GuardMode.RETURN);

        this.chargeAvailable = false;
        this.playerWasInAggroRadius = false;
    }

    /**
     * Reads the Summoner's shared "charge token" and starts a charge when it changes.
     *
     * IMPORTANT:
     * - This only applies when the guard has an owning Summoner.
     * - Orphaned guards do not use shared charge logic; they stay in permanent melee mode instead.
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

        // Must be fully active
        if (!this.isFullyActive()) return;

        // Must be in GUARD mode
        if (this.mode != GuardMode.GUARD) return;

        // Must be in formation beside summoner
        if (!this.isInFormation(summoner)) return;

        // Must have charge available
        if (!this.chargeAvailable) return;

        Player target = this.findClosestPlayerNearSummoner(summoner);
        if (target == null) return;

        this.lastSeenChargeToken = token;
        this.startCharge(target);
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

        if (!this.isFullyActive()) return;
        if (this.mode != GuardMode.GUARD) return;
        if (!this.isInFormation(summoner)) return;

        Player p = this.findClosestPlayerNearSummoner(summoner);
        if (p == null) {
            this.chargeAvailable = true;
        }
    }

    /**
     * Reacquires a player target after reload if the guard is in a combat mode but has no target.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickReacquireTargetIfNeeded() {

        if (this.level().isClientSide) return;
        if (this.mode != GuardMode.MELEE && this.mode != GuardMode.CHARGE) return;
        if (this.getTarget() instanceof Player) return;

        SummonerEntity summoner = this.getOwningSummoner();
        if (summoner == null) return;

        Player target = this.findClosestPlayerNearSummoner(summoner);
        if (target != null) {
            this.setTarget(target);
        }
    }

    /**
     * Returns whether the bodyguard is currently charging.
     *
     * @return boolean - True if charging, otherwise false.
     * Version: 1.0.0
     * Comments:
     */
    public boolean isCharging() {
        return this.entityData.get(DATA_CHARGING);
    }

    /**
     * Sets the charging state and synchronizes it to clients.
     *
     * @param value boolean value - True to enable charging state, false to disable it.
     * Version: 1.0.0
     * Comments:
     */
    private void setCharging(boolean value) {
        this.entityData.set(DATA_CHARGING, value);
    }

    /**
     * Returns whether the bodyguard is currently playing an attack animation window.
     *
     * @return boolean - True if attacking animation flag is active.
     * Version: 1.0.0
     * Comments:
     */
    public boolean isAttacking() {
        return this.entityData.get(DATA_ATTACKING);
    }

    /**
     * Sets the attack animation window flag.
     *
     * @param value boolean value - True to enable attack animation, false to disable it.
     * Version: 1.0.0
     * Comments:
     */
    private void setAttacking(boolean value) {
        this.entityData.set(DATA_ATTACKING, value);
    }

    /**
     * Returns the current attack animation index (0..2).
     *
     * @return int - Current attack animation index.
     * Version: 1.0.0
     * Comments:
     */
    public int getAttackIndex() {
        return this.entityData.get(DATA_ATTACK_INDEX);
    }

    /**
     * Sets the current attack animation index.
     *
     * @param index int index - Attack animation index to use.
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
     * @return boolean - True if the guard is active.
     * Version: 1.0.0
     * Comments:
     */
    private boolean isFullyActive() {
        return !this.getTags().contains("summoned_rising");
    }

    /**
     * Attempts to locate and return the owning Summoner entity from stored NBT.
     *
     * @return SummonerEntity - The owner summoner if found, otherwise null.
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
     * @return boolean - True if the stored side is left.
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
     * This version keeps the formation at fixed world offsets beside the summoner.
     *
     * @param summoner SummonerEntity summoner - The owning summoner.
     * @return Vec3 - Desired formation position.
     * Version: 1.0.0
     * Comments:
     */
    private Vec3 getDesiredGuardPosition(SummonerEntity summoner) {

        double sideSign = this.isLeftSide() ? -1.0 : 1.0;

        double targetX = summoner.getX() + (GUARD_SIDE_OFFSET * sideSign);
        double targetY = summoner.getY();
        double targetZ = summoner.getZ();

        return new Vec3(targetX, targetY, targetZ);
    }

    /**
     * Returns whether the bodyguard is currently in formation beside the Summoner.
     *
     * @param summoner SummonerEntity summoner - The owning summoner.
     * @return boolean - True if the guard is close enough to its formation point.
     * Version: 1.0.0
     * Comments:
     */
    private boolean isInFormation(SummonerEntity summoner) {

        double distToSummoner = this.distanceToSqr(summoner);
        boolean withinRadius = distToSummoner <= (FORMATION_RADIUS * FORMATION_RADIUS);
        boolean notMoving = this.getNavigation().isDone();

        return withinRadius && notMoving;
    }

    /**
     * Finds the closest player within AGGRO_RADIUS of the summoner (true radius check).
     *
     * @param summoner SummonerEntity summoner - The summoner to scan around.
     * @return Player - Closest player found within radius, or null if none exist in range.
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
     * Finds the closest player to this bodyguard within its follow range.
     *
     * This is used when the bodyguard has no owning summoner, so it can operate
     * in permanent melee mode on its own.
     *
     * @return Player - The closest player found, or null if none are nearby.
     * Version: 1.0.0
     * Comments:
     */
    private Player findClosestPlayerToSelf() {

        double range = 32.0D;

        if (this.getAttribute(Attributes.FOLLOW_RANGE) != null) {
            range = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        }

        AABB box = this.getBoundingBox().inflate(range);
        List<Player> players = this.level().getEntitiesOfClass(Player.class, box);
        if (players.isEmpty()) return null;

        Player closest = null;
        double bestDist = Double.MAX_VALUE;

        for (Player p : players) {
            double d = this.distanceToSqr(p);
            if (d < bestDist) {
                bestDist = d;
                closest = p;
            }
        }

        return closest;
    }

    /**
     * Starts a charge toward a target.
     *
     * @param player Player player - The target player being charged.
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

        // Always go into melee after a charge
        this.setMode(GuardMode.MELEE);
    }

    /**
     * Applies one-time charge impact damage.
     *
     * @param target Player target - Player being hit.
     * @return boolean - True if impact damage was applied this tick.
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

        this.setAttacking(true);
        this.attackAnimationJustStarted = true;

        this.advanceAttackIndex();
        this.triggerAttackAnimation();
    }

    /**
     * Applies melee hit damage.
     *
     * @param target Player target - Player being damaged.
     * Version: 1.0.0
     * Comments:
     */
    private void applyMeleeHitDamage(Player target) {
        target.hurt(this.damageSources().mobAttack(this), MELEE_DAMAGE);
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

        this.setTarget(null);
        this.getNavigation().stop();

        this.setCharging(false);
        this.chargeTicksLeft = 0;
        this.chargeSteerCooldown = 0;
        this.chargeImpactApplied = false;

        var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null && speedAttr.hasModifier(CHARGE_SPEED_MOD)) {
            speedAttr.removeModifier(CHARGE_SPEED_MOD);
        }

        this.setAttacking(false);
        this.attackAnimTicksLeft = 0;
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
         * @return boolean - True while in CHARGE mode with a player target.
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
         * @return boolean - True while charge remains valid.
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
            this.guard.getLookControl().setLookAt(ahead.x, ahead.y + 1.0D, ahead.z);
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
         * @return boolean - True while in MELEE mode with a player target.
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

            if (this.pendingHitTicksLeft > 0) {

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

                return;
            }

            this.guard.getNavigation().moveTo(target, 1.15D);
            this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);

            if (this.attackCooldown > 0) {
                this.attackCooldown--;
            }

            double reachSqr = MELEE_REACH * MELEE_REACH;
            if (this.attackCooldown <= 0 && this.guard.distanceToSqr(target) <= reachSqr) {

                this.guard.startMeleeAttackAnimation();

                this.pendingHitTarget = target;
                this.pendingHitTicksLeft = MELEE_HIT_DELAY_TICKS;
            }
        }

        /**
         * Cleanup on melee stop.
         *
         * IMPORTANT:
         * - Do NOT change mode here.
         * - Mode must remain MELEE unless recall triggers RETURN or another explicit state change occurs.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void stop() {
            this.pendingHitTicksLeft = 0;
            this.pendingHitTarget = null;
        }
    }

    /**
     * Formation goal while in GUARD mode.
     *
     * The guard will not stop moving until isInFormation() returns true.
     * moveTo is only re-issued when the navigator finishes (completed or failed)
     * or when the summoner has moved far enough to change the target, so the
     * path is not reset every tick and can actually complete.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class StayBesideSummonerGoal extends Goal {

        private final BodyguardEntity guard;

        // Re-issue moveTo when the formation target shifts at least this far (0.5 blocks)
        private static final double REPATH_MOVE_THRESHOLD_SQR = 0.25;

        // Last formation position we issued a moveTo for
        private Vec3 lastTargetPos = null;

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
         * @return boolean - True while in GUARD mode with an owner.`
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
         * @return boolean - True while in GUARD mode with an owner.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.guard.getOwningSummoner() != null && this.guard.mode == GuardMode.GUARD;
        }

        /**
         * Resets navigation state when the goal becomes active.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void start() {
            this.lastTargetPos = null;
        }

        /**
         * Tick handler for formation movement.
         *
         * Only issues moveTo when the navigator is done or the target moved —
         * this lets each path complete instead of being reset every tick.
         * The guard never stops moving until isInFormation() is satisfied.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void tick() {

            SummonerEntity summoner = this.guard.getOwningSummoner();
            if (summoner == null) return;

            Vec3 target = this.guard.getDesiredGuardPosition(summoner);

            if (this.guard.isInFormation(summoner)) {
                // Reached formation — stop and record position
                this.guard.getNavigation().stop();
                this.lastTargetPos = target;
            } else {
                // Only repath when the navigator has finished (completed or failed)
                // OR when the summoner moved far enough that the target changed
                boolean navDone = this.guard.getNavigation().isDone();
                boolean targetMoved = this.lastTargetPos == null
                        || this.lastTargetPos.distanceToSqr(target) > REPATH_MOVE_THRESHOLD_SQR;

                if (navDone || targetMoved) {
                    this.guard.getNavigation().moveTo(target.x, target.y, target.z, 1.15D);
                    this.lastTargetPos = target;
                }
            }

            this.guard.setYRot(summoner.getYRot());
            this.guard.setYHeadRot(summoner.getYHeadRot());
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

            // Ignore player entirely while returning
            this.guard.setTarget(null);

            // Clear any leftover combat animation flags
            this.guard.setCharging(false);
            this.guard.setAttacking(false);

            // Switch to GUARD as soon as navigation finishes and guard is back in formation.
            // Uses isInFormationForOwner() (5-block radius)
            if (this.guard.isInFormationForOwner()) {
                this.guard.getNavigation().stop();
                this.guard.setMode(GuardMode.GUARD);
                this.guard.playerWasInAggroRadius = false;
                return;
            }

            // Only issue moveTo when the current path has finished (succeeded or failed).
            // Re-issuing it every tick was resetting the path before it could complete.
            if (this.guard.getNavigation().isDone()) {
                Vec3 desired = this.guard.getDesiredGuardPosition(summoner);
                this.guard.getNavigation().moveTo(desired.x, desired.y, desired.z, 1.35D);
            }
        }
    }
}