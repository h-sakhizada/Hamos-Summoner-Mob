package com.example.examplemod.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SummonerEntity extends Zombie implements GeoEntity {

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

    // Rising logic settings for summoned zombies
    private static final double RISE_DEPTH = 2.0;

    // Respawn timing (10 seconds)
    private static final int RESPAWN_DELAY_TICKS = 200;

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

    // Server-side casting state (kept alongside synced DATA_CASTING)
    private boolean casting = false;

    // Tracks whether the initial “immune” summon has completed
    private boolean initialSummonCompleted = false;

    // Respawn countdown (-1 means no respawn is scheduled)
    private int respawnDelayTicksLeft = -1;

    // Tick counter for the current summoning sequence
    private int summonTick = 0;

    // UUIDs of currently summoned zombies (used for rising + enabling them)
    private final List<UUID> summonedIds = new ArrayList<>();

    /**
     * Constructs a new SummonerEntity instance.
     *
     * Initializes the entity using the provided EntityType and Level context.
     *
     * @param type EntityType<? extends Zombie> type - The entity type registered for this summoner. (Used by Forge spawning)
     * @param level Level level - The world/level the entity exists in. (Used for server/client checks and spawning)
     * Version: 1.0.0
     * Comments:
     */
    public SummonerEntity(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    /**
     * Returns the GeckoLib animation cache for this entity.
     *
     * Provides GeckoLib the per-entity cache used to manage animations and controllers.
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
     * Adds basic goals such as looking at players, wandering, and moving toward targets.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();

        // Basic “awareness” behavior (visual tracking)
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));

        // Light wandering so you can visually test walking animation
        this.goalSelector.addGoal(3, new RandomStrollGoal(this, 0.8D));

        // Moves toward its chosen target (no attacking logic yet)
        this.goalSelector.addGoal(1, new MoveTowardsTargetGoal(this, 1.0D, 32.0F));

        // Defines what entity type this mob will target (players)
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
     * @param controllers AnimatableManager.ControllerRegistrar controllers - Controller registry used by GeckoLib to attach controllers.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {

        // Main controller that decides which animation should be active
        AnimationController<SummonerEntity> controller =
                new AnimationController<>(this, "controller", 5, state -> {

                    AnimationController<SummonerEntity> c = state.getController();
                    RawAnimation target;

                    // Casting has highest priority over movement animations
                    if (this.isCasting()) {
                        // Force a reset so the cast animation reliably starts when casting begins
                        c.forceAnimationReset();
                        target = RawAnimation.begin().thenLoop("summoning_cast_animation");
                    } else {
                        // Normal locomotion selection (walk vs idle)
                        target = state.isMoving()
                                ? RawAnimation.begin().thenLoop("walking_animation")
                                : RawAnimation.begin().thenLoop("idle_animation");
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
     * Uses Zombie base attributes and customizes health, movement speed, and disables attack damage.
     *
     * @return AttributeSupplier.Builder - Builder containing the entity's attributes to be registered.
     * Version: 1.0.0
     * Comments:
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.23D)
                .add(Attributes.ATTACK_DAMAGE, 0.0D);
    }

    /**
     * Prevents this entity from burning in sunlight.
     *
     * Overrides Zombie behavior so the summoner is not damaged by daytime sun.
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

        // Only execute logic on the server to avoid duplicate simulation
        if (this.level().isClientSide) return;

        // Applies/removes movement freeze when casting
        this.updateCastRoot();

        // Runs respawn timer even when not currently casting
        this.tickRespawnCountdown();

        // Runs summoning phases while actively casting
        if (this.casting) {
            this.tickSummoning();
        }
    }

    /**
     * Returns whether the summoner is currently casting.
     *
     * Reads the synced DATA_CASTING value to keep client/server animation state consistent.
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
     * Sets both the synced casting value and the server-side boolean field.
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
     * Applies damage immunity during the first-ever summon cast.
     *
     * Prevents taking damage only while casting AND only until the first summon completes.
     *
     * @param source DamageSource source - The incoming damage source (e.g., player, fire, explosion).
     * @param amount float amount - The incoming damage amount before armor/resistance adjustments.
     * @return boolean - False if damage is blocked; otherwise delegates to Zombie damage handling.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.casting && !this.initialSummonCompleted) {
            return false;
        }

        return super.hurt(source, amount);
    }

    /**
     * Disables knockback during the first-ever summon cast.
     *
     * Prevents knockback only while casting AND only until the first summon completes.
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
     * Currently triggers the first summon shortly after spawning for testing purposes.
     *
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public void aiStep() {
        super.aiStep();

        // Server-only logic (clients should not simulate AI)
        if (this.level().isClientSide) return;

        // Temporary testing trigger: summon once shortly after spawn
        if (!this.casting && this.tickCount == 40) {
            this.startSummoning();
        }
    }

    /**
     * Schedules a respawn summon to occur after 10 seconds.
     *
     * Prevents scheduling if a respawn is already queued or if the entity is currently casting.
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
     * When the timer reaches zero, summoning begins and the timer resets to "not scheduled".
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
     * Applies a negative movement modifier, stops navigation, and clears X/Z movement while casting.
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
     * Starts the summoning sequence and spawns six rising zombie minions.
     *
     * Sets casting state, resets timers, spawns zombies underground, and stores rise endpoints in NBT.
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

            Zombie z = EntityType.ZOMBIE.create(serverLevel);
            if (z == null) continue;

            z.moveTo(spawnX, startY, spawnZ, this.getYRot(), 0);

            z.setInvulnerable(true);
            z.setNoAi(true);
            z.setNoGravity(true);

            z.addTag("summoner_minion");
            z.addTag("summoned_rising");
            z.getPersistentData().putUUID("SummonerOwner", this.getUUID());

            z.getPersistentData().putDouble("riseStartY", startY);
            z.getPersistentData().putDouble("riseEndY", endY);

            serverLevel.addFreshEntity(z);
            this.summonedIds.add(z.getUUID());
        }
    }

    /**
     * Advances the summoning sequence through circle drawing, rising, and activation.
     *
     * Phase 1 draws the circle, Phase 2 maintains the ring and raises minions, Phase 3 enables minions and ends casting.
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
     * Draws and maintains the expanding summoning circle during the draw phase.
     *
     * Spawns particles along the arc from 0..current progress so the circle appears to be drawn over time.
     *
     * @param tick int tick - Current draw tick in the draw phase (1..CIRCLE_DRAW_TICKS).
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
     * Spawns particles all around the ring to keep it visible while minions rise.
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
     * Adds a subtle vertical particle effect to make the ritual feel more “active”.
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
}
