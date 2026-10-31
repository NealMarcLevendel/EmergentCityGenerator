package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/**
 * Evaluation + feedback between growth phases (spec G steps 5-9): movement demand, street hierarchy
 * and widths, centre strength and emergence, plazas, landmarks, and the attraction field that steers
 * the next phase of growth.
 */
final class Analysis {
    private Analysis() {}

    static void phaseUpdate(CityModel m, int phase, GrowthEngine ge, boolean fin) { phaseUpdate(m, phase, ge, fin, !fin); }

    /** spawn=false defers feedback spawning (it edits the graph) so it can run on the server thread. */
    static void phaseUpdate(CityModel m, int phase, GrowthEngine ge, boolean fin, boolean spawn) {
        m.chains = m.g.chains(Math.toRadians(40));
        computeDemand(m, phase);
        updateClassesAndWidths(m, ge);
        updateCenters(m, phase, ge, fin);
        updatePlazas(m, phase);
        updateCemeteries(m, phase);
        Parcels.build(m, phase);
        placeLandmarks(m, phase, ge, fin);
        Parcels.removeLandmarkOverlaps(m);
        m.indexHouses();
        Vista.castRays(m);
        Architecture.assign(m);
        if (fin) {
            Vista.enclosureAndProportion(m);
            Vista.landmarkVisibility(m);
        }
        updateAttraction(m);
        if (spawn) spawnFeedback(m, phase, ge);
        m.phaseSummaries.put("phase " + phase, String.format(
                "nodes=%d edges=%d len=%.0fm blocks=%d houses=%d centres=%d plazas=%d landmarks=%d bridges=%d heads=%d",
                m.g.aliveNodes().size(), m.g.aliveEdges().size(), m.g.totalLength(), m.blocks.size(), m.houses.size(),
                m.centers.size(), m.plazas.size(), m.landmarks.size(), m.bridges, ge.heads.size()));
    }

    // ------------------------------------------------------------------ movement demand (spec B)

