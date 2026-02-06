package com.example.examplemod;

import com.example.examplemod.registry.ModEntities;
import com.example.examplemod.registry.ModItems;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Main mod entry point responsible for initializing registries and mod-level events.
 *
 * Version: 1.0.0
 * Comments:
 */
@Mod(ExampleMod.MODID)
public class ExampleMod {

    // Unique mod identifier (must match gradle.properties mod_id)
    public static final String MODID = "hamo_summoner";

    /**
     * Constructs the mod and registers all mod-specific systems.
     *
     * Version: 1.0.0
     * Comments:
     */
    public ExampleMod() {

        // Get the mod event bus used for registering deferred registers and mod lifecycle listeners
        IEventBus modEventBus = getModEventBus();

        // Register all entity types with the mod event bus
        ModEntities.ENTITY_TYPES.register(modEventBus);

        // Register all custom items with the mod event bus
        ModItems.ITEMS.register(modEventBus);

        // Register mod-specific event handlers (attributes, setup hooks, etc.)
        modEventBus.register(new ModEvents());
    }

    /**
     * Returns the Forge MOD event bus for this mod using the 1.20.1-compatible loading context.
     *
     * @return IEventBus - The mod-specific event bus used for registration and lifecycle events.
     * Version: 1.0.0
     * Comments:
     */
    @SuppressWarnings({"removal", "deprecation"})
    private static IEventBus getModEventBus() {

        // Forge 1.20.1 uses this context; Forge marks it for removal in later versions (1.21.1+),
        // so we suppress the warning here to keep it isolated to a single method
        return FMLJavaModLoadingContext.get().getModEventBus();
    }
}
