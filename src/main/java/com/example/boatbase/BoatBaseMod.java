package com.example.boatbase;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class BoatBaseMod implements ModInitializer {
    public static final String MOD_ID = "boatbase";

    public static Block BOAT_BASE;
    public static Item BOAT_BASE_ITEM;

    @Override
    public void onInitialize() {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "boat_base");
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);

        BOAT_BASE = Registry.register(BuiltInRegistries.BLOCK, blockKey,
                new BoatBaseBlock(BlockBehaviour.Properties.of()
                        .setId(blockKey)
                        .strength(2.0f)
                        .sound(SoundType.WOOD)));

        BOAT_BASE_ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BoatBaseItem(BOAT_BASE, new Item.Properties()
                        .setId(itemKey)
                        .useBlockDescriptionPrefix()));

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(output -> output.accept(BOAT_BASE_ITEM));

        ServerTickEvents.END_SERVER_TICK.register(server -> BoatMover.tick());
    }
}
