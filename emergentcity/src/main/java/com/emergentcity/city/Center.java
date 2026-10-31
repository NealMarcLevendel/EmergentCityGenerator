package com.emergentcity.city;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Center {
    int id;
    Node node;
    public P pos;
    double attraction, strength, accessibility, density;
    boolean seeded;
    /** Where a boulevard ends: a plaza with a monument facing back along it (boulevardDir points away from the main centre). */
    boolean boulevardEnd;
    P boulevardDir;
    int bornPhase;
    Plaza plaza;
    public Landmark landmark;
    String why = "";
}
