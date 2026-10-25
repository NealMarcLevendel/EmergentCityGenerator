package com.emergentcity.city;

import java.util.ArrayList;
import java.util.List;

/**
 * Vista, enclosure and landmark-visibility evaluation (spec D, F).
 *
 * Vista: rays cast forward/backward along each street segment from samples on the centreline; a ray
 * ends at the first house footprint, landmark, water or the map edge. Length / street width is the
 * vista ratio; the hit object is the endpoint type.
 * Enclosure proxy (documented): at each sample, perpendicular rays left/right of length width/2 + 6 m;
 * enclosure = share of sides with a facade (continuity), and R = width / mean height of facades found.
 * Landmark visibility is 2.5D: a sightline from eye height (1.7 m) to the landmark top is blocked by any
 * house whose height exceeds the sightline at that distance.
 */
final class Vista {
    private Vista() {}

    record Ray(double len, int type, House house, Landmark lm) {}

    static Ray cast(CityModel m, P o, P d, double maxLen) {
        double lastBuilt = 0;
        for (double s = 2; s <= maxLen; s += 1.5) {
            P q = o.add(d.mul(s));
            if (((int) s) % 6 == 0 && s - lastBuilt > 1) {
                if (m.housesWithin(q, 25) > 0) lastBuilt = s;
                else if (s - lastBuilt > 60) return new Ray(lastBuilt + 25, 4, null, null); // view leaves the town
            }
            if (!m.t.inside(q, 0)) return new Ray(s, 0, null, null);
            if (m.t.isWater(q)) return new Ray(s, 3, null, null);
            Landmark l = m.landmarkAt(q);
            if (l != null) return new Ray(s, 2, null, l);
            House h = m.houseAt(q);
            if (h != null) return new Ray(s, 1, h, null);
        }
        return new Ray(maxLen, 0, null, null);
    }

    static void castRays(CityModel m) {
        for (House h : m.houses) h.vistaHits = 0;
        m.vistaSamples.clear();
        double sp = m.cfg.d("vista.sampleSpacing"), maxRay = m.cfg.d("vista.maxRay");
        for (Edge e : m.g.aliveEdges()) {
            double L = e.len();
            int n = Math.max(1, (int) (L / sp));
            P dir = e.dir();
            e.vistaMaxRatio = 0;
            e.vistaEndType = "";
            for (int k = 0; k < n; k++) {
                P p = e.a.p.lerp(e.b.p, (k + 0.5) / n);
                boolean interior = builtBothSides(m, p, dir, e.width);
                for (int sgn : new int[]{1, -1}) {
                    P d = dir.mul(sgn);
                    Ray r = cast(m, p, d, maxRay);
                    if (r.house() != null && r.len() >= 3 * e.width) r.house().vistaHits++;
                    double ratio = r.len() / e.width;
                    if (ratio > e.vistaMaxRatio) {
                        e.vistaMaxRatio = ratio;
                        e.vistaEndType = switch (r.type()) { case 1 -> "facade"; case 2 -> "landmark"; case 3 -> "water"; case 4 -> "countryside"; default -> "open"; };
                    }
                    m.vistaSamples.add(new double[]{p.x(), p.y(), d.x(), d.y(), r.len(), e.width, r.type(), e.id, interior ? 1 : 0});
                }
            }
        }
    }

    /** A sample is inside the urban fabric when facades exist on both sides within 30 m. */
    static boolean builtBothSides(CityModel m, P p, P dir, double width) {
        P nrm = dir.perpL();
        int sides = 0;
        for (int sgn : new int[]{1, -1}) {
            for (double s = width / 2; s <= width / 2 + 30; s += 2)
                if (m.houseAt(p.add(nrm.mul(sgn * s))) != null || m.landmarkAt(p.add(nrm.mul(sgn * s))) != null) { sides++; break; }
        }
        return sides == 2;
    }

    static void enclosureAndProportion(CityModel m) {
        for (Edge e : m.g.aliveEdges()) {
            double L = e.len();
            int n = Math.max(1, (int) (L / 10));
            P nrm = e.dir().perpL();
            double encl = 0, hsum = 0;
            int hcnt = 0;
            for (int k = 0; k < n; k++) {
                P p = e.a.p.lerp(e.b.p, (k + 0.5) / n);
                for (int sgn : new int[]{1, -1}) {
                    double reach = e.width / 2 + 6;
                    for (double s = e.width / 2 - 0.5; s <= reach; s += 1.0) {
                        House h = m.houseAt(p.add(nrm.mul(sgn * s)));
                        if (h != null) { encl += 0.5; hsum += h.height; hcnt++; break; }
                    }
                }
            }
            e.enclosure = encl / n;
            e.proportionR = hcnt > 0 ? e.width / (hsum / hcnt) : -1;
        }
    }

    static void landmarkVisibility(CityModel m) {
        for (Landmark l : m.landmarks) {
            P top = l.front.add(l.axisDir.mul(-Math.min(8, l.depth * 0.15)));
            int vis = 0, tot = 0;
            for (Edge e : m.g.aliveEdges()) {
                if (e.cls < 2) continue;
                int n = Math.max(1, (int) (e.len() / 20));
                for (int k = 0; k < n; k++) {
                    P p = e.a.p.lerp(e.b.p, (k + 0.5) / n);
                    double D = p.dist(top);
                    if (D > 600 || D < 20) continue;
                    tot++;
                    if (visible(m, p, top, l)) vis++;
                }
            }
            l.visibility = tot > 0 ? vis / (double) tot : 0;
        }
    }

    static boolean visible(CityModel m, P eye, P target, Landmark l) {
        double D = eye.dist(target);
        P d = target.sub(eye).norm();
        for (double s = 3; s < D - 2; s += 2) {
            P q = eye.add(d.mul(s));
            if (Geom.pointInPoly(q, l.fp)) return true;
            House h = m.houseAt(q);
            if (h != null) {
                double sight = 1.7 + (l.height - 1.7) * s / D;
                if (h.height > sight) return false;
            }
        }
        return true;
    }

    static List<double[]> samplesForEdge(CityModel m, int edgeId) {
        List<double[]> r = new ArrayList<>();
        for (double[] s : m.vistaSamples) if ((int) s[7] == edgeId) r.add(s);
        return r;
    }
}
