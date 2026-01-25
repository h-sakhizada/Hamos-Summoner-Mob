package com.example.examplemod.client.renderer;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import com.example.examplemod.client.model.SummonerModel;
import com.example.examplemod.entity.SummonerEntity;

public class SummonerRenderer extends GeoEntityRenderer<SummonerEntity> {
    public SummonerRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new SummonerModel());
        this.shadowRadius = 0.6f;
    }
}
