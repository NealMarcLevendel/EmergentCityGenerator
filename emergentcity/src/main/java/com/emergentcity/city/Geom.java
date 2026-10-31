package com.emergentcity.city;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Computational-geometry helpers.
 *
 * LIMITATION (documented per spec section K/M): the JDK has no full computational-geometry
 * library. Boolean polygon operations use java.awt.geom.Area (robust, but flattens curves and
 * works in double precision without snapping). Polygon offsetting and validity checks are
 * implemented here with explicit validation and rejection rather than silent repair.
 * For production use, JTS Topology Suite (org.locationtech.jts) is the recommended
 * replacement: it offers robust buffering, snapping, polygon validation and STRtree indexing.
 */
public final class Geom {
    private Geom() {}

    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static double angDiff(double a, double b) {
        double d = (a - b) % (2 * Math.PI);
        if (d > Math.PI) d -= 2 * Math.PI;
        if (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    /** Parametric intersection of segments ab and cd: returns {t,u} or null when parallel. */
    static double[] segInter(P a, P b, P c, P d) {
        P r = b.sub(a), s = d.sub(c);
        double den = r.cross(s);
        if (Math.abs(den) < 1e-12) return null;
        P ca = c.sub(a);
        return new double[]{ca.cross(s) / den, ca.cross(r) / den};
    }

    static boolean properCross(P a, P b, P c, P d, double eps) {
        double[] tu = segInter(a, b, c, d);
        return tu != null && tu[0] > eps && tu[0] < 1 - eps && tu[1] > eps && tu[1] < 1 - eps;
    }

    public static double distPointSeg(P p, P a, P b) {
        P ab = b.sub(a);
        double l2 = ab.dot(ab);
        if (l2 < 1e-12) return p.dist(a);
        double t = clamp(p.sub(a).dot(ab) / l2, 0, 1);
        return p.dist(a.add(ab.mul(t)));
    }

    static double signedArea(List<P> r) {
        double s = 0;
        int n = r.size();
        for (int i = 0; i < n; i++) {
            P a = r.get(i), b = r.get((i + 1) % n);
            s += a.x() * b.y() - b.x() * a.y();
        }
        return s / 2;
    }

    static double signedArea(P[] r) {
        double s = 0;
        for (int i = 0; i < r.length; i++) {
            P a = r[i], b = r[(i + 1) % r.length];
            s += a.x() * b.y() - b.x() * a.y();
        }
        return s / 2;
    }

    static double perimeter(List<P> r) {
        double s = 0;
        for (int i = 0; i < r.size(); i++) s += r.get(i).dist(r.get((i + 1) % r.size()));
        return s;
    }

    public static P centroid(List<P> r) {
        double a = signedArea(r);
        if (Math.abs(a) < 1e-9) {
            double x = 0, y = 0;
            for (P p : r) { x += p.x(); y += p.y(); }
            return new P(x / r.size(), y / r.size());
        }
        double cx = 0, cy = 0;
        int n = r.size();
        for (int i = 0; i < n; i++) {
            P p = r.get(i), q = r.get((i + 1) % n);
            double f = p.x() * q.y() - q.x() * p.y();
            cx += (p.x() + q.x()) * f;
            cy += (p.y() + q.y()) * f;
        }
        return new P(cx / (6 * a), cy / (6 * a));
    }

    static P centroid(P[] q) { return centroid(List.of(q)); }

    public static boolean pointInPoly(P p, List<P> poly) {
        boolean in = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            P a = poly.get(i), b = poly.get(j);
            if ((a.y() > p.y()) != (b.y() > p.y())
                    && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x()) in = !in;
        }
        return in;
    }

    static boolean pointInPoly(P p, P[] poly) { return pointInPoly(p, List.of(poly)); }

    static boolean isSimple(List<P> r) {
        int n = r.size();
        if (n < 3) return false;
        for (int i = 0; i < n; i++) {
            if (r.get(i).dist(r.get((i + 1) % n)) < 1e-6) return false;
            for (int j = i + 1; j < n; j++) {
                if (j == i + 1 || (i == 0 && j == n - 1)) continue;
                if (properCross(r.get(i), r.get((i + 1) % n), r.get(j), r.get((j + 1) % n), 1e-9)) return false;
                if (r.get(i).dist(r.get(j)) < 1e-6) return false;
            }
        }
        return true;
    }

    private static double triArea(P a, P b, P c) { return Math.abs(b.sub(a).cross(c.sub(a))) / 2; }

    private static List<P> dp(List<P> pts, double tol) {
        if (pts.size() < 3) return new ArrayList<>(pts);
        P a = pts.get(0), b = pts.get(pts.size() - 1);
        int idx = -1;
        double best = -1;
        for (int i = 1; i < pts.size() - 1; i++) {
            double d = distPointSeg(pts.get(i), a, b);
            if (d > best) { best = d; idx = i; }
        }
        if (best <= tol) { List<P> r = new ArrayList<>(); r.add(a); r.add(b); return r; }
        List<P> l = dp(pts.subList(0, idx + 1), tol), rr = dp(pts.subList(idx, pts.size()), tol);
        l.remove(l.size() - 1);
        l.addAll(rr);
        return l;
    }

    /** Douglas-Peucker on a closed ring, then collinear removal, then short-edge collapse. */
    static List<P> simplifyRing(List<P> ring, double tol, double minEdge) {
        List<P> r = new ArrayList<>();
        for (P p : ring) if (r.isEmpty() || r.get(r.size() - 1).dist(p) > 0.02) r.add(p);
        while (r.size() > 1 && r.get(0).dist(r.get(r.size() - 1)) < 0.02) r.remove(r.size() - 1);
        if (r.size() < 4) return r;
        int far = 0;
        double fd = -1;
        for (int i = 0; i < r.size(); i++) { double d = r.get(i).dist(r.get(0)); if (d > fd) { fd = d; far = i; } }
        List<P> h1 = dp(new ArrayList<>(r.subList(0, far + 1)), tol);
        List<P> second = new ArrayList<>(r.subList(far, r.size()));
        second.add(r.get(0));
        List<P> h2 = dp(second, tol);
        List<P> out = new ArrayList<>(h1.subList(0, h1.size() - 1));
        out.addAll(h2.subList(0, h2.size() - 1));
        r = out;
        boolean changed = true;
        while (changed && r.size() > 3) {
            changed = false;
            for (int i = 0; i < r.size() && r.size() > 3; i++) {
                P a = r.get((i - 1 + r.size()) % r.size()), b = r.get(i), c = r.get((i + 1) % r.size());
                double dev = Math.abs(angDiff(b.sub(a).angle(), c.sub(b).angle()));
                if (dev < Math.toRadians(4)) { r.remove(i); changed = true; i--; }
            }
        }
        while (r.size() > 3) {
            int bi = -1;
            double bl = minEdge;
            for (int i = 0; i < r.size(); i++) {
                double l = r.get(i).dist(r.get((i + 1) % r.size()));
                if (l < bl) { bl = l; bi = i; }
            }
            if (bi < 0) break;
            int n = r.size(), i = bi, j = (bi + 1) % n;
            double ai = triArea(r.get((i - 1 + n) % n), r.get(i), r.get(j));
            double aj = triArea(r.get(i), r.get(j), r.get((j + 1) % n));
            r.remove(ai <= aj ? i : j);
        }
        return r;
    }

    /**
     * Inward offset of a counter-clockwise ring by distance d using mitred offset lines.
     * Returns null (never a silently repaired shape) when the offset collapses, flips an edge,
     * self-intersects, leaves the parent ring or produces an over-long mitre.
     */
    static List<P> offsetInward(List<P> p, double d, double maxMiter) {
        int n = p.size();
        if (n < 3) return null;
        P[] e = new P[n], nn = new P[n];
        for (int i = 0; i < n; i++) {
            e[i] = p.get((i + 1) % n).sub(p.get(i)).norm();
            nn[i] = e[i].perpL();
        }
        List<P> q = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int h = (i - 1 + n) % n;
            P a = p.get(i).add(nn[h].mul(d)), b = p.get(i).add(nn[i].mul(d));
            double den = e[h].cross(e[i]);
            P qi;
            if (Math.abs(den) < 1e-6) qi = b;
            else {
                double s = b.sub(a).cross(e[i]) / den;
                qi = a.add(e[h].mul(s));
            }
            if (qi.dist(p.get(i)) > maxMiter * d) return null;
            q.add(qi);
        }
        for (int i = 0; i < n; i++) {
            P qe = q.get((i + 1) % n).sub(q.get(i));
            if (qe.dot(e[i]) <= 0.3 || qe.len() < 0.3) return null;
        }
        if (signedArea(q) <= 1 || !isSimple(q)) return null;
        for (P x : q) if (!pointInPoly(x, p)) return null;
        return q;
    }

