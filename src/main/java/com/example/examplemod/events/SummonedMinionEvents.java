package com.example.examplemod.events;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.entity.SummonerEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * Handles Forge events related to the Summoner's spawned minions (death + rising knockback behavior).
 *
 * Version: 1.0.0
 * Comments:
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID)
public class SummonedMinionEvents {

    /**
     * Detects when a tagged summoned minion dies and notifies its owning Summoner to schedule a respawn.
     *
     * @param event LivingDeathEvent event - Forge event fired when a living entity dies.
     * Version: 1.0.0
     * Comments:
     */
    @SubscribeEvent
    public static void onMinionDeath(LivingDeathEvent event) {

        // The entity that just died
        LivingEntity dead = event.getEntity();

        // Only process entities that are tagged as summoner minions
        if (!dead.getTags().contains("summoner_minion")) return;

        // Server-only logic (entity lookups + respawn scheduling)
        if (!(dead.level() instanceof ServerLevel serverLevel)) return;

        // Ensure the minion has an owner UUID stored before trying to look up the summoner
        if (!dead.getPersistentData().hasUUID("SummonerOwner")) return;
        UUID ownerId = dead.getPersistentData().getUUID("SummonerOwner");

        // Find the owning summoner entity in the same server level
        Entity owner = serverLevel.getEntity(ownerId);
        if (owner instanceof SummonerEntity summoner) {

            // Ask the summoner to start its respawn timer (10 seconds) for re-summoning minions
            summoner.scheduleRespawnIn10s();
        }
    }

    /**
     * Cancels knockback on entities that are currently in the “rising from the ground” phase.
     *
     * @param event LivingKnockBackEvent event - Forge event fired when a living entity receives knockback.
     * Version: 1.0.0
     * Comments:
     */
    @SubscribeEvent
    public static void onRisingKnockback(LivingKnockBackEvent event) {

        // The entity receiving knockback
        LivingEntity e = event.getEntity();

        // While rising, block knockback completely so the “emerging” animation isn't disrupted
        if (e.getTags().contains("summoned_rising")) {
            event.setCanceled(true);
        }
    }
}
