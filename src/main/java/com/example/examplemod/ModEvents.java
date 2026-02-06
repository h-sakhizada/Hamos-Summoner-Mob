package com.example.examplemod;

import com.example.examplemod.entity.SummonerEntity;
import com.example.examplemod.registry.ModEntities;

import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Registers entity-related Forge events for this mod.
 *
 * Version: 1.0.0
 * Comments:
 */
public class ModEvents {

    /**
     * Registers attribute definitions for custom entities during mod initialization.
     *
     * @param event EntityAttributeCreationEvent event - Forge event used to assign attribute sets to entity types.
     * Version: 1.0.0
     * Comments:
     */
    @SubscribeEvent
    public void onEntityAttributeCreation(EntityAttributeCreationEvent event) {

        // Attach the SummonerEntity attribute set (health, movement speed, etc.) to its EntityType
        event.put(
                ModEntities.SUMMONER.get(),
                SummonerEntity.createAttributes().build()
        );
    }
}
