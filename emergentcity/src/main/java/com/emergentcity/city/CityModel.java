package com.emergentcity.city;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


final class Plaza {
    int id;
    Node node;
    List<P> poly = new ArrayList<>();
    double radius, score;
    boolean central;
    /** A block left open beside its centre (rather than a round square drawn at the junction). */
    boolean block;
    long faceKey;
    /** Junctions around a block square: its paving slopes between their levels. */
    List<Node> corners = new ArrayList<>();
    int bornPhase;
    Area area() { return new Area(Geom.path(poly)); }
    /** Where the fountain and the square's middle are. */
    P center() { return block && poly.size() >= 3 ? Geom.centroid(poly) : node.p; }
}



final class Facade {
    int bays, doorBay;
    double bayW, winW, winH, corniceH, ornament;
    double[] floorH;
    boolean pilasters, rustication, doorPediment, balconies, symmetric;
    String roof, rule;
}



final class DecisionLog {
    final Map<String, Integer> counts = new TreeMap<>();
    final List<String> events = new ArrayList<>();
    void count(String k) { counts.merge(k, 1, Integer::sum); }
    void count(String k, int n) { if (n != 0) counts.merge(k, n, Integer::sum); }
    void event(int phase, String msg) { events.add(String.format("[phase %d] %s", phase, msg)); }
}

/** Whole-city state: network, centres, parcels, architecture, plus spatial indices and diagnostics. */
public final class CityModel {
    /** Streets to take out so blocks merge into a larger square for a free-standing monument (applied where the graph may change). */
    final List<Edge> squareMerges = new ArrayList<>();
    /** Villager mode: footprints of every house standing in the world (snapshot taken before each re-planning). */
    volatile List<P[]> standing = List.of();

    final Config cfg;
    public final Terrain t;
    final long seed;
    final StreetGraph g = new StreetGraph();
    public final List<Center> centers = new ArrayList<>();
    final List<Plaza> plazas = new ArrayList<>();
    public final List<Landmark> landmarks = new ArrayList<>();
    public final List<Cemetery> cemeteries = new ArrayList<>();
    /** Plague mode: the cemeteries are the plague's business now; the town lays out no new ones of its own. */
    public volatile boolean noNewCemeteries;
    /** An addon (the plague) has a ruined monument to rebuild first: no new ones meanwhile. */
    public volatile boolean noNewLandmarks;
    /** Plague mode's newcomers: they settle where the old town's main square was (null: wherever suits best). */
    P preferCentre;
    public List<CityBlock> blocks = new ArrayList<>();
    List<House> houses = new ArrayList<>();
    List<Chain> chains = new ArrayList<>();
    Map<Long, CityBlock> blockCache = new HashMap<>();
    final List<CityBlock> lockedBlocks = new ArrayList<>();
    /** Grid cells the town must leave alone (existing structures in the world); null = none. */
    public boolean[] keepOut;

    boolean keepOutAt(P p) {
        if (keepOut == null) return false;
        int i = (int) Math.round(p.x()), j = (int) Math.round(p.y());
        return i >= 0 && j >= 0 && i < t.cols && j < t.rows && keepOut[j * t.cols + i];
    }

    /** Does a footprint cover any kept-out cell? */
    boolean keepOutIn(P[] q) {
        if (keepOut == null) return false;
        double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
        for (P p : q) { x0 = Math.min(x0, p.x()); y0 = Math.min(y0, p.y()); x1 = Math.max(x1, p.x()); y1 = Math.max(y1, p.y()); }
        for (int j = (int) Math.floor(y0); j <= (int) Math.ceil(y1); j++)
            for (int i = (int) Math.floor(x0); i <= (int) Math.ceil(x1); i++)
                if (i >= 0 && j >= 0 && i < t.cols && j < t.rows && keepOut[j * t.cols + i] && Geom.pointInPoly(new P(i, j), q)) return true;
        return false;
    }
    final DecisionLog log = new DecisionLog();
    final Map<String, String> phaseSummaries = new LinkedHashMap<>();
    int phase, bridges;
    double radius;
    // attraction field
    double acell = 20;
    int ac, ar;
    float[] attr;
    // house index
    private final double HC = 16;
    private final HashMap<Long, List<House>> hGrid = new HashMap<>();
    private final HashMap<Long, List<House>> cGrid = new HashMap<>();
    // vista diagnostics
    final List<double[]> vistaSamples = new ArrayList<>(); // x,y,dx,dy,len,width,type(0 open,1 facade,2 landmark,3 water),edgeId
    Metrics metrics;