    /**
     * Inward offset that handles "edge events": an offset edge that would flip is collapsed to a point
     * (as in a straight skeleton) and the offset is recomputed. Returns one inner vertex per ring vertex
     * (collapsed edges give coincident inner vertices), or null when the result is invalid.
     */
    static List<P> offsetInwardCollapsing(List<P> p, double d, double maxMiter) {
        int n = p.size();
        if (n < 3) return null;
        P[] e = new P[n], nn = new P[n];
        double[] len = new double[n];
        for (int i = 0; i < n; i++) {
            P v = p.get((i + 1) % n).sub(p.get(i));
            len[i] = v.len(); e[i] = v.norm(); nn[i] = e[i].perpL();
        }
        boolean[] active = new boolean[n];
        java.util.Arrays.fill(active, true);
        P[] start = new P[n];
        for (int iter = 0; iter <= n; iter++) {
            List<Integer> act = new ArrayList<>();
            for (int i = 0; i < n; i++) if (active[i]) act.add(i);
            if (act.size() < 3) return null;
            int k = act.size();
            for (int a = 0; a < k; a++) {
                int i = act.get(a), h = act.get((a - 1 + k) % k);
                P pa = p.get(i).add(nn[h].mul(d)), pb = p.get(i).add(nn[i].mul(d));
                // line h: point p_h_start + n_h d, dir e_h; line i: point p_i + n_i d, dir e_i
                P ph = p.get(h).add(nn[h].mul(d));
                double den = e[h].cross(e[i]);
                P qi;
                if (Math.abs(den) < 1e-6) qi = pb;
                else { double s = pb.sub(ph).cross(e[i]) / den; qi = ph.add(e[h].mul(s)); }
                if (qi.dist(pa) > maxMiter * d * 3 && qi.dist(pb) > maxMiter * d * 3) return null;
                start[i] = qi;
            }
            int worst = -1;
            double worstLen = Double.MAX_VALUE;
            for (int a = 0; a < k; a++) {
                int i = act.get(a), j = act.get((a + 1) % k);
                P qe = start[j].sub(start[i]);
                if (qe.dot(e[i]) <= 0.3 && len[i] < worstLen) { worstLen = len[i]; worst = i; }
            }
            if (worst < 0) break;
            active[worst] = false;
            if (iter == n) return null;
        }
        List<P> q = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int j = i;
            while (!active[j]) j = (j + 1) % n;
            q.add(start[j]);
        }
        List<P> dd = new ArrayList<>();
        for (P x : q) if (dd.isEmpty() || dd.get(dd.size() - 1).dist(x) > 1e-6) dd.add(x);
        while (dd.size() > 1 && dd.get(0).dist(dd.get(dd.size() - 1)) < 1e-6) dd.remove(dd.size() - 1);
        if (dd.size() < 3 || signedArea(dd) <= 1 || !isSimple(dd)) return null;
        for (P x : dd) if (!pointInPoly(x, p)) return null;
        for (int i = 0; i < n; i++) if (q.get(i).dist(p.get(i)) > maxMiter * d) return null;
        return q;
    }

    static List<P> dedupRing(List<P> q) {
        List<P> dd = new ArrayList<>();
        for (P x : q) if (dd.isEmpty() || dd.get(dd.size() - 1).dist(x) > 1e-6) dd.add(x);
        while (dd.size() > 1 && dd.get(0).dist(dd.get(dd.size() - 1)) < 1e-6) dd.remove(dd.size() - 1);
        return dd;
    }

    static List<P> offsetOrSame(List<P> ring) {
        List<P> q = offsetInward(ring, 1.5, 3);
        return q == null ? ring : q;
    }

    /** Hard validity test for a four-node house footprint (ordered, CCW, simple, non-degenerate). */
    static boolean validQuad(P[] q, double minArea, double minEdge, double minAngleDeg) {
        if (q == null || q.length != 4) return false;
        for (int i = 0; i < 4; i++) if (q[i].dist(q[(i + 1) % 4]) < minEdge) return false;
        if (properCross(q[0], q[1], q[2], q[3], 1e-9) || properCross(q[1], q[2], q[3], q[0], 1e-9)) return false;
        if (signedArea(q) < minArea) return false;
        for (int i = 0; i < 4; i++) {
            P u = q[(i + 3) % 4].sub(q[i]).norm(), v = q[(i + 1) % 4].sub(q[i]).norm();
            double ang = Math.toDegrees(Math.acos(clamp(u.dot(v), -1, 1)));
            if (ang < minAngleDeg) return false;
        }
        return true;
    }

    /** Flattens an Area into closed rings (outer boundaries and holes, unclassified). */
    static List<List<P>> areaRings(Area a) {
        List<List<P>> rings = new ArrayList<>();
        PathIterator it = a.getPathIterator(null, 0.25);
        double[] c = new double[6];
        List<P> cur = null;
        while (!it.isDone()) {
            int t = it.currentSegment(c);
            if (t == PathIterator.SEG_MOVETO) { cur = new ArrayList<>(); cur.add(new P(c[0], c[1])); }
            else if (t == PathIterator.SEG_LINETO) { if (cur != null) cur.add(new P(c[0], c[1])); }
            else if (t == PathIterator.SEG_CLOSE) {
                if (cur != null && cur.size() >= 3) {
                    if (cur.get(0).dist(cur.get(cur.size() - 1)) < 1e-9) cur.remove(cur.size() - 1);
                    rings.add(cur);
                }
                cur = null;
            }
            it.next();
        }
        return rings;
    }

    static Path2D.Double path(List<P> r) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < r.size(); i++) {
            if (i == 0) p.moveTo(r.get(i).x(), r.get(i).y()); else p.lineTo(r.get(i).x(), r.get(i).y());
        }
        p.closePath();
        return p;
    }

    static Path2D.Double path(P[] r) { return path(List.of(r)); }

    static P[] shrink(P[] q, double by) {
        P c = centroid(q);
        P[] r = new P[q.length];
        for (int i = 0; i < q.length; i++) {
            P d = q[i].sub(c);
            double l = d.len();
            r[i] = l < by + 1e-6 ? c : c.add(d.mul((l - by) / l));
        }
        return r;
    }

    /** True if two polygons overlap by more than touching (party walls are allowed to touch). */
    static boolean polysOverlap(P[] a, P[] b, double tol) {
        P[] sa = shrink(a, tol), sb = shrink(b, tol);
        for (int i = 0; i < sa.length; i++)
            for (int j = 0; j < sb.length; j++)
                if (properCross(sa[i], sa[(i + 1) % sa.length], sb[j], sb[(j + 1) % sb.length], 1e-9)) return true;
        return pointInPoly(sa[0], sb) || pointInPoly(sb[0], sa);
    }

    static boolean segHitsPoly(P a, P b, P[] poly) {
        if (pointInPoly(a, poly) || pointInPoly(b, poly)) return true;
        for (int i = 0; i < poly.length; i++) if (properCross(a, b, poly[i], poly[(i + 1) % poly.length], 0)) return true;
        return false;
    }

    static P[] expandRect(P[] q, double by) {
        P c = centroid(q);
        P[] r = new P[q.length];
        for (int i = 0; i < q.length; i++) {
            P d = q[i].sub(c);
            double l = d.len();
            r[i] = c.add(d.mul((l + by) / Math.max(l, 1e-9)));
        }
        return r;
    }
}
