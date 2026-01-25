package com.example.examplemod.registry;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.entity.SummonerEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ExampleMod.MODID);

    public static final RegistryObject<EntityType<SummonerEntity>> SUMMONER =
            ENTITY_TYPES.register("summoner",
                    () -> EntityType.Builder
                            .of(SummonerEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f) // human-sized hitbox
                            .build(new ResourceLocation(ExampleMod.MODID, "summoner").toString())
            );
}
