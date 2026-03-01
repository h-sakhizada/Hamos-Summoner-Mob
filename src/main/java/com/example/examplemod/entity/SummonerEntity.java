package com.example.examplemod.entity;

import com.example.examplemod.registry.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Summoner entity that performs a ritual cast and summons minions.
 *
 * Key behaviors:
 * - Casting state is synced to clients for animation selection.
 * - During casting, movement is rooted.
 * - Spawns a summoning circle + mist particles.
 * - Spawns 6 rising minions; 2 are bodyguards and 4 are zombies.
 * - Tracks player damage centrally and emits a retaliate token when threshold is reached so all bodyguards charge the attacker.
 *
 * AI Behavior update:
 * - Summoner behaves like a "stand-still skeleton":
 *   - Moves only if out of preferred range OR line of sight is blocked.
 *   - Uses hysteresis + repath cooldown to avoid micro-movements.
 *
 * Version: 1.0.0
 * Comments:
 */
public class SummonerEntity extends Skeleton implements GeoEntity {

    // GeckoLib animation instance cache (stores per-entity animation state)
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Synced casting state used by client + server to keep animations consistent
    private static final EntityDataAccessor<Boolean> DATA_CASTING =
            SynchedEntityData.defineId(SummonerEntity.class, EntityDataSerializers.BOOLEAN);

    // Circle timing (draw phase + hold phase)
    private static final int CIRCLE_DRAW_TICKS = 60;
    private static final int CIRCLE_HOLD_TICKS = 100;
    private static final int CIRCLE_TOTAL_TICKS = CIRCLE_DRAW_TICKS + CIRCLE_HOLD_TICKS;

    // Circle appearance settings
    private static final double CIRCLE_RADIUS = 5.0;
    private static final int DRAW_POINTS_PER_TICK = 6;
    private static final int HOLD_POINTS_PER_REFRESH = 80;
    private static final double RING_SPREAD_XZ = 0.02;
    private static final double RING_SPREAD_Y = 0.01;

    // Floating “energy mist” settings above the circle
    private static final int CONDUIT_PARTICLES_PER_TICK = 3;
    private static final double CONDUIT_HEIGHT = 0.25;
    private static final double CONDUIT_SPREAD_XZ = 0.10;
    private static final double CONDUIT_SPREAD_Y = 0.20;

    // Rising logic settings for summoned mobs
    private static final double RISE_DEPTH = 2.0;

    // Respawn timing (10 seconds)
    private static final int RESPAWN_DELAY_TICKS = 2000;

    // Movement root modifier used to freeze horizontal movement while casting
    private static final UUID CAST_ROOT_UUID =
            UUID.fromString("b0c2a7d2-8a1d-4c4a-9a33-4e40b5c9b1a1");
    private static final AttributeModifier CAST_ROOT_MOD =
            new AttributeModifier(
                    CAST_ROOT_UUID,
                    "Summoner cast root",
                    -1.0,
                    AttributeModifier.Operation.MULTIPLY_TOTAL
            );

    // Summoner follow / spacing tuning (hysteresis style)
    private static final double FOLLOW_STOP_DISTANCE = 12.0;     // "in range" distance where summoner stops
    private static final double FOLLOW_RESUME_BUFFER = 5.0;      // must move 5 blocks farther to resume movement
    private static final double FOLLOW_START_DISTANCE = FOLLOW_STOP_DISTANCE + FOLLOW_RESUME_BUFFER; // 17 blocks
    private static final double TOO_CLOSE_DISTANCE = 8.0;        // optional back-off distance
    private static final double FOLLOW_SPEED = 1.05D;


    // How far away the summoner can "attack" from (standing still once in range)
    private static final double SUMMONER_ATTACK_RANGE = 24.0;
    // Extra buffer so it doesn't micro-move when you're near the boundary
    private static final double SUMMONER_ATTACK_RANGE_BUFFER = 3.0;
    // How far away the summoner will pursue a target (used by FOLLOW_RANGE attribute)
    private static final double SUMMONER_PURSUE_RANGE = 40.0;
    // Navigation speed while closing distance
    private static final double SUMMONER_APPROACH_SPEED = 1.05D;
    // If LOS breaks, how long (ticks) we keep trying before forcing a pathing refresh behavior
    private static final int SUMMONER_LOS_FORGET_TICKS = 40;


