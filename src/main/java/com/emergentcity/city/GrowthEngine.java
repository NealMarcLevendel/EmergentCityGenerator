package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/**
 * Incremental street growth (spec G steps 2-4). Growth "heads" sit on street ends; each step a head
 * proposes several local extensions (bends, straight, connection to an existing street/node), every
 * proposal passes hard geometric/topological checks and is then scored by weighted soft costs.
 * One proposal is chosen by a seeded softmax (controlled stochastic selection).
 */
final class GrowthEngine {
    enum Type { FREE, NODE, EDGE }

    static final class Head {
        int id, cls, steps;
        Node node;
        double heading, bias, distL, distR, runHeading, axisLeft;
        P runStart;
        boolean axis, seedRoad, infill, repair, dormant, boulevard;
    }

    static final class Proposal {
        double heading, delta, len, cost, vistaExcess, landmarkAlign, loopPerim = -1;
        P end;
        Type type = Type.FREE;
        Node targetNode;
        Edge targetEdge;
        boolean bridge;
        String reject;

        static Proposal rej(String r) { Proposal p = new Proposal(); p.reject = r; return p; }
    }

    private record Hit(Edge e, double t, P p) {}

    private static final double[] WIDE_TURNS = {-75, -60, -45, -30, -18, -8, 0, 8, 18, 30, 45, 60, 75};
    private static final double[] HILL_TURNS = {-90, -75, -60, -45, -32, 32, 45, 60, 75, 90};

    final CityModel m;
    final Config c;
    final StreetGraph g;
    final SplittableRandom rng;
    final List<Head> heads = new ArrayList<>();

    /** Existing structures in the world (villager mode): streets may not be laid through them. */
    interface Obstacle { boolean blocked(P a, P b, double halfWidth); }
    Obstacle obstacle;
    int headIds;
    double radius;
    private final double minJunction, minCross, lookAhead, snapFrac, snapEndpoint, minSeg, clearFree, clearNode,
            minLoop, goodLoopMax, maxLoopSearch, maxTurn, temperature, margin, curvStrength, minBlockDepth;
    private final double[] turns;

    GrowthEngine(CityModel m) {
        this.m = m; this.c = m.cfg; this.g = m.g;
        rng = new SplittableRandom(m.seed * 0x9E3779B97F4A7C15L + 17);
        minJunction = Math.toRadians(c.d("growth.minJunctionAngleDeg"));
        minCross = Math.toRadians(c.d("growth.minCrossAngleDeg"));
        lookAhead = c.d("growth.lookAhead");
        snapFrac = c.d("growth.snapNodeFraction");
        snapEndpoint = c.d("growth.snapEndpoint");
        minSeg = c.d("growth.minSegment");
        clearFree = c.d("growth.clearanceFree");
        clearNode = c.d("growth.clearanceNode");
        minLoop = c.d("growth.minLoopPerimeter");
        goodLoopMax = c.d("growth.goodLoopPerimeterMax");
        maxLoopSearch = c.d("growth.maxLoopSearch");
        maxTurn = Math.toRadians(c.d("growth.maxTurnDeg"));
        temperature = c.d("growth.temperature");
        margin = c.d("growth.mapMargin");
        curvStrength = c.d("growth.curvatureStrength");
        minBlockDepth = c.d("growth.minBlockDepth");
        turns = c.arr("growth.turnSamplesDeg");
        radius = c.d("growth.initialRadius");
    }

    double segLen(int cls) {
        return switch (cls) {
            case 1 -> c.d("growth.segmentLength.local");
            case 2 -> c.d("growth.segmentLength.connector");
            default -> c.d("growth.segmentLength.primary");
        };
    }

    double maxGrade(int cls) {
        return switch (cls) {
            case 1 -> c.d("growth.maxGrade.local");
            case 2 -> c.d("growth.maxGrade.connector");
            default -> c.d("growth.maxGrade.primary");
        };
    }

    static String clsName(int cls) { return switch (cls) { case 1 -> "local"; case 2 -> "connector"; case 3 -> "primary"; default -> "civic"; }; }

    double classMin(int cls) { return c.d("street." + clsName(cls) + ".min"); }
    double classMax(int cls) { return c.d("street." + clsName(cls) + ".max"); }
    double classBaseWidth(int cls) { return 0.5 * (classMin(cls) + classMax(cls)); }

    // ------------------------------------------------------------------ seeding (spec G step 1)

    void seed() {
        List<P> cs = chooseInitialCenters();
        for (P p : cs) {
            Node n = g.addNode(p, 0);
            Center ce = new Center();
            ce.id = m.centers.size(); ce.node = n; ce.pos = p; ce.seeded = true; ce.bornPhase = 0;
            ce.attraction = c.d("centers.attraction"); ce.strength = ce.attraction;
            ce.why = "seeded at best terrain suitability (flat, dry, near water when available)";
            m.centers.add(ce);
            m.log.event(0, String.format("Seeded centre %d at (%.0f, %.0f): %s", ce.id, p.x(), p.y(), ce.why));
            int nb = ce.id == 0 ? Math.max(0, Math.min(4, c.i("mc.boulevards"))) : 0;
            double bh = nb > 0 ? spawnBoulevards(n, nb) : Double.NaN;
            if (nb >= 2) spawnSeedRoads(n, Math.max(0, c.i("growth.seedRoads") - nb), bh + Math.PI / 4);
            else spawnSeedRoads(n, c.i("growth.seedRoads"));
        }
    }

