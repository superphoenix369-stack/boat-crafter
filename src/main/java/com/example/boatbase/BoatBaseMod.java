package com.example.boatbase;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;

public class BoatBaseMod implements ModInitializer {
    public static final String MOD_ID = "boatbase";

    public static Block BOAT_BASE;
    public static Item BOAT_BASE_ITEM;
    /** One Ship's Wheel per wood type, made from that wood's planks. */
    public static final List<Block> SHIP_WHEELS = new ArrayList<>();
    public static final List<Item> SHIP_WHEEL_ITEMS = new ArrayList<>();

    private static final String[] WOODS = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped"
    };

    @Override
    public void onInitialize() {
        // Boat Base
        Identifier baseId = Identifier.fromNamespaceAndPath(MOD_ID, "boat_base");
        ResourceKey<Block> baseBlockKey = ResourceKey.create(Registries.BLOCK, baseId);
        ResourceKey<Item> baseItemKey = ResourceKey.create(Registries.ITEM, baseId);

        BOAT_BASE = Registry.register(BuiltInRegistries.BLOCK, baseBlockKey,
                new BoatBaseBlock(BlockBehaviour.Properties.of()
                        .setId(baseBlockKey)
                        .strength(2.0f)
                        .sound(SoundType.METAL)
                        .noOcclusion()));

        BOAT_BASE_ITEM = Registry.register(BuiltInRegistries.ITEM, baseItemKey,
                new BoatBaseItem(BOAT_BASE, new Item.Properties()
                        .setId(baseItemKey)
                        .useBlockDescriptionPrefix()));

        // Ship's Wheels, one per wood type. The dark oak one keeps the original id "ship_wheel".
        for (String wood : WOODS) {
            String path = wood.equals("dark_oak") ? "ship_wheel" : "ship_wheel_" + wood;
            Identifier wheelId = Identifier.fromNamespaceAndPath(MOD_ID, path);
            ResourceKey<Block> wheelBlockKey = ResourceKey.create(Registries.BLOCK, wheelId);
            ResourceKey<Item> wheelItemKey = ResourceKey.create(Registries.ITEM, wheelId);

            Block wheel = Registry.register(BuiltInRegistries.BLOCK, wheelBlockKey,
                    new ShipWheelBlock(BlockBehaviour.Properties.of()
                            .setId(wheelBlockKey)
                            .strength(2.0f)
                            .sound(SoundType.WOOD)
                            .noOcclusion()));
            Item wheelItem = Registry.register(BuiltInRegistries.ITEM, wheelItemKey,
                    new BlockItem(wheel, new Item.Properties()
                            .setId(wheelItemKey)
                            .useBlockDescriptionPrefix()));
            SHIP_WHEELS.add(wheel);
            SHIP_WHEEL_ITEMS.add(wheelItem);
        }

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(output -> {
                    output.accept(BOAT_BASE_ITEM);
                    for (Item wheelItem : SHIP_WHEEL_ITEMS) {
                        output.accept(wheelItem);
                    }
                });

        ServerTickEvents.END_SERVER_TICK.register(server -> BoatMover.tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BoatMover.shutdown());
    }
}
