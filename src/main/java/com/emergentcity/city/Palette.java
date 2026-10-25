package com.emergentcity.city;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Block vocabulary (spec N: material palette). A town uses ONE named palette (config mc.palette):
 * - one wall material per district (classical core / vernacular outer), shared by every house there;
 * - one roof material and one roof form for all houses in the town;
 * - the same windows, doors, plinth and floors everywhere in a district;
 * - importance is expressed by trim, cornices and pilasters, not by changing colour;
 * - tier-4 buildings use the same walls with plain trim; monuments use their own stone on purpose.
 * Optional tonal variation (mc.paletteVariation) switches a whole street block to a close alternate tone.
 */
final class Palette {
    final Block wallMain, wallAlt;
    final Block trim, rustic, pilaster, roofFull, roofStair, roofSlab, floor, door, cornice, foundation;

    private Palette(Block wallMain, Block wallAlt, Block trim, Block rustic, Block pilaster, Block roofFull, Block roofStair,
                    Block roofSlab, Block floor, Block door, Block cornice, Block foundation) {
        this.wallMain = wallMain; this.wallAlt = wallAlt; this.trim = trim; this.rustic = rustic; this.pilaster = pilaster;
        this.roofFull = roofFull; this.roofStair = roofStair; this.roofSlab = roofSlab; this.floor = floor; this.door = door;
        this.cornice = cornice; this.foundation = foundation;
    }

    /** Roof set shared by every house of a town. */
    private record Roof(Block full, Block stair, Block slab) {}

    private static final Roof TILE = new Roof(Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB);
    private static final Roof NETHER = new Roof(Blocks.RED_NETHER_BRICKS, Blocks.RED_NETHER_BRICK_STAIRS, Blocks.RED_NETHER_BRICK_SLAB);
    private static final Roof SLATE = new Roof(Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB);

    /** District schemes per named palette: {core, outer}. */
    private static Palette[] scheme(String name) {
        return switch (name) {
            case "brick" -> new Palette[]{
                    district(Blocks.BRICKS, Blocks.BRICKS, Blocks.SMOOTH_QUARTZ, Blocks.STONE_BRICKS, Blocks.QUARTZ_PILLAR, SLATE, Blocks.OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.SMOOTH_QUARTZ_STAIRS),
                    district(Blocks.BRICKS, Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, SLATE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.STONE_BRICK_STAIRS)};
            case "redbrick" -> new Palette[]{ // brick facades in several tones, red nether brick roofs
                    district(Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, NETHER, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.STONE_BRICK_STAIRS),
                    district(Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.STRIPPED_DARK_OAK_WOOD, Blocks.STONE_BRICKS, Blocks.STRIPPED_DARK_OAK_WOOD, NETHER, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.DARK_OAK_STAIRS)};
            case "whitewash" -> new Palette[]{
                    district(Blocks.CALCITE, Blocks.CALCITE, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, SLATE, Blocks.OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.STONE_BRICK_STAIRS),
                    district(Blocks.CALCITE, Blocks.CALCITE, Blocks.STRIPPED_SPRUCE_WOOD, Blocks.STONE_BRICKS, Blocks.STRIPPED_SPRUCE_WOOD, SLATE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_STAIRS)};
            case "stone" -> new Palette[]{
                    district(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, SLATE, Blocks.OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.POLISHED_ANDESITE_STAIRS),
                    district(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STRIPPED_SPRUCE_WOOD, Blocks.COBBLESTONE, Blocks.STRIPPED_SPRUCE_WOOD, SLATE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_STAIRS)};
            default -> new Palette[]{ // "warm": honey sandstone core, pale plaster with timber outer, red tile roofs
                    district(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_QUARTZ, Blocks.STONE_BRICKS, Blocks.QUARTZ_PILLAR, TILE, Blocks.OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.SMOOTH_QUARTZ_STAIRS),
                    district(Blocks.WHITE_TERRACOTTA, Blocks.SMOOTH_SANDSTONE, Blocks.STRIPPED_SPRUCE_WOOD, Blocks.STONE_BRICKS, Blocks.STRIPPED_SPRUCE_WOOD, TILE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_STAIRS)};
        };
    }

    private static Palette district(Block wall, Block alt, Block trim, Block plinth, Block pilaster, Roof roof, Block floor, Block door, Block cornice) {
        return new Palette(wall, alt, trim, plinth, pilaster, roof.full(), roof.stair(), roof.slab(), floor, door, cornice, plinth);
    }

