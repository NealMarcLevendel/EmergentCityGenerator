package com.emergentcity.city;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Center {
    int id;
    Node node;
    P pos;
    double attraction, strength, accessibility, density;
    boolean seeded;
    /** Where a boulevard ends: a plaza with a monument facing back along it (boulevardDir points away from the main centre). */
    boolean boulevardEnd;
    P boulevardDir;
    int bornPhase;
    Plaza plaza;
    Landmark landmark;
    String why = "";
}

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

final class Landmark {
    int id;
    String type;
    P[] fp;
    double width, depth, height, visibility = -1;
    Center center;
    /** Free-standing in the middle of its square (option 'freestanding') rather than on a block's edge. */
    boolean free;
    P front, axisDir;
    String style = "neoclassical_monumental";
    /** The optional oversized landmark (command option 'grand'). */
    boolean grand;
    String why = "";
    double ratio() { return height / width; }
}

final class Facade {
    int bays, doorBay;
    double bayW, winW, winH, corniceH, ornament;
    double[] floorH;
    boolean pilasters, rustication, doorPediment, balconies, symmetric;
    String roof, rule;
}

final class House {
    int id, ring, seq, edgeIdx, tier = 3, floors, streetCls = 1, sideCls, vistaHits;
    P[] q; // front0, front1, rear1, rear0 (CCW)
    CityBlock block;
    double frontage, depth, height, importance, ornament, focal, centerDist, streetWidth;
    boolean corner, plazaFront, passage, yard;
    /** Added by the gap-filling pass to close the street wall (no courtyard access needed; usually a shop). */
    boolean infill;
    /** Corner house: q1-q2 is a second street front (windows on both streets). */
    boolean front2;
    /** What the building is used for (residential, shop, bookstore, mixed, restaurant); set by Architecture. */
    String use = "residential";
    /** Use is kept once the building stands (villager mode); only a deliberate conversion changes it. */
    boolean useFixed;
    /** Storeys of the standing building (villager mode); 0 = not built yet, free to be planned. */
    int fixedFloors;
    /** Restaurant tables placed outside (0-2). */
    int seating;
    /** World columns and floor level this house was last built with (filled by the voxelizer). */
    java.util.List<int[]> builtCells;
    int builtBase, builtTop;
    /** Unit house: the unit design (key in the unit library), its quarter turn, and the grid cell of its origin. */
    String unitKey;
    int unitTurn, ai, aj;
    /**
     * Party walls: world column -> solid runs {y0, y1, y0', y1', ...} of this house's perimeter walls as last built.
     * Neighbours built later lean on these instead of building a second wall alongside.
     */
    java.util.Map<Long, int[]> wallSpan;
    /** Evolving towns: extra storeys earned by prosperity, facade variant after a rebuild, cycle a lot was cleared. */
    int bonusFloors, variant, vacantSince;
    Edge street;
    Plaza plaza;
    String style = "", tierReason = "";
    Facade facade;
    P c;

    P frontMid() { return q[0].lerp(q[1], 0.5); }
    P rearMid() { return q[3].lerp(q[2], 0.5); }
    P outward() { return frontMid().sub(rearMid()).norm(); }
}

final class CityBlock {
    long key;
    Face face;
    List<P> facePoly;
    Area buildable;
    final List<List<P>> rings = new ArrayList<>();
    final List<List<P>> gardens = new ArrayList<>();
    /** Sharp block corners left open as small paved squares (never absorbed into houses). */
    final List<List<P>> tips = new ArrayList<>();
    /** District style fixed when the block is first built, so a block never mixes wall materials later. */
    String style;
    /** Evolving towns: cleared house lots, kept as gardens until rebuilt. */
    final List<House> vacant = new ArrayList<>();
    final List<House> houses = new ArrayList<>();
    final List<House> openUnits = new ArrayList<>();
    double faceArea, buildArea, gardenArea, frontage;
    String status = "new";
    /** Villager mode: once a block is being built its parcels are frozen (anchor + area identify it later). */
    boolean locked;
    P lockAnchor;
    double lockArea;
}

final class DecisionLog {
    final Map<String, Integer> counts = new TreeMap<>();
    final List<String> events = new ArrayList<>();
    void count(String k) { counts.merge(k, 1, Integer::sum); }
    void count(String k, int n) { if (n != 0) counts.merge(k, n, Integer::sum); }
    void event(int phase, String msg) { events.add(String.format("[phase %d] %s", phase, msg)); }
}

/** Whole-city state: network, centres, parcels, architecture, plus spatial indices and diagnostics. */
final class CityModel {
    /** Streets to take out so blocks merge into a larger square for a free-standing monument (applied where the graph may change). */
    final List<Edge> squareMerges = new ArrayList<>();
    /** Villager mode: footprints of every house standing in the world (snapshot taken before each re-planning). */
    volatile List<P[]> standing = List.of();

    final Config cfg;
    final Terrain t;
    final long seed;
    final StreetGraph g = new StreetGraph();
    final List<Center> centers = new ArrayList<>();
    final List<Plaza> plazas = new ArrayList<>();
    final List<Landmark> landmarks = new ArrayList<>();
    List<CityBlock> blocks = new ArrayList<>();
    List<House> houses = new ArrayList<>();
    List<Chain> chains = new ArrayList<>();
    Map<Long, CityBlock> blockCache = new HashMap<>();
    final List<CityBlock> lockedBlocks = new ArrayList<>();
    /** Grid cells the town must leave alone (existing structures in the world); null = none. */
    boolean[] keepOut;

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
