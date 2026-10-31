package com.emergentcity.city;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One vanilla structure file (as saved by a structure block): its size, its blocks in local coordinates and its
 * data markers (structure blocks in DATA mode; their metadata names the marker, e.g. "origin").
 * Air is kept (so a footprint can be read from the bottom layer) but never placed by the town.
 */
final class Template {
    final String name;
    int sx, sy, sz;
    final List<int[]> pos = new ArrayList<>();
    final List<BlockState> state = new ArrayList<>();
    final Map<String, int[]> markers = new HashMap<>();
    int unknown;

    Template(String name) { this.name = name; }

    @SuppressWarnings("unchecked")
    static Template read(String name, InputStream in) throws IOException {
        Map<String, Object> root = Nbt.read(in);
        Template t = new Template(name);
        List<Object> size = (List<Object>) root.get("size");
        if (size == null || size.size() != 3) throw new IOException("no size");
        t.sx = Nbt.asInt(size.get(0)); t.sy = Nbt.asInt(size.get(1)); t.sz = Nbt.asInt(size.get(2));
        List<Object> palette = (List<Object>) root.get("palette");
        if (palette == null && root.get("palettes") instanceof List<?> ps && !ps.isEmpty()) palette = (List<Object>) ps.get(0);
        if (palette == null) throw new IOException("no palette");
        BlockState[] pal = new BlockState[palette.size()];
        String[] names = new String[palette.size()];
        for (int i = 0; i < pal.length; i++) {
            Map<String, Object> e = (Map<String, Object>) palette.get(i);
            names[i] = String.valueOf(e.get("Name"));
            Map<String, String> props = new TreeMap<>();
            if (e.get("Properties") instanceof Map<?, ?> pm) for (var pe : pm.entrySet()) props.put(String.valueOf(pe.getKey()), String.valueOf(pe.getValue()));
            pal[i] = States.parse(names[i], props);
        }
        for (Object o : (List<Object>) root.getOrDefault("blocks", List.of())) {
            Map<String, Object> b = (Map<String, Object>) o;
            List<Object> p = (List<Object>) b.get("pos");
            int[] xyz = {Nbt.asInt(p.get(0)), Nbt.asInt(p.get(1)), Nbt.asInt(p.get(2))};
            int si = Nbt.asInt(b.get("state"));
            if (si < 0 || si >= pal.length) continue;
            if (names[si].endsWith(":structure_block") && b.get("nbt") instanceof Map<?, ?> nbt && "DATA".equals(String.valueOf(nbt.get("mode")))) {
                String meta = String.valueOf(nbt.get("metadata")).trim().toLowerCase(java.util.Locale.ROOT);
                t.markers.put(meta, xyz);
                continue;
            }
            if (names[si].endsWith(":structure_void")) continue;
            if (pal[si] == null) { t.unknown++; continue; }
            t.pos.add(xyz);
            t.state.add(pal[si]);
        }
        return t;
    }
}
