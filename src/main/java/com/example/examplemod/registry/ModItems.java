package com.example.examplemod.registry;

import com.example.examplemod.ExampleMod;

import net.minecraft.world.item.Item;

import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Handles registration of all custom items for the mod.
 *
 * Version: 1.0.0
 * Comments:
 */
public class ModItems {

    // Central deferred register used to safely register items during mod loading
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);

    // Spawn egg item for the Summoner entity (colors are placeholder values)
    public static final RegistryObject<Item> SUMMONER_SPAWN_EGG =
            ITEMS.register(
                    "summoner_spawn_egg",
                    () -> new ForgeSpawnEggItem(
                            ModEntities.SUMMONER,
                            0x2B2B2B, // Primary egg color (dark gray)
                            0x7A1FA2, // Secondary egg color (purple)
                            new Item.Properties()
                    )
            );
}
