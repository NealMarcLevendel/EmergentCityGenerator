package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Diagnostics, objective terms and acceptance criteria (spec H, K, L, N). */
final class Metrics {
    record Check(String id, String name, boolean hard, boolean pass, String detail) {}

    final Map<String, Double> v = new LinkedHashMap<>();
    final Map<String, double[]> dist = new LinkedHashMap<>();
    final List<Check> checks = new ArrayList<>();
    final Map<String, Double> objective = new LinkedHashMap<>();
    long fingerprint;

    static double[] pct(List<Double> xs) {
        if (xs.isEmpty()) return new double[]{0, 0, 0, 0, 0, 0};
        double[] a = xs.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        double mean = Arrays.stream(a).average().orElse(0);
        return new double[]{a[0], a[(int) (0.1 * (a.length - 1))], a[(int) (0.5 * (a.length - 1))], a[(int) (0.9 * (a.length - 1))], a[a.length - 1], mean};
    }

    static double spearman(List<Double> x, List<Double> y) {
        int n = x.size();
        if (n < 3) return 0;
        double[] rx = ranks(x), ry = ranks(y);
        double mx = (n - 1) / 2.0, sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) { sxy += (rx[i] - mx) * (ry[i] - mx); sxx += Math.pow(rx[i] - mx, 2); syy += Math.pow(ry[i] - mx, 2); }
        return sxy / Math.sqrt(sxx * syy + 1e-12);
    }

    private static double[] ranks(List<Double> x) {
        Integer[] idx = new Integer[x.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(x.get(a), x.get(b)));
        double[] r = new double[x.size()];
        int i = 0;
        while (i < idx.length) {
            int j = i;
            while (j + 1 < idx.length && x.get(idx[j + 1]).equals(x.get(idx[i]))) j++;
            for (int k = i; k <= j; k++) r[idx[k]] = (i + j) / 2.0;
            i = j + 1;
        }
        return r;
    }

    static Metrics compute(CityModel m) {
        Metrics mt = new Metrics();
        Config c = m.cfg;
        List<Edge> es = m.g.aliveEdges();
        List<Node> ns = m.g.aliveNodes();
        mt.v.put("nodes", (double) ns.size());
        mt.v.put("edges", (double) es.size());
        mt.v.put("streetLength_m", m.g.totalLength());
        mt.v.put("blocks", (double) m.blocks.size());
        mt.v.put("houses", (double) m.houses.size());
        mt.v.put("centres", (double) m.centers.size());
        mt.v.put("emergentCentres", (double) m.centers.stream().filter(x -> !x.seeded).count());
        mt.v.put("plazas", (double) m.plazas.size());
        mt.v.put("landmarks", (double) m.landmarks.size());
        mt.v.put("bridges", (double) m.bridges);
        mt.v.put("cityRadius_m", m.cityRadius());

        // ---------- geometry validity (hard)
        int crossings = 0;
        for (Edge e : es) for (Edge f : m.g.edgesNear(e.a.p.x(), e.a.p.y(), e.b.p.x(), e.b.p.y())) {
            if (f.id <= e.id || f.a == e.a || f.a == e.b || f.b == e.a || f.b == e.b) continue;
            if (Geom.properCross(e.a.p, e.b.p, f.a.p, f.b.p, 1e-7)) crossings++;
        }
        int invalidQuads = 0, overlaps = 0, outside = 0;
        for (House h : m.houses) {
            if (h.q.length != 4 || !Geom.validQuad(h.q, c.d("house.minArea"), c.d("house.minEdge"), c.d("house.minAngleDeg"))) invalidQuads++;
            if (!Geom.pointInPoly(h.c, h.block.facePoly)) outside++;
        }
        m.indexHouses();
        for (House h : m.houses) for (House o : m.housesNear(h.c, 25)) {
            if (o.id <= h.id) continue;
            if (Geom.polysOverlap(h.q, o.q, 0.05)) overlaps++;
        }
        int houseOnStreet = 0;
        for (House h : m.houses) for (Edge e : m.g.edgesNear(h.c.x() - 20, h.c.y() - 20, h.c.x() + 20, h.c.y() + 20)) {
            if (Geom.segHitsPoly(e.a.p, e.b.p, Geom.shrink(h.q, 0.2))) { houseOnStreet++; break; }
        }
        // connectivity
        Center main = m.centers.get(0);
        Set<Integer> seen = new HashSet<>();
        List<Node> stack = new ArrayList<>(List.of(main.node));
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            if (!seen.add(n.id)) continue;
            for (Edge e : n.edges) stack.add(e.other(n));
        }
        double connected = seen.size() / (double) Math.max(1, ns.size());
        int deadEnds = 0;
        for (Node n : ns) if (n.deg() == 1) deadEnds++;
        int onWater = 0;
        for (Edge e : es) if (!e.bridge && m.t.waterLength(e.a.p, e.b.p) > 0) onWater++;
        mt.v.put("edgeCrossings", (double) crossings);
        mt.v.put("invalidHouseQuads", (double) invalidQuads);
        mt.v.put("houseOverlaps", (double) overlaps);
        mt.v.put("housesOutsideBlock", (double) outside);
        mt.v.put("housesCrossingStreets", (double) houseOnStreet);
        mt.v.put("connectedShare", connected);
        mt.v.put("deadEndShare", deadEnds / (double) ns.size());
        mt.v.put("nonBridgeEdgesOnWater", (double) onWater);

        // ---------- streets
        Map<Integer, Double> lenByCls = new HashMap<>();
        List<Double> dem = new ArrayList<>(), wid = new ArrayList<>();
        for (Edge e : es) { lenByCls.merge(e.cls, e.len(), Double::sum); dem.add(e.demand); wid.add(e.width); }
        double rho = spearman(dem, wid);
        mt.v.put("widthDemandSpearman", rho);
        for (int k = 1; k <= 4; k++) mt.v.put("lengthShare.class" + k, lenByCls.getOrDefault(k, 0.0) / m.g.totalLength());
        int classesPresent = 0;
        for (int k = 1; k <= 3; k++) if (lenByCls.getOrDefault(k, 0.0) > 0) classesPresent++;
        double maxStep = c.d("street.maxStepPerEdge");
        int jumps = 0, pairs = 0;
        List<Double> turnsDeg = new ArrayList<>();
        for (Chain ch : m.chains) for (int i = 0; i + 1 < ch.edges.size(); i++) {
            Edge a = ch.edges.get(i), b = ch.edges.get(i + 1);
            pairs++;
            if (a.cls == b.cls && Math.abs(a.width - b.width) > maxStep + 1e-6) jumps++;
            Node n = ch.nodes.get(i + 1);
            turnsDeg.add(Math.toDegrees(Math.PI - Math.abs(Geom.angDiff(a.angleFrom(n), b.angleFrom(n)))));
        }
        mt.v.put("withinClassWidthJumps", (double) jumps);
        mt.dist.put("turnAngleAlongStreets_deg", pct(turnsDeg));
        List<Double> wd = new ArrayList<>(), chainLen = new ArrayList<>();
        for (Edge e : es) wd.add(e.width);
        for (Chain ch : m.chains) chainLen.add(ch.length());
        mt.dist.put("streetWidth_m", pct(wd));
        mt.dist.put("streetLength_m (chains)", pct(chainLen));
        List<Double> jang = new ArrayList<>();
        for (Node n : ns) for (int i = 0; i < n.deg(); i++) for (int j = i + 1; j < n.deg(); j++)
            jang.add(Math.toDegrees(Math.abs(Geom.angDiff(n.edges.get(i).angleFrom(n), n.edges.get(j).angleFrom(n)))));
        double minJ = jang.stream().mapToDouble(x -> x).min().orElse(180);
        mt.v.put("minJunctionAngle_deg", minJ);

        // ---------- vistas and enclosure
        double maxW = c.d("vista.maxWidths");
        int countryside = 0, featureless = 0, endpointsFacade = 0, endpointsLm = 0, samples = 0, purposeful = 0;
        List<Double> ratios = new ArrayList<>();
        Map<Integer, Edge> byId = new HashMap<>();
        for (Edge e : es) byId.put(e.id, e);
        for (double[] s : m.vistaSamples) {
            Edge e = byId.get((int) s[7]);
            if (e == null) continue;
            // only streets inside the built fabric: views out over open countryside at the edge are expected
            if (s[8] < 0.5) continue;
            samples++;
            double ratio = s[4] / s[5];
            ratios.add(ratio);
            int type = (int) s[6];
            if (type == 1) endpointsFacade++;
            if (type == 2) endpointsLm++;
            boolean axis = e.axis || e.cls == 4;
            if (axis && ratio > maxW) purposeful++;
            if (type == 4) countryside++;
            if (!axis && type != 4 && ((ratio > maxW * 1.5 && type != 1 && type != 2) || ratio > maxW * 3)) featureless++;
        }
        mt.dist.put("vistaRatio (len/width)", pct(ratios));
        mt.v.put("featurelessVistaShare", featureless / (double) Math.max(1, samples));
        mt.v.put("vistaFacadeEndpointShare", endpointsFacade / (double) Math.max(1, samples));
        mt.v.put("vistaLandmarkEndpointShare", endpointsLm / (double) Math.max(1, samples));
        mt.v.put("purposefulAxisSamples", (double) purposeful);
        mt.v.put("countrysideViewShare", countryside / (double) Math.max(1, samples));
        List<Double> encl = new ArrayList<>(), rOrd = new ArrayList<>(), rMajor = new ArrayList<>();
        double eProp = 0;
        int nProp = 0;
        for (Edge e : es) {
            if (e.enclosure >= 0 && Vista.builtBothSides(m, e.mid(), e.dir(), e.width)) encl.add(e.enclosure);
            if (e.proportionR > 0) {
                if (e.cls <= 2) {
                    rOrd.add(e.proportionR);
                    eProp += Math.pow(Math.log(e.proportionR / 1.0), 2);
                    nProp++;
                } else rMajor.add(e.proportionR);
            }
        }
        mt.dist.put("enclosure (0-1)", pct(encl));
        mt.dist.put("R width/height ordinary", pct(rOrd));
        mt.dist.put("R width/height major", pct(rMajor));
        mt.v.put("E_proportion_mean_ordinary", nProp > 0 ? eProp / nProp : 0);

        // ---------- blocks and houses
        List<Double> barea = new ArrayList<>(), garden = new ArrayList<>(), front = new ArrayList<>(), depth = new ArrayList<>();
        int oversize = 0, withGarden = 0, housed = 0;
        for (CityBlock b : m.blocks) {
            barea.add(b.faceArea);
            if (b.faceArea > c.d("block.maxArea") * 1.5) oversize++;
            if (!b.houses.isEmpty()) housed++;
            if (b.gardenArea > 30) { withGarden++; garden.add(b.gardenArea); }
        }
        for (House h : m.houses) { front.add(h.frontage); depth.add(h.depth); }
        mt.dist.put("blockArea_m2", pct(barea));
        mt.dist.put("gardenArea_m2", pct(garden));
        mt.dist.put("houseFrontage_m", pct(front));
        mt.dist.put("houseDepth_m", pct(depth));
        mt.v.put("oversizeBlockShare", oversize / (double) Math.max(1, m.blocks.size()));
        mt.v.put("housedBlocksWithGardenShare", withGarden / (double) Math.max(1, housed));
        int facing = 0;
        for (House h : m.houses) {
            P probeF = h.frontMid().add(h.outward().mul(1.5)), probeR = h.rearMid().sub(h.outward().mul(1.5));
            double dF = 1e9, dR = 1e9;
            for (Edge e : m.g.edgesNear(h.c.x() - 40, h.c.y() - 40, h.c.x() + 40, h.c.y() + 40)) {
                dF = Math.min(dF, Geom.distPointSeg(probeF, e.a.p, e.b.p) - e.width / 2);
                dR = Math.min(dR, Geom.distPointSeg(probeR, e.a.p, e.b.p) - e.width / 2);
            }
            boolean plazaF = h.plazaFront;
            if (plazaF || dF < dR) facing++;
        }
        mt.v.put("housesFrontingStreetShare", facing / (double) Math.max(1, m.houses.size()));

        // ---------- centres and landmarks
        double lmRatio = 0, lmVis = 0;
        for (Landmark l : m.landmarks) { lmRatio = Math.max(lmRatio, l.ratio()); lmVis = Math.max(lmVis, l.visibility); }
        mt.v.put("landmarkMaxHW", lmRatio);
        mt.v.put("landmarkMaxVisibility", lmVis);

        // ---------- architecture
        int[] tierCount = new int[5];
        double[] ornSum = new double[5];
        List<Double> heights = new ArrayList<>();
        int neighPairs = 0, neighJumps = 0, styleSame = 0;
        for (House h : m.houses) { tierCount[h.tier]++; ornSum[h.tier] += h.ornament; heights.add(h.height); }
        for (CityBlock b : m.blocks) for (int i = 0; i + 1 < b.houses.size(); i++) {
            House a = b.houses.get(i), z = b.houses.get(i + 1);
            if (a.ring != z.ring) continue;
            neighPairs++;
            if (a.style.equals(z.style)) styleSame++;
            if (a.tier >= 3 && z.tier >= 3 && Math.abs(a.floors - z.floors) > 1) neighJumps++;
        }
        for (int k = 2; k <= 4; k++) {
            mt.v.put("tier" + k + "Share", tierCount[k] / (double) Math.max(1, m.houses.size()));
            mt.v.put("tier" + k + "MeanOrnament", tierCount[k] > 0 ? ornSum[k] / tierCount[k] : 0);
        }
        mt.v.put("tier1MeanOrnament", m.landmarks.isEmpty() ? 0 : 1.0);
        double[] hp = pct(heights);
        mt.dist.put("houseHeight_m", hp);
        double lmHeight = 0;
        for (Landmark l : m.landmarks) lmHeight = Math.max(lmHeight, l.height);
        mt.v.put("landmarkToP90HouseHeight", hp[3] > 0 ? lmHeight / hp[3] : 0);
        mt.v.put("neighbourFloorJumpShare", neighJumps / (double) Math.max(1, neighPairs));
        mt.v.put("neighbourStyleCoherence", styleSame / (double) Math.max(1, neighPairs));
        double imp2 = 0, imp3 = 0;
        int k2 = 0, k3 = 0;
        for (House h : m.houses) { if (h.tier == 2) { imp2 += h.importance; k2++; } if (h.tier == 3) { imp3 += h.importance; k3++; } }
        double vistaImp = 0, otherImp = 0;
        int nv = 0, no = 0;
        for (House h : m.houses) { if (h.vistaHits > 0) { vistaImp += h.importance; nv++; } else { otherImp += h.importance; no++; } }
        mt.v.put("vistaEndpointImportanceLift", (nv > 0 ? vistaImp / nv : 0) - (no > 0 ? otherImp / no : 0));

        // ---------- objective terms (lower is better)
        double eStreets = jumps / (double) Math.max(1, pairs) + pct(turnsDeg)[3] / 90.0;
        double eVistas = mt.v.get("featurelessVistaShare");
        double eBlocks = mt.v.get("oversizeBlockShare") + (1 - mt.v.get("housedBlocksWithGardenShare")) * 0.5;
        double eProps = mt.v.get("E_proportion_mean_ordinary");
        double eCenters = 1 - lmVis;
        double gsum = 0;
        for (Edge e : es) gsum += m.t.maxGrade(e.a.p, e.b.p) / 0.16;
        double eTerrain = gsum / Math.max(1, es.size());
        double eAccess = 1 - connected + mt.v.get("deadEndShare");
        double eHier = (tierCount[2] > 0 && ornSum[2] / tierCount[2] > (tierCount[3] > 0 ? ornSum[3] / tierCount[3] : 0)) ? 0 : 1;
        int badRb = 0;
        for (House h : m.houses) { double rb = h.height / h.frontage; if (rb < 0.4 || rb > 4.5) badRb++; }
        double eArchProp = badRb / (double) Math.max(1, m.houses.size());
        List<Double> bays = new ArrayList<>();
        for (House h : m.houses) bays.add(h.facade.bayW);
        double[] bp = pct(bays);
        double eRhythm = bp[5] > 0 ? (bp[3] - bp[1]) / bp[5] : 0;
        double eOrn = tierCount[4] > 0 && tierCount[3] > 0 && ornSum[4] / tierCount[4] > ornSum[3] / tierCount[3] ? 1 : 0;
        double eNeigh = mt.v.get("neighbourFloorJumpShare");
        double eStyle = 1 - mt.v.get("neighbourStyleCoherence");
        String[] keys = {"streets", "vistas", "blocks", "proportions", "centers", "terrain", "accessibility",
                "hierarchy", "archProportion", "facadeRhythm", "ornament", "neighbor", "style"};
        double[] vals = {eStreets, eVistas, eBlocks, eProps, eCenters, eTerrain, eAccess, eHier, eArchProp, eRhythm, eOrn, eNeigh, eStyle};
        double tot = 0;
        for (int i = 0; i < keys.length; i++) { mt.objective.put("E_" + keys[i], vals[i]); tot += c.d("lambda." + keys[i]) * vals[i]; }
        mt.objective.put("E_total", tot);

        // ---------- acceptance criteria (spec L 1-12 and N 1-11)
        mt.add("L1", "coherent centre(s) with plaza", false, m.centers.size() >= 1 && m.centers.get(0).plaza != null,
                m.centers.size() + " centre(s), " + m.plazas.size() + " plaza(s)");
        mt.add("L2", "monument meets height/width threshold", false, lmRatio >= c.d("landmark.minHeightToWidth") - 1e-9,
                String.format("max H/W %.2f (min %.2f)", lmRatio, c.d("landmark.minHeightToWidth")));
        mt.add("L3", "street hierarchy, width tracks demand", false, classesPresent >= 3 && rho > 0.6,
                String.format("%d classes, Spearman(width, demand)=%.2f", classesPresent, rho));
        mt.add("L4", "gradual transitions", false, jumps == 0 && pct(turnsDeg)[3] <= c.d("growth.maxTurnDeg") + 22,
                String.format("%d within-class width jumps > %.1f m; p90 turn along street %.1f deg", jumps, maxStep, pct(turnsDeg)[3]));
        mt.add("L5", "few long featureless straight streets", false, mt.v.get("featurelessVistaShare") < 0.15,
                String.format("featureless share %.1f%% (purposeful axis samples %d)", 100 * mt.v.get("featurelessVistaShare"), purposeful));
        mt.add("L6", "terminating vistas and enclosure", false, mt.v.get("vistaFacadeEndpointShare") > 0.5 && pct(encl)[2] >= 0.5,
                String.format("facade endpoints %.0f%%, median enclosure %.2f", 100 * mt.v.get("vistaFacadeEndpointShare"), pct(encl)[2]));
        mt.add("L7", "compact blocks, few oversize", false, mt.v.get("oversizeBlockShare") < 0.15,
                String.format("median block %.0f m2, oversize %.1f%%", pct(barea)[2], 100 * mt.v.get("oversizeBlockShare")));
        mt.add("L8", "all houses valid four-node quads", true, invalidQuads == 0 && !m.houses.isEmpty(),
                invalidQuads + " invalid of " + m.houses.size());
        mt.add("L9", "fronts face streets, shared gardens", false, mt.v.get("housesFrontingStreetShare") > 0.95 && mt.v.get("housedBlocksWithGardenShare") > 0.6,
                String.format("%.1f%% fronting streets, %.0f%% housed blocks with garden", 100 * mt.v.get("housesFrontingStreetShare"), 100 * mt.v.get("housedBlocksWithGardenShare")));
        mt.add("L10", "ordinary street R near 1:1", false, pct(rOrd)[2] >= 0.6 && pct(rOrd)[2] <= 1.6,
                String.format("median R ordinary %.2f, major %.2f", pct(rOrd)[2], pct(rMajor)[2]));
        mt.add("L11a", "planar street graph (no crossings)", true, crossings == 0, crossings + " crossings");
        mt.add("L11b", "no overlapping / misplaced houses", true, overlaps == 0 && outside == 0 && houseOnStreet == 0,
                overlaps + " overlaps, " + outside + " outside block, " + houseOnStreet + " crossing a street");
        mt.add("L11c", "network connected", true, connected > 0.98, String.format("%.1f%% nodes reachable from main centre", 100 * connected));
        mt.add("L11d", "streets avoid water except bridges", true, onWater == 0, onWater + " non-bridge segments on water");
        mt.add("L11e", "min junction angle respected", true, minJ >= c.d("growth.minJunctionAngleDeg") - 0.5, String.format("min %.1f deg", minJ));
        mt.add("N1", "legible tier hierarchy", false, tierCount[2] > 0 && tierCount[3] > 0 && (tierCount[4] > 0 || m.houses.size() < 300) && !m.landmarks.isEmpty(),
                String.format("tiers 2/3/4 = %d/%d/%d, monuments %d", tierCount[2], tierCount[3], tierCount[4], m.landmarks.size()));
        mt.add("N3", "ornament decreases with tier", false, (tierCount[2] == 0 || mt.v.get("tier2MeanOrnament") > mt.v.get("tier3MeanOrnament"))
                        && (tierCount[4] == 0 || mt.v.get("tier3MeanOrnament") > mt.v.get("tier4MeanOrnament")),
                String.format("mean ornament t1/t2/t3/t4 = %.2f/%.2f/%.2f/%.2f", mt.v.get("tier1MeanOrnament"), mt.v.get("tier2MeanOrnament"),
                        mt.v.get("tier3MeanOrnament"), mt.v.get("tier4MeanOrnament")));
        mt.add("N7", "coherent street wall", false, mt.v.get("neighbourFloorJumpShare") < 0.05 && mt.v.get("neighbourStyleCoherence") > 0.85,
                String.format("floor jumps >1 storey %.1f%%, style coherence %.0f%%", 100 * mt.v.get("neighbourFloorJumpShare"), 100 * mt.v.get("neighbourStyleCoherence")));
        mt.add("N9", "monuments break pattern, scarcity kept", false, mt.v.get("landmarkToP90HouseHeight") >= 2 && mt.v.get("tier2Share") <= c.d("arch.tier2Quota") + 1e-9,
                String.format("landmark/p90 house height %.1fx, tier-2 share %.1f%%", mt.v.get("landmarkToP90HouseHeight"), 100 * mt.v.get("tier2Share")));
        mt.add("N10", "vista endpoints get more important architecture", false, mt.v.get("vistaEndpointImportanceLift") > 0,
                String.format("importance lift %.3f", mt.v.get("vistaEndpointImportanceLift")));
        mt.fingerprint = fingerprint(m);
        return mt;
    }

    void add(String id, String name, boolean hard, boolean pass, String detail) { checks.add(new Check(id, name, hard, pass, detail)); }

    boolean hardPass() { return checks.stream().filter(Check::hard).allMatch(Check::pass); }
    long softPassed() { return checks.stream().filter(x -> !x.hard() && x.pass()).count(); }
    long softTotal() { return checks.stream().filter(x -> !x.hard()).count(); }

    static long fingerprint(CityModel m) {
        long h = 1125899906842597L;
        for (Edge e : m.g.aliveEdges()) {
            h = 31 * h + Double.hashCode(Math.round(e.a.p.x() * 100) / 100.0);
            h = 31 * h + Double.hashCode(Math.round(e.b.p.y() * 100) / 100.0);
            h = 31 * h + e.cls;
        }
        for (House x : m.houses) { h = 31 * h + Double.hashCode(Math.round(x.q[0].x() * 100) / 100.0); h = 31 * h + x.floors + 7L * x.tier; }
        return h;
    }

    String report(CityModel m) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "EMERGENT HISTORIC CITY - metrics report%nterrain: %s%nseed: %d%nfingerprint: %016x%n%n",
                m.t.name, m.seed, fingerprint));
        sb.append("== Growth phases ==\n");
        m.phaseSummaries.forEach((k, val) -> sb.append(k).append(": ").append(val).append('\n'));
        sb.append("\n== Acceptance criteria ==\n");
        for (Check ck : checks)
            sb.append(String.format(Locale.ROOT, "%-5s %-4s %-6s %-46s %s%n", ck.id(), ck.hard() ? "HARD" : "soft", ck.pass() ? "PASS" : "FAIL", ck.name(), ck.detail()));
        sb.append(String.format(Locale.ROOT, "hard constraints: %s; soft criteria passed: %d/%d%n", hardPass() ? "ALL PASS" : "FAILED", softPassed(), softTotal()));
        sb.append("\n== Scalar metrics ==\n");
        v.forEach((k, val) -> sb.append(String.format(Locale.ROOT, "%-34s %12.3f%n", k, val)));
        sb.append("\n== Distributions (min / p10 / median / p90 / max / mean) ==\n");
        dist.forEach((k, a) -> sb.append(String.format(Locale.ROOT, "%-34s %8.2f %8.2f %8.2f %8.2f %8.2f %8.2f%n", k, a[0], a[1], a[2], a[3], a[4], a[5])));
        sb.append("\n== Objective terms (lower is better; weighted by lambda.*) ==\n");
        objective.forEach((k, val) -> sb.append(String.format(Locale.ROOT, "%-34s %8.3f%n", k, val)));
        sb.append("\n== Centres ==\n");
        for (Center ce : m.centers)
            sb.append(String.format(Locale.ROOT, "centre %d %s at (%.0f, %.0f) attraction %.2f strength %.2f - %s%n", ce.id,
                    ce.seeded ? "seeded" : "emergent (phase " + ce.bornPhase + ")", ce.pos.x(), ce.pos.y(), ce.attraction, ce.strength, ce.why));
        sb.append("\n== Landmarks ==\n");
        for (Landmark l : m.landmarks)
            sb.append(String.format(Locale.ROOT, "%s (%s): %.0f x %.0f m, height %.0f m, H/W %.2f, visible from %.0f%% of approach samples - %s%n",
                    l.type, l.style, l.width, l.depth, l.height, l.ratio(), 100 * l.visibility, l.why));
        sb.append("\nIMPLEMENTED vs APPROXIMATED: see README.md section 'Implementation status'.\n");
        return sb.toString();
    }
}
