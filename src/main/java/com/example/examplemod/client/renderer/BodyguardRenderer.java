package com.example.examplemod.client.renderer;

import com.example.examplemod.client.model.BodyguardModel;
import com.example.examplemod.entity.BodyguardEntity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer implementation for the BodyguardEntity using GeckoLib.
 *
 * Responsible for binding the BodyguardModel and controlling basic render properties
 * such as shadow size.
 *
 * Version: 1.0.0
 * Comments:
 */
public class BodyguardRenderer extends GeoEntityRenderer<BodyguardEntity> {

    /**
     * Constructs a new renderer for the BodyguardEntity.
     *
     * Initializes the GeckoLib renderer with the bodyguard model and configures
     * the entity's shadow radius.
     *
     * @param renderManager EntityRendererProvider.Context renderManager - Rendering context provided by Minecraft.
     * Version: 1.0.0
     * Comments:
     */
    public BodyguardRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new BodyguardModel());

        // Controls the size of the shadow rendered beneath the bodyguard
        this.shadowRadius = 0.7f;
    }
}
