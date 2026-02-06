package com.example.examplemod.client.renderer;

import com.example.examplemod.client.model.SummonerModel;
import com.example.examplemod.entity.SummonerEntity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer implementation for the SummonerEntity using GeckoLib.
 *
 * Responsible for binding the SummonerModel and controlling basic render properties
 * such as shadow size.
 *
 * Version: 1.0.0
 * Comments:
 */
public class SummonerRenderer extends GeoEntityRenderer<SummonerEntity> {

    /**
     * Constructs a new renderer for the SummonerEntity.
     *
     * Initializes the GeckoLib renderer with the Summoner model and configures
     * the entity's shadow radius.
     *
     * @param renderManager EntityRendererProvider.Context renderManager - Rendering context provided by Minecraft.
     * Version: 1.0.0
     * Comments:
     */
    public SummonerRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new SummonerModel());

        // Controls the size of the shadow rendered beneath the summoner
        this.shadowRadius = 0.6f;
    }
}
