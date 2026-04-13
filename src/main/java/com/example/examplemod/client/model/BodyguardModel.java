package com.example.examplemod.client.model;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.entity.BodyguardEntity;

import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.GeoModel;

import net.minecraft.util.Mth;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.data.EntityModelData;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

/**
 * Defines the GeckoLib model, texture, and animation resources used by the BodyguardEntity.
 *
 * Version: 1.0.0
 * Comments:
 */
public class BodyguardModel extends GeoModel<BodyguardEntity> {

    /**
     * Provides the GeckoLib geometry (model) file for the BodyguardEntity.
     *
     * @param animatable BodyguardEntity animatable - The entity instance requesting its model resource.
     * @return ResourceLocation - Resource location pointing to the .geo.json model file.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public ResourceLocation getModelResource(BodyguardEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "geo/bodyguard.geo.json"
        );
    }

    /**
     * Provides the texture used to render the BodyguardEntity model.
     *
     * @param animatable BodyguardEntity animatable - The entity instance requesting its texture.
     * @return ResourceLocation - Resource location pointing to the entity texture file.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public ResourceLocation getTextureResource(BodyguardEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "textures/bodyguard_texture.png"
        );
    }

    /**
     * Provides the GeckoLib animation file containing all animations for the BodyguardEntity.
     *
     * @param animatable BodyguardEntity animatable - The entity instance requesting its animation data.
     * @return ResourceLocation - Resource location pointing to the .animation.json file.
     * Version: 1.0.0
     * Comments:
     */
    @Override
    public ResourceLocation getAnimationResource(BodyguardEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                ExampleMod.MODID,
                "animations/bodyguard_animations.json"
        );
    }

    @Override
    public void setCustomAnimations(BodyguardEntity animatable, long instanceId, AnimationState<BodyguardEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        EntityModelData data = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
        if (data == null) return;

        CoreGeoBone head = this.getAnimationProcessor().getBone("head");
        if (head == null) return;

        float yawRadians = data.netHeadYaw() * Mth.DEG_TO_RAD;
        float pitchRadians = data.headPitch() * Mth.DEG_TO_RAD;

        head.setRotY(yawRadians);
        head.setRotX(pitchRadians);
    }
}