    /**
     * Boulevards: wide avenues laid dead straight out from the main centre. The first runs along the direction
     * where the ground is gentlest and dry (and its opposite, so two boulevards make one straight line through
     * the centre); a third and fourth cross it at right angles. Returns the heading of the first.
     */
    private double spawnBoulevards(Node n, int k) {
        double R = finalRadius();
        double best = 0, bestScore = Double.MAX_VALUE;
        for (int a = 0; a < 36; a++) {
            double h = a * Math.PI / 18;
            double sc = rayCost(n.p, h, R) + (k >= 2 ? rayCost(n.p, h + Math.PI, R) : 0);
            if (k >= 3) sc += 0.7 * (rayCost(n.p, h + Math.PI / 2, R) + (k >= 4 ? rayCost(n.p, h - Math.PI / 2, R) : 0));
            if (sc < bestScore) { bestScore = sc; best = h; }
        }
        double[] hs = {best, best + Math.PI, best + Math.PI / 2, best - Math.PI / 2};
        for (int i = 0; i < k; i++) {
            Head h = newHead(n, hs[i], 3);
            h.axis = true; h.boulevard = true;
            // each boulevard runs out to a square of its own (a new centre with a monument), waiting for the town to grow
            h.axisLeft = c.d("growth.boulevardReach") * R;
        }
        m.log.event(0, String.format("Spawned %d straight boulevard(s) from the main centre, heading %.0f deg", k, Math.toDegrees(best)));
        return best;
    }

    /** How hard it is to run a straight street from p along heading h: slope, water and leaving the map. */
    private double rayCost(P p, double h, double R) {
        double cost = 0, prev = m.t.height(p);
        for (double d = 8; d <= R; d += 8) {
            P q = p.add(P.dir(h).mul(d));
            if (!m.t.inside(q, margin)) { cost += 50 * (R - d) / 8; break; }
            if (m.t.isWater(q)) cost += 40;
            double z = m.t.height(q);
            cost += Math.abs(z - prev) * 3 / (1 + d / R);
            prev = z;
        }
        return cost;
    }

    private List<P> chooseInitialCenters() {
        Terrain t = m.t;
        int k = Math.max(1, Math.min(5, c.i("centers.initialCount")));
        double aff = c.d("centers.waterAffinity"), spacing = c.d("centers.minSpacing");
        double mg = Math.min(t.widthM(), t.heightM()) * 0.18;
        List<double[]> cand = new ArrayList<>();
        boolean anyWater = t.hasWater();
        for (double x = mg; x <= t.widthM() - mg; x += 20)
            for (double y = mg; y <= t.heightM() - mg; y += 20) {
                if (t.isWater(x, y)) continue;
                if (m.keepOutAt(new P(x, y))) continue;
                double dw = t.waterDist(x, y);
                if (anyWater && dw < 70) continue;
                double nb = 0;
                for (int a = 0; a < 8; a++) nb += t.slope(x + 60 * Math.cos(a * Math.PI / 4), y + 60 * Math.sin(a * Math.PI / 4)) / 8;
                double flat = Math.exp(-Math.pow(t.slope(x, y) / 0.05, 2)) * Math.exp(-Math.pow(nb / 0.06, 2));
                double wat = anyWater ? (1 - aff) + aff * Math.exp(-dw / 200.0) : 1;
                double cx = (x - t.widthM() / 2) / (t.widthM() * 0.5), cy = (y - t.heightM() / 2) / (t.heightM() * 0.5);
                double mid = 0.6 + 0.4 * Math.exp(-(cx * cx + cy * cy) * 1.5);
                cand.add(new double[]{x, y, flat * wat * mid * (0.97 + 0.06 * rng.nextDouble())});
            }
        List<P> out = new ArrayList<>();
        for (int s = 0; s < k; s++) {
            double[] best = null;
            double bs = -1;
            for (double[] cd : cand) {
                double sc = cd[2];
                for (P o : out) sc *= 1 - Math.exp(-Math.pow(o.dist(new P(cd[0], cd[1])) / spacing, 2));
                if (sc > bs) { bs = sc; best = cd; }
            }
            if (best == null) break;
            out.add(new P(best[0], best[1]));
        }
        if (out.isEmpty()) out.add(new P(t.widthM() / 2, t.heightM() / 2));
        return out;
    }

    private void spawnSeedRoads(Node n, int k) { spawnSeedRoads(n, k, rng.nextDouble() * Math.PI * 2); }

