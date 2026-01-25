package com.example.examplemod.registry;

import com.example.examplemod.ExampleMod;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);

    // Spawn egg for Summoner (colors are placeholder)
    public static final RegistryObject<Item> SUMMONER_SPAWN_EGG =
            ITEMS.register("summoner_spawn_egg",
                    () -> new ForgeSpawnEggItem(
                            ModEntities.SUMMONER,
                            0x2B2B2B, // primary egg color (dark gray)
                            0x7A1FA2, // secondary egg color (purple)
                            new Item.Properties()
                    )
            );
}