    /**
     * Demand proxy: trips from every node (weighted by nearby houses) to each centre plus sampled local
     * trips, routed on shortest grade-penalised paths; flow is accumulated per edge via shortest-path trees.
     * Replaceable by a full origin-destination simulation.
     */
    static void computeDemand(CityModel m, int phase) {
        List<Node> ns = m.g.aliveNodes();
        List<Edge> es = m.g.aliveEdges();
        int N = ns.size();
        if (N < 2) return;
        HashMap<Integer, Integer> ni = new HashMap<>(), ei = new HashMap<>();
        for (int i = 0; i < N; i++) ni.put(ns.get(i).id, i);
        for (int i = 0; i < es.size(); i++) ei.put(es.get(i).id, i);
        double[] cost = new double[es.size()];
        for (int i = 0; i < es.size(); i++) {
            Edge e = es.get(i);
            cost[i] = e.len() * (1 + 4 * m.t.maxGrade(e.a.p, e.b.p));
        }
        double[] w = new double[N];
        Arrays.fill(w, 1);
        for (House h : m.houses) {
            Node best = null;
            double bd = 80;
            for (Node n : m.g.nodesNear(h.c, 80)) { double d = n.p.dist(h.c); if (d < bd) { bd = d; best = n; } }
            if (best != null) w[ni.get(best.id)] += 1;
        }
        double[] flow = new double[es.size()];
        List<double[]> roots = new ArrayList<>(); // idx, weight
        for (Center ce : m.centers) if (ni.containsKey(ce.node.id)) roots.add(new double[]{ni.get(ce.node.id), 2.5 * ce.attraction});
        SplittableRandom r = new SplittableRandom(m.seed * 7919 + phase * 104729L);
        int K = m.cfg.i("demand.sampleRoots");
        double tw = 0;
        for (double x : w) tw += x;
        for (int k = 0; k < K; k++) {
            double t = r.nextDouble() * tw;
            int pick = 0;
            for (; pick < N - 1; pick++) { t -= w[pick]; if (t <= 0) break; }
            roots.add(new double[]{pick, m.cfg.d("demand.localTripWeight") / K});
        }
        double[] dist = new double[N];
        int[] parent = new int[N], pedge = new int[N];
        for (double[] root : roots) {
            int s = (int) root[0];
            Arrays.fill(dist, Double.MAX_VALUE);
            Arrays.fill(parent, -1);
            dist[s] = 0;
            PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0]));
            pq.add(new double[]{0, s});
            while (!pq.isEmpty()) {
                double[] top = pq.poll();
                int u = (int) top[1];
                if (top[0] > dist[u]) continue;
                for (Edge e : ns.get(u).edges) {
                    int ix = ei.get(e.id);
                    int v = ni.get(e.other(ns.get(u)).id);
                    double nd = dist[u] + cost[ix];
                    if (nd < dist[v]) { dist[v] = nd; parent[v] = u; pedge[v] = ix; pq.add(new double[]{nd, v}); }
                }
            }
            Integer[] order = new Integer[N];
            for (int i = 0; i < N; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Double.compare(dist[b], dist[a]));
            double[] sub = w.clone();
            for (int v : order) {
                if (dist[v] == Double.MAX_VALUE || parent[v] < 0) continue;
                flow[pedge[v]] += sub[v] * root[1];
                sub[parent[v]] += sub[v];
            }
        }
        double fmax = 0;
        for (double f : flow) fmax = Math.max(fmax, f);
        for (int i = 0; i < es.size(); i++) {
            es.get(i).flow = flow[i];
            es.get(i).demand = fmax > 0 ? Math.log1p(flow[i]) / Math.log1p(fmax) : 0;
        }
    }

    // ------------------------------------------------------------------ hierarchy and widths (spec B, C, F)

    static void updateClassesAndWidths(CityModel m, GrowthEngine ge) {
        Config c = m.cfg;
        double pT = c.d("street.primaryDemand"), cT = c.d("street.connectorDemand"), hys = c.d("street.classHysteresis");
        List<Edge> es = m.g.aliveEdges();
        for (Edge e : es) {
            int prev = e.clsPrev;
            double d = e.demand;
            int cls;
            if (d >= pT || (prev >= 3 && d >= pT - hys)) cls = 3;
            else if (d >= cT || (prev >= 2 && d >= cT - hys)) cls = 2;
            else cls = 1;
            if (e.seedRoad) cls = Math.max(cls, 2);
            if (e.axis) cls = 4;
            e.cls = cls;
        }
        // hierarchy continuity: fill one-segment class gaps along a street
        for (int it = 0; it < 2; it++)
            for (Chain ch : m.chains)
                for (int i = 1; i + 1 < ch.edges.size(); i++) {
                    Edge e = ch.edges.get(i);
                    int k = Math.min(ch.edges.get(i - 1).cls, ch.edges.get(i + 1).cls);
                    if (k > e.cls && k <= 3) e.cls = k;
                }
        double alpha = c.d("street.proportionNudge"), rate = c.d("street.maxRatePerPhase");
        for (Edge e : es) {
            int k = e.cls;
            double lo = k == 1 ? 0 : (k == 2 ? cT : pT), hi = k == 1 ? cT : (k == 2 ? pT : 1);
            double mn = ge.classMin(k), mx = ge.classMax(k);
            double target = mn + (mx - mn) * Geom.clamp((e.demand - lo) / Math.max(1e-6, hi - lo), 0, 1);
            if (k <= 2 && e.meanFacadeH > 0) {
                double rt = c.d(k == 1 ? "arch.rTarget.local" : "arch.rTarget.connector");
                target = (1 - alpha) * target + alpha * Geom.clamp(e.meanFacadeH * rt, mn, mx);
            }
            double w = e.width + Geom.clamp(target - e.width, -rate, rate);
            e.width = Geom.clamp(w, mn, mx);
            e.clsPrev = e.cls;
        }
        double step = c.d("street.maxStepPerEdge");
        for (int it = 0; it < 15; it++)
            for (Chain ch : m.chains)
                for (int i = 0; i + 1 < ch.edges.size(); i++) {
                    Edge a = ch.edges.get(i), b = ch.edges.get(i + 1);
                    double diff = a.width - b.width;
                    if (Math.abs(diff) <= step * 0.97) continue;
                    double ex = (Math.abs(diff) - step * 0.95) / 2;
                    if (diff > 0) { a.width -= ex; b.width += ex; } else { a.width += ex; b.width -= ex; }
                    a.width = Geom.clamp(a.width, ge.classMin(a.cls), ge.classMax(a.cls));
                    b.width = Geom.clamp(b.width, ge.classMin(b.cls), ge.classMax(b.cls));
                }
        double bw = c.d("street.boulevard.width");
        for (Edge e : es) if (e.boulevard) e.width = bw; // boulevards keep their full width end to end
        for (Edge e : es) {
            e.wA = e.chainA != null ? (e.width + e.chainA.width) / 2 : e.width;
            e.wB = e.chainB != null ? (e.width + e.chainB.width) / 2 : e.width;
        }
    }

    // ------------------------------------------------------------------ centres (spec A)

    static void updateCenters(CityModel m, int phase, GrowthEngine ge, boolean fin) {
        Config c = m.cfg;
        double base = c.d("centers.attraction");
        for (Center ce : m.centers) {
            int access = 0;
            for (Edge e : ce.node.edges) if (e.cls >= 2) access++;
            ce.accessibility = Geom.clamp(access / 4.0, 0, 1);
            ce.density = Geom.clamp(m.housesWithin(ce.pos, 150) / 120.0, 0, 1);
            double target = (ce.seeded ? base : 0.8 * base) * (0.6 + 0.25 * ce.accessibility + 0.15 * ce.density);
            ce.attraction += Geom.clamp(target - ce.attraction, -0.1, 0.1);
            ce.strength = ce.attraction * (0.5 + 0.5 * ce.accessibility);
        }
        // boulevard ends become centres (and so squares) whatever the usual thresholds say
        for (Object[] be : ge.boulevardEnds) {
            Node bn = (Node) be[0];
            if (bn.deg() == 0) continue;
            Center at = null;
            for (Center ce : m.centers) if (ce.pos.dist(bn.p) < 0.4 * c.d("centers.minSpacing")) { at = ce; break; }
            if (at == null) {
                at = new Center();
                at.id = m.centers.size(); at.node = bn; at.pos = bn.p; at.bornPhase = phase;
                at.attraction = 0.8 * base; at.strength = at.attraction;
                at.why = "end of a boulevard";
                at.boulevardEnd = true; at.boulevardDir = (P) be[1];
                m.centers.add(at);
                m.log.event(phase, String.format("Square planned at the end of a boulevard (%.0f, %.0f)", bn.p.x(), bn.p.y()));
                ge.spawnFromCenter(at, phase);
            }
            if (at.id != 0) { at.boulevardEnd = true; at.boulevardDir = (P) be[1]; }
        }
        ge.boulevardEnds.clear();
        if (fin || phase < c.i("centers.minPhase") || m.centers.size() >= c.i("centers.max")) return;
        Node best = null;
        double bs = 0;
        String why = "";
        for (Node n : m.g.aliveNodes()) {
            if (n.deg() < 3) continue;
            boolean far = true;
            for (Center ce : m.centers) if (ce.pos.dist(n.p) < c.d("centers.minSpacing")) { far = false; break; }
            if (!far) continue;
            double conv = 0;
            for (Edge e : n.edges) conv += e.demand;
            if (conv < c.d("centers.convergenceThreshold")) continue;
            int dens = m.housesWithin(n.p, 130);
            if (dens < c.d("centers.densityThreshold")) continue;
            double sc = conv / 3 + dens / (2 * c.d("centers.densityThreshold"));
            if (sc > bs) {
                bs = sc; best = n;
                why = String.format("route convergence %.2f, %d houses within 130 m", conv, dens);
            }
        }
        if (best != null) {
            Center ce = new Center();
            ce.id = m.centers.size(); ce.node = best; ce.pos = best.p; ce.seeded = false; ce.bornPhase = phase;
            ce.attraction = 0.6 * base; ce.strength = ce.attraction;
            ce.why = "emerged: " + why;
            m.centers.add(ce);
            m.log.event(phase, String.format("New centre %d emerged at (%.0f, %.0f): %s", ce.id, best.p.x(), best.p.y(), why));
            ge.spawnFromCenter(ce, phase);
        }
    }

    // ------------------------------------------------------------------ plazas (spec C)

    /**
     * Plazas are blocks left open beside the town's centres: each centre's square is one of the blocks touching
     * its junction (about plaza.blockTarget in area, larger for a cathedral's square, compact, dry, not already
     * built on), and busy junctions elsewhere take a small adjoining block (often a leftover wedge) as a square.
     * A square keeps its block from phase to phase. Where no block has closed yet (early growth, the town's
     * edge), a centre keeps a small round square at its junction until one does.
     */
    static void updatePlazas(CityModel m, int phase) {
        Config c = m.cfg;
        double tend = c.d("plaza.tendency");
        double enter = c.d("plaza.enterThreshold") + (0.5 - tend) * 0.3, exit = c.d("plaza.exitThreshold") + (0.5 - tend) * 0.3;
        Map<Integer, Plaza> existing = new HashMap<>();
        for (Plaza p : m.plazas) existing.put(p.node.id, p);
        Map<Integer, List<Face>> facesAt = new HashMap<>();
        for (Face f : m.g.faces()) for (Node n : f.cycle()) facesAt.computeIfAbsent(n.id, k -> new ArrayList<>()).add(f);
        java.util.Set<Long> taken = new java.util.HashSet<>();
        List<Plaza> next = new ArrayList<>();
        for (Center ce : m.centers) {
            if (ce.node.deg() == 0) continue;
            Plaza p = existing.getOrDefault(ce.node.id, new Plaza());
            if (p.node == null) {
                p.node = ce.node; p.bornPhase = phase;
                m.log.event(phase, "Central plaza formed at centre " + ce.id);
            }
            p.central = true; p.score = 1;
            double target = c.d("plaza.blockTarget");
            if (ce.landmark != null && ce.landmark.type.endsWith("cathedral"))
                target = Math.max(target, Math.pow((ce.landmark.grand ? 2.0 : 1.7) * ce.landmark.width, 2));
            Face f = plazaFace(m, ce.node, p, facesAt, taken, 0.45 * target, c.d("plaza.blockMaxArea") * (ce.landmark != null ? 1.4 : 1), target);
            setBlock(p, f);
            if (f != null) taken.add(f.key());
            ce.plaza = p;
            next.add(p);
        }
        List<Object[]> cands = new ArrayList<>();
        for (Node n : m.g.aliveNodes()) {
            if (n.deg() < 3) continue;
            boolean isCenter = false;
            for (Center ce : m.centers) if (ce.node == n) isCenter = true;
            if (isCenter) continue;
            double conv = 0;
            for (Edge e : n.edges) conv += e.demand;
            double sc = 0.5 * Geom.clamp(conv / 2.4, 0, 1) + 0.3 * Geom.clamp(m.housesWithin(n.p, 90) / 45.0, 0, 1)
                    + 0.2 * Math.exp(-m.nearestCenterDist(n.p) / 250);
            boolean was = existing.containsKey(n.id) && !existing.get(n.id).central;
            if (sc >= (was ? exit : enter)) cands.add(new Object[]{sc, n, was});
        }
        cands.sort((a, b) -> {
            int k = Boolean.compare((Boolean) b[2], (Boolean) a[2]);
            if (k != 0) return k;
            int s = Double.compare((Double) b[0], (Double) a[0]);
            return s != 0 ? s : Integer.compare(((Node) a[1]).id, ((Node) b[1]).id);
        });
        for (Object[] cd : cands) {
            if (next.size() >= c.i("plaza.maxCount")) break;
            Node n = (Node) cd[1];
            boolean ok = true;
            for (Plaza p : next) if (p.center().dist(n.p) < c.d("plaza.minSpacing")) { ok = false; break; }
            if (!ok) continue;
            Plaza p = existing.getOrDefault(n.id, new Plaza());
            // a junction square is a small block beside the junction; no block, no square
            Face f = plazaFace(m, n, p, facesAt, taken, c.d("plaza.blockMinArea"), c.d("plaza.smallMaxArea"), 0.5 * c.d("plaza.smallMaxArea"));
            if (f == null) continue;
            if (p.node == null) {
                p.node = n; p.bornPhase = phase;
                m.log.event(phase, String.format("Junction square formed at (%.0f, %.0f): score %.2f (convergence, density, centrality)",
                        n.p.x(), n.p.y(), (Double) cd[0]));
            }
            setBlock(p, f);
            taken.add(f.key());
            p.score = (Double) cd[0];
            p.central = false;
            next.add(p);
        }
        for (Plaza old : m.plazas) if (!next.contains(old)) m.log.event(phase, "Plaza at node " + old.node.id + " dissolved (score fell below exit threshold)");
        m.plazas.clear();
        for (int i = 0; i < next.size(); i++) {
            Plaza p = next.get(i);
            p.id = i;
            if (!p.block) {
                // no block yet: a small round square at the junction, kept clear of monuments
                double mw = 0;
                for (Edge e : p.node.edges) mw += e.width;
                mw /= Math.max(1, p.node.deg());
                double nearJ = 1e9;
                for (Node o : m.g.nodesNear(p.node.p, 120)) if (o != p.node && o.deg() >= 3) nearJ = Math.min(nearJ, o.p.dist(p.node.p));
                double r = Geom.clamp(Math.min(c.d("plaza.radiusWidths") * mw, 0.9 * nearJ), 6, c.d("plaza.maxRadius"));
                for (Landmark l : m.landmarks) {
                    double dl = Geom.pointInPoly(p.node.p, java.util.Arrays.asList(l.fp)) ? 0 : 1e9;
                    for (int q = 0; q < 4; q++) dl = Math.min(dl, Geom.distPointSeg(p.node.p, l.fp[q], l.fp[(q + 1) % 4]));
                    if (dl < r + 1.5) r = Math.max(4, dl - 1.5);
                }
                p.radius = r;
                p.poly = plazaPolygon(p.node, r, new SplittableRandom(m.seed ^ (p.node.id * 0x9E3779B97F4A7C15L)));
            }
            m.plazas.add(p);
        }
    }

    // ------------------------------------------------------------------ cemeteries

    /**
     * Cemeteries take whole blocks, as squares do, and grow with the town: together they hold about cemetery.share of
     * its block area, none larger than cemetery.maxArea, at most cemetery.maxCount of them. Each is a compact, dry,
     * gently sloping block that nothing is built on yet, away from the centre, and never touching a square (it would
     * upset the square's scale) or another cemetery. A cemetery keeps its block from phase to phase; one whose block
     * has since come to touch a square is given up unless it is already built.
     */
    static void updateCemeteries(CityModel m, int phase) {
        Config c = m.cfg;
        if (!c.b("mc.cemeteries") && !m.noNewCemeteries) { m.cemeteries.clear(); return; }
        List<Face> faces = m.g.faces();
        java.util.Set<Integer> plazaNodes = new java.util.HashSet<>();
        java.util.Set<Long> plazaFaces = new java.util.HashSet<>();
        for (Plaza p : m.plazas) {
            plazaNodes.add(p.node.id);
            if (p.block) { plazaFaces.add(p.faceKey); for (Node n : p.corners) plazaNodes.add(n.id); }
        }
        java.util.Set<Long> built = new java.util.HashSet<>();
        for (CityBlock b : m.lockedBlocks) if (b.cemetery != null) built.add(b.cemetery.faceKey);
        // keep the ones whose block still stands (by key, or the face that now holds its middle)
        List<Cemetery> kept = new ArrayList<>();
        for (Cemetery cm : m.cemeteries) {
            Face now = null;
            for (Face f : faces) if (f.key() == cm.faceKey) { now = f; break; }
            if (now == null) for (Face f : faces)
                if (Geom.pointInPoly(cm.center(), f.poly()) && Math.abs(f.area() - cm.area) < 0.25 * cm.area) { now = f; break; }
            boolean isBuilt = built.contains(cm.faceKey);
            if (now == null) { if (isBuilt) kept.add(cm); else m.log.event(phase, "Cemetery " + cm.id + " given up: its block changed"); continue; }
            if (!isBuilt && touches(now, plazaNodes, plazaFaces)) { m.log.event(phase, "Cemetery " + cm.id + " given up: a square formed beside it"); continue; }
            cm.faceKey = now.key(); cm.poly = new ArrayList<>(now.poly()); cm.area = now.area();
            kept.add(cm);
        }
        m.cemeteries.clear();
        m.cemeteries.addAll(kept);
        if (phase < c.i("cemetery.fromPhase") || m.noNewCemeteries) { for (int i = 0; i < m.cemeteries.size(); i++) m.cemeteries.get(i).id = i; return; }
        double townArea = 0;
        for (Face f : faces) if (f.area() <= c.d("block.maxHousingFace")) townArea += f.area();
        double target = c.d("cemetery.share") * townArea, have = 0;
        for (Cemetery cm : m.cemeteries) have += cm.area;
        double cap = Math.min(c.d("cemetery.maxArea"), c.d("block.maxArea"));
        P c0 = m.centers.get(0).pos;
        double reach = 1;
        for (Face f : faces) reach = Math.max(reach, Geom.centroid(f.poly()).dist(c0));
        while (target - have >= 0.8 * c.d("cemetery.minArea") && m.cemeteries.size() < c.i("cemetery.maxCount")) {
            double want = Math.min(cap, target - have);
            // never much more than the town is owed, so a small town gets a small cemetery (or none yet)
            double most = Math.min(cap, Math.max(1.5 * want, 1.3 * c.d("cemetery.minArea")));
            Face best = null;
            double bs = -1e9;
            for (Face f : faces) {
                double a = f.area();
                if (a < c.d("cemetery.minArea") || a > most) continue;
                if (plazaFaces.contains(f.key()) || touches(f, plazaNodes, plazaFaces)) continue;
                boolean bad = false;
                for (Cemetery cm : m.cemeteries) {
                    if (cm.faceKey == f.key()) { bad = true; break; }
                    for (Node n : f.cycle()) for (P q : cm.poly) if (q.dist(n.p) < 0.5) bad = true; // shares a corner
                }
                List<P> poly = f.poly();
                for (Landmark l : m.landmarks) if (Geom.polysOverlap(poly.toArray(new P[0]), l.fp, 0.05)) bad = true;
                for (CityBlock b : m.blocks) if (b.locked && b.face != null && b.face.key() == f.key()) bad = true; // being built in a villager town
                if (bad || occupied(m, poly)) continue;
                P cen = Geom.centroid(poly);
                double slope = m.t.slope(cen.x(), cen.y());
                if (m.t.isWater(cen) || slope > 0.6) continue;
                double per = Geom.perimeter(poly), compact = 4 * Math.PI * a / (per * per);
                if (compact < 0.45) continue;
                double out = cen.dist(c0) / reach; // towards the edge of town, as cemeteries were
                double sc = -Math.abs(Math.log(a / want)) + 1.2 * compact + 1.5 * Geom.clamp((out - 0.3) / 0.5, 0, 1) - 2.5 * slope;
                for (Cemetery cm : m.cemeteries) sc -= 2.0 * Math.exp(-cm.center().dist(cen) / 60); // spread them out
                if (sc > bs) { bs = sc; best = f; }
            }
            if (best == null) break;
            Cemetery cm = new Cemetery();
            cm.id = m.cemeteries.size(); cm.faceKey = best.key(); cm.poly = new ArrayList<>(best.poly()); cm.area = best.area(); cm.bornPhase = phase;
            m.cemeteries.add(cm);
            have += cm.area;
            m.log.event(phase, String.format("Cemetery %d laid out: a block of %.0f m2 (town %.0f m2, cemeteries %.0f of %.0f m2)", cm.id, cm.area, townArea, have, target));
        }
        for (int i = 0; i < m.cemeteries.size(); i++) m.cemeteries.get(i).id = i;
    }

    /** Does the face share a corner with a square (or contain a square's junction)? */
    private static boolean touches(Face f, java.util.Set<Integer> plazaNodes, java.util.Set<Long> plazaFaces) {
        if (plazaFaces.contains(f.key())) return true;
        for (Node n : f.cycle()) if (plazaNodes.contains(n.id)) return true;
        return false;
    }

    /** A dead-end street running into a block (it lies on the same face on both sides), houses fronting both sides of it. */
    static boolean hasStub(Face f) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Edge e : f.edges()) if (!seen.add(e.id)) return true;
        return false;
    }

    /**
     * Dead-end streets left inside blocks (a cutting street that stopped short, a branch that never met anything) are
     * taken out before the blocks are built: otherwise houses line both sides of them, back to back with the block's
     * own row, and the block gets a second row of houses and no courtyard. Only streets inside a block-sized face,
     * never seed roads, axes, boulevards, bridges, or streets to a square or a centre. ok: may this one go (villager
     * mode: not where a house already stands). removed gets {edge, deadA, deadB} for each one taken out.
     */
    static int pruneStubs(CityModel m, GrowthEngine ge, java.util.function.Predicate<Edge> ok, List<Object[]> removed) {
        Config c = m.cfg;
        java.util.Set<Integer> keepNodes = new java.util.HashSet<>();
        for (Plaza p : m.plazas) keepNodes.add(p.node.id);
        for (Center ce : m.centers) keepNodes.add(ce.node.id);
        int n = 0;
        for (int round = 0; round < 40; round++) {
            boolean any = false;
            for (Face f : m.g.faces()) {
                if (f.area() > c.d("block.maxHousingFace")) continue;
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                for (Edge e : f.edges()) {
                    if (seen.add(e.id) || !e.alive) continue; // only edges met twice going round the face: inside it
                    boolean deadA = e.a.deg() == 1, deadB = e.b.deg() == 1;
                    if (!deadA && !deadB) continue; // the leaves go first, then what they hung from
                    if (e.seedRoad || e.axis || e.boulevard || e.bridge) continue;
                    if (keepNodes.contains(e.a.id) || keepNodes.contains(e.b.id)) continue;
                    if (!ok.test(e)) continue;
                    if (ge != null) ge.heads.removeIf(h -> (h.node == e.a && deadA) || (h.node == e.b && deadB));
                    m.g.removeEdge(e);
                    if (removed != null) removed.add(new Object[]{e, deadA, deadB});
                    n++;
                    any = true;
                }
                if (any) break; // the faces have changed
            }
            if (!any) break;
        }
        if (n > 0) m.log.event(m.phase, n + " dead-end street(s) inside blocks taken out");
        return n;
    }

    /** Is there a block-sized face over block.maxArea, not yet built on, that is not a square or a cemetery? */
    static boolean oversizeOpen(CityModel m) {
        Config c = m.cfg;
        java.util.Set<Long> skip = new java.util.HashSet<>();
        for (Plaza p : m.plazas) if (p.block) skip.add(p.faceKey);
        for (Cemetery cm : m.cemeteries) skip.add(cm.faceKey);
        for (Face f : m.g.faces()) {
            if (f.area() <= c.d("block.maxArea") || f.area() > c.d("block.maxHousingFace") || skip.contains(f.key())) continue;
            if (Parcels.findLocked(m, f) == null) return true;
        }
        return false;
    }

    /** Takes out the streets planned to merge blocks into squares (where the graph may change). */
    static int applySquareMerges(CityModel m, GrowthEngine ge) {
        int n = 0;
        for (Edge e : m.squareMerges) if (e.alive) { m.g.removeEdge(e); n++; }
        m.squareMerges.clear();
        if (n > 0) ge.heads.removeIf(h -> h.node.deg() == 0);
        return n;
    }

    /**
     * Is anything already standing in this area: an existing structure in the world (kept out), a house standing in a
     * villager town, or a house of a block being built? Squares and free monuments never take such ground.
     */
    static boolean occupied(CityModel m, List<P> poly) {
        if (m.keepOutIn(poly.toArray(new P[0]))) return true;
        for (P[] q : m.standing) if (Geom.pointInPoly(Geom.centroid(java.util.Arrays.asList(q)), poly)) return true;
        for (CityBlock b : m.blocks) if (b.locked) for (House h : b.houses) if (Geom.pointInPoly(h.c, poly)) return true;
        return false;
    }

    static final String[] FREE_TYPES = {"forum", "colosseum", "church", "civic hall", "market hall"};

    /** Footprint width, depth and height of a free-standing monument of the given type, scaled to the town. */
    static double[] freeDims(Config c, String type) {
        double cw = c.d("landmark.cathedral.width"), hw = c.d("landmark.hall.width"), hd = c.d("landmark.hall.depth");
        return switch (type) {
            case "grand cathedral" -> new double[]{cw * c.d("landmark.grandScale"), c.d("landmark.cathedral.depth") * c.d("landmark.grandScale"), 2.0 * cw * c.d("landmark.grandScale")};
            case "cathedral" -> new double[]{cw, c.d("landmark.cathedral.depth"), 2.0 * cw};
            case "church" -> new double[]{Math.round(0.6 * cw), Math.round(1.15 * cw), Math.round(1.4 * cw)};
            case "forum" -> new double[]{Math.round(1.25 * cw), Math.round(1.6 * cw), 10};
            case "colosseum" -> new double[]{Math.round(1.7 * cw) | 1, Math.round(1.4 * cw) | 1, 15};
            default -> new double[]{hw, hd, Math.round(1.4 * hw)};
        };
    }

    /**
     * A free-standing footprint in the middle of the square: as large as fits (down to 70%) with a clear margin all round,
     * its front turned to the centre's junction where the main streets arrive.
     */
    static Landmark placeFree(CityModel m, Center ce, Plaza pl, String type, double[] dm) {
        List<P> poly = pl.poly;
        int n = poly.size();
        double margin = 7;
        P cen = Geom.centroid(poly);
        // orientations: along the two longest sides of the square
        double a0 = 0, l0 = -1, a1 = 0, l1 = -1;
        for (int i = 0; i < n; i++) {
            P a = poly.get(i), b = poly.get((i + 1) % n);
            double l = a.dist(b), ang = b.sub(a).angle();
            if (l > l0) { l1 = l0; a1 = a0; l0 = l; a0 = ang; } else if (l > l1) { l1 = l; a1 = ang; }
        }
        P toNode = ce.node.p.sub(cen);
        P fdir = toNode.len() < 1 ? P.dir(a0) : toNode.norm();
        Landmark best = null;
        double bestSc = -1e9;
        // candidate middles: a 3 m grid over the square, nearest the centroid first (irregular merged squares)
        java.awt.geom.Rectangle2D bb = Geom.path(poly).getBounds2D();
        List<double[]> shifts = new ArrayList<>();
        for (double x = -bb.getWidth(); x <= bb.getWidth(); x += 3) for (double y = -bb.getHeight(); y <= bb.getHeight(); y += 3)
            if (x * x + y * y <= 0.3 * (bb.getWidth() * bb.getWidth() + bb.getHeight() * bb.getHeight())) shifts.add(new double[]{x, y});
        shifts.sort(java.util.Comparator.comparingDouble(v -> v[0] * v[0] + v[1] * v[1]));
        for (double s = 1.0; s >= 0.69 && best == null; s -= 0.1) {
            double W = dm[0] * s, D = dm[1] * s;
            for (double base : new double[]{a0, a1})
                for (int rot = 0; rot < 4; rot++) {
                    P U = P.dir(base + rot * Math.PI / 2), V = U.perpL();
                    for (double[] sh : shifts) {
                        P c0 = cen.add(U.mul(sh[0])).add(V.mul(sh[1]));
                        P f0 = c0.sub(U.mul(W / 2)).sub(V.mul(D / 2));
                        P[] fp = {f0, f0.add(U.mul(W)), f0.add(U.mul(W)).add(V.mul(D)), f0.add(V.mul(D))};
                        if (!Geom.pointInPoly(c0, poly) || !clearInside(poly, fp, margin)) continue;
                        P[] wide = Geom.expandRect(fp, 2.0);
                        if (m.keepOutIn(wide)) continue;
                        boolean hit = false;
                        for (P[] q : m.standing) if (Geom.polysOverlap(q, wide, 0.05)) { hit = true; break; }
                        if (hit) continue;
                        double sc = V.mul(-1).dot(fdir) - 0.05 * (Math.abs(sh[0]) + Math.abs(sh[1]));
                        if (sc > bestSc) {
                            bestSc = sc;
                            Landmark lm = new Landmark();
                            lm.type = type; lm.fp = fp; lm.width = W; lm.depth = D; lm.height = dm[2] * Math.sqrt(s);
                            lm.center = ce; lm.free = true; lm.grand = type.startsWith("grand");
                            lm.front = fp[0].lerp(fp[1], 0.5); lm.axisDir = V.mul(-1);
                            lm.why = "free-standing in the square of centre " + ce.id;
                            best = lm;
                        }
                    }
                }
        }
        if (best != null) best.id = m.landmarks.size();
        return best;
    }

    /** Every corner and side midpoint of the footprint is inside the polygon, at least 'margin' from its edge. */
    private static boolean clearInside(List<P> poly, P[] fp, double margin) {
        for (int k = 0; k < 4; k++) {
            for (P q : new P[]{fp[k], fp[k].lerp(fp[(k + 1) % 4], 0.5)}) {
                if (!Geom.pointInPoly(q, poly)) return false;
                for (int i = 0; i < poly.size(); i++) if (Geom.distPointSeg(q, poly.get(i), poly.get((i + 1) % poly.size())) < margin) return false;
            }
        }
        return true;
    }

    /**
     * The square is too small for its monument: plan to merge neighbouring blocks into it, taking out the streets between
     * (not boulevards, bridges or approach roads, nor streets at the centre's own junction; never a built, watery or
     * monument block). Picks the merge that keeps the square most compact; up to two per phase. The streets go when the
     * graph may change (spawnFeedback); the merged block becomes the square next phase.
     */
    static boolean planMerge(CityModel m, Center ce, Plaza pl, double need) {
        List<Face> faces = m.g.faces();
        Face F = null;
        P pc = pl.center();
        for (Face f : faces) if (f.key() == pl.faceKey || Geom.pointInPoly(pc, f.poly())) { F = f; if (f.key() == pl.faceKey) break; }
        if (F == null) return false;
        java.util.Set<Edge> fe = new java.util.HashSet<>(F.edges());
        double area = F.area(), per = Geom.perimeter(F.poly());
        java.util.Set<Long> used = new java.util.HashSet<>();
        boolean any = false;
        for (int round = 0; round < 2 && area < need; round++) {
            Face best = null; List<Edge> bestShared = null; double bestC = -1, bestPer = 0;
            for (Face g : faces) {
                if (g.key() == F.key() || used.contains(g.key())) continue;
                List<Edge> shared = new ArrayList<>();
                for (Edge e : g.edges()) if (fe.contains(e)) shared.add(e);
                if (shared.isEmpty()) continue;
                boolean ok = true;
                double sl = 0;
                for (Edge e : shared) {
                    if (e.axis || e.boulevard || e.bridge || e.seedRoad || e.a == ce.node || e.b == ce.node) { ok = false; break; }
                    sl += e.len();
                }
                if (!ok || g.area() > 2 * need) continue;
                List<P> gp = g.poly();
                for (Landmark l : m.landmarks) if (Geom.pointInPoly(Geom.centroid(java.util.Arrays.asList(l.fp)), gp)) { ok = false; break; }
                if (!ok) continue;
                if (occupied(m, gp) || m.t.isWater(Geom.centroid(gp))) continue;
                double A = area + g.area(), Pm = per + Geom.perimeter(gp) - 2 * sl;
                double cmp = 4 * Math.PI * A / (Pm * Pm);
                if (cmp > bestC) { bestC = cmp; best = g; bestShared = shared; bestPer = Pm; }
            }
            if (best == null) break;
            for (Edge e : bestShared) if (!m.squareMerges.contains(e)) m.squareMerges.add(e);
            used.add(best.key());
            area += best.area(); per = bestPer;
            any = true;
            m.log.event(m.phase, String.format("Square of centre %d grows: block of %.0f m2 merged in (streets between them removed)", ce.id, best.area()));
        }
        return any;
    }

    private static void setBlock(Plaza p, Face f) {
        if (f == null) { p.block = false; p.corners.clear(); return; }
        p.block = true;
        p.faceKey = f.key();
        p.poly = new ArrayList<>(f.poly());
        p.corners = new ArrayList<>(f.cycle());
        p.radius = Math.sqrt(f.area() / Math.PI);
    }

    /**
     * The block beside node n best suited to be its square: area within [minA, maxA] and close to target, compact,
     * dry, not a monument's block, not one already built on (unless it is this square's block already, which is kept).
     */
    private static Face plazaFace(CityModel m, Node n, Plaza p, Map<Integer, List<Face>> facesAt, java.util.Set<Long> taken,
                                  double minA, double maxA, double target) {
        Face best = null;
        double bs = -1e9;
        // never bigger than an ordinary block (more only for a big monument's square), and never a straggling shape
        maxA = Math.min(maxA, Math.max(1.15 * m.cfg.d("block.maxArea"), 1.3 * target));
        for (Face f : facesAt.getOrDefault(n.id, List.of())) {
            if (taken.contains(f.key())) continue;
            boolean mine = p.block && (f.key() == p.faceKey || p.poly.size() >= 3 && Geom.pointInPoly(Geom.centroid(p.poly), f.poly()));
            double a = f.area();
            if (mine && a > 1.6 * maxA) mine = false; // its block merged into something much bigger: choose again
            if (!mine && (a < minA || a > maxA)) continue;
            List<P> poly = f.poly();
            boolean bad = false;
            for (Landmark l : m.landmarks)
                if (!l.free && Geom.pointInPoly(Geom.centroid(java.util.Arrays.asList(l.fp)), poly)) { bad = true; break; } // a free monument stands in its square
            if (bad) continue;
            if (!mine) {
                if (occupied(m, poly)) continue; // built on (or an existing structure), even if its outline has changed since
                P cen = Geom.centroid(poly);
                if (m.t.isWater(cen) || m.t.slope(cen.x(), cen.y()) > 0.65) continue; // squares follow the slope of their corners
            }
            double per = Geom.perimeter(poly);
            double compact = 4 * Math.PI * a / (per * per);
            // a long sliver or a crooked block makes a shapeless square (one kept from before gets a little more leeway,
            // but not once a new street has left it a strip)
            if (compact < (mine ? 0.42 : 0.5)) continue;
            if (mine && a < 0.6 * minA) continue;
            double sc = -Math.abs(Math.log(a / target)) + 0.8 * compact + (mine ? 1.5 : 0);
            if (sc > bs) { bs = sc; best = f; }
        }
        return best;
    }

    /** Star-shaped plaza outline that opens at each street mouth and bulges into the gaps between them. */
    static List<P> plazaPolygon(Node n, double r, SplittableRandom rnd) {
        Edge[] arr = n.edges.toArray(new Edge[0]);
        Arrays.sort(arr, Comparator.comparingDouble((Edge e) -> e.angleFrom(n)));
        List<double[]> pts = new ArrayList<>();
        int k = arr.length;
        for (int i = 0; i < k; i++) {
            double a = arr[i].angleFrom(n);
            double off = Math.atan2(arr[i].widthAt(n) / 2, r);
            pts.add(new double[]{a - off, r / Math.cos(off)});
            pts.add(new double[]{a + off, r / Math.cos(off)});
            double a1 = k == 1 ? a + 2 * Math.PI : arr[(i + 1) % k].angleFrom(n);
            if (a1 <= a) a1 += 2 * Math.PI;
            double gap = a1 - a;
            int extra = (int) (gap / Math.toRadians(70));
            for (int t = 1; t <= extra; t++) pts.add(new double[]{a + gap * t / (extra + 1), r * (0.75 + 0.4 * rnd.nextDouble())});
        }
        if (k == 0) for (int t = 0; t < 7; t++) pts.add(new double[]{t * 2 * Math.PI / 7, r * (0.8 + 0.3 * rnd.nextDouble())});
        for (double[] p : pts) { while (p[0] < -Math.PI) p[0] += 2 * Math.PI; while (p[0] >= Math.PI) p[0] -= 2 * Math.PI; }
        pts.sort(Comparator.comparingDouble(x -> x[0]));
        List<P> poly = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0 && pts.get(i)[0] - pts.get(i - 1)[0] < 1e-3) continue;
            poly.add(n.p.add(P.dir(pts.get(i)[0]).mul(pts.get(i)[1])));
        }
        return poly;
    }

    // ------------------------------------------------------------------ landmarks (spec A, N)

    static void placeLandmarks(CityModel m, int phase, GrowthEngine ge, boolean fin) {
        if (m.noNewLandmarks) return; // a ruined one is rebuilt first
        Config c = m.cfg;
        List<Center> order = new ArrayList<>(m.centers);
        // the main square first, then the squares at the ends of boulevards, then the strongest other centres
        order.sort((a, b) -> a.id == 0 || b.id == 0 ? Integer.compare(a.id == 0 ? 0 : 1, b.id == 0 ? 0 : 1)
                : a.boulevardEnd != b.boulevardEnd ? Boolean.compare(b.boulevardEnd, a.boulevardEnd) : Double.compare(b.strength, a.strength));
        int ends = 0;
        for (Center ce : m.centers) if (ce.boulevardEnd) ends++;
        int maxLm = Math.max(c.i("landmark.max"), ends > 0 ? 1 + ends : 0);
        SplittableRandom r = new SplittableRandom(m.seed * 31 + phase);
        for (Center ce : order) {
            if (ce.landmark != null || ce.plaza == null) continue;
            if (m.landmarks.size() >= maxLm) break;
            if (!ce.boulevardEnd && ce.strength < c.d("landmark.strengthThreshold")) continue;
            int near = m.housesWithin(ce.pos, 200);
            if (near < (ce.boulevardEnd ? c.i("landmark.minHouses") / 2 : c.i("landmark.minHouses"))) continue;
            if (!ce.boulevardEnd && ends > 0 && m.landmarks.size() >= 1 && m.landmarks.size() < maxLm
                    && m.centers.stream().anyMatch(o -> o.boulevardEnd && o.landmark == null)) continue; // keep monuments for the boulevard squares
            // with boulevards the cathedral belongs to the main square where they meet; their own squares get halls
            boolean cathedral = ends > 0 ? ce.id == 0 && m.landmarks.stream().noneMatch(l -> l.type.endsWith("cathedral"))
                    : m.landmarks.isEmpty();
            String type = cathedral ? "cathedral" : (m.landmarks.size() % 2 == 1 ? "civic hall" : "market hall");
            double tw = c.d(cathedral ? "landmark.cathedral.width" : "landmark.hall.width");
            double td = c.d(cathedral ? "landmark.cathedral.depth" : "landmark.hall.depth");
            boolean wantGrand = cathedral && c.b("landmark.grand");
            double gs = wantGrand ? Math.max(1.0, c.d("landmark.grandScale")) : 1.0;
            double tw0 = tw, td0 = td;
            Plaza pl = ce.plaza;
            if (c.b("mc.freestanding")) {
                // free-standing monuments: in the middle of the square, which grows (blocks merge) to stay in proportion
                // the main square gets the cathedral; the others a sequence of types (forum, colosseum, church, halls)
                long others = m.landmarks.stream().filter(l -> !l.type.endsWith("cathedral")).count();
                String ft = cathedral ? (wantGrand ? "grand cathedral" : "cathedral") : FREE_TYPES[(int) Math.floorMod(others + m.seed, (long) FREE_TYPES.length)];
                double[] dm = freeDims(c, ft);
                if (pl.block) {
                    double need = c.d("plaza.freeFactor") * (dm[0] + 12) * (dm[1] + 12);
                    double have = Math.abs(Geom.signedArea(pl.poly));
                    Landmark lm = have >= need || fin ? placeFree(m, ce, pl, ft, dm) : null;
                    if (lm != null) {
                        m.landmarks.add(lm); ce.landmark = lm;
                        m.log.event(phase, String.format("Free-standing %s %.0fx%.0f m, height %.0f m, in the square of centre %d (%.0f m2)",
                                ft, lm.width, lm.depth, lm.height, ce.id, have));
                        if (cathedral && !fin && c.b("landmark.enableAxis")) ge.spawnAxis(lm, ce, phase);
                        continue;
                    }
                    if (!fin) { if (planMerge(m, ce, pl, need)) m.log.count("square:merge-planned"); continue; }
                } else if (!fin) continue; // wait for the centre's square to be a block
                m.log.count("landmark:free-fallback-to-block-edge");
            }
            P[] bestFp = null;
            double bestScore = -1, bw = 0, bd = 0;
            P bestFront = null, bestIn = null;
            boolean grand = false;
            // a grand landmark tries its full size first, then steps down towards an ordinary cathedral until a site fits
            // an ordinary cathedral may also shrink a little (never below 70%) rather than not being built at all
            double minScale = cathedral ? 0.7 : 1.0;
            for (double scale = gs; bestFp == null && scale >= minScale - 1e-9; scale = scale - 0.1 < minScale - 1e-9 ? -1 : (scale > 1.0 && scale - 0.1 < 1.0 ? 1.0 : scale - 0.1)) {
            tw = tw0 * scale; td = td0 * scale;
            grand = wantGrand && scale > 1.15;
            for (CityBlock b : m.blocks) {
                if (b.buildable == null || b.locked) continue;
                for (List<P> ring : b.rings) {
                    for (int i = 0; i < ring.size(); i++) {
                        P a = ring.get(i), bb = ring.get((i + 1) % ring.size());
                        double len = a.dist(bb);
                        if (len < 0.6 * tw + 4) continue;
                        P mid = a.lerp(bb, 0.5);
                        // the main square of a boulevard town is crossed by wide avenues: look a little further for the cathedral's site
                        boolean wide = ce.id == 0 && ends > 0;
                        P pc = pl.center();
                        if (mid.dist(pc) > pl.radius * 1.9 + (wide ? 30 : 15)) continue;
                        P along = bb.sub(a).norm(), in = along.perpL();
                        double facing = in.dot(mid.sub(pc).norm());
                        if (facing < (wide ? 0.3 : 0.5)) continue;
                        // the cathedral stands back from the square's centre, leaving room for a square in proportion
                        // (a round square: the cathedral stands back from its centre, leaving room; a block square
                        // is already its own open block across the street)
                        double setback = pl.block ? 0.85 * tw : mid.dist(pc);
                        if (cathedral && setback < 0.55 * tw) continue;
                        double W = Math.min(tw, len * 0.85);
                        for (double D = td; D >= 0.55 * td; D -= 0.08 * td) {
                            P f0 = mid.sub(along.mul(W / 2)).add(in.mul(1.0)), f1 = mid.add(along.mul(W / 2)).add(in.mul(1.0));
                            P[] fp = {f0, f1, f1.add(in.mul(D)), f0.add(in.mul(D))};
                            boolean fits = true;
                            for (P q : fp) if (!b.buildable.contains(q.x(), q.y())) { fits = false; break; }
                            if (fits && !b.buildable.contains(Geom.path(fp).getBounds2D())) {
                                // bounding box test is conservative for rotated rectangles; sample the interior instead
                                for (int s = 1; s <= 3 && fits; s++) for (int u = 1; u <= 3; u++) {
                                    P q = f0.lerp(f1, u / 4.0).add(in.mul(D * s / 4.0));
                                    if (!b.buildable.contains(q.x(), q.y())) { fits = false; break; }
                                }
                            }
                            if (fits && m.keepOutIn(Geom.expandRect(fp, 1.0))) fits = false; // never on (or against) an existing structure
                            if (fits) {
                                double sc = W / tw + D / td + 0.5 * facing + 0.2 * r.nextDouble();
                                if (cathedral) sc += 0.8 * Math.min(1, setback / (0.85 * tw));
                                // at a boulevard's square, the monument faces back down the boulevard (terminating the view)
                                // (its front faces the main centre: the block lies further out along the boulevard)
                                if (ce.boulevardEnd && ce.boulevardDir != null) sc += 4.0 * Math.max(0, in.dot(ce.boulevardDir));
                                if (sc > bestScore) { bestScore = sc; bestFp = fp; bw = W; bd = D; bestFront = mid; bestIn = in; }
                                break;
                            }
                        }
                    }
                }
            }
            }
            if (bestFp == null) { m.log.count("landmark:no-site"); continue; }
            // a boulevard's square waits (until the town's final pass) for a site straight ahead, facing back along it
            if (ce.boulevardEnd && ce.boulevardDir != null && !fin && bestIn.dot(ce.boulevardDir) < 0.7) { m.log.count("landmark:waiting-for-axis-site"); continue; }
            if (wantGrand && !grand) m.log.count("landmark:grand-too-big-for-site");
            if (tw < tw0 - 1e-6 && !wantGrand) m.log.count("landmark:cathedral-reduced-to-fit");
            Landmark lm = new Landmark();
            lm.id = m.landmarks.size(); lm.type = type; lm.fp = bestFp; lm.width = bw; lm.depth = bd; lm.center = ce;
            double minR = c.d("landmark.minHeightToWidth");
            double ratio = grand ? 1.6 + 0.4 * r.nextDouble() : cathedral ? 1.6 + 0.8 * r.nextDouble() : 1.15 + 0.5 * r.nextDouble();
            lm.height = Math.max(minR, ratio) * bw;
            lm.front = bestFront; lm.axisDir = bestIn.mul(-1);
            lm.why = String.format("centre %d strength %.2f, %d houses within 200 m; fronts the central plaza", ce.id, ce.strength, near);
            lm.grand = grand;
            if (grand) lm.type = "grand cathedral";
            ce.landmark = lm;
            m.landmarks.add(lm);
            m.log.event(phase, String.format("Landmark placed: %s %.0fx%.0f m, height %.0f m (H/W %.2f) at centre %d - %s",
                    type, bw, bd, lm.height, lm.ratio(), ce.id, lm.why));
            if (cathedral && !fin && c.b("landmark.enableAxis")) ge.spawnAxis(lm, ce, phase);
        }
    }

    // ------------------------------------------------------------------ attraction field + feedback spawning

    static void updateAttraction(CityModel m) {
        for (int j = 0; j < m.ar; j++) for (int i = 0; i < m.ac; i++) {
            P p = new P(i * m.acell, j * m.acell);
            double a = 0;
            for (Center ce : m.centers) {
                double sigma = 150 + 200 * ce.strength;
                a = Math.max(a, ce.attraction * Math.exp(-Math.pow(p.dist(ce.pos) / sigma, 2)));
            }
            a += 0.35 * Geom.clamp(m.housesWithin(p, 60) / 30.0, 0, 1);
            if (m.t.isWater(p)) a = 0;
            a *= 0.5 + 0.5 * Math.exp(-Math.pow(m.t.slope(p.x(), p.y()) / 0.12, 2));
            m.attr[j * m.ac + i] = (float) a;
        }
    }

    static void spawnFeedback(CityModel m, int phase, GrowthEngine ge) {
        Config c = m.cfg;
        applySquareMerges(m, ge);
        // oversize blocks -> cutting passages
        List<Face> big = new ArrayList<>();
        java.util.Set<Long> squares = new java.util.HashSet<>();
        for (Plaza p : m.plazas) if (p.block) squares.add(p.faceKey);
        for (Cemetery cm : m.cemeteries) squares.add(cm.faceKey);
        for (CityBlock b : m.blocks) if (!b.locked && b.faceArea > c.d("block.maxArea") && b.face != null && !squares.contains(b.face.key())) big.add(b.face);
        big.sort((a, b) -> Double.compare(b.area(), a.area()));
        int n = 0;
        for (Face f : big) {
            if (n >= c.i("growth.infillPerPhase")) break;
            boolean stillValid = true;
            for (Edge e : f.edges()) if (!e.alive) { stillValid = false; break; }
            if (!stillValid) continue;
            ge.spawnInfill(f, phase);
            n++;
        }
        if (n > 0) m.log.event(phase, n + " oversize block(s) attracted cutting streets (access penalty feedback)");
        // dead ends inside the built fabric -> repair attempts
        int rep = 0;
        for (Node node : m.g.aliveNodes()) {
            if (rep >= c.i("growth.repairPerPhase")) break;
            if (node.deg() == 1 && m.housesWithin(node.p, 50) >= 6) {
                boolean has = false;
                for (GrowthEngine.Head h : ge.heads) if (h.node == node) { has = true; break; }
                if (!has) { ge.spawnRepair(node); rep++; }
            }
        }
    }
}
