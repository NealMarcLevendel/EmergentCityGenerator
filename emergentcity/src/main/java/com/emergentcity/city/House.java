package com.emergentcity.city;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class House {
    public int id, ring, seq, edgeIdx, tier = 3, floors, streetCls = 1, sideCls, vistaHits;
    public P[] q; // front0, front1, rear1, rear0 (CCW)
    public CityBlock block;
    double frontage, depth, height, importance, ornament, focal, centerDist, streetWidth;
    public boolean corner, plazaFront, passage, yard;
    /** Added by the gap-filling pass to close the street wall (no courtyard access needed; usually a shop). */
    boolean infill;
    /** Corner house: q1-q2 is a second street front (windows on both streets). */
    boolean front2;
    /** What the building is used for (residential, shop, bookstore, mixed, restaurant); set by Architecture. */
    String use = "residential";
    /** Use is kept once the building stands (villager mode); only a deliberate conversion changes it. */
    public boolean useFixed;
    /** Storeys of the standing building (villager mode); 0 = not built yet, free to be planned. */
    public int fixedFloors;
    /** Restaurant tables placed outside (0-2). */
    int seating;
    /** World columns and floor level this house was last built with (filled by the voxelizer). */
    public java.util.List<int[]> builtCells;
    public int builtBase, builtTop;
    /** Unit house: the unit design (key in the unit library), its quarter turn, and the grid cell of its origin. */
    String unitKey;
    int unitTurn, ai, aj;
    /**
     * Party walls: world column -> solid runs {y0, y1, y0', y1', ...} of this house's perimeter walls as last built.
     * Neighbours built later lean on these instead of building a second wall alongside.
     */
    java.util.Map<Long, int[]> wallSpan;
    /** Evolving towns: extra storeys earned by prosperity, facade variant after a rebuild, cycle a lot was cleared. */
    public int bonusFloors, variant, vacantSince;
    Edge street;
    Plaza plaza;
    String style = "", tierReason = "";
    Facade facade;
    public P c;

    public P frontMid() { return q[0].lerp(q[1], 0.5); }
    P rearMid() { return q[3].lerp(q[2], 0.5); }
    public P outward() { return frontMid().sub(rearMid()).norm(); }
}
