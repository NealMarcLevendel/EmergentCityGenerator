package com.emergentcity.city;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Architectural hierarchy (spec N): importance -> tier (with scarcity) -> massing/height from street
 * proportion and neighbours -> ornament budget -> parametric facade derived from a few parent
 * dimensions and style-specific proportional rules. Styles are presets of a configured synthesis.
 */
final class Architecture {
    private Architecture() {}

    /** Style family preset: proportional rules, not a historical catalogue. */
    record Style(String name, double bayW, double winFrac, double winRatio, String roof, boolean classical,
                 double ornamentFactor, String family) {}

    static final Map<String, Style> STYLES = new HashMap<>();
    static {
        STYLES.put("georgian_classical", new Style("georgian_classical", 2.75, 0.42, 1.85, "parapet", true, 1.0,
                "Georgian/classical residential (e.g. Bath): repeated bays, restrained detail, regular cornices"));
        STYLES.put("medieval_vernacular", new Style("medieval_vernacular", 2.4, 0.36, 1.4, "steep-eaves", false, 0.8,
                "Medieval/early-modern inherited fabric: plastered fronts, steep roofs, irregular openings"));
        STYLES.put("perimeter_block", new Style("perimeter_block", 2.2, 0.48, 1.7, "front-gable", false, 0.9,
                "Narrow-plot perimeter blocks (e.g. Amsterdam, Ghent): gable fronts, tall windows"));
        STYLES.put("neoclassical_monumental", new Style("neoclassical_monumental", 4.2, 0.4, 2.2, "pediment", true, 1.2,
                "Neoclassical monumental (e.g. Paris civic compositions): orders, pediments, domes"));
        STYLES.put("baroque", new Style("baroque", 3.6, 0.42, 2.0, "pediment", true, 1.3,
                "Baroque composition (e.g. Rome): focal facades closing vistas"));
    }

    static Map<String, String> synthesis(Config c) {
        Map<String, String> m = new HashMap<>();
        for (String part : c.s("arch.synthesis").split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            if (!STYLES.containsKey(kv[1].trim())) throw new IllegalArgumentException("Unknown style " + kv[1]);
            m.put(kv[0].trim(), kv[1].trim());
        }
        m.putIfAbsent("core", "georgian_classical");
        m.putIfAbsent("outer", "medieval_vernacular");
        m.putIfAbsent("monument", "neoclassical_monumental");
        return m;
    }

    static double rTarget(Config c, House h) {
        if (h.plazaFront) return c.d("arch.rTarget.plaza");
        return switch (h.streetCls) {
            case 1 -> c.d("arch.rTarget.local");
            case 2 -> c.d("arch.rTarget.connector");
            case 3 -> c.d("arch.rTarget.primary");
            default -> c.d("arch.rTarget.civic");
        };
    }

