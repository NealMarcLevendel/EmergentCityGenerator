package com.emergentcity.city;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Landmark {
    public int id;
    public String type;
    public P[] fp;
    double width, depth, height, visibility = -1;
    Center center;
    /** Free-standing in the middle of its square (option 'freestanding') rather than on a block's edge. */
    boolean free;
    public P front, axisDir;
    String style = "neoclassical_monumental";
    /** The optional oversized landmark (command option 'grand'). */
    boolean grand;
    String why = "";
    double ratio() { return height / width; }
}
