package com.emergentcity.city;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Block states by name and properties, as structure files store them ("minecraft:oak_stairs" + {facing: north, ...}). */
public final class States {
    private States() {}

    /** The state, or null for an unknown block id (a structure saved with blocks from another mod). */
    static BlockState parse(String name, Map<String, String> props) {
        ResourceLocation rl = ResourceLocation.tryParse(name);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        Block b = BuiltInRegistries.BLOCK.get(rl);
        BlockState s = b.defaultBlockState();
        if (props != null)
            for (var e : props.entrySet()) {
                Property<?> p = b.getStateDefinition().getProperty(e.getKey());
                if (p != null) s = with(s, p, e.getValue());
            }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
        Optional<T> o = p.getValue(v);
        return o.isPresent() ? s.setValue(p, o.get()) : s;
    }

    public static String name(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(); }

    public static Map<String, String> props(BlockState s) {
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> e : s.getValues().entrySet()) out.put(e.getKey().getName(), valueName(e.getKey(), e.getValue()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Property<T> p, Comparable<?> v) { return p.getName((T) v); }

    /** The same block with another block's id, keeping every property the other block also has (stairs -> stairs). */
    static BlockState retype(BlockState from, Block to) {
        BlockState s = to.defaultBlockState();
        for (var e : props(from).entrySet()) {
            Property<?> p = to.getStateDefinition().getProperty(e.getKey());
            if (p != null) s = with(s, p, e.getValue());
        }
        return s;
    }

    /** Quarter turns clockwise seen from above (east -> south -> west -> north). */
    static BlockState rotate(BlockState s, int quarters) {
        return switch (Math.floorMod(quarters, 4)) {
            case 1 -> s.rotate(Rotation.CLOCKWISE_90);
            case 2 -> s.rotate(Rotation.CLOCKWISE_180);
            case 3 -> s.rotate(Rotation.COUNTERCLOCKWISE_90);
            default -> s;
        };
    }
}
