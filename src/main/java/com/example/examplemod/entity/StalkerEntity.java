package com.example.examplemod.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

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
 * Stalker entity summoned by the Summoner.
 *
 * Core behavior:
 * - Baby-zombie-sized melee attacker with high movement speed.
 * - Chases and attacks the nearest player on sight.
 * - Uses a 3-hit attack sequence (stalker_attack1/2/3_animation).
 * - No special mechanics — purely aggressive melee mob.
 *
 * Version: 1.0.0
 * Comments:
 */
public class StalkerEntity extends Zombie implements GeoEntity {

    // GeckoLib animation instance cache (stores per-entity animation state)
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Synced state for animation selection (server -> client)
    private static final EntityDataAccessor<Boolean> DATA_ATTACKING =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_INDEX =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_IN_COMBAT =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.BOOLEAN);

    // Persistent NBT keys used to link this stalker to its summoner
    private static final String NBT_OWNER_UUID = "SummonerOwner";

    // Regular melee tuning
    private static final float MELEE_DAMAGE = 5.0F;
    private static final int MELEE_ATTACK_INTERVAL_TICKS = 15;
    private static final double MELEE_REACH = 2.0;

    // Melee animation timing
    private static final int MELEE_HIT_DELAY_TICKS = 2;

    // True for 1 tick after starting an attack so the controller can reset once
    private boolean attackAnimationJustStarted = false;

    // Attack animation window timer
    private int attackAnimTicksLeft = 0;

    /**
     * Constructs a new StalkerEntity instance.
     *
     * @param type EntityType<? extends Zombie> type - The entity type registered for this stalker.
     * @param level Level level - The world/level the entity exists in.
     * Version: 1.0.0
     * Comments:
     */
    public StalkerEntity(EntityType<? extends Zombie> type, Level level) {
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

        this.entityData.define(DATA_ATTACKING, false);
        this.entityData.define(DATA_ATTACK_INDEX, 0);
        this.entityData.define(DATA_IN_COMBAT, false);
    }

    /**
     * Registers AI goals that define the entity's behavior.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected void registerGoals() {

        // Chase and melee attack with 3-hit combo
        this.goalSelector.addGoal(1, new StalkerMeleeGoal(this));

        // Wander when no target
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));

        // Visual awareness
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 10.0F));

        // Always target players
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                this, Player.class, true));
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

        AnimationController<StalkerEntity> controller =
                new AnimationController<>(this, "controller", 5, state -> {

                    AnimationController<StalkerEntity> c = state.getController();
                    RawAnimation target;

                    // Attack animation has highest priority
                    if (this.isAttacking()) {

                        String animName = switch (this.getAttackIndex()) {
                            case 1 -> "stalker_attack2_animation";
                            case 2 -> "stalker_attack3_animation";
                            default -> "stalker_attack1_animation";
                        };

                        if (this.attackAnimationJustStarted) {
                            c.forceAnimationReset();
                            this.attackAnimationJustStarted = false;
                        }

                        target = RawAnimation.begin().then(animName, Animation.LoopType.PLAY_ONCE);

                    // Running animation when chasing a target
                    } else if (state.isMoving() && this.isInCombat()) {
                        target = RawAnimation.begin().thenLoop("stalker_running_animation");

                    // Walking animation when wandering
                    } else if (state.isMoving()) {
                        target = RawAnimation.begin().thenLoop("stalker_walking_animation");

                    // Idle when standing still
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
     * Creates the attribute set for the StalkerEntity.
     *
     * Speed is set faster than a baby zombie (0.345) but slightly slower than a
     * sprint-jumping player (~0.38).
     *
     * @return AttributeSupplier.Builder - Builder containing the entity's attributes to be registered.
     * Version: 1.0.0
     * Comments:
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.36D)
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
     * Prevents the stalker from despawning due to distance.
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
     * Prevents the stalker from converting to a drowned when underwater.
     *
     * @return boolean - Always false to prevent conversion.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected boolean convertsInWater() {
        return false;
    }

    /**
     * Main per-tick update for this entity.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) return;

        // Sync combat state to client for animation selection
        boolean hasCombatTarget = (this.getTarget() instanceof Player);
        if (this.isInCombat() != hasCombatTarget) {
            this.entityData.set(DATA_IN_COMBAT, hasCombatTarget);
        }

        // If orphaned (no summoner), ensure we always have a target
        if (this.getOwningSummoner() == null && !(this.getTarget() instanceof Player)) {
            Player nearest = this.findClosestPlayerToSelf();
            if (nearest != null) {
                this.setTarget(nearest);
            }
        }

        // Attack animation window timer
        this.tickAttackAnimationWindow();
    }

    /**
     * Returns whether the stalker is currently playing an attack animation window.
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
     * Returns whether the stalker is currently in combat (has a target).
     *
     * @return boolean - True if in combat.
     * Version: 1.0.0
     * Comments:
     */
    public boolean isInCombat() {
        return this.entityData.get(DATA_IN_COMBAT);
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
        this.attackAnimTicksLeft = 7;
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
     * Finds the closest player to this stalker within its follow range.
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
     * Melee chase + delayed-hit attack goal for the stalker.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class StalkerMeleeGoal extends Goal {

        private final StalkerEntity stalker;

        private int attackCooldown = 0;
        private int pendingHitTicksLeft = 0;
        private Player pendingHitTarget = null;

        /**
         * Constructs melee goal.
         *
         * @param stalker StalkerEntity stalker - Stalker running the goal.
         * Version: 1.0.0
         * Comments:
         */
        public StalkerMeleeGoal(StalkerEntity stalker) {
            this.stalker = stalker;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /**
         * Determines whether melee can run.
         *
         * @return boolean - True while a player target exists.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return this.stalker.getTarget() instanceof Player;
        }

        /**
         * Continues while a player target exists.
         *
         * @return boolean - True while target is a player.
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

            if (!(this.stalker.getTarget() instanceof Player target)) return;

            if (this.pendingHitTicksLeft > 0) {

                this.stalker.getLookControl().setLookAt(target, 30.0F, 30.0F);
                this.pendingHitTicksLeft--;

                if (this.pendingHitTicksLeft <= 0) {

                    this.pendingHitTicksLeft = 0;

                    if (this.pendingHitTarget != null && this.pendingHitTarget.isAlive()) {

                        double reachSqr = MELEE_REACH * MELEE_REACH;

                        if (this.stalker.distanceToSqr(this.pendingHitTarget) <= reachSqr) {
                            this.stalker.applyMeleeHitDamage(this.pendingHitTarget);
                        }
                    }

                    this.pendingHitTarget = null;
                    this.attackCooldown = MELEE_ATTACK_INTERVAL_TICKS;
                }

                return;
            }

            this.stalker.getNavigation().moveTo(target, 1.0D);
            this.stalker.getLookControl().setLookAt(target, 30.0F, 30.0F);

            if (this.attackCooldown > 0) {
                this.attackCooldown--;
            }

            double reachSqr = MELEE_REACH * MELEE_REACH;
            if (this.attackCooldown <= 0 && this.stalker.distanceToSqr(target) <= reachSqr) {

                this.stalker.startMeleeAttackAnimation();

                this.pendingHitTarget = target;
                this.pendingHitTicksLeft = MELEE_HIT_DELAY_TICKS;
            }
        }

        /**
         * Cleanup on melee stop.
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
}