    private void spawnSeedRoads(Node n, int k, double base) {
        if (k <= 0) return;
        for (int j = 0; j < k; j++) {
            double a = base + j * 2 * Math.PI / k + rng.nextGaussian() * 0.2;
            double chosen = a;
            outer:
            for (int off = 0; off <= 60; off += 10)
                for (int sgn : new int[]{1, -1}) {
                    double aa = a + sgn * Math.toRadians(off);
                    boolean ok = true;
                    for (double d = 40; d <= 200; d += 40) {
                        P q = n.p.add(P.dir(aa).mul(d));
                        if (m.t.isWater(q) || !m.t.inside(q, margin)) { ok = false; break; }
                    }
                    if (ok) { chosen = aa; break outer; }
                }
            Head h = newHead(n, chosen, 3);
            h.seedRoad = true;
        }
    }

    Head newHead(Node n, double heading, int cls) {
        Head h = new Head();
        h.id = headIds++; h.node = n; h.heading = heading; h.cls = cls;
        h.runStart = n.p; h.runHeading = heading;
        heads.add(h);
        return h;
    }

    // ------------------------------------------------------------------ phase loop

    /** Sets the growth radius for a phase and wakes heads that were waiting for the city to expand. */
    /** Growth never extends beyond this distance from the seeded centre (the command radius in Minecraft). */
    double maxRadius = Double.MAX_VALUE;

    void beginPhase(int phase) {
        radius = Math.min(maxRadius, c.d("growth.initialRadius") + phase * c.d("growth.radiusPerPhase"));
        for (Head h : heads) h.dormant = false;
        m.radius = radius;
        reseedIfStalled();
    }

    /**
     * On rough terrain every growing street end can die (all directions too steep, or blocked) long before the
     * budget is used, which used to leave a town of a few segments. If no street end is left, new ones start from
     * existing street nodes in their open directions, so growth keeps probing for gentler ground.
     */
    private boolean crossesKeepOut(P a, P b, double half) {
        double L = a.dist(b);
        P d = b.sub(a).norm(), n = d.perpL();
        for (double s = 0; s <= L; s += 1.0)
            for (double w = -half; w <= half + 1e-9; w += Math.max(0.5, half / 2))
                if (m.keepOutAt(a.add(d.mul(s)).add(n.mul(w)))) return true;
        return false;
    }

    void reseedIfStalled() {
        if (!heads.isEmpty() || g.aliveNodes().isEmpty()) return;
        List<Node> ns = new ArrayList<>(g.aliveNodes());
        int made = 0;
        for (int tries = 0; tries < 40 && made < 6; tries++) {
            Node n = ns.get(rng.nextInt(ns.size()));
            if (n.deg() >= 4) continue;
            double a = rng.nextDouble() * Math.PI * 2;
            if (!angleClear(n, a, minJunction, null)) continue;
            newHead(n, a, n.deg() <= 1 ? 2 : 1);
            made++;
        }
        if (made > 0) m.log.count("head:reseeded", made);
    }

    void runPhase(int phase) {
        beginPhase(phase);
        int steps = c.i("growth.stepsPerPhase");
        for (int s = 0; s < steps; s++) {
            Head h = pick();
            if (h == null) break;
            step(h, phase);
        }
        m.radius = radius;
    }

    private Head pick() { return pickFree(java.util.Collections.emptySet()); }

    /** Highest-priority head not already being worked by an agent (villager mode). */
    Head pickFree(java.util.Set<Head> busy) {
        Head best = null;
        double bp = -1e18;
        for (Head h : heads) {
            if (h.dormant || busy.contains(h)) continue;
            double p = m.attraction(h.node.p) + (h.seedRoad ? 0.35 : 0) + (h.infill || h.repair ? 0.7 : 0) + (h.axis ? 3 : 0)
                    + 0.25 * rng.nextDouble();
            if (p > bp) { bp = p; best = h; }
        }
        return best;
    }

    /** One growth decision for one head; returns the street segment laid, or null if the head stopped or waits. */
    Edge advance(Head h, int phase) { return step(h, phase); }

    /** The radius the town grows to by its last phase (the radius asked for). */
    double finalRadius() {
        return Math.min(maxRadius, c.d("growth.initialRadius") + c.i("growth.phases") * c.d("growth.radiusPerPhase"));
    }

    /** Boulevard ends waiting to become squares: {Node, P direction away from the main centre}. */
    final List<Object[]> boulevardEnds = new ArrayList<>();

    private static final double[] BOULEVARD_TURNS = {0, -3, 3, -6, 6};

    private String lastReject;

