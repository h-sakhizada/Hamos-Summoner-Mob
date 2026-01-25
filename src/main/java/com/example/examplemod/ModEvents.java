package com.example.examplemod;

import com.example.examplemod.entity.SummonerEntity;
import com.example.examplemod.registry.ModEntities;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ModEvents {

    @SubscribeEvent
    public void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.SUMMONER.get(), SummonerEntity.createAttributes().build());
    }
}
