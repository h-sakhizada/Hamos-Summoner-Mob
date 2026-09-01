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

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * Summoner entity that performs a ritual cast and summons minions.
 *
 * Key behaviors:
 * - Casting state is synced to clients for animation selection.
 * - During casting, movement is rooted.
 * - Spawns a summoning circle + mist particles.
 * - Spawns 6 rising minions; 2 are bodyguards and 4 are zombies.
 * - Tracks damage taken centrally and emits a recall token when threshold is reached so all bodyguards return.
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
    private static final int RESPAWN_DELAY_TICKS = 20000;

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

    // --------------------
    // Force field tuning
    // --------------------

    // Force field size (5 block diameter = 2.5 radius)
    private static final double FORCEFIELD_RADIUS = 5D;

    // Vertical offset so the dome starts slightly above the ground
    private static final double FORCEFIELD_BASE_Y_OFFSET = 0.05D;

    // Particle density controls (increase for denser dome)
    private static final int FORCEFIELD_LATITUDE_STEPS = 10;
    private static final int FORCEFIELD_LONGITUDE_STEPS = 28;

    // Particle appearance controls
    private static final float FORCEFIELD_PARTICLE_SIZE = 1.0F;
    private static final Vector3f FORCEFIELD_PARTICLE_COLOR = new Vector3f(1.0F, 1.0F, 1.0F);

    // How often the dome particles refresh (1 = every tick, 2 = every other tick, etc.)
    private static final int FORCEFIELD_PARTICLE_REFRESH_TICKS = 2;

    // Movement threshold used to decide whether the summoner is "standing still"
    private static final double FORCEFIELD_STILL_SPEED_THRESHOLD = 0.0025D;

    // How far away the summoner can "attack" from (standing still once in range)
    private static final double SUMMONER_ATTACK_RANGE = 24.0;
    // Extra buffer so it doesn't micro-move when you're near the boundary
    private static final double SUMMONER_ATTACK_RANGE_BUFFER = 6.0;
    // How far away the summoner will pursue a target (used by FOLLOW_RANGE attribute)
    private static final double SUMMONER_PURSUE_RANGE = 40.0;
    // Navigation speed while closing distance
    private static final double SUMMONER_APPROACH_SPEED = 1.05D;
    // If LOS breaks, how long (ticks) we keep trying before forcing a pathing refresh behavior
    private static final int SUMMONER_LOS_FORGET_TICKS = 40;

    // How often we allow a full path recalculation while approaching (prevents jittery micro-steps)
    private static final int SUMMONER_REPATH_INTERVAL_TICKS = 10;

    // How far the target must move (in blocks) before we force a repath sooner
    private static final double SUMMONER_REPATH_TARGET_MOVE_THRESHOLD = 2.0;


    // Shared bodyguard recall tuning (10 hearts = 20 damage)
    private static final float BODYGUARD_RECALL_DAMAGE_THRESHOLD = 20.0F;

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

    // Centralized damage accumulation for triggering bodyguard recall
    private float recallDamageAccumulated = 0.0F;

    // Token increments whenever the recall threshold is reached (guards compare last-seen token)
    private int bodyguardRecallToken = 0;

    // Shared charge token increments whenever a player enters the aggro radius
    private int bodyguardChargeToken = 0;

    // Tracks whether a player was previously in the summoner aggro radius
    private boolean playerWasInAggroRadius = false;


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
        //this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 10.0F));

        // Faster target refresh so getTarget() doesn't "drop" for a second
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(
                this,
                Player.class,
                1,      // <--- check every tick (or try 2/3 if you want)
                true,   // mustSee
                false,  // mustReach
                null
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

        // Runs the bodyguard force field when both guards are ready and the summoner is stationary
        this.tickBodyguardForceField();

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
     * Returns the current bodyguard recall token.
     *
     * Bodyguards compare this token to their last-seen value; if it changes, they return to guard stance.
     *
     * @return int - The current recall token value.
     * Version: 1.0.0
     * Comments:
     */
    public int getBodyguardRecallToken() {
        return this.bodyguardRecallToken;
    }

    /**
     * Applies damage immunity during the first-ever summon cast.
     *
     * Also centralizes damage tracking after damage is actually applied so bodyguards can be recalled reliably.
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

        // Accumulate damage centrally and emit a recall token when threshold is reached
        this.recallDamageAccumulated += amount;

        if (this.recallDamageAccumulated >= BODYGUARD_RECALL_DAMAGE_THRESHOLD) {
            this.recallDamageAccumulated = 0.0F;
            this.bodyguardRecallToken++;
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
     * - 3 zombies
     * - 2 bodyguards
     * - 1 stalker
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

            // Choose which indices become bodyguards (2 of 6) and stalker (1 of 6)
            boolean isStalker = (i == 0);
            boolean isBodyguard = (i == 2 || i == 4);

            Zombie mob;
            if (isStalker) {
                mob = ModEntities.STALKER.get().create(serverLevel);
            } else if (isBodyguard) {
                mob = ModEntities.BODYGUARD.get().create(serverLevel);
            } else {
                mob = EntityType.ZOMBIE.create(serverLevel);
            }

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
     * Runs the bodyguard force field logic for this tick.
     *
     * The force field activates only when:
     * - The summoner is not casting
     * - The summoner is standing still
     * - Both bodyguards exist
     * - Both bodyguards are in GUARD mode
     * - Both bodyguards are in formation
     *
     * When active:
     * - A half-sphere particle dome is rendered from the summoner
     * - Projectiles inside the dome have their velocity set to zero
     *
     * Version: 1.0.0
     * Comments:
     */
    private void tickBodyguardForceField() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;
        if (!this.shouldActivateBodyguardForceField(serverLevel)) return;

        // Refresh dome particles at the configured interval
        if (this.tickCount % FORCEFIELD_PARTICLE_REFRESH_TICKS == 0) {
            this.spawnBodyguardForceFieldParticles(serverLevel);
        }

        // Stop all projectiles inside the dome
        this.stopProjectilesInsideForceField(serverLevel);
    }

    /**
     * Returns whether the bodyguard force field should currently be active.
     *
     * @param serverLevel ServerLevel serverLevel - The current server world.
     * @return boolean - True if the shield should be active this tick.
     * Version: 1.0.0
     * Comments:
     */
    private boolean shouldActivateBodyguardForceField(ServerLevel serverLevel) {

        // Do not overlap with casting visuals/behavior
        if (this.isCasting()) return false;

        // Summoner must be standing still
        if (this.isMovingForForceField()) return false;

        int readyBodyguards = 0;

        // Check all currently tracked summoned entities
        for (UUID id : this.summonedIds) {
            Entity entity = serverLevel.getEntity(id);

            if (entity instanceof BodyguardEntity bodyguard) {

                // Count only bodyguards that are guarding and in formation
                if (bodyguard.isGuardModeActive() && bodyguard.isInFormationForOwner()) {

                    readyBodyguards++;
                }
            }
        }
        // Require both bodyguards
        return readyBodyguards >= 2;
    }

    /**
     * Returns whether the summoner is moving enough that the force field should be disabled.
     *
     * IMPORTANT FIX:
     * - Do NOT rely on Navigation#isDone() here, because the summoner can remain visually still
     *   while the navigation path is still considered active after combat movement.
     * - Use actual horizontal movement only.
     *
     * @return boolean - True if the summoner is currently moving.
     * Version: 1.0.0
     * Comments:
     */
    private boolean isMovingForForceField() {

        // Horizontal velocity only
        Vec3 motion = this.getDeltaMovement();
        double horizontalSpeedSqr = (motion.x * motion.x) + (motion.z * motion.z);

        // Also check actual position change from last tick for safety
        double dx = this.getX() - this.xo;
        double dz = this.getZ() - this.zo;
        double movedHorizontallySqr = (dx * dx) + (dz * dz);

        return horizontalSpeedSqr > FORCEFIELD_STILL_SPEED_THRESHOLD
                || movedHorizontallySqr > FORCEFIELD_STILL_SPEED_THRESHOLD;
    }

    /**
     * Spawns the half-sphere force field particle dome centered on the summoner.
     *
     * The dome is a white hemisphere with configurable density and particle size.
     *
     * @param serverLevel ServerLevel serverLevel - The current server world.
     * Version: 1.0.0
     * Comments:
     */
    private void spawnBodyguardForceFieldParticles(ServerLevel serverLevel) {

        double centerX = this.getX();
        double centerY = this.getY() + FORCEFIELD_BASE_Y_OFFSET;
        double centerZ = this.getZ();

        DustParticleOptions particle = new DustParticleOptions(
                FORCEFIELD_PARTICLE_COLOR,
                FORCEFIELD_PARTICLE_SIZE
        );

        // Build a hemisphere using horizontal rings from base -> top
        for (int lat = 0; lat <= FORCEFIELD_LATITUDE_STEPS; lat++) {

            double phi = (Math.PI / 2.0D) * (lat / (double) FORCEFIELD_LATITUDE_STEPS);

            double ringRadius = FORCEFIELD_RADIUS * Math.cos(phi);
            double y = centerY + FORCEFIELD_RADIUS * Math.sin(phi);

            for (int lon = 0; lon < FORCEFIELD_LONGITUDE_STEPS; lon++) {

                double theta = (Math.PI * 2.0D) * (lon / (double) FORCEFIELD_LONGITUDE_STEPS);

                double x = centerX + ringRadius * Math.cos(theta);
                double z = centerZ + ringRadius * Math.sin(theta);

                serverLevel.sendParticles(
                        particle,
                        x, y, z,
                        1,
                        0.0D, 0.0D, 0.0D,
                        0.0D
                );
            }
        }
    }

    /**
     * Deflects all projectile velocity inside the active force field dome.
     *
     * Updated behavior:
     * - Projectiles bounce away from the shield surface instead of freezing.
     * - A small upward push is added so they arc/fall to the ground more naturally.
     * - Speed is reduced so the result feels more like a shield deflection than a full reflection.
     *
     * @param serverLevel ServerLevel serverLevel - The current server world.
     * Version: 1.0.0
     * Comments:
     */
    private void stopProjectilesInsideForceField(ServerLevel serverLevel) {

        double centerX = this.getX();
        double centerY = this.getY() + FORCEFIELD_BASE_Y_OFFSET;
        double centerZ = this.getZ();

        // Broad-phase box around the hemisphere
        AABB searchBox = new AABB(
                centerX - FORCEFIELD_RADIUS,
                centerY,
                centerZ - FORCEFIELD_RADIUS,
                centerX + FORCEFIELD_RADIUS,
                centerY + FORCEFIELD_RADIUS,
                centerZ + FORCEFIELD_RADIUS
        );

        List<Projectile> projectiles = serverLevel.getEntitiesOfClass(Projectile.class, searchBox);

        for (Projectile projectile : projectiles) {

            // Skip invalid projectiles
            if (!projectile.isAlive()) continue;

            // Only affect projectiles that are actually inside the dome volume
            if (!this.isPointInsideForceFieldDome(
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getZ(),
                    centerX,
                    centerY,
                    centerZ
            )) {
                continue;
            }

            Vec3 currentVelocity = projectile.getDeltaMovement();

            // If the projectile is already nearly stationary, do not keep re-bouncing it
            if (currentVelocity.lengthSqr() < 0.0004D) continue;

            // Compute outward normal from dome center to projectile position
            Vec3 outward = new Vec3(
                    projectile.getX() - centerX,
                    projectile.getY() - centerY,
                    projectile.getZ() - centerZ
            );

            // Fallback if projectile is extremely close to the exact center
            if (outward.lengthSqr() < 0.0001D) {
                outward = new Vec3(0.0D, 1.0D, 0.0D);
            } else {
                outward = outward.normalize();
            }

            // Reflect current velocity across the shield normal
            double dot = currentVelocity.dot(outward);
            Vec3 reflected = currentVelocity.subtract(outward.scale(2.0D * dot));

            // Reduce overall speed so it feels like a shield deflection
            reflected = reflected.scale(0.45D);

            // Add a slight outward push so the projectile exits the shield cleanly
            reflected = reflected.add(outward.scale(0.20D));

            // Add a small upward lift so the projectile falls to the ground more naturally
            reflected = new Vec3(reflected.x, Math.max(reflected.y, 0.12D), reflected.z);

            // Apply the bounced velocity
            projectile.setDeltaMovement(reflected);

            // Force the client to update projectile motion immediately
            projectile.hurtMarked = true;
        }
    }

    /**
     * Returns whether a point lies inside the half-sphere force field volume.
     *
     * @param x double x - Point X position.
     * @param y double y - Point Y position.
     * @param z double z - Point Z position.
     * @param centerX double centerX - Dome center X.
     * @param centerY double centerY - Dome base center Y.
     * @param centerZ double centerZ - Dome center Z.
     * @return boolean - True if the point is inside the hemisphere.
     * Version: 1.0.0
     * Comments:
     */
    private boolean isPointInsideForceFieldDome(
            double x,
            double y,
            double z,
            double centerX,
            double centerY,
            double centerZ
    ) {

        // Hemisphere only exists above the base plane
        if (y < centerY) return false;

        double dx = x - centerX;
        double dy = y - centerY;
        double dz = z - centerZ;

        double distSqr = (dx * dx) + (dy * dy) + (dz * dz);
        double radiusSqr = FORCEFIELD_RADIUS * FORCEFIELD_RADIUS;

        return distSqr <= radiusSqr;
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

        // Throttles how often we recalculate a path (prevents jitter)
        private int repathCooldownTicks = 0;

        // Last target position used to decide if we should repath early
        private Vec3 lastTargetPos = Vec3.ZERO;

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
            this.repathCooldownTicks = 0;
            this.lastTargetPos = Vec3.ZERO;
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

            /**
             * Forces instant facing toward the target.
             *
             * IMPORTANT:
             * - Mob rotation packets can be throttled when standing still.
             * - Snapping yaw each tick keeps deltas large enough to send updates consistently.
             *
             * Version: 1.0.0
             * Comments:
             */
            double dx = target.getX() - this.summoner.getX();
            double dz = target.getZ() - this.summoner.getZ();

            // Convert direction -> yaw
            float desiredYaw = (float)(Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;

            // SNAP (no smoothing)
            this.summoner.setYRot(desiredYaw);
            this.summoner.setYHeadRot(desiredYaw);
            this.summoner.yBodyRot = desiredYaw;

            // Keep "old" rotation in sync to avoid renderer interpolation delay
            this.summoner.yRotO = desiredYaw;
            this.summoner.yHeadRotO = desiredYaw;
            this.summoner.yBodyRotO = desiredYaw;

            // LOS tracking similar to skeleton logic
            boolean canSee = this.summoner.getSensing().hasLineOfSight(target);
            if (canSee) {
                this.seeTime++;
            } else {
                this.seeTime--;
            }

            // Use squared distances to avoid sqrt jitter
            double distSqr = this.summoner.distanceToSqr(target);

            double stopRange = SUMMONER_ATTACK_RANGE;
            double resumeRange = SUMMONER_ATTACK_RANGE + SUMMONER_ATTACK_RANGE_BUFFER;

            double stopRangeSqr = stopRange * stopRange;
            double resumeRangeSqr = resumeRange * resumeRange;

            // If we are within attack range AND we can see the target reliably, stand still
            if (distSqr <= stopRangeSqr && this.seeTime >= 0) {
                this.summoner.getNavigation().stop();
                this.repathCooldownTicks = 0;
                return;
            }

            // Tick down repath cooldown
            if (this.repathCooldownTicks > 0) {
                this.repathCooldownTicks--;
            }

            // If LOS has been broken for a while, keep moving to regain it (but still throttle repaths)
            if (!canSee && this.seeTime < -SUMMONER_LOS_FORGET_TICKS) {

                boolean shouldRepath = false;

                // Repath if cooldown is done
                if (this.repathCooldownTicks <= 0) {
                    shouldRepath = true;
                }

                // Or if the target moved enough since last repath
                Vec3 nowPos = target.position();
                if (!shouldRepath && this.lastTargetPos != Vec3.ZERO) {
                    double movedSqr = nowPos.distanceToSqr(this.lastTargetPos);
                    double thresholdSqr = SUMMONER_REPATH_TARGET_MOVE_THRESHOLD * SUMMONER_REPATH_TARGET_MOVE_THRESHOLD;
                    if (movedSqr >= thresholdSqr) {
                        shouldRepath = true;
                    }
                }

                if (shouldRepath) {
                    this.summoner.getNavigation().moveTo(target, SUMMONER_APPROACH_SPEED);
                    this.repathCooldownTicks = SUMMONER_REPATH_INTERVAL_TICKS;
                    this.lastTargetPos = nowPos;
                }

                return;
            }

            // If outside resume range, approach target (throttled)
            if (distSqr >= resumeRangeSqr) {

                boolean shouldRepath = false;

                if (this.repathCooldownTicks <= 0) {
                    shouldRepath = true;
                }

                Vec3 nowPos = target.position();
                if (!shouldRepath && this.lastTargetPos != Vec3.ZERO) {
                    double movedSqr = nowPos.distanceToSqr(this.lastTargetPos);
                    double thresholdSqr = SUMMONER_REPATH_TARGET_MOVE_THRESHOLD * SUMMONER_REPATH_TARGET_MOVE_THRESHOLD;
                    if (movedSqr >= thresholdSqr) {
                        shouldRepath = true;
                    }
                }

                if (shouldRepath) {
                    this.summoner.getNavigation().moveTo(target, SUMMONER_APPROACH_SPEED);
                    this.repathCooldownTicks = SUMMONER_REPATH_INTERVAL_TICKS;
                    this.lastTargetPos = nowPos;
                }

            } else {
                // Inside the buffer zone: do nothing (prevents jitter)
                this.summoner.getNavigation().stop();
                this.repathCooldownTicks = 0;
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