    static void assign(CityModel m) {
        Config c = m.cfg;
        SplittableRandom r = new SplittableRandom(m.seed * 13 + 5);
        Map<String, String> syn = synthesis(c);
        double R = Math.max(200, m.cityRadius());
        for (Landmark l : m.landmarks) l.style = syn.get("monument");
        // --- context
        for (House h : m.houses) {
            P f = h.frontMid(), out = h.outward();
            P probe = f.add(out.mul(1.0));
            Edge best = null;
            double bd = 30;
            for (Edge e : m.g.edgesNear(probe.x() - 30, probe.y() - 30, probe.x() + 30, probe.y() + 30)) {
                double d = Geom.distPointSeg(probe, e.a.p, e.b.p);
                if (d < bd) { bd = d; best = e; }
            }
            h.street = best;
            h.streetWidth = best != null ? best.width : 6;
            h.streetCls = best != null ? best.cls : 1;
            h.sideCls = 0;
            if (h.front2) { // corner houses: the class of the side street too
                P sm = h.q[1].lerp(h.q[2], 0.5), so = h.q[2].sub(h.q[1]).norm().perpL().mul(-1);
                P sp = sm.add(so.mul(1.0));
                double sd = 30;
                for (Edge e : m.g.edgesNear(sp.x() - 30, sp.y() - 30, sp.x() + 30, sp.y() + 30)) {
                    double d = Geom.distPointSeg(sp, e.a.p, e.b.p);
                    if (d < sd) { sd = d; h.sideCls = e.cls; }
                }
            }
            h.plazaFront = false; h.plaza = null;
            P far = f.add(out.mul(4.0));
            for (Plaza p : m.plazas) if (Geom.pointInPoly(far, p.poly)) { h.plazaFront = true; h.plaza = p; break; }
            Center nc = m.nearestCenter(h.c);
            h.centerDist = nc == null ? 1e9 : nc.pos.dist(h.c);
            double coreR = c.d("arch.coreRadiusFraction") * R * (nc != null && nc.seeded ? 1 : 0.6);
            h.style = h.centerDist < coreR ? syn.get("core") : syn.get("outer");
        }
        // one style per street block: a block keeps the style it was built with, otherwise the majority of its houses
        for (CityBlock b : m.blocks) {
            if (b.houses.isEmpty()) continue;
            String st = b.style;
            if (st == null) {
                Map<String, Integer> votes = new HashMap<>();
                for (House h : b.houses) votes.merge(h.style, 1, Integer::sum);
                st = java.util.Collections.max(votes.entrySet(), Map.Entry.comparingByValue()).getKey();
            }
            for (House h : b.houses) h.style = st;
        }
        // --- importance (context first, randomness only as a small tie-breaker)
        for (House h : m.houses) {
            h.focal = Math.max(h.plazaFront ? 1 : 0, Math.max(Math.min(1, h.vistaHits / 3.0), h.corner ? 0.6 : 0));
            double lmAdj = 0;
            for (Landmark l : m.landmarks) if (Geom.centroid(l.fp).dist(h.c) < Math.max(l.width, l.depth) * 0.8 + 25) lmAdj = 1;
            h.importance = 0.35 * Math.exp(-h.centerDist / 260) + 0.2 * (h.streetCls - 1) / 3.0 + 0.25 * h.focal + 0.1 * lmAdj + 0.1 * r.nextDouble();
        }
        // --- tiers with scarcity
        List<House> sorted = new ArrayList<>(m.houses);
        sorted.sort((a, b) -> a.importance != b.importance ? Double.compare(b.importance, a.importance) : Integer.compare(a.id, b.id));
        for (House h : m.houses) { h.tier = 3; h.tierReason = "ordinary fabric (default)"; }
        int quota2 = (int) Math.floor(c.d("arch.tier2Quota") * m.houses.size());
        int n2 = 0;
        for (House h : sorted) {
            if (n2 >= quota2 || h.importance < c.d("arch.tier2Threshold")) break;
            if (neighbourHasTier(h, 2)) continue;
            h.tier = 2;
            n2++;
            h.tierReason = String.format("importance %.2f%s%s%s%s", h.importance, h.plazaFront ? ", plaza frontage" : "",
                    h.vistaHits > 0 ? ", terminates " + h.vistaHits + " vista ray(s)" : "", h.corner ? ", corner" : "",
                    h.streetCls >= 3 ? ", major street" : "");
        }
        int quota4 = (int) Math.floor(c.d("arch.tier4Share") * m.houses.size());
        int n4 = 0;
        for (int i = sorted.size() - 1; i >= 0 && n4 < quota4; i--) {
            House h = sorted.get(i);
            if (h.tier != 3 || h.streetCls > 1 || h.centerDist < 0.5 * R || h.plazaFront) continue;
            h.tier = 4;
            n4++;
            h.tierReason = String.format("low importance %.2f, peripheral lane", h.importance);
        }
        // --- massing: height from street proportion, capped by tier and distance from centre
        double fh = c.d("arch.floorHeight"), gx = c.d("arch.groundFloorExtra");
        double hsc = c.d("arch.heightScale"); // > 1 when the player allows more storeys than usual
        for (House h : m.houses) {
            double wEff = h.plazaFront && h.plaza != null ? h.plaza.radius * 2 : h.streetWidth;
            double hTarget = wEff / rTarget(c, h);
            int f = (int) Math.round((hTarget - gx) / fh);
            int cap = 2 + (int) Math.round(3.2 * Math.exp(-h.centerDist / 380));
            int lo = h.tier == 4 ? 1 : (h.tier == 3 ? 2 : 3), hi = h.tier == 4 ? 2 : (h.tier == 3 ? Math.min(5, cap) : Math.min(6, cap + 1));
            hi = Math.max(lo, hi);
            h.floors = (int) Geom.clamp(f, lo, hi);
        }
        // --- neighbour compatibility: smooth cornice lines along each ring for tiers 3/4
        for (CityBlock b : m.blocks) {
            List<House> hs = b.houses;
            for (int it = 0; it < 2; it++)
                for (int i = 0; i < hs.size(); i++) {
                    House h = hs.get(i);
                    if (h.tier < 3) continue;
                    House a = i > 0 && hs.get(i - 1).ring == h.ring ? hs.get(i - 1) : null;
                    House z = i + 1 < hs.size() && hs.get(i + 1).ring == h.ring ? hs.get(i + 1) : null;
                    if (a == null || z == null) continue;
                    int f = (int) Math.round(0.5 * h.floors + 0.25 * a.floors + 0.25 * z.floors);
                    int lo = h.tier == 4 ? 1 : 2, hi = h.tier == 4 ? 2 : 5;
                    h.floors = (int) Geom.clamp(f, lo, Math.max(lo, Math.min(hi, h.floors + 1)));
                }
        }
        // hard height caps: no storey count above arch.maxFloors anywhere (plaza frontages included), and no house
        // taller than arch.maxHeightToFrontage x its frontage, so narrow plots never turn into towers
        int maxFloors = c.i("arch.maxFloors");
        double maxHF = c.d("arch.maxHeightToFrontage") * hsc;
        for (House h : m.houses) {
            int byShape = (int) Math.floor((maxHF * h.frontage - gx) / fh);
            // with a player storey limit above the usual, the whole town scales up in proportion (centre reaching the limit)
            h.floors = Math.max(1, Math.min((int) Math.round((h.floors + h.bonusFloors) * hsc), Math.min(maxFloors, Math.max(1, byShape))));
            if (h.fixedFloors > 0) h.floors = h.fixedFloors; // a standing building keeps its storeys
        }
        uniformBlockHeights(m, maxFloors);
        assignUses(m, R);
        double[] base = {0, 1.0, 0.55, 0.2, 0.04};
        for (House h : m.houses) {
            h.height = h.floors * fh + gx;
            Style st = STYLES.get(h.style);
            h.ornament = Geom.clamp(base[h.tier] * (0.75 + 0.25 * h.focal) * st.ornamentFactor(), 0, 1);
            h.facade = facade(h, st, c, new SplittableRandom(m.seed ^ (h.id * 0x9E3779B97F4A7C15L) ^ (h.variant * 0xC2B2AE3D27D4EB4FL)));
        }
        // --- feed facade heights back to streets (used next phase for the proportion nudge)
        Map<Integer, double[]> acc = new HashMap<>();
        for (House h : m.houses) if (h.street != null) {
            double[] a = acc.computeIfAbsent(h.street.id, k -> new double[2]);
            a[0] += h.height; a[1]++;
        }
        for (Edge e : m.g.aliveEdges()) { double[] a = acc.get(e.id); e.meanFacadeH = a == null ? -1 : a[0] / a[1]; }
    }

