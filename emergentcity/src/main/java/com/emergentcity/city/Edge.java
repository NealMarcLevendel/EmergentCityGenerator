package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Street segment. Topology lives in the graph; geometry is the straight centreline a-b plus a width profile. */
public final class Edge {
    public final int id;
    public final Node a, b;
    final int born;
    public boolean alive = true;
    /** 1 local, 2 connector, 3 primary, 4 civic approach. */
    int cls = 1, clsPrev = 1;
    public double width = 5, wA = 5, wB = 5, flow, demand, meanFacadeH = -1;
    boolean seedRoad, axis, bridge, infill;
    /** A boulevard: laid dead straight from the main centre, with its own fixed width (street.boulevard.width). */
    boolean boulevard;
    /** Width this street was physically laid with (villager mode); -1 = not laid yet. */
    public double laidWidth = -1;
    Edge chainA, chainB;
    int chainId = -1, stamp;
    // vista metrics (filled by Vista)
    double vistaMaxRatio, enclosure = -1, proportionR = -1;
    String vistaEndType = "";

    Edge(int id, Node a, Node b, int born) { this.id = id; this.a = a; this.b = b; this.born = born; }

    Node other(Node n) { return n == a ? b : a; }
    double len() { return a.p.dist(b.p); }
    P dir() { return b.p.sub(a.p).norm(); }
    public P mid() { return a.p.lerp(b.p, 0.5); }
    double angleFrom(Node n) { P d = other(n).p.sub(n.p); return Math.atan2(d.y(), d.x()); }
    public double widthAt(Node n) { return n == a ? wA : wB; }
    Edge chainAt(Node n) { return n == a ? chainA : chainB; }
}
