package com.emergentcity.city;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A cemetery: a whole block, kept from phase to phase like a block square. */
public final class Cemetery {
    public int id;
    long faceKey;
    public List<P> poly = new ArrayList<>();
    public double area;
    int bornPhase;
    /**
     * Its layout as last planned, and (villager mode) as built: once its block is being built the layout is frozen, so
     * later re-planning can't move a mausoleum the catacombs then dig a stair into. Opaque to everything but Voxelizer.
     */
    Object lastPlan, plan;
    public P center() { return Geom.centroid(poly); }
}