    // Shared bodyguard recall tuning (10 hearts = 20 damage)
    private static final float BODYGUARD_RETALIATE_DAMAGE_THRESHOLD = 20.0F;

    // Aggro radius for starting a synchronized bodyguard charge
    private static final double BODYGUARD_AGGRO_RADIUS = 10.0;

    // Server-side casting state (kept alongside synced DATA_CASTING)
    private boolean casting = false;

    // Tracks whether the initial “immune” summon has completed
    private boolean initialSummonCompleted = false;

    // Respawn countdown (-1 means no respawn is scheduled)
    private int respawnDelayTicksLeft = -1;

    // Tick counter for the current summoning sequence
    private int summonTick = 0;

    // UUIDs of currently summoned mobs (used for rising + enabling them)
    private final List<UUID> summonedIds = new ArrayList<>();

    // Centralized player damage accumulation for triggering bodyguard retaliate
    private float retaliateDamageAccumulated = 0.0F;

    // Token increments whenever the retaliate threshold is reached (guards compare last-seen token)
    private int bodyguardRetaliateToken = 0;

    // Shared charge token increments whenever a player enters the aggro radius
    private int bodyguardChargeToken = 0;

    // Tracks whether a player was previously in the summoner aggro radius
    private boolean playerWasInAggroRadius = false;

    // UUID of the player who last triggered the retaliate threshold
    private UUID retaliateAttackerUUID = null;

