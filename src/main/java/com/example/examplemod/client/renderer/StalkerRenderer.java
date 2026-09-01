package com.example.examplemod.client.renderer;

import com.example.examplemod.client.model.StalkerModel;
import com.example.examplemod.entity.StalkerEntity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer implementation for the StalkerEntity using GeckoLib.
 *
 * Responsible for binding the StalkerModel and controlling basic render properties
 * such as shadow size.
 *
 * Version: 1.0.0
 * Comments:
 */
public class StalkerRenderer extends GeoEntityRenderer<StalkerEntity> {

    /**
     * Constructs a new renderer for the StalkerEntity.
     *
     * Initializes the GeckoLib renderer with the stalker model and configures
     * the entity's shadow radius.
     *
     * @param renderManager EntityRendererProvider.Context renderManager - Rendering context provided by Minecraft.
     * Version: 1.0.0
     * Comments:
     */
    public StalkerRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new StalkerModel());

        // Small shadow matching the baby-zombie-sized entity
        this.shadowRadius = 0.3f;
    }
}