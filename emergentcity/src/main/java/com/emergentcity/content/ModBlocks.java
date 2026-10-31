package com.emergentcity.content;

import com.emergentcity.EmergentCityMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The catacombs' bone blocks: bone gravel (loose bones packed together, falls like gravel), skulls (one to four in a
 * block, placed one at a time like candles) and femurs (laid in layers, one to four to a block, like snow).
 */
public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, EmergentCityMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, EmergentCityMod.MOD_ID);

    public static final RegistryObject<Block> BONE_GRAVEL = BLOCKS.register("bone_gravel", () -> new BoneGravelBlock(
            BlockBehaviour.Properties.copy(Blocks.GRAVEL).mapColor(MapColor.SAND).strength(0.7F).sound(SoundType.BONE_BLOCK)));
    public static final RegistryObject<Block> SKULL_PILE = BLOCKS.register("skull_pile", () -> new SkullPileBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(0.8F).sound(SoundType.BONE_BLOCK).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> FEMUR_STACK = BLOCKS.register("femur_stack", () -> new FemurStackBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(1.0F).sound(SoundType.BONE_BLOCK).noOcclusion().pushReaction(PushReaction.DESTROY)));

    public static final RegistryObject<Item> BONE_GRAVEL_ITEM = ITEMS.register("bone_gravel", () -> new BlockItem(BONE_GRAVEL.get(), new Item.Properties()));
    public static final RegistryObject<Item> SKULL_PILE_ITEM = ITEMS.register("skull_pile", () -> new BlockItem(SKULL_PILE.get(), new Item.Properties()));
    public static final RegistryObject<Item> FEMUR_STACK_ITEM = ITEMS.register("femur_stack", () -> new BlockItem(FEMUR_STACK.get(), new Item.Properties()));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(ModBlocks::creativeTabs);
    }

    private static void creativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS || event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(BONE_GRAVEL_ITEM);
            event.accept(SKULL_PILE_ITEM);
            event.accept(FEMUR_STACK_ITEM);
        }
    }
}
