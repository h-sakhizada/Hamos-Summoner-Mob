package com.example.examplemod.client.model;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import com.example.examplemod.ExampleMod;
import com.example.examplemod.entity.SummonerEntity;

public class SummonerModel extends GeoModel<SummonerEntity> {

    @Override
    public ResourceLocation getModelResource(SummonerEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "geo/sem_id.geo.json"
        );
    }

    @Override
    public ResourceLocation getTextureResource(SummonerEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "textures/summoner_texture.png"
        );
    }

    @Override
    public ResourceLocation getAnimationResource(SummonerEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "animations/summoner_animations.json"
        );
    }
}