    private Edge step(Head h, int phase) {
        double L = segLen(h.cls) * (0.85 + 0.3 * rng.nextDouble());
        List<Proposal> ok = new ArrayList<>();
        boolean onlyRadius = true;
        // a fresh street may set off in any reasonable direction; on hills it needs room to find the contour
        // axes run dead straight; a boulevard may sidestep an obstacle by a few degrees but keeps its heading
        double[] ds = h.boulevard ? BOULEVARD_TURNS : h.axis ? new double[]{0} : (h.steps == 0 ? WIDE_TURNS : turns);
        boolean sawGrade = false;
        for (int pass = 0; pass < 2; pass++) {
            for (double dDeg : ds) {
                double delta = Math.toRadians(dDeg);
                Proposal p = evaluate(h, h.heading + delta, delta, L, phase);
                if (p.reject == null) ok.add(p);
                else {
                    m.log.count("reject:" + p.reject);
                    if (Math.abs(dDeg) < 1e-9 || lastReject == null) lastReject = p.reject;
                    if (p.reject.equals("grade")) sawGrade = true;
                    if (!p.reject.equals("radius")) onlyRadius = false;
                }
            }
            // too steep straight on: turn harder to follow the slope (switchback-like contour streets)
            if (!ok.isEmpty() || !sawGrade || h.axis || ds == WIDE_TURNS) break;
            ds = HILL_TURNS;
            m.log.count("retry:steep-turn");
        }
        if (ok.isEmpty()) {
            if (h.infill && lastReject != null) m.log.count("cut-failed:" + lastReject);
            lastReject = null;
            if (onlyRadius && (!h.axis || h.boulevard)) { h.dormant = true; return null; }
            heads.remove(h);
            m.log.count(h.axis ? "axis:stopped" : "head:stopped");
            // a boulevard stopped by the terrain still ends in a square, if it got some way out
            if (h.boulevard && h.node.p.dist(m.centers.get(0).pos) > 0.3 * finalRadius())
                boulevardEnds.add(new Object[]{h.node, P.dir(h.heading)});
            if (h.axis) m.log.event(phase, "Landmark approach axis stopped after obstacles/constraints");
            return null;
        }
        return apply(h, choose(ok), phase);
    }

    private Proposal choose(List<Proposal> ok) {
        double min = Double.MAX_VALUE;
        for (Proposal p : ok) min = Math.min(min, p.cost);
        double[] w = new double[ok.size()];
        double sum = 0;
        for (int i = 0; i < ok.size(); i++) { w[i] = Math.exp(-(ok.get(i).cost - min) / temperature); sum += w[i]; }
        double r = rng.nextDouble() * sum;
        for (int i = 0; i < ok.size(); i++) { r -= w[i]; if (r <= 0) return ok.get(i); }
        return ok.get(ok.size() - 1);
    }

    // ------------------------------------------------------------------ proposal evaluation

    boolean angleClear(Node n, double heading, double min, Node ignoreOther) {
        for (Edge e : n.edges) {
            if (ignoreOther != null && e.other(n) == ignoreOther) continue;
            if (Math.abs(Geom.angDiff(e.angleFrom(n), heading)) < min) return false;
        }
        return true;
    }

    private Hit firstHit(P a, P b, Node ex1, Node ex2) {
        List<Edge> cand = g.edgesNear(a.x(), a.y(), b.x(), b.y());
        Hit best = null;
        for (Edge e : cand) {
            if (ex1 != null && (e.a == ex1 || e.b == ex1)) continue;
            if (ex2 != null && (e.a == ex2 || e.b == ex2)) continue;
            double[] tu = Geom.segInter(a, b, e.a.p, e.b.p);
            if (tu == null) continue;
            if (tu[0] > 1e-6 && tu[0] <= 1 && tu[1] >= -1e-9 && tu[1] <= 1 + 1e-9) {
                if (best == null || tu[0] < best.t) best = new Hit(e, tu[0], a.lerp(b, tu[0]));
            }
        }
        return best;
    }

    private double headWidth(Head h) { return h.boulevard ? c.d("street.boulevard.width") : classMin(h.axis ? 4 : h.cls); }

    /**
     * True when the street a-b would run alongside another street (within 35 degrees) closer than a full block:
     * growth.minBlockDepth of buildable depth (two rows of plots and a courtyard) plus half of each street's width.
     * Streets meeting a or b (the junctions at either end) are not compared. Samples along the stretch t0..t1.
     */
    private boolean thinBlock(P a, P b, double wNew, Node ex1, Node ex2, Edge exE, double t0, double t1) {
        double len = a.dist(b);
        if (len < 1e-6 || minBlockDepth <= 0) return false;
        P u = b.sub(a).mul(1 / len);
        double reach = minBlockDepth + (wNew + 16) / 2;
        for (double t = t0; t <= t1 + 1e-9; t += 0.25) {
            P q = a.lerp(b, t);
            for (Edge e : g.edgesNear(q.x() - reach, q.y() - reach, q.x() + reach, q.y() + reach)) {
                if (e == exE) continue;
                if (ex1 != null && (e.a == ex1 || e.b == ex1)) continue;
                if (ex2 != null && (e.a == ex2 || e.b == ex2)) continue;
                P d = e.b.p.sub(e.a.p);
                double el = Math.hypot(d.x(), d.y());
                if (el < 1e-6) continue;
                double sin = Math.abs(u.x() * d.y() - u.y() * d.x()) / el;
                if (sin > 0.57) continue; // crossing or meeting at an angle: the ordinary clearances apply
                double along = q.sub(e.a.p).dot(d) / (el * el);
                if (along < 0.05 || along > 0.95) continue; // end to end (the same street running on), not side by side
                if (Geom.distPointSeg(q, e.a.p, e.b.p) < minBlockDepth + (wNew + e.width) / 2) return true;
            }
        }
        return false;
    }