    private static final java.util.Map<String, Palette[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The palette for a house: its district's scheme; tier 4 drops the contrasting trim. */
    static Palette forHouse(Config c, House h) {
        Palette[] sc = CACHE.computeIfAbsent(c.s("mc.palette"), Palette::scheme);
        Palette p = h.style.equals("georgian_classical") || h.style.equals("perimeter_block") ? sc[0] : sc[1];
        if (h.tier < 4) return p;
        return new Palette(p.wallMain, p.wallAlt, p.wallMain, p.foundation, p.wallMain, p.roofFull, p.roofStair, p.roofSlab, p.floor,
                p.door, p.roofStair, p.foundation);
    }

    /** Brick facades of the "redbrick" palette: red brick most often, then tan, granite-pink, grey and dark brick. */
    private static final Block[] BRICK_FACADES = {Blocks.BRICKS, Blocks.BRICKS, Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.MUD_BRICKS,
            Blocks.POLISHED_GRANITE, Blocks.STONE_BRICKS, Blocks.DEEPSLATE_BRICKS};

    /**
     * Wall material: the district's main tone, or (with mc.paletteVariation) the alternate tone for a whole block.
     * The "redbrick" palette varies brick from house to house instead (keyed to the plot position, so a house keeps
     * its brick when the town is re-planned).
     */
    BlockState wallFor(Config c, House h) {
        if (roofStair == Blocks.RED_NETHER_BRICK_STAIRS && h.q != null) {
            long k = Math.round(h.q[0].x()) * 73856093L ^ Math.round(h.q[0].y()) * 19349663L ^ (long) h.variant * 83492791L;
            k ^= k >>> 29; k *= 0xbf58476d1ce4e5b9L; k ^= k >>> 32;
            return BRICK_FACADES[(int) Math.floorMod(k, (long) BRICK_FACADES.length)].defaultBlockState();
        }
        double var = c.d("mc.paletteVariation");
        if (var <= 0 || h.block == null) return wallMain.defaultBlockState();
        long k = h.block.lockAnchor != null ? Double.doubleToLongBits(h.block.lockAnchor.x() * 31 + h.block.lockAnchor.y())
                : h.block.face != null ? h.block.face.key() : h.block.key;
        k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33;
        double u = (k >>> 11) * (1.0 / (1L << 53));
        return (u < var ? wallAlt : wallMain).defaultBlockState();
    }

    static final Palette MONUMENT = new Palette(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ,
            Blocks.CALCITE, Blocks.STONE_BRICKS, Blocks.QUARTZ_PILLAR, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS,
            Blocks.DEEPSLATE_TILE_SLAB, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_DOOR, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.STONE_BRICKS);

    BlockState wall(int ignored) { return wallMain.defaultBlockState(); }

    static BlockState s(Block b) { return b.defaultBlockState(); }

    static BlockState stair(Block b, Direction facing, boolean upsideDown) {
        return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, upsideDown ? Half.TOP : Half.BOTTOM);
    }

    static BlockState slab(Block b, boolean top) {
        return b.defaultBlockState().setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }

    /** Painted-wood accents for entrances and shop signs: {planks, matching door}. */
    static final Block[][] ACCENTS = {
            {Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_DOOR}, {Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR},
            {Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_DOOR}, {Blocks.WARPED_PLANKS, Blocks.WARPED_DOOR},
            {Blocks.CRIMSON_PLANKS, Blocks.CRIMSON_DOOR}};

    /** Accent for a house: bookstores dark wood, restaurants deep red, shops any colour; neighbours differ by id. */
    static Block[] accent(House h) {
        if ("bookstore".equals(h.use)) return ACCENTS[0];
        if ("restaurant".equals(h.use)) return ACCENTS[(h.id & 1) == 0 ? 2 : 4];
        int k = Math.floorMod(h.id * 7 + (h.block == null ? 0 : (int) h.block.key), ACCENTS.length);
        return ACCENTS[k];
    }

    static BlockState door(Block b, Direction facing, boolean upper) {
        return b.defaultBlockState().setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
    }

    /** Glass pane connected along the wall it sits in (placement skips neighbour updates, so set it here). */
    static BlockState pane(Block b, Direction facing) {
        boolean alongX = facing.getAxis() == Direction.Axis.Z;
        return b.defaultBlockState()
                .setValue(BlockStateProperties.EAST, alongX).setValue(BlockStateProperties.WEST, alongX)
                .setValue(BlockStateProperties.NORTH, !alongX).setValue(BlockStateProperties.SOUTH, !alongX);
    }

    /** Bed half: the head lies in the facing direction from the foot. */
    static BlockState bed(Direction facing, boolean head) {
        return Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, head ? BedPart.HEAD : BedPart.FOOT);
    }

    static BlockState ladder(Direction facing) { return Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing); }

    static BlockState leaves() { return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true); }

    /** Nearest horizontal direction for a simulation vector. Simulation y maps to world +z (south). */
    static Direction dirOf(P v) {
        if (Math.abs(v.x()) >= Math.abs(v.y())) return v.x() >= 0 ? Direction.EAST : Direction.WEST;
        return v.y() >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
