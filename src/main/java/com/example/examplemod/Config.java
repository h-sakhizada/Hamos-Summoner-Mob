package com.example.examplemod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds Forge-configurable settings for the mod and loads them into static fields at runtime.
 *
 * Version: 1.0.0
 * Comments:
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    // Builder used to declare all config entries for this mod
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // Basic example config values (these become entries in the mod's config .toml file)
    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // List of item IDs (as strings) that will be parsed into real Item instances on config load
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // The final config spec that Forge registers/loads
    static final ForgeConfigSpec SPEC = BUILDER.build();

    // Runtime fields your mod code can read after config loads
    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;

    /**
     * Validates that an object is a valid item ResourceLocation string that exists in the item registry.
     *
     * @param obj Object obj - Value from the config list (expected to be a String).
     * @return boolean - True if the value is a valid, registered item ID.
     * Version: 1.0.0
     * Comments:
     */
    private static boolean validateItemName(final Object obj) {

        // Ensure the config entry is a string, then safely parse it into a ResourceLocation
        if (!(obj instanceof final String itemName)) return false;

        // tryParse avoids throwing if the string isn't a valid "namespace:path"
        ResourceLocation id = ResourceLocation.tryParse(itemName);
        if (id == null) return false;

        // Only accept IDs that exist in the item registry
        return ForgeRegistries.ITEMS.containsKey(id);
    }

    /**
     * Loads config values into static runtime fields when Forge fires the config load event.
     *
     * @param event ModConfigEvent event - Forge config event fired after the config file is read.
     * Version: 1.0.0
     * Comments:
     */
    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {

        // Copy config values into easy-to-use static fields
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // Convert item ID strings into actual Item instances (ignoring invalid entries safely)
        items = ITEM_STRINGS.get().stream()
                .map(ResourceLocation::tryParse)
                .filter(id -> id != null)
                .map(ForgeRegistries.ITEMS::getValue)
                .collect(Collectors.toSet());
    }
}