    private double minEdgeDist(P p, Node exclude) {
        double best = Double.MAX_VALUE;
        for (Edge e : g.edgesNear(p.x() - clearFree, p.y() - clearFree, p.x() + clearFree, p.y() + clearFree)) {
            if (exclude != null && (e.a == exclude || e.b == exclude)) continue;
            best = Math.min(best, Geom.distPointSeg(p, e.a.p, e.b.p));
        }
        return best;
    }

    /** Shortest network distance from src to t1 (+off1) or t2 (+off2), bounded by maxLoopSearch. */
    private double graphDist(Node src, Node t1, Node t2, double off1, double off2) {
        HashMap<Integer, Double> dist = new HashMap<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>(Comparator.comparingDouble(o -> (Double) o[0]));
        dist.put(src.id, 0.0);
        pq.add(new Object[]{0.0, src});
        double best = Double.MAX_VALUE;
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            double d = (Double) top[0];
            Node u = (Node) top[1];
            if (d > dist.getOrDefault(u.id, Double.MAX_VALUE) + 1e-9) continue;
            if (d > maxLoopSearch || d >= best) break;
            if (u == t1) best = Math.min(best, d + off1);
            if (t2 != null && u == t2) best = Math.min(best, d + off2);
            for (Edge e : u.edges) {
                Node v = e.other(u);
                double nd = d + e.len();
                if (nd < dist.getOrDefault(v.id, Double.MAX_VALUE)) { dist.put(v.id, nd); pq.add(new Object[]{nd, v}); }
            }
        }
        return best;
    }

    private boolean withinRadius(P end, Head h) {
        if (h.infill || h.repair || h.axis) return true;
        for (Center ce : m.centers) {
            double lim = radius * (ce.seeded ? 1 : 0.65) * (h.cls == 3 ? 1.5 : 1);
            if (ce.pos.dist(end) <= lim) return true;
        }
        return false;
    }

    /** A street may run along a block square's edge but never across it. */
    private boolean entersBlockSquare(P a, P b) {
        for (Plaza p : m.plazas) {
            if (!p.block || p.poly.size() < 3) continue;
            for (double t = 0.2; t < 0.85; t += 0.3) {
                P q = a.lerp(b, t);
                if (!Geom.pointInPoly(q, p.poly)) continue;
                double edge = 1e9;
                for (int i = 0; i < p.poly.size(); i++) edge = Math.min(edge, Geom.distPointSeg(q, p.poly.get(i), p.poly.get((i + 1) % p.poly.size())));
                if (edge > 2.0) return true;
            }
        }
        return false;
    }

    private boolean hitsLandmark(P a, P b) {
        for (Landmark l : m.landmarks) if (Geom.segHitsPoly(a, b, Geom.expandRect(l.fp, 3))) return true;
        return false;
    }

    double landmarkAlign(P from, double heading) {
        double best = 0;
        for (Landmark l : m.landmarks) {
            P to = l.front.sub(from);
            double d = to.len();
            if (d < 30 || d > 500) continue;
            double diff = Math.abs(Geom.angDiff(heading, to.angle()));
            if (diff < Math.toRadians(5)) best = Math.max(best, 1 - diff / Math.toRadians(5));
        }
        return best;
    }

    Proposal evaluate(Head h, double heading, double delta, double L, int phase) {
        Node src = h.node;
        // a street cutting through an oversized block has little choice of angle where it meets the far side
        double mj = h.infill ? minJunction * 0.75 : minJunction;
        P p0 = src.p, dir = P.dir(heading);
        if (!angleClear(src, heading, mj, null)) return Proposal.rej("junction-angle");
        Proposal pr = new Proposal();
        pr.heading = heading; pr.delta = delta;
        double Lnom = L;
        P nominal = p0.add(dir.mul(L));
        if (m.t.isWater(nominal)) {
            if (h.cls < 2 || phase < c.i("growth.bridgeMinPhase") || m.bridges >= c.i("growth.maxBridges")) return Proposal.rej("water");
            double ext = L;
            boolean found = false;
            while (ext < L + c.d("growth.bridgeMaxLength")) {
                ext += 2;
                if (!m.t.isWater(p0.add(dir.mul(ext)))) { found = true; break; }
            }
            if (!found) return Proposal.rej("water");
            Lnom = ext + 6;
            nominal = p0.add(dir.mul(Lnom));
        }
        double look = Lnom + (lookAhead - 1) * L;
        Hit hit = firstHit(p0, p0.add(dir.mul(look)), src, null);
        double minLoopHere = h.axis ? minLoop * 0.6 : minLoop;
        if (hit != null) {
            Edge e = hit.e();
            P x = hit.p();
            double crossAng = Math.abs(Math.sin(Geom.angDiff(heading, e.dir().angle())));
            if (crossAng < Math.sin(minCross)) return Proposal.rej("cross-angle");
            if (x.dist(e.a.p) < snapEndpoint) { pr.type = Type.NODE; pr.targetNode = e.a; }
            else if (x.dist(e.b.p) < snapEndpoint) { pr.type = Type.NODE; pr.targetNode = e.b; }
            else {
                pr.type = Type.EDGE; pr.targetEdge = e; pr.end = x;
                if (x.dist(e.a.p) < minSeg || x.dist(e.b.p) < minSeg) return Proposal.rej("sliver-split");
            }
            if (pr.type == Type.NODE) {
                Node tn = pr.targetNode;
                pr.end = tn.p;
                if (tn == src || src.edgeTo(tn) != null) return Proposal.rej("duplicate");
                double hd = tn.p.sub(p0).angle();
                if (!angleClear(src, hd, mj, null)) return Proposal.rej("junction-angle");
                if (!angleClear(tn, p0.sub(tn.p).angle(), mj, null)) return Proposal.rej("junction-angle");
                if (firstHit(p0, tn.p, src, tn) != null) return Proposal.rej("crossing");
            }
            pr.len = p0.dist(pr.end);
            if (pr.len < minSeg) return Proposal.rej("short");
            if (!h.axis && thinBlock(p0, pr.end, headWidth(h), src, pr.targetNode, pr.targetEdge, 0.3, 0.7)) return Proposal.rej("thin-block");
            double gd = pr.type == Type.NODE ? graphDist(src, pr.targetNode, null, 0, 0)
                    : graphDist(src, e.a, e.b, x.dist(e.a.p), x.dist(e.b.p));
            pr.loopPerim = gd < Double.MAX_VALUE ? gd + pr.len : -1;
            if (pr.loopPerim > 0 && pr.loopPerim < minLoopHere) return Proposal.rej("tiny-block");
        } else {
            Node near = null;
            double nd = Lnom * snapFrac;
            for (Node n : g.nodesNear(nominal, Lnom * snapFrac)) {
                if (n == src || src.edgeTo(n) != null) continue;
                double d = n.p.dist(nominal);
                if (d < nd) { nd = d; near = n; }
            }
            if (near != null) {
                pr.type = Type.NODE; pr.targetNode = near; pr.end = near.p;
                double hd = near.p.sub(p0).angle();
                if (!angleClear(src, hd, mj, null) || !angleClear(near, p0.sub(near.p).angle(), mj, null))
                    return Proposal.rej("junction-angle");
                if (firstHit(p0, near.p, src, near) != null) return Proposal.rej("crossing");
                pr.len = p0.dist(near.p);
                if (pr.len < minSeg) return Proposal.rej("short");
                if (!h.axis && thinBlock(p0, near.p, headWidth(h), src, near, null, 0.3, 0.7)) return Proposal.rej("thin-block");
                double gd = graphDist(src, near, null, 0, 0);
                pr.loopPerim = gd < Double.MAX_VALUE ? gd + pr.len : -1;
                if (pr.loopPerim > 0 && pr.loopPerim < minLoopHere) return Proposal.rej("tiny-block");
            } else {
                pr.type = Type.FREE; pr.end = nominal; pr.len = Lnom;
                if (!g.nodesNear(nominal, clearNode).isEmpty()) return Proposal.rej("clearance-node");
                // (a street cutting a block is checked by thinBlock below, which accounts for the block depth on each side)
                if (!h.infill && minEdgeDist(nominal, src) < clearFree) return Proposal.rej("clearance-edge");
                if (!h.infill && minEdgeDist(p0.lerp(nominal, 0.5), src) < clearFree * 0.6) return Proposal.rej("parallel");
                if (!h.axis && thinBlock(p0, nominal, headWidth(h), src, null, null, 0.25, 1.0)) return Proposal.rej("thin-block");
                for (Node n : g.nodesNear(p0.lerp(nominal, 0.5), Lnom)) {
                    if (n == src) continue;
                    if (Geom.distPointSeg(n.p, p0, nominal) < clearNode) return Proposal.rej("clearance-node");
                }
            }
        }
        P end = pr.end;
        if (!m.t.inside(end, margin)) return Proposal.rej("bounds");
        if (pr.type == Type.FREE && !withinRadius(end, h)) return Proposal.rej("radius");
        double grade = m.t.maxGrade(p0, end);
        if (grade > maxGrade(Math.min(h.cls, 3)) * (h.axis ? 1.2 : 1)) return Proposal.rej("grade");
        double wl = m.t.waterLength(p0, end);
        if (wl > 0) {
            if (h.cls < 2 || phase < c.i("growth.bridgeMinPhase") || m.bridges >= c.i("growth.maxBridges")
                    || wl > c.d("growth.bridgeMaxLength") || m.t.isWater(end) || m.t.isWater(p0)) return Proposal.rej("water");
            pr.bridge = true;
        }
        if (hitsLandmark(p0, end)) return Proposal.rej("landmark");
        if (entersBlockSquare(p0, end)) return Proposal.rej("square");
        if (obstacle != null && obstacle.blocked(p0, end, classMin(Math.min(h.cls, 4)) / 2 + 1)) return Proposal.rej("building");
        if (m.keepOut != null && crossesKeepOut(p0, end, classMin(Math.min(h.cls, 4)) / 2 + 1)) return Proposal.rej("structure");

        // ---- soft costs
        double cost = c.d("w.grade") * Math.pow(grade / maxGrade(Math.min(h.cls, 3)), 2);
        double turnDev = (delta - h.bias) / maxTurn;
        cost += c.d("w.curvature") * turnDev * turnDev;
        if (Math.abs(delta) < 1e-9 && !h.axis) cost += c.d("w.straight");
        double run = Math.abs(Geom.angDiff(heading, h.runHeading)) < Math.toRadians(8) ? h.runStart.dist(end) : pr.len;
        double maxRun = c.d("vista.maxWidths") * classBaseWidth(Math.min(h.cls, 3));
        pr.landmarkAlign = landmarkAlign(end, heading);
        if (pr.type == Type.FREE && run > maxRun && pr.landmarkAlign < 0.2 && !h.axis) {
            boolean endpoint = firstHit(end, end.add(dir.mul(maxRun * 0.6)), null, null) != null || m.rayHitsBuilt(end, dir, maxRun * 0.6);
            if (!endpoint) { pr.vistaExcess = (run - maxRun) / maxRun; cost += c.d("w.vista") * pr.vistaExcess; }
        }
        cost -= c.d("w.landmarkAxis") * pr.landmarkAlign;
        cost -= c.d("w.attraction") * m.attraction(end);
        double rr = m.nearestCenterDist(end) / radius;
        if (rr > 0.8) cost += c.d("w.radius") * (rr - 0.8) * (rr - 0.8);
        if (pr.type != Type.FREE) {
            double q = pr.loopPerim < 0 ? 0.6 : (pr.loopPerim <= goodLoopMax ? 1 : 0.4);
            cost -= c.d("w.connect") * q;
        }
        if (pr.bridge) cost += c.d("w.bridge");
        pr.cost = cost;
        return pr;
    }

    // ------------------------------------------------------------------ applying a proposal

    private Edge apply(Head h, Proposal p, int phase) {
        Node src = h.node, dst;
        switch (p.type) {
            case FREE -> dst = g.addNode(p.end, phase);
            case NODE -> dst = p.targetNode;
            default -> dst = g.splitEdge(p.targetEdge, p.end, phase);
        }
        Edge e = g.addEdge(src, dst, phase);
        e.cls = h.axis ? 4 : h.cls;
        e.clsPrev = e.cls;
        e.width = classMin(e.cls);
        e.wA = e.wB = e.width;
        e.seedRoad = h.seedRoad; e.axis = h.axis; e.bridge = p.bridge; e.infill = h.infill;
        e.boulevard = h.boulevard;
        if (h.boulevard) { e.width = c.d("street.boulevard.width"); e.wA = e.wB = e.width; }
        if (p.bridge) {
            m.bridges++;
            m.log.event(phase, String.format("Bridge built at (%.0f, %.0f): %s street needed to cross water (span ~%.0f m)",
                    e.mid().x(), e.mid().y(), clsName(e.cls), m.t.waterLength(src.p, dst.p)));
        }
        m.log.count("accept:" + p.type.name().toLowerCase());
        if (p.vistaExcess > 0) m.log.count("accept:with-vista-penalty");
        double actual = dst.p.sub(src.p).angle();
        if (Math.abs(Geom.angDiff(actual, h.runHeading)) >= Math.toRadians(8)) { h.runStart = src.p; h.runHeading = actual; }
        if (p.type != Type.FREE && !h.axis) { heads.remove(h); return e; }
        if (h.axis) {
            h.axisLeft -= p.len;
            h.node = dst;
            if (h.boulevard) {
                // side streets leave a boulevard at right angles, one block apart, so the blocks along it are regular
                double sp = c.d("growth.boulevardBranchSpacing");
                h.distL += p.len; h.distR += p.len;
                for (int side : new int[]{1, -1}) {
                    double dist = side > 0 ? h.distL : h.distR;
                    if (dist < sp * (0.9 + 0.2 * rng.nextDouble())) continue;
                    double a = h.heading + side * Math.PI / 2;
                    if (!angleClear(dst, a, minJunction, null)) continue;
                    newHead(dst, a, 2);
                    if (side > 0) h.distL = 0; else h.distR = 0;
                    m.log.count("branch:boulevard");
                }
            }
            if (h.axisLeft <= 0) {
                heads.remove(h);
                if (h.boulevard) boulevardEnds.add(new Object[]{dst, P.dir(h.heading)});
                m.log.event(phase, h.boulevard ? "Boulevard reached its square" : "Landmark approach axis completed");
            }
            return e;
        }
        h.node = dst;
        h.bias = 0.7 * h.bias + 0.3 * p.delta + rng.nextGaussian() * Math.toRadians(6) * curvStrength;
        h.bias = Geom.clamp(h.bias, -maxTurn * 0.6, maxTurn * 0.6);
        h.heading = actual;
        h.steps++;
        h.distL += p.len;
        h.distR += p.len;
        double fmin = c.d("growth.branchFrontageMin"), fmax = c.d("growth.branchFrontageMax");
        for (int side : new int[]{1, -1}) {
            double dist = side > 0 ? h.distL : h.distR;
            double prob = Geom.clamp((dist - fmin) / (fmax - fmin), 0, 1);
            if (rng.nextDouble() < prob) {
                double a = h.heading + side * (Math.PI / 2 + rng.nextGaussian() * Math.toRadians(c.d("growth.branchAngleJitterDeg")));
                int bc = h.cls == 3 ? 2 : (h.cls == 2 ? (rng.nextDouble() < 0.3 ? 2 : 1) : 1);
                newHead(dst, a, bc);
                if (side > 0) h.distL = 0; else h.distR = 0;
                m.log.count("branch");
            }
        }
        return e;
    }

    // ------------------------------------------------------------------ feedback-driven spawning

    /** Oversize block -> passage/street cutting it (spec E: subdivide blocks that are too large). */
    /**
     * After the last growth phase: cut every block still larger than block.maxArea with a street, stepping only
     * those cutting streets, so blocks formed late at the edge of town end up the same size as the rest.
     */
    void cutOversizeBlocks(int phase) {
        List<Head> others = new ArrayList<>(heads);
        heads.clear();
        List<Face> big = new ArrayList<>();
        for (CityBlock b : m.blocks) if (!b.locked && b.face != null && b.faceArea > c.d("block.maxArea")) big.add(b.face);
        big.sort((a, b) -> Double.compare(b.area(), a.area()));
        for (Face f : big) {
            boolean valid = true;
            for (Edge e : f.edges()) if (!e.alive) { valid = false; break; }
            if (valid) spawnInfill(f, phase);
        }
        for (int it = 0; it < 60 && !heads.isEmpty(); it++)
            for (Head h : new ArrayList<>(heads)) {
                if (h.dormant) { heads.remove(h); continue; }
                step(h, phase);
            }
        heads.clear();
        heads.addAll(others);
        m.log.count("final-cuts", big.size());
        if (System.getenv("GROWDBG") != null) System.out.println("  final cuts: blocks=" + m.blocks.size() + " big=" + big.size() + " maxArea=" + c.d("block.maxArea"));
    }

    void spawnInfill(Face f, int phase) {
        Edge best = null;
        Node from = null;
        for (int i = 0; i < f.edges().size(); i++) {
            Edge e = f.edges().get(i);
            if (!e.alive) continue;
            if (best == null || e.len() > best.len()) { best = e; from = f.cycle().get(i); }
        }
        if (best == null || best.len() < 2 * minSeg + 4) return;
        Node to = best.other(from);
        P mid = from.p.lerp(to.p, 0.45 + 0.1 * rng.nextDouble());
        P inward = to.p.sub(from.p).norm().perpL();
        Node n = g.splitEdge(best, mid, phase);
        Head h = newHead(n, inward.angle() + rng.nextGaussian() * Math.toRadians(6), 1);
        h.infill = true;
        m.log.count("spawn:infill");
    }

    void spawnRepair(Node n) {
        if (n.deg() != 1) return;
        Edge e = n.edges.get(0);
        Head h = newHead(n, n.p.sub(e.other(n).p).angle(), Math.min(e.cls, 3));
        h.repair = true;
        m.log.count("spawn:repair");
    }

    /** A new centre attracts routes: grow connectors into its widest angular gaps. */
    void spawnFromCenter(Center ce, int phase) {
        Node n = ce.node;
        List<Double> angs = new ArrayList<>();
        for (Edge e : n.edges) angs.add(e.angleFrom(n));
        angs.sort(Double::compare);
        int spawned = 0;
        for (int i = 0; i < angs.size(); i++) {
            double a0 = angs.get(i), a1 = i + 1 < angs.size() ? angs.get(i + 1) : angs.get(0) + 2 * Math.PI;
            if (a1 - a0 <= Math.toRadians(110)) continue;
            double a = (a0 + a1) / 2;
            // at a boulevard's square nothing carries straight on: the far side is kept whole for its monument
            if (ce.boulevardEnd && ce.boulevardDir != null && Math.abs(Geom.angDiff(a, ce.boulevardDir.angle())) < Math.toRadians(50)) {
                double s1 = ce.boulevardDir.angle() + Math.toRadians(60), s2 = ce.boulevardDir.angle() - Math.toRadians(60);
                for (double sa : new double[]{s1, s2}) if (Geom.angDiff(sa, a0) > 0 && Geom.angDiff(a1, sa) > 0 && angleClear(n, sa, minJunction, null)) { newHead(n, sa, 2); spawned++; }
                continue;
            }
            newHead(n, a, 2); spawned++;
        }
        if (spawned > 0) m.log.event(phase, "Centre " + ce.id + " spawned " + spawned + " connector(s) into open directions");
    }

    /** Baroque-style purposeful axis: one straight civic approach aimed at the main landmark's facade. */
    void spawnAxis(Landmark lm, Center ce, int phase) {
        Node n = ce.node;
        double heading = lm.axisDir.angle();
        if (angleClear(n, heading, minJunction, null)) {
            Head h = newHead(n, heading, 3);
            h.axis = true;
            h.axisLeft = c.d("landmark.axisMaxLength");
            m.log.event(phase, String.format("Spawned straight civic approach axis toward %s (purposeful terminating vista)", lm.type));
        } else {
            for (Edge e : n.edges) if (Math.abs(Geom.angDiff(e.angleFrom(n), heading)) < Math.toRadians(25)) {
                e.axis = true;
                m.log.event(phase, "Existing street aligned with " + lm.type + " promoted to civic approach");
                break;
            }
        }
    }
}
