package com.example.examplemod;

import com.example.examplemod.registry.ModEntities;
import com.example.examplemod.registry.ModItems;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ExampleMod.MODID)
public class ExampleMod {

    // ✅ MUST match gradle.properties -> mod_id=...
    public static final String MODID = "hamo_summoner";

    public ExampleMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register our registries
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        // Register mod event handlers (attributes, etc.)
        modEventBus.register(new ModEvents());
    }
}