    /**
     * Building use from location, with scarcity (shares of all houses) and spacing; built houses keep their use
     * (useFixed) unless an evolving town converts them on purpose.
     * - restaurant: plaza frontage first, then main streets; never next to another restaurant; needs 2+ storeys;
     * - bookstore: standalone commercial near a centre on a busier street;
     * - mixed: shop on the ground floor, home above, along connector/primary streets and plazas; needs 2+ storeys;
     * - residential: everything else.
     */
    static void assignUses(CityModel m, double R) {
        Config c = m.cfg;
        int n = m.houses.size();
        if (n == 0) return;
        int qRest = (int) Math.round(c.d("use.restaurantShare") * n), qBook = (int) Math.round(c.d("use.bookstoreShare") * n),
                qMixed = (int) Math.round(c.d("use.mixedShare") * n);
        int haveRest = 0, haveBook = 0, haveMixed = 0;
        for (House h : m.houses) {
            if (!h.useFixed) { h.use = "residential"; continue; }
            switch (h.use) { case "restaurant" -> haveRest++; case "bookstore" -> haveBook++; case "mixed" -> haveMixed++; default -> { } }
        }
        List<House> order = new ArrayList<>(m.houses);
        order.sort((a, b) -> a.importance != b.importance ? Double.compare(b.importance, a.importance) : Integer.compare(a.id, b.id));
        for (House h : order) {
            if (h.useFixed || haveRest >= qRest || h.floors < 2 || h.passage) continue;
            if (!onMainStreet(h, c)) continue;
            if (neighbourHasUse(h, "restaurant")) continue;
            h.use = "restaurant";
            haveRest++;
        }
        for (House h : order) {
            if (h.useFixed || !"residential".equals(h.use) || haveBook >= qBook || h.passage) continue;
            if (!onMainStreet(h, c) || h.centerDist > 0.55 * R || neighbourHasUse(h, "bookstore")) continue;
            h.use = "bookstore";
            haveBook++;
        }
        // corner and infill buildings (often without a door to the courtyard) serve as shops, with rooms above
        double cornerShare = c.d("use.cornerShopShare");
        for (House h : order) {
            if (h.useFixed || !"residential".equals(h.use) || h.passage || !(h.corner || h.infill)) continue;
            if (!onMainStreet(h, c)) continue; // smaller streets stay residential
            if (((h.id * 2654435761L) >>> 8) % 1000 >= cornerShare * 1000) continue;
            h.use = "mixed";
            haveMixed++;
        }
        for (House h : order) {
            if (h.useFixed || !"residential".equals(h.use) || haveMixed >= qMixed || h.floors < 2 || h.passage) continue;
            if (!onMainStreet(h, c)) continue;
            h.use = "mixed";
            haveMixed++;
        }
    }

