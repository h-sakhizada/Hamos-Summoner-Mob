package com.example.examplemod.client;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.client.renderer.SummonerRenderer;
import com.example.examplemod.registry.ModEntities;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles client-only mod events such as entity renderer registration.
 *
 * Version: 1.0.0
 * Comments:
 */
@Mod.EventBusSubscriber(
        modid = ExampleMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public class ClientModEvents {

    /**
     * Registers all custom entity renderers for the client.
     *
     * @param event EntityRenderersEvent.RegisterRenderers event - Forge event used to register entity renderers.
     * Version: 1.0.0
     * Comments:
     */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {

        // Register the SummonerEntity renderer
        event.registerEntityRenderer(
                ModEntities.SUMMONER.get(),
                SummonerRenderer::new
        );
    }
}
