package com.example.examplemod.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;

import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;



public class SummonerEntity extends Zombie implements GeoEntity{

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // --- Summoning circle timing ---
    private static final int CIRCLE_DRAW_TICKS = 60;      // 3 seconds * 20 ticks
    private static final int CIRCLE_HOLD_TICKS = 100;     // 5 seconds * 20 ticks
    private static final int CIRCLE_TOTAL_TICKS = CIRCLE_DRAW_TICKS + CIRCLE_HOLD_TICKS;

    private int circleTicks = 0;
    private boolean circleActive = true; // starts automatically when entity exists

    private static final double CIRCLE_RADIUS = 5.0;

    // Density controls how “connected” the circle looks
    private static final int DRAW_POINTS_PER_TICK = 6;     // during draw phase
    private static final int HOLD_POINTS_PER_REFRESH = 80; // during hold refresh

    // Small spread so particles overlap and look continuous
    private static final double RING_SPREAD_XZ = 0.02;
    private static final double RING_SPREAD_Y  = 0.01;

    // Conduit “mist” above the circle
    private static final int CONDUIT_PARTICLES_PER_TICK = 3;
    private static final double CONDUIT_HEIGHT = 0.25;      // above ring
    private static final double CONDUIT_SPREAD_XZ = 0.10;   // across circle area
    private static final double CONDUIT_SPREAD_Y  = 0.20;



    public SummonerEntity(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    // ✅ No AI goals = does nothing
    @Override
    protected void registerGoals() {
        super.registerGoals();

        // Look at nearby players
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));

        // Random idle movement (so it animates when no player nearby)
        this.goalSelector.addGoal(3, new RandomStrollGoal(this, 0.8D));

        // Chase nearby players (NO attacking yet)
        this.goalSelector.addGoal(1, new MoveTowardsTargetGoal(this, 1.0D, 32.0F));

        // Tell the entity what it should target
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(
                this,
                Player.class,
                true
        ));
    }


    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {

        AnimationController<SummonerEntity> controller =
                new AnimationController<>(this, "controller", 5, state -> {

                    AnimationController<SummonerEntity> c = state.getController();

                    // choose which animation should be playing
                    RawAnimation target = state.isMoving()
                            ? RawAnimation.begin().thenLoop("walking_animation")
                            : RawAnimation.begin().thenLoop("idle_animation");

                    // only set if it's not already playing (prevents “stuck” / spam resets)
                    if (c.getCurrentRawAnimation() == null || !c.getCurrentRawAnimation().equals(target)) {
                        c.setAnimation(target);
                    }

                    return PlayState.CONTINUE;
                });

        // ensures something plays immediately when the world loads / entity already exists
        controller.setAnimation(RawAnimation.begin().thenLoop("idle_animation"));

        controllers.add(controller);
    }


    // ✅ Attributes: HP + no movement
    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)      // HP (adjust later)
                .add(Attributes.MOVEMENT_SPEED, 0.23D)   // stands still
                .add(Attributes.ATTACK_DAMAGE, 0.0D);   // not attacking yet
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }


    @Override
    public void tick() {
        super.tick();

        // Only spawn particles on the server (server sends them to clients)
        if (this.level().isClientSide) return;

        if (!circleActive) return;

        circleTicks++;

        spawnConduitMist();

        if (circleTicks <= CIRCLE_DRAW_TICKS) {
            // Draw the circle over 3 seconds (one point per tick)
            spawnCircleDrawAndMaintain(circleTicks);
        } else if (circleTicks <= CIRCLE_TOTAL_TICKS) {
            // Hold phase: keep the ring visible for 5 seconds
            // (spawn several points every few ticks)
            if (circleTicks % 4 == 0) {
                spawnCircleMaintenanceRing();
            }
        } else {
            // Done
            circleActive = false;
        }
    }

    private void spawnCircleDrawAndMaintain(int tick) {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        double centerX = this.getX();
        double centerY = this.getY() + 0.05;
        double centerZ = this.getZ();

        // Progress of the draw: 0 -> 1 over 60 ticks
        double endProgress = tick / (double) CIRCLE_DRAW_TICKS;

        // We’ll refresh the already-drawn arc densely so it looks connected.
        // Total points shown so far increases as the circle grows.
        int totalPointsSoFar = Math.max(8, (int) Math.round(endProgress * CIRCLE_DRAW_TICKS * DRAW_POINTS_PER_TICK));

        for (int i = 0; i <= totalPointsSoFar; i++) {
            double progress = (i / (double) totalPointsSoFar) * endProgress; // 0 -> endProgress
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



    private void spawnCircleMaintenanceRing() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        double centerX = this.getX();
        double centerY = this.getY() + 0.05;
        double centerZ = this.getZ();

        int points = HOLD_POINTS_PER_REFRESH;

        for (int i = 0; i < points; i++) {
            double angle = (2.0 * Math.PI) * (i / (double) points);

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

    private void spawnConduitMist() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        double centerX = this.getX();
        double centerY = this.getY() + 0.05 + CONDUIT_HEIGHT;
        double centerZ = this.getZ();

        // A gentle upward speed makes it feel like energy rising
        double upwardSpeed = 0.02;

        serverLevel.sendParticles(
                ParticleTypes.EFFECT,
                centerX, centerY, centerZ,
                CONDUIT_PARTICLES_PER_TICK,
                CONDUIT_SPREAD_XZ, CONDUIT_SPREAD_Y, CONDUIT_SPREAD_XZ,
                upwardSpeed
        );
    }




}