    CityModel(Config cfg, Terrain t, long seed) {
        this.cfg = cfg; this.t = t; this.seed = seed;
        ac = (int) Math.ceil(t.widthM() / acell) + 1;
        ar = (int) Math.ceil(t.heightM() / acell) + 1;
        attr = new float[ac * ar];
    }

    double attraction(P p) {
        int i = (int) Geom.clamp(Math.round(p.x() / acell), 0, ac - 1), j = (int) Geom.clamp(Math.round(p.y() / acell), 0, ar - 1);
        return attr[j * ac + i];
    }

    Center nearestCenter(P p) {
        Center best = null;
        double bd = Double.MAX_VALUE;
        for (Center c : centers) { double d = c.pos.dist(p); if (d < bd) { bd = d; best = c; } }
        return best;
    }

    double nearestCenterDist(P p) {
        Center c = nearestCenter(p);
        return c == null ? 1e9 : c.pos.dist(p);
    }

    /** Current extent of the city: radius covering 95% of street nodes around the main centre. */
    double cityRadius() {
        if (centers.isEmpty()) return 300;
        List<Double> ds = new ArrayList<>();
        for (Node n : g.aliveNodes()) ds.add(nearestCenterDist(n.p));
        if (ds.size() < 5) return 200;
        ds.sort(Double::compare);
        return Math.max(150, ds.get((int) (ds.size() * 0.95) - 1));
    }

    private static long hk(int i, int j) { return (((long) i) << 32) ^ (j & 0xffffffffL); }

    void indexHouses() {
        hGrid.clear();
        cGrid.clear();
        for (House h : houses) {
            double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
            for (P p : h.q) { x0 = Math.min(x0, p.x()); y0 = Math.min(y0, p.y()); x1 = Math.max(x1, p.x()); y1 = Math.max(y1, p.y()); }
            for (int i = (int) Math.floor(x0 / HC); i <= (int) Math.floor(x1 / HC); i++)
                for (int j = (int) Math.floor(y0 / HC); j <= (int) Math.floor(y1 / HC); j++)
                    hGrid.computeIfAbsent(hk(i, j), k -> new ArrayList<>()).add(h);
            cGrid.computeIfAbsent(hk((int) Math.floor(h.c.x() / HC), (int) Math.floor(h.c.y() / HC)), k -> new ArrayList<>()).add(h);
        }
    }

    House houseAt(P p) {
        List<House> l = hGrid.get(hk((int) Math.floor(p.x() / HC), (int) Math.floor(p.y() / HC)));
        if (l == null) return null;
        for (House h : l) if (Geom.pointInPoly(p, h.q)) return h;
        return null;
    }

    Landmark landmarkAt(P p) {
        for (Landmark l : landmarks) if (Geom.pointInPoly(p, l.fp)) return l;
        return null;
    }

    List<House> housesNear(P p, double r) {
        List<House> out = new ArrayList<>();
        for (int i = (int) Math.floor((p.x() - r) / HC); i <= (int) Math.floor((p.x() + r) / HC); i++)
            for (int j = (int) Math.floor((p.y() - r) / HC); j <= (int) Math.floor((p.y() + r) / HC); j++) {
                List<House> l = cGrid.get(hk(i, j));
                if (l != null) for (House h : l) if (h.c.dist(p) <= r) out.add(h);
            }
        return out;
    }

    /**
     * Density near p, in "house equivalents": built footprint area divided by density.houseUnitArea, so a
     * town of fewer, larger houses is not mistaken for an emptier one.
     */
    int housesWithin(P p, double r) {
        double unit = cfg.d("density.houseUnitArea"), n = 0;
        for (int i = (int) Math.floor((p.x() - r) / HC); i <= (int) Math.floor((p.x() + r) / HC); i++)
            for (int j = (int) Math.floor((p.y() - r) / HC); j <= (int) Math.floor((p.y() + r) / HC); j++) {
                List<House> l = cGrid.get(hk(i, j));
                if (l != null) for (House h : l) if (h.c.dist(p) <= r) n += Math.abs(Geom.signedArea(h.q)) / unit;
            }
        return n <= 0 ? 0 : (int) Math.max(1, Math.round(n));
    }

    boolean rayHitsBuilt(P o, P dir, double len) {
        for (double s = 2; s <= len; s += 2) {
            P q = o.add(dir.mul(s));
            if (houseAt(q) != null || landmarkAt(q) != null) return true;
        }
        return false;
    }
}
