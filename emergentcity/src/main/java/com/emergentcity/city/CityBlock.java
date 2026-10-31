package com.emergentcity.city;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CityBlock {
    long key;
    public Face face;
    public List<P> facePoly;
    Area buildable;
    final List<List<P>> rings = new ArrayList<>();
    final List<List<P>> gardens = new ArrayList<>();
    /** Sharp block corners left open as small paved squares (never absorbed into houses). */
    final List<List<P>> tips = new ArrayList<>();
    /** The cemetery this block is, if it is one (no houses). */
    public Cemetery cemetery;
    /** District style fixed when the block is first built, so a block never mixes wall materials later. */
    String style;
    /** Evolving towns: cleared house lots, kept as gardens until rebuilt. */
    final List<House> vacant = new ArrayList<>();
    public final List<House> houses = new ArrayList<>();
    final List<House> openUnits = new ArrayList<>();
    double faceArea, buildArea, gardenArea, frontage;
    String status = "new";
    /** Villager mode: once a block is being built its parcels are frozen (anchor + area identify it later). */
    boolean locked;
    P lockAnchor;
    double lockArea;
}
