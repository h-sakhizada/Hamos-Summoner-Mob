package com.example.examplemod.client.model;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.entity.SummonerEntity;

import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.GeoModel;

/**
 * Defines the GeckoLib model, texture, and animation resources used by the SummonerEntity.
 *
 * Version: 1.0.0
 * Comments:
 */
public class SummonerModel extends GeoModel<SummonerEntity> {

    /**
     * Provides the GeckoLib geometry (model) file for the SummonerEntity.
     *
     * @param animatable SummonerEntity animatable - The entity instance requesting its model resource.
     * @return ResourceLocation - Resource location pointing to the .geo.json model file.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public ResourceLocation getModelResource(SummonerEntity animatable) {

        // Points to the Blockbench-exported GeckoLib model file
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "geo/sem_id.geo.json"
        );
    }

    /**
     * Provides the texture used to render the SummonerEntity model.
     *
     * @param animatable SummonerEntity animatable - The entity instance requesting its texture.
     * @return ResourceLocation - Resource location pointing to the entity texture file.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public ResourceLocation getTextureResource(SummonerEntity animatable) {

        // Points to the PNG texture applied to the model
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "textures/summoner_texture.png"
        );
    }

    /**
     * Provides the GeckoLib animation file containing all animations for the SummonerEntity.
     *
     * @param animatable SummonerEntity animatable - The entity instance requesting its animation data.
     * @return ResourceLocation - Resource location pointing to the .animation.json file.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public ResourceLocation getAnimationResource(SummonerEntity animatable) {

        // Points to the GeckoLib animation definitions (idle, walk, cast, etc.)
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "animations/summoner_animations.json"
        );
    }
}