    /**
     * Constructs a new SummonerEntity instance.
     *
     * Initializes the entity using the provided EntityType and Level context.
     *
     * @param type EntityType<? extends Skeleton> type - The entity type registered for this summoner.
     * @param level Level level - The world/level the entity exists in.
     * Version: 1.0.0
     * Comments:
     */
    public SummonerEntity(EntityType<? extends Skeleton> type, Level level) {
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
     * Registers AI goals that define the entity’s behavior.
     *
     * Update:
     * - Uses vanilla Skeleton AI (line-of-sight pursuit + strafing behavior).
     * - We do NOT add custom movement goals here.
     * - The actual ranged attack is disabled in performRangedAttack().
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();

        // Approach target until within attack range, then stand still (NO strafing)
        this.goalSelector.addGoal(1, new StationaryRangedApproachGoal(this));

        // Only random stroll when no target exists
        this.goalSelector.addGoal(3, new RandomStrollOnlyWhenNoTargetGoal(this, 0.8D));

        // Visual awareness
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 10.0F));

        // Targets players
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(
                this,
                Player.class,
                true
        ));
    }

    /**
     * Registers GeckoLib animation controllers for this entity.
     *
     * Selects casting animation first, then walking if moving, otherwise idle.
     *
     * @param controllers AnimatableManager.ControllerRegistrar controllers - Controller registry used by GeckoLib.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {

        AnimationController<SummonerEntity> controller =
                new AnimationController<>(this, "controller", 5, state -> {

                    AnimationController<SummonerEntity> c = state.getController();
                    RawAnimation target;

                    if (this.isCasting()) {
                        c.forceAnimationReset();
                        target = RawAnimation.begin().thenLoop("summoning_cast_animation");
                    } else {
                        target = state.isMoving()
                                ? RawAnimation.begin().thenLoop("walking_animation")
                                : RawAnimation.begin().thenLoop("idle_animation");
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
     * Defines synchronized data fields for this entity.
     *
     * Registers DATA_CASTING so the casting state can be synchronized to clients.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();

        // Default casting state is false
        this.entityData.define(DATA_CASTING, false);
    }

    /**
     * Creates the attribute set for the SummonerEntity.
     *
     * Uses Skeleton base attributes and customizes health and movement speed.
     *
     * @return AttributeSupplier.Builder - Builder containing the entity's attributes to be registered.
     * Version: 1.0.0
     * Comments:
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Skeleton.createAttributes()
                .add(Attributes.MAX_HEALTH, 120.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.23D)
                .add(Attributes.ATTACK_DAMAGE, 0.0D)

                // Controls how far the Summoner will detect/pursue targets (affects targeting + chase)
                .add(Attributes.FOLLOW_RANGE, SUMMONER_PURSUE_RANGE);
    }

    /**
     * Disables the Skeleton's ranged attack while keeping skeleton movement behavior.
     *
     * The vanilla Skeleton AI will still try to perform ranged attacks,
     * but this override prevents any arrows/projectiles from being fired.
     *
     * @param target LivingEntity target - The target the skeleton would normally shoot.
     * @param distanceFactor float distanceFactor - Vanilla distance factor passed by AI.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        // Intentionally empty: keep skeleton combat movement, but do not shoot.
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
     * Returns the current bodyguard charge token.
     *
     * Bodyguards compare this token to their last-seen value; if it changes, they begin a charge.
     *
     * @return int - The current charge token value.
     * Version: 1.0.0
     * Comments:
     */
    public int getBodyguardChargeToken() {
        return this.bodyguardChargeToken;
    }

    /**
     * Main per-tick update for this entity.
     *
     * Handles rooting while casting, respawn countdown timing, and summoning phase progression.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) return;

        // Broadcast synchronized charge when a player enters summoner aggro radius
        this.tickBodyguardChargeBroadcast();

        this.updateCastRoot();
        this.tickRespawnCountdown();

        if (this.casting) {
            this.tickSummoning();
        }
    }

    /**
     * Returns whether the summoner is currently casting.
     *
     * @return boolean - True if casting is active, otherwise false.
     * Version: 1.0.0
     * Comments:
     */
    public boolean isCasting() {
        return this.entityData.get(DATA_CASTING);
    }

    /**
     * Updates the casting state and synchronizes it to clients.
     *
     * @param value boolean value - True to enable casting state, false to disable it.
     * Version: 1.0.0
     * Comments:
     */
    private void setCasting(boolean value) {
        this.entityData.set(DATA_CASTING, value);
        this.casting = value;
    }

    /**
     * Returns the current bodyguard retaliate token.
     *
     * Bodyguards compare this token to their last-seen value; if it changes, they charge the attacker.
     *
     * @return int - The current recall token value.
     * Version: 1.0.0
     * Comments:
     */
    public int getBodyguardRetaliateToken() {
        return this.bodyguardRetaliateToken;
    }

    /**
     * Returns the UUID of the player who last triggered the retaliate threshold.
     *
     * @return UUID - The attacker's UUID, or null if no attacker recorded.
     * Version: 1.0.0
     * Comments:
     */
    public UUID getRetaliateAttackerUUID() {
        return this.retaliateAttackerUUID;
    }

    /**
     * Applies damage immunity during the first-ever summon cast.
     *
     * Also centralizes player damage tracking and emits a retaliate token so bodyguards charge the attacker.
     *
     * @param source DamageSource source - The incoming damage source.
     * @param amount float amount - The incoming damage amount.
     * @return boolean - False if damage is blocked; otherwise delegates to parent damage handling.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {

        // Immunity ONLY during the initial summon cast
        if (this.casting && !this.initialSummonCompleted) {
            return false;
        }

        // Apply real damage first
        boolean applied = super.hurt(source, amount);
        if (!applied) return false;

        // Accumulate player damage and emit a retaliate token when threshold is reached
        if (source.getEntity() instanceof Player attackerPlayer) {
            this.retaliateDamageAccumulated += amount;

            if (this.retaliateDamageAccumulated >= BODYGUARD_RETALIATE_DAMAGE_THRESHOLD) {
                this.retaliateAttackerUUID = attackerPlayer.getUUID();
                this.retaliateDamageAccumulated = 0.0F;
                this.bodyguardRetaliateToken++;
            }
        }

        return true;
    }

    /**
     * Disables knockback during the first-ever summon cast.
     *
     * @param strength double strength - Knockback magnitude applied to the entity.
     * @param x double x - X direction component of knockback.
     * @param z double z - Z direction component of knockback.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void knockback(double strength, double x, double z) {
        if (this.casting && !this.initialSummonCompleted) {
            return;
        }

        super.knockback(strength, x, z);
    }

    /**
     * Runs additional server-side AI updates for this entity.
     *
     * Temporary testing trigger: summon once shortly after spawning.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void aiStep() {
        super.aiStep();

        if (this.level().isClientSide) return;

        if (!this.casting && this.tickCount == 40) {
            this.startSummoning();
        }
    }

    /**
     * Schedules a respawn summon to occur after 10 seconds.
     *
     * Version: 1.0.0
     * Comments:
     */
    public void scheduleRespawnIn10s() {
        if (this.respawnDelayTicksLeft >= 0) return;
        if (this.casting) return;

        this.respawnDelayTicksLeft = RESPAWN_DELAY_TICKS;
    }

    /**
     * Advances the respawn countdown and triggers summoning when it finishes.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickRespawnCountdown() {
        if (this.respawnDelayTicksLeft < 0) return;

        this.respawnDelayTicksLeft--;

        if (this.respawnDelayTicksLeft <= 0) {
            this.respawnDelayTicksLeft = -1;
            this.startSummoning();
        }
    }

    /**
     * Freezes horizontal movement while casting by applying a speed modifier.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void updateCastRoot() {
        var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr == null) return;

        if (this.isCasting()) {

            if (!speedAttr.hasModifier(CAST_ROOT_MOD)) {
                speedAttr.addTransientModifier(CAST_ROOT_MOD);
            }

            this.getNavigation().stop();
            this.setDeltaMovement(0, this.getDeltaMovement().y, 0);

        } else {

            if (speedAttr.hasModifier(CAST_ROOT_MOD)) {
                speedAttr.removeModifier(CAST_ROOT_MOD);
            }
        }
    }

    /**
     * Starts the summoning sequence and spawns 6 rising minions.
     *
     * Spawns:
     * - 4 zombies
     * - 2 bodyguards
     *
     * Version: 1.0.0
     * Comments:
     */
    private void startSummoning() {

        this.setCasting(true);
        this.summonTick = 0;
        this.summonedIds.clear();

        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        double radius = 2.5;

        for (int i = 0; i < 6; i++) {

            double angle = (Math.PI * 2.0) * (i / 6.0);
            double spawnX = this.getX() + Math.cos(angle) * radius;
            double spawnZ = this.getZ() + Math.sin(angle) * radius;

            double startY = this.getY() - RISE_DEPTH;
            double endY = this.getY();

            // Choose which indices become bodyguards (2 of 6)
            boolean isBodyguard = (i == 2 || i == 4);

            Zombie mob = isBodyguard
                    ? ModEntities.BODYGUARD.get().create(serverLevel)
                    : EntityType.ZOMBIE.create(serverLevel);

            if (mob == null) continue;

            mob.moveTo(spawnX, startY, spawnZ, this.getYRot(), 0);

            mob.setInvulnerable(true);
            mob.setNoAi(true);
            mob.setNoGravity(true);

            mob.addTag("summoner_minion");
            mob.addTag("summoned_rising");
            mob.getPersistentData().putUUID("SummonerOwner", this.getUUID());

            // Bodyguard side assignment for formation logic
            if (isBodyguard) {
                mob.getPersistentData().putString("SummonerSide", (i == 2) ? "left" : "right");
            }

            mob.getPersistentData().putDouble("riseStartY", startY);
            mob.getPersistentData().putDouble("riseEndY", endY);

            serverLevel.addFreshEntity(mob);
            this.summonedIds.add(mob.getUUID());
        }
    }

    /**
     * Advances the summoning sequence through circle drawing, rising, and activation.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickSummoning() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        this.summonTick++;

        this.spawnConduitMist();

        if (this.summonTick <= CIRCLE_DRAW_TICKS) {
            this.spawnCircleDrawAndMaintain(this.summonTick);
            return;
        }

        if (this.summonTick <= CIRCLE_TOTAL_TICKS) {

            if (this.summonTick % 2 == 0) {
                this.spawnCircleMaintenanceRing();
            }

            int riseTick = this.summonTick - CIRCLE_DRAW_TICKS;
            double progress = riseTick / (double) CIRCLE_HOLD_TICKS;

            for (UUID id : this.summonedIds) {
                Entity e = serverLevel.getEntity(id);
                if (e instanceof Zombie z) {

                    double startY = z.getPersistentData().getDouble("riseStartY");
                    double endY = z.getPersistentData().getDouble("riseEndY");
                    double y = startY + (endY - startY) * progress;

                    z.teleportTo(z.getX(), y, z.getZ());
                }
            }

            return;
        }

        for (UUID id : this.summonedIds) {
            Entity e = serverLevel.getEntity(id);
            if (e instanceof Zombie z) {

                z.setInvulnerable(false);
                z.setNoAi(false);
                z.setNoGravity(false);
                z.removeTag("summoned_rising");
            }
        }

        if (!this.initialSummonCompleted) {
            this.initialSummonCompleted = true;
        }

        this.setCasting(false);
        this.summonTick = 0;
    }

    /**
     * Detects "player entered radius" around the summoner and increments a shared charge token.
     *
     * This makes both bodyguards charge together, because they react to the same token change.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickBodyguardChargeBroadcast() {

        // Do not trigger charges while casting
        if (this.isCasting()) return;

        // Find any player within true radius
        Player p = this.level().getNearestPlayer(this, BODYGUARD_AGGRO_RADIUS);

        boolean anyInRadius = (p != null);
        boolean enteredRadius = anyInRadius && !this.playerWasInAggroRadius;

        this.playerWasInAggroRadius = anyInRadius;

        // On enter event, trigger a new charge signal
        if (enteredRadius) {
            this.bodyguardChargeToken++;
        }
    }

    /**
     * Draws and maintains the expanding summoning circle during the draw phase.
     *
     * @param tick int tick - Current draw tick in the draw phase.
     * Version: 1.0.0
     * Comments:
     */
    private void spawnCircleDrawAndMaintain(int tick) {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        double centerX = this.getX();
        double centerY = this.getY() + 0.05;
        double centerZ = this.getZ();

        double endProgress = tick / (double) CIRCLE_DRAW_TICKS;

        int totalPointsSoFar = Math.max(
                8,
                (int) Math.round(endProgress * CIRCLE_DRAW_TICKS * DRAW_POINTS_PER_TICK)
        );

        for (int i = 0; i <= totalPointsSoFar; i++) {

            double progress = (i / (double) totalPointsSoFar) * endProgress;
            double angle = (2.0 * Math.PI) * progress;

            double x = centerX + CIRCLE_RADIUS * Math.cos(angle);
            double z = centerZ + CIRCLE_RADIUS * Math.sin(angle);

            serverLevel.sendParticles(
                    ParticleTypes.DRAGON_BREATH,
                    x, centerY, z,
                    1,
                    RING_SPREAD_XZ, RING_SPREAD_Y, RING_SPREAD_XZ,
                    0.0
            );
        }
    }

    /**
     * Refreshes the full summoning circle during the hold phase.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void spawnCircleMaintenanceRing() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        double centerX = this.getX();
        double centerY = this.getY() + 0.05;
        double centerZ = this.getZ();

        for (int i = 0; i < HOLD_POINTS_PER_REFRESH; i++) {

            double angle = (2.0 * Math.PI) * (i / (double) HOLD_POINTS_PER_REFRESH);

            double x = centerX + CIRCLE_RADIUS * Math.cos(angle);
            double z = centerZ + CIRCLE_RADIUS * Math.sin(angle);

            serverLevel.sendParticles(
                    ParticleTypes.DRAGON_BREATH,
                    x, centerY, z,
                    1,
                    RING_SPREAD_XZ, RING_SPREAD_Y, RING_SPREAD_XZ,
                    0.0
            );
        }
    }

    /**
     * Spawns a floating energy mist above the summoning circle.
     *
     * Version: 1.0.0
     * Comments:
     */
    private void spawnConduitMist() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        serverLevel.sendParticles(
                ParticleTypes.EFFECT,
                this.getX(),
                this.getY() + 0.05 + CONDUIT_HEIGHT,
                this.getZ(),
                CONDUIT_PARTICLES_PER_TICK,
                CONDUIT_SPREAD_XZ,
                CONDUIT_SPREAD_Y,
                CONDUIT_SPREAD_XZ,
                0.02
        );
    }

    /**
     * Random stroll goal that only runs when the summoner has no target.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class RandomStrollOnlyWhenNoTargetGoal extends RandomStrollGoal {

        /**
         * Constructs a conditional random stroll goal.
         *
         * @param mob SummonerEntity mob - The summoner that will wander.
         * @param speed double speed - Wander speed.
         * Version: 1.0.0
         * Comments:
         */
        public RandomStrollOnlyWhenNoTargetGoal(SummonerEntity mob, double speed) {
            super(mob, speed);
        }

        /**
         * Determines whether this goal can run.
         *
         * @return boolean - True only when no target is set.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return this.mob.getTarget() == null && super.canUse();
        }

        /**
         * Determines whether this goal should continue running.
         *
         * @return boolean - True only while no target is set.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.mob.getTarget() == null && super.canContinueToUse();
        }
    }

    /**
     * Goal that makes the summoner keep a mostly-stationary ranged spacing behavior.
     *
     * Behavior:
     * - If target exists:
     *   - If line-of-sight is blocked, move to regain LoS (repath cooldown prevents micro jitter).
     *   - If LoS is clear:
     *     - If too far (> 17 blocks), move toward player.
     *     - If within 12 blocks, stop moving.
     *     - If too close (< 8 blocks), back away slightly.
     *
     * This produces skeleton-like positioning without constant micro movement.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class MaintainLineOfSightAndDistanceGoal extends Goal {

        // Owning summoner
        private final SummonerEntity summoner;

        // Limits how often we issue new path requests (prevents jitter)
        private int repathCooldownTicks = 0;

        /**
         * Constructs the maintain-LoS-and-distance goal.
         *
         * @param summoner SummonerEntity summoner - The summoner that will run this goal.
         * Version: 1.0.0
         * Comments:
         */
        public MaintainLineOfSightAndDistanceGoal(SummonerEntity summoner) {
            this.summoner = summoner;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /**
         * Determines whether this goal can run.
         *
         * @return boolean - True when a player target exists and summoner is not casting.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return !this.summoner.isCasting() && this.summoner.getTarget() instanceof Player;
        }

        /**
         * Determines whether this goal should continue.
         *
         * @return boolean - True while the target exists and summoner is not casting.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        /**
         * Initializes timers when the goal starts.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void start() {
            this.repathCooldownTicks = 0;
        }

        /**
         * Called every tick while the goal is active.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void tick() {

            if (!(this.summoner.getTarget() instanceof Player target)) return;

            // Decrement cooldown (prevents constant path recalculation)
            if (this.repathCooldownTicks > 0) {
                this.repathCooldownTicks--;
            }

            // Always face the player (even while standing still)
            this.summoner.getLookControl().setLookAt(target, 30.0F, 30.0F);

            double dist = this.summoner.distanceTo(target);

            // Line-of-sight gate: if LoS is blocked, move to regain it (but not every tick)
            boolean hasLos = this.summoner.getSensing().hasLineOfSight(target);
            if (!hasLos) {

                // Only issue a path request occasionally to avoid jitter
                if (this.repathCooldownTicks <= 0) {
                    this.summoner.getNavigation().moveTo(target, FOLLOW_SPEED);
                    this.repathCooldownTicks = 20; // ~1 second
                }

                return;
            }

            // Too close: back away slightly (rarely)
            if (dist < TOO_CLOSE_DISTANCE) {

                if (this.repathCooldownTicks <= 0) {
                    Vec3 away = this.summoner.position().subtract(target.position());

                    if (away.lengthSqr() > 0.0001) {
                        away = away.normalize();
                        Vec3 dest = this.summoner.position().add(away.scale(4.0));
                        this.summoner.getNavigation().moveTo(dest.x, dest.y, dest.z, FOLLOW_SPEED);
                    }

                    // Short cooldown so we don't oscillate
                    this.repathCooldownTicks = 10;
                }

                return;
            }

            // In preferred range: stop moving completely (no micro adjustments)
            if (dist <= FOLLOW_STOP_DISTANCE) {
                this.summoner.getNavigation().stop();
                return;
            }

            // Too far: only resume moving when beyond the start distance buffer
            if (dist >= FOLLOW_START_DISTANCE) {

                if (this.repathCooldownTicks <= 0) {
                    this.summoner.getNavigation().moveTo(target, FOLLOW_SPEED);
                    this.repathCooldownTicks = 20; // ~1 second
                }

                return;
            }

            // Buffer zone (between 12 and 17): stay still
            this.summoner.getNavigation().stop();
        }
    }

    /**
     * Goal that makes the summoner approach like a skeleton would, but stand still once in range.
     *
     * Differences vs vanilla Skeleton:
     * - No strafing left/right.
     * - When within attack range, navigation stops and the summoner holds position.
     * - Uses simple LOS memory so it can keep moving if vision breaks.
     *
     * Version: 1.0.0
     * Comments:
     */
    private static class StationaryRangedApproachGoal extends Goal {

        private final SummonerEntity summoner;

        // Tracks how long we've had LOS to the target
        private int seeTime = 0;

        /**
         * Constructs the goal.
         *
         * @param summoner SummonerEntity summoner - The summoner that will run this goal.
         * Version: 1.0.0
         * Comments:
         */
        public StationaryRangedApproachGoal(SummonerEntity summoner) {
            this.summoner = summoner;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        /**
         * Determines whether this goal can run.
         *
         * @return boolean - True when a player target exists and the summoner is not casting.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canUse() {
            return !this.summoner.isCasting() && this.summoner.getTarget() instanceof Player;
        }

        /**
         * Determines whether this goal should continue.
         *
         * @return boolean - True while the target exists and summoner is not casting.
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        /**
         * Resets LOS timer on start.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void start() {
            this.seeTime = 0;
        }

        /**
         * Tick handler for approach + stationary in-range behavior.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void tick() {

            if (!(this.summoner.getTarget() instanceof Player target)) return;

            // Always face the player
            this.summoner.getLookControl().setLookAt(target, 30.0F, 30.0F);

            // LOS tracking similar to skeleton logic
            boolean canSee = this.summoner.getSensing().hasLineOfSight(target);
            if (canSee) {
                this.seeTime++;
            } else {
                this.seeTime--;
            }

            // Distance checks (with buffer to avoid micro movement)
            double dist = this.summoner.distanceTo(target);
            double stopRange = SUMMONER_ATTACK_RANGE;
            double resumeRange = SUMMONER_ATTACK_RANGE + SUMMONER_ATTACK_RANGE_BUFFER;

            // If we are within attack range AND we can see the target reliably, stand still
            if (dist <= stopRange && this.seeTime >= 0) {
                this.summoner.getNavigation().stop();
                return;
            }

            // If LOS has been broken for a while, force the summoner to keep moving to regain it
            // (later we can improve this into a smarter LOS regain)
            if (!canSee && this.seeTime < -SUMMONER_LOS_FORGET_TICKS) {
                this.summoner.getNavigation().moveTo(target, SUMMONER_APPROACH_SPEED);
                return;
            }

            // If outside resume range, approach target
            if (dist >= resumeRange) {
                this.summoner.getNavigation().moveTo(target, SUMMONER_APPROACH_SPEED);
            } else {
                // Inside the buffer zone: do nothing (prevents jitter)
                this.summoner.getNavigation().stop();
            }
        }

        /**
         * Cleanup when the goal ends.
         *
         * Version: 1.0.0
         * Comments:
         */
        @Override
        public void stop() {
            this.summoner.getNavigation().stop();
            this.seeTime = 0;
        }
    }
}