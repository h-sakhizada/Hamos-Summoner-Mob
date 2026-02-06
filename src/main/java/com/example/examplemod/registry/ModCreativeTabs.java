package com.example.examplemod.registry;

import com.example.examplemod.ExampleMod;

import net.minecraft.world.item.CreativeModeTabs;

import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles adding this mod's items to existing creative mode tabs.
 *
 * Version: 1.0.0
 * Comments:
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModCreativeTabs {

    /**
     * Adds registered mod items to the appropriate creative mode tabs.
     *
     * @param event BuildCreativeModeTabContentsEvent event - Forge event used to populate creative mode tabs.
     * Version: 1.0.0
     * Comments:
     */
    @SubscribeEvent
    public static void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {

        // Put the Summoner spawn egg into the vanilla "Spawn Eggs" creative tab
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.SUMMONER_SPAWN_EGG.get());
        }
    }
}
