package com.example.examplemod.registry;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.entity.BodyguardEntity;
import com.example.examplemod.entity.SummonerEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Handles registration of all custom entity types for the mod.
 *
 * Version: 1.0.0
 * Comments:
 */
public class ModEntities {

    // Central deferred register used to safely register entity types during mod loading
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ExampleMod.MODID);

    // Registry entry for the Summoner entity type
    public static final RegistryObject<EntityType<SummonerEntity>> SUMMONER =
            ENTITY_TYPES.register("summoner",
                    () -> EntityType.Builder
                            // Binds the SummonerEntity constructor to this entity type
                            .of(SummonerEntity::new, MobCategory.MONSTER)

                            // Defines a human-sized collision box (width, height)
                            .sized(0.6f, 1.95f)

                            // Builds the entity type using the modern, non-deprecated ResourceLocation API
                            .build(
                                    ResourceLocation
                                            .fromNamespaceAndPath(ExampleMod.MODID, "summoner")
                                            .toString()
                            )
            );

    // Registry entry for the Bodyguard entity type
    public static final RegistryObject<EntityType<BodyguardEntity>> BODYGUARD =
            ENTITY_TYPES.register("bodyguard",
                    () -> EntityType.Builder
                            // Binds the BodyguardEntity constructor to this entity type
                            .of(BodyguardEntity::new, MobCategory.MONSTER)

                            // Enderman-height bodyguard (tall) with normal humanoid proportions
                            .sized(0.7f, 2.9f)

                            // Builds the entity type using the modern, non-deprecated ResourceLocation API
                            .build(
                                    ResourceLocation
                                            .fromNamespaceAndPath(ExampleMod.MODID, "bodyguard")
                                            .toString()
                            )
            );
}