    /**
     * One cornice line per block: every house along a block's streets takes the block's typical storey count (the
     * median), so rows read as one even street wall. Variation goes to the corners, which may rise a storey above
     * the row or (on small corners) drop one below it. Houses already standing keep their storeys.
     */
    static void uniformBlockHeights(CityModel m, int maxFloors) {
        for (CityBlock b : m.blocks) {
            List<Integer> fl = new ArrayList<>();
            for (House h : b.houses) if (!h.corner) fl.add(h.floors);
            if (fl.isEmpty()) for (House h : b.houses) fl.add(h.floors);
            if (fl.isEmpty()) continue;
            java.util.Collections.sort(fl);
            int med = fl.get(fl.size() / 2);
            for (House h : b.houses) {
                if (h.fixedFloors > 0) continue;
                if (!h.corner) { h.floors = med; continue; }
                // corners: an accent storey where the corner is important or the house wanted to be taller
                long k = Math.round(h.q[0].x()) * 73856093L ^ Math.round(h.q[0].y()) * 19349663L; // stable across re-planning
                boolean accent = h.floors > med || Math.floorMod(k, 3) == 0;
                int want = accent ? med + 1 : h.floors < med - 1 ? med - 1 : med;
                h.floors = Math.max(1, Math.min(maxFloors, want));
            }
        }
    }

    /** Shops, bookstores and restaurants only on main streets and plazas (use.minStreetClass); homes on smaller streets. */
    static boolean onMainStreet(House h, Config c) {
        int min = c.i("use.minStreetClass");
        return h.plazaFront || h.streetCls >= min || (h.front2 && h.sideCls >= min);
    }

    private static boolean neighbourHasUse(House h, String use) {
        if (h.block == null) return false;
        List<House> hs = h.block.houses;
        int i = hs.indexOf(h);
        for (int d = -1; d <= 1; d += 2) {
            int j = i + d;
            if (j >= 0 && j < hs.size() && use.equals(hs.get(j).use)) return true;
        }
        return false;
    }

    private static boolean neighbourHasTier(House h, int tier) {
        List<House> hs = h.block.houses;
        int i = hs.indexOf(h);
        for (int d = -2; d <= 2; d++) {
            int j = i + d;
            if (d == 0 || j < 0 || j >= hs.size()) continue;
            if (hs.get(j).ring == h.ring && hs.get(j).tier == tier) return true;
        }
        return false;
    }

    /** Parametric facade grammar: everything derives from frontage, floors, tier, ornament and style. */
    static Facade facade(House h, Style st, Config c, SplittableRandom r) {
        Facade f = new Facade();
        int bays = Math.max(1, (int) Math.round(h.frontage / st.bayW()));
        f.symmetric = h.tier <= 2 || st.classical();
        if (h.tier <= 2 && bays >= 2 && bays % 2 == 0) bays = h.frontage / (bays + 1) >= 1.9 ? bays + 1 : bays - 1;
        f.bays = bays;
        f.bayW = h.frontage / bays;
        double fh = c.d("arch.floorHeight");
        f.floorH = new double[h.floors];
        for (int i = 0; i < h.floors; i++) {
            double v = fh;
            if (i == 0) v += c.d("arch.groundFloorExtra");
            if (i == 1 && h.tier == 2 && h.floors >= 3) v *= 1.15; // piano nobile
            if (i == h.floors - 1 && st.classical() && h.floors >= 3) v *= 0.85; // attic storey
            f.floorH[i] = v;
        }
        f.winW = f.bayW * st.winFrac();
        f.winH = Math.min(f.winW * st.winRatio(), fh * 0.62);
        f.ornament = h.ornament;
        f.corniceH = 0.2 + 0.9 * h.ornament;
        f.pilasters = h.tier <= 2 && st.classical() && h.ornament > 0.4;
        f.rustication = h.tier <= 2 && st.classical();
        f.doorPediment = h.tier <= 2 || (h.tier == 3 && st.classical() && h.ornament > 0.18);
        f.balconies = h.tier == 2 && h.floors >= 3 && h.ornament > 0.45;
        f.roof = st.roof();
        f.doorBay = f.symmetric ? bays / 2 : r.nextInt(bays);
        f.rule = String.format("style=%s tier=%d bays=%d (frontage %.1f / bay %.2f) floors=%d ornament=%.2f%s%s",
                st.name(), h.tier, bays, h.frontage, st.bayW(), h.floors, h.ornament,
                f.pilasters ? " pilasters" : "", f.balconies ? " piano-nobile balcony" : "");
        return f;
    }
}
