package com.emergentcity.city;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * CityBlock-first parcel generation (spec E): street faces -> buildable area (minus street bodies,
 * junction openings and plazas) -> perimeter ring of four-node house units whose front edge lies on
 * the street edge and whose rear edge faces a shared interior garden (the residual inner polygon).
 * Unchanged blocks are reused from a cache keyed by topology + quantised widths (incremental update).
 */
final class Parcels {
    private Parcels() {}

    static Shape streetShape(Edge e) {
        P a = e.a.p, b = e.b.p, n = b.sub(a).norm().perpL();
        double ha = e.wA / 2, hb = e.wB / 2;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(a.x() + n.x() * ha, a.y() + n.y() * ha);
        p.lineTo(b.x() + n.x() * hb, b.y() + n.y() * hb);
        p.lineTo(b.x() - n.x() * hb, b.y() - n.y() * hb);
        p.lineTo(a.x() - n.x() * ha, a.y() - n.y() * ha);
        p.closePath();
        return p;
    }

    static Shape junctionShape(Node n, double expansion) {
        double w = 0;
        for (Edge e : n.edges) w = Math.max(w, e.widthAt(n));
        double r = w / 2 * (n.deg() >= 3 ? expansion : 1.0);
        return new Ellipse2D.Double(n.p.x() - r, n.p.y() - r, 2 * r, 2 * r);
    }

    static void build(CityModel m, int phase) {
        Config c = m.cfg;
        List<Face> faces = m.g.faces();
        Map<Long, CityBlock> cache = new HashMap<>();
        List<CityBlock> blocks = new ArrayList<>();
        int reused = 0, built = 0;
        for (Face f : faces) {
            if (f.area() < c.d("block.minArea") || f.area() > c.d("block.maxHousingFace")) continue;
            CityBlock frozen = findLocked(m, f);
            if (frozen != null) { frozen.face = f; cache.put(frozen.key, frozen); blocks.add(frozen); reused++; continue; }
            long key = blockKey(m, f);
            CityBlock b = m.blockCache.get(key);
            if (b != null) { reused++; b.face = f; }
            else { b = buildBlock(m, f, key); built++; }
            cache.put(key, b);
            blocks.add(b);
        }
        m.blockCache = cache;
        m.blocks = blocks;
        List<House> hs = new ArrayList<>();
        for (CityBlock b : blocks) hs.addAll(b.houses);
        for (int i = 0; i < hs.size(); i++) hs.get(i).id = i;
        m.houses = hs;
        m.log.count("blocks:reused", reused);
        m.log.count("blocks:rebuilt", built);
    }

    /**
     * A block already being built keeps its parcels even if its face changes topology (e.g. a new street
     * joins one of its edges, adding a node). Identified by its anchor point and roughly equal area.
     */
    static CityBlock findLocked(CityModel m, Face f) {
        if (m.lockedBlocks.isEmpty()) return null;
        List<P> poly = f.poly();
        for (CityBlock b : m.lockedBlocks)
            if (Math.abs(f.area() - b.lockArea) < 0.2 * b.lockArea && Geom.pointInPoly(b.lockAnchor, poly)) return b;
        return null;
    }

    static long blockKey(CityModel m, Face f) {
        long h = f.key();
        LinkedHashSet<Edge> es = new LinkedHashSet<>(f.edges());
        for (Edge e : es) { h = h * 31 + Math.round(e.wA * 2); h = h * 31 + Math.round(e.wB * 2); }
        List<P> poly = f.poly();
        Rectangle2D bb = Geom.path(poly).getBounds2D();
        for (Plaza p : m.plazas) {
            if (bb.intersects(Geom.path(p.poly).getBounds2D())) { h = h * 131 + p.node.id; h = h * 131 + Math.round(p.radius * 2); }
        }
        for (Landmark l : m.landmarks) if (bb.intersects(Geom.path(l.fp).getBounds2D())) h = h * 137 + l.id + 1;
        return h;
    }

    static CityBlock buildBlock(CityModel m, Face f, long key) {
        Config c = m.cfg;
        CityBlock b = new CityBlock();
        b.key = key; b.face = f; b.facePoly = f.poly(); b.faceArea = f.area();
        // water inside the face?
        Rectangle2D bb = Geom.path(b.facePoly).getBounds2D();
        int tot = 0, wet = 0;
        for (double x = bb.getMinX(); x <= bb.getMaxX(); x += 8) for (double y = bb.getMinY(); y <= bb.getMaxY(); y += 8) {
            P q = new P(x, y);
            if (!Geom.pointInPoly(q, b.facePoly)) continue;
            tot++;
            if (m.t.isWater(q)) wet++;
        }
        if (tot > 0 && wet > 0.3 * tot) { b.status = "waterside green"; return b; }
        Area A = new Area(Geom.path(b.facePoly));
        double exp = c.d("street.junctionExpansion");
        for (Edge e : new LinkedHashSet<>(f.edges())) A.subtract(new Area(streetShape(e)));
        for (Node n : new LinkedHashSet<>(f.cycle())) A.subtract(new Area(junctionShape(n, exp)));
        for (Plaza p : m.plazas) if (bb.intersects(Geom.path(p.poly).getBounds2D())) A.subtract(p.area());
        b.buildable = A;
        List<List<P>> rings = Geom.areaRings(A);
        List<List<P>> outers = new ArrayList<>();
        for (List<P> r : rings) {
            double ar = Geom.signedArea(r);
            if (Math.abs(ar) < c.d("block.minArea") * 0.5) continue;
            List<P> rr = new ArrayList<>(r);
            if (ar < 0) java.util.Collections.reverse(rr);
            boolean hole = false;
            for (List<P> o : rings) if (o != r && Math.abs(Geom.signedArea(o)) > Math.abs(ar) && Geom.pointInPoly(rr.get(0), o)) { hole = true; break; }
            if (!hole) outers.add(rr);
        }
        SplittableRandom rnd = new SplittableRandom(m.seed ^ key);
        int ringIdx = 0;
        for (List<P> o : outers) {
            b.buildArea += Geom.signedArea(o);
            List<P> s = Geom.simplifyRing(o, c.d("block.simplifyTolerance"), c.d("block.minEdge"));
            if (s.size() < 3 || !Geom.isSimple(s) || Geom.signedArea(s) < c.d("block.minArea") * 0.5) { m.log.count("ring:unusable"); continue; }
            subdivide(m, b, s, rnd, ringIdx);
            if (c.b("block.infill")) infill(m, b, s, rnd, ringIdx);
            ringIdx++;
        }
        if (c.b("block.infill")) orderAlongRings(b);
        b.status = b.houses.isEmpty() ? "open space" : "perimeter block";
        return b;
    }

    /**
     * Lays out one buildable ring: square-ish corner houses and rectangular plots along the streets around a shared
     * garden. Where that geometry fails, the ring is left to the infill pass, which closes the street wall.
     */
    static void subdivide(CityModel m, CityBlock b, List<P> ring, SplittableRandom r, int ringIdx) {
        if (!subdivideCorners(m, b, ring, r, ringIdx)) m.log.count("ring:left-to-infill");
    }

    // ------------------------------------------------------------------ corner-aware subdivision

    private static P lineInter(P a, P da, P b, P db) {
        double den = da.cross(db);
        if (Math.abs(den) < 1e-9) return null;
        double t = b.sub(a).cross(db) / den;
        return a.add(da.mul(t));
    }

    /** One long street edge of the ring: units are built between its front line and a rear line d inside it. */
    private static final class Side {
        int ringIdx;
        P s, t, e, n;      // start, end, unit direction, inward normal
        P fs, fe, rs, re;  // unit strip: front start/end, rear start/end
        double len;
    }

    /**
     * Subdivides one buildable ring so houses are as square as possible:
     * - the ring is split into long street edges (at least ~0.55 x plot depth) and corner chains of short
     *   edges between them (typically the arc left by a junction opening);
     * - along each long edge, units are rectangles between the street line and a rear line at depth d,
     *   with frontage close to d (block.frontageToDepth);
     * - at each convex corner, the region between the two edges' strips and the inner corner point Q is
     *   split into one or two corner houses fronting both streets (a near-square "kite" for a right angle);
     * - corners sharper than block.minCornerAngleDeg stay open as a small paved square;
     * - nearly straight corners are joined across; concave corners leave a small garden nook.
     * Returns false (caller falls back) when the geometry does not allow a valid ring at any depth.
     */
    static boolean subdivideCorners(CityModel m, CityBlock b, List<P> ringIn, SplittableRandom r, int ringIdx) {
        Config c = m.cfg;
        double dmin = c.d("block.houseDepthMin"), dmax = c.d("block.houseDepthMax");
        double fmin = c.d("block.houseFrontageMin"), fmax = c.d("block.houseFrontageMax"), ft0 = c.d("block.houseFrontageTarget");
        List<P> ring = Geom.simplifyRing(ringIn, 1.2, 2.0);
        if (ring.size() < 3 || !Geom.isSimple(ring) || Geom.signedArea(ring) < 150) return false;
        P cen = Geom.centroid(ring);
        double R = Math.max(200, m.radius);
        double t = Geom.clamp(m.nearestCenterDist(cen) / (R * 0.9), 0, 1);
        double dTarget = (dmax - (dmax - dmin) * t) * (0.94 + 0.12 * r.nextDouble());
        dTarget = Math.min(dTarget, Math.max(dmin, 0.3 * Math.sqrt(Geom.signedArea(ring))));
        for (double d = Math.max(dmin, Math.round(dTarget)); d >= dmin - 1e-9; d -= 1.0) {
            double ftRatio = c.d("block.frontageToDepth");
            double ft = ftRatio > 0 ? Geom.clamp(d * ftRatio, fmin, fmax) : Geom.clamp(ft0 * (0.85 + 0.3 * t), fmin, fmax);
            if (tryCorners(m, b, ring, r, ringIdx, d, ft)) return true;
        }
        m.log.count("ring:corner-subdivision-failed");
        return false;
    }

    private static boolean fail(CityModel m, String why) { m.log.count("cornerfail:" + why); return false; }

    /** A corner between two long sides; Q is the inner corner point shared by both sides' rear lines. */
    private static final class Corner {
        int type; // 0 straight-ish (bisector), 1 concave, 2 house corner, 3 open tip
        List<P> chain;
        P q, v;
        double tIn, tOut; // foot parameters on the incoming side (from its start) and outgoing side
    }

    private static boolean tryCorners(CityModel m, CityBlock b, List<P> ring, SplittableRandom r, int ringIdx, double d, double ft) {
        Config c = m.cfg;
        double fmin = c.d("block.houseFrontageMin"), fmax = c.d("block.houseFrontageMax");
        double minA = c.d("house.minArea"), minE = c.d("house.minEdge"), minAng = c.d("house.minAngleDeg");
        double thetaMin = Math.toRadians(c.d("block.minCornerAngleDeg"));
        int n = ring.size();
        double lmin = Math.max(4.0, 0.55 * d);
        List<Side> sides = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            P a = ring.get(i), bb = ring.get((i + 1) % n);
            double l = a.dist(bb);
            if (l < lmin) continue;
            Side s = new Side();
            s.ringIdx = i; s.s = a; s.t = bb; s.len = l; s.e = bb.sub(a).norm(); s.n = s.e.perpL();
            sides.add(s);
        }
        int k = sides.size();
        if (k < 3) return fail(m, "few-long-edges");
        // pass 1: classify each corner (corner j lies between side j and side j+1)
        Corner[] cs = new Corner[k];
        for (int j = 0; j < k; j++) {
            Side a = sides.get(j), bn = sides.get((j + 1) % k);
            Corner co = new Corner();
            cs[j] = co;
            co.chain = new ArrayList<>();
            for (int v = (a.ringIdx + 1) % n; ; v = (v + 1) % n) {
                co.chain.add(ring.get(v));
                if (v == bn.ringIdx) break;
                if (co.chain.size() > n) return fail(m, "chain");
            }
            double turn = Geom.angDiff(bn.e.angle(), a.e.angle()); // > 0: left turn = convex corner
            P q = lineInter(a.t.add(a.n.mul(d)), a.e, bn.s.add(bn.n.mul(d)), bn.e);
            if (Math.abs(turn) < Math.toRadians(30)) {
                P v = co.chain.size() == 1 ? co.chain.get(0) : lineInter(a.s, a.e, bn.t, bn.e);
                if (v == null || q == null) { v = a.t.lerp(bn.s, 0.5); q = v.add(a.n.add(bn.n).norm().mul(d)); }
                double dev = 0;
                for (P p : co.chain) dev = Math.max(dev, Math.min(Geom.distPointSeg(p, a.s, v), Geom.distPointSeg(p, v, bn.t)));
                if (dev > 1.6) return fail(m, "blunt-deviation");
                co.type = 0; co.v = v; co.q = q;
                co.tIn = v.sub(a.s).dot(a.e); co.tOut = v.sub(bn.s).dot(bn.e);
            } else if (turn < 0) {
                co.type = 1; co.tIn = a.len; co.tOut = 0;
            } else {
                if (q == null) return fail(m, "parallel");
                co.q = q;
                co.type = Math.PI - turn < thetaMin ? 3 : 2;
                co.tIn = Math.min(a.len, q.sub(a.n.mul(d)).sub(a.s).dot(a.e));
                co.tOut = Math.max(0, q.sub(bn.n.mul(d)).sub(bn.s).dot(bn.e));
            }
        }
        // pass 2: per side, resolve competing corners. Side j runs from corner j-1 (start) to corner j (end).
        for (int it = 0; it < 3; it++)
            for (int j = 0; j < k; j++) {
                Side s = sides.get(j);
                Corner c0 = cs[(j - 1 + k) % k], c1 = cs[j];
                double t0 = c0.tOut, t1 = c1.tIn;
                boolean both = c0.type >= 2 && c1.type >= 2;
                if (both && t1 - t0 < Math.max(2.5, 0.6 * fmin)) {
                    double tm = Geom.clamp((t0 + t1) / 2, 0.5, s.len - 0.5);
                    c0.tOut = tm; c1.tIn = tm;
                    // inner corner points crossed or nearly met: merge them so the two corner houses share a side
                    if (c0.q != null && c1.q != null && c1.q.sub(c0.q).dot(s.e) < 2.0) {
                        P qm = c0.q.lerp(c1.q, 0.5);
                        c0.q = qm; c1.q = qm;
                    }
                } else if (t1 - t0 < Math.max(2.5, 0.6 * fmin)) {
                    // one corner house meets a straight or concave end: the corner house takes the whole side
                    if (c1.type >= 2 && c0.type < 2) c1.tIn = Math.max(t0, 0.5);
                    else if (c0.type >= 2 && c1.type < 2) c0.tOut = Math.min(t1, s.len - 0.5);
                    else if (t1 - t0 < -0.3) return fail(m, "corner-swallows-edge");
                }
            }
        // set strips from the resolved corners
        for (int j = 0; j < k; j++) {
            Side s = sides.get(j);
            Corner c0 = cs[(j - 1 + k) % k], c1 = cs[j];
            s.fs = s.s.add(s.e.mul(c0.tOut));
            s.fe = s.s.add(s.e.mul(c1.tIn));
            s.rs = c0.type == 1 ? s.s.add(s.n.mul(d)) : c0.q;
            s.re = c1.type == 1 ? s.t.add(s.n.mul(d)) : c1.q;
        }
        // garden ring = the inner points in order, plus nooks behind concave corners
        List<P> garden = new ArrayList<>();
        List<P[]> corners = new ArrayList<>();
        List<List<P>> tips = new ArrayList<>(), nooks = new ArrayList<>();
        for (int j = 0; j < k; j++) {
            Side a = sides.get(j), bn = sides.get((j + 1) % k);
            Corner co = cs[j];
            if (co.type == 1) {
                garden.add(a.re); garden.add(bn.rs);
                List<P> nook = new ArrayList<>(co.chain);
                nook.add(bn.rs); nook.add(a.re);
                nooks.add(nook);
                continue;
            }
            garden.add(co.q);
            if (co.type == 0) continue;
            List<P> front = new ArrayList<>();
            front.add(a.fe);
            if (a.fe.dist(a.t) > 0.3) front.add(a.t);
            for (int ci = 1; ci < co.chain.size() - 1; ci++) front.add(co.chain.get(ci));
            if (co.chain.size() > 1 && bn.s.dist(front.get(front.size() - 1)) > 0.3) front.add(bn.s);
            if (bn.fs.dist(front.get(front.size() - 1)) > 0.3) front.add(bn.fs);
            if (co.type == 3) {
                List<P> tip = new ArrayList<>(front);
                tip.add(co.q);
                tips.add(tip);
                m.log.count("corner:open-tip");
                continue;
            }
            for (int it = 0; it < 4 && front.size() > 3; it++) {
                int best = -1;
                double bd = 0.6;
                for (int fi = 1; fi < front.size() - 1; fi++) {
                    double dv = Geom.distPointSeg(front.get(fi), front.get(fi - 1), front.get(fi + 1));
                    if (dv < bd) { bd = dv; best = fi; }
                }
                if (best < 0) break;
                front.remove(best);
            }
            if (front.size() == 2) front.add(1, front.get(0).lerp(front.get(1), 0.5));
            if ((front.size() - 1) % 2 == 1) {
                int li = 0;
                double ll = -1;
                for (int fi = 0; fi + 1 < front.size(); fi++) { double l = front.get(fi).dist(front.get(fi + 1)); if (l > ll) { ll = l; li = fi; } }
                front.add(li + 1, front.get(li).lerp(front.get(li + 1), 0.5));
            }
            for (int fi = 0; fi + 2 < front.size(); fi += 2) corners.add(new P[]{front.get(fi), front.get(fi + 1), front.get(fi + 2), co.q});
            m.log.count("corner:house");
        }
        List<P> gq = Geom.dedupRing(garden);
        boolean gardenOk = gq.size() >= 3 && Geom.isSimple(gq) && Geom.signedArea(gq) >= 20;
        if (gardenOk) for (P p : gq) if (!Geom.pointInPoly(p, ring)) { gardenOk = false; break; }
        // a block too small for a garden is still fine if the houses meet in the middle
        if (!gardenOk && gq.size() >= 3 && Geom.signedArea(gq) > 2) return fail(m, "garden-invalid");
        for (Side s : sides)
            if (s.fe.sub(s.fs).dot(s.e) < -0.3 || s.re.sub(s.rs).dot(s.e) < -0.6) return fail(m, "strip-inverted");
        // build units
        List<House> units = new ArrayList<>();
        int seq = 0;
        for (P[] cq : corners) {
            if (!Geom.validQuad(cq, minA, minE, minAng)) { m.log.count("unit:rejected-corner"); continue; }
            House h = new House();
            h.q = cq; h.block = b; h.ring = ringIdx; h.seq = seq++; h.corner = true; h.front2 = true;
            h.frontage = cq[0].dist(cq[1]); h.depth = d; h.c = Geom.centroid(cq);
            units.add(h);
        }
        boolean useUnits = c.b("mc.units") && !Units.all().isEmpty();
        for (Side s : sides) {
            double lf = s.fe.sub(s.fs).dot(s.e), lr = s.re.sub(s.rs).dot(s.e);
            if (lf < 2.5 || lr < 2.0) { if (lf > 0.5) m.log.count("unit:short-strip"); continue; }
            if (useUnits) {
                List<House> run = unitRun(m, b, s, d, ft, r, ringIdx, seq);
                if (run != null) { for (House h : run) { h.seq = seq++; units.add(h); } continue; }
            }
            int cnt = Math.max(1, (int) Math.round(lf / ft));
            while (cnt > 1 && lf / cnt < fmin) cnt--;
            while (lf / cnt > fmax) cnt++;
            while (cnt > 1 && lr / cnt < 2.8) cnt--;
            double[] w = new double[cnt];
            double sum = 0;
            for (int i = 0; i < cnt; i++) { w[i] = 1 + 0.2 * (r.nextDouble() - 0.5); sum += w[i]; }
            double acc = 0;
            for (int i = 0; i < cnt; i++) {
                double t0 = acc / sum, t1 = (acc + w[i]) / sum;
                acc += w[i];
                P[] q = {s.fs.lerp(s.fe, t0), s.fs.lerp(s.fe, t1), s.rs.lerp(s.re, t1), s.rs.lerp(s.re, t0)};
                if (!Geom.validQuad(q, minA, minE, minAng)) { m.log.count("unit:rejected-invalid-quad"); continue; }
                House h = new House();
                h.q = q; h.block = b; h.ring = ringIdx; h.edgeIdx = s.ringIdx; h.seq = seq++;
                h.frontage = q[0].dist(q[1]); h.depth = d; h.c = Geom.centroid(q);
                units.add(h);
            }
        }
        if (units.isEmpty()) return fail(m, "no-units");
        b.rings.add(ring);
        if (gardenOk) { b.gardens.add(gq); b.gardenArea += Geom.signedArea(gq); }
        b.gardens.addAll(nooks);
        b.tips.addAll(tips);
        b.frontage += Geom.perimeter(ring);
        if (Geom.perimeter(ring) >= c.d("block.passageMinPerimeter")) {
            Side longest = sides.get(0);
            for (Side s : sides) if (s.fe.sub(s.fs).dot(s.e) > longest.fe.sub(longest.fs).dot(longest.e)) longest = s;
            List<House> onEdge = new ArrayList<>();
            for (House h : units) if (!h.corner && h.edgeIdx == longest.ringIdx) onEdge.add(h);
            if (onEdge.size() >= 3) onEdge.get(onEdge.size() / 2).passage = true;
        }
        double cont = c.d("block.continuity");
        for (House h : units) if (!h.corner && !h.passage && r.nextDouble() > cont) h.yard = true;
        finalizeUnits(m, b, units);
        return true;
    }

    // ------------------------------------------------------------------ unit plots

    /**
     * Plots sized to units: the street side is filled with unit designs chained party wall to party wall, each unit's
     * origin on the previous unit's "next" point. Each step picks, among designs for an angle close to the street's
     * and the plot depth, the one whose front keeps the row on the street line and whose width brings the row out
     * evenly at the far corner. What is left at the end is closed by the corner house or a narrow infill house.
     * Returns null when no design fits this side (it is then subdivided into plain plots).
     */
    private static List<House> unitRun(CityModel m, CityBlock b, Side s, double d, double ft, SplittableRandom r, int ringIdx, int seq) {
        double dirDeg = Math.toDegrees(Math.atan2(s.e.y(), s.e.x()));
        double maxErr = m.cfg.d("units.maxAngleError");
        List<Units.Unit> cand = new ArrayList<>();
        List<Integer> turns = new ArrayList<>();
        double bestDepth = -1;
        for (Units.Unit u : Units.all()) {
            int[] tq = new int[1];
            double err = Units.angleFit(u, dirDeg, tq);
            if (Math.abs(err) > maxErr || u.depth > d + 0.6 || u.depth < d - 3.5) continue;
            cand.add(u); turns.add(tq[0]);
            bestDepth = Math.max(bestDepth, u.depth);
        }
        if (cand.isEmpty()) { m.log.count("units:no-design"); return null; }
        // one depth for the whole row, so the rear line stays even
        for (int i = cand.size() - 1; i >= 0; i--) if (Math.abs(cand.get(i).depth - bestDepth) > 0.01) { cand.remove(i); turns.remove(i); }
        P n = s.n, e = s.e;
        double lf = s.fe.sub(s.fs).dot(e);
        double off0 = 1.0; // the front wall band reaches ~0.6 in front of the unit's front line
        P start = s.fs.add(n.mul(off0)).add(e.mul(0.2));
        int ai = (int) Math.round(start.x()), aj = (int) Math.round(start.y());
        List<House> out = new ArrayList<>();
        double minAdv = 1e9;
        for (int q = 0; q < cand.size(); q++) { int[] pv = Units.rot(cand.get(q).px, cand.get(q).pz, turns.get(q)); minAdv = Math.min(minAdv, pv[0] * e.x() + pv[1] * e.y()); }
        for (int guard = 0; guard < 64; guard++) {
            double done = new P(ai, aj).sub(s.fs).dot(e);
            double remaining = lf - done;
            if (remaining < minAdv - 0.4) break;
            int nLeft = Math.max(1, (int) Math.round(remaining / ft));
            double want = remaining / nLeft;
            List<double[]> ranked = new ArrayList<>(); // {score, candidate}
            for (int q = 0; q < cand.size(); q++) {
                Units.Unit u = cand.get(q);
                int[] pv = Units.rot(u.px, u.pz, turns.get(q));
                double adv = pv[0] * e.x() + pv[1] * e.y();
                if (adv > remaining + 0.6 || adv < 2.5) continue;
                P next = new P(ai + pv[0], aj + pv[1]);
                double perp = next.sub(s.fs).dot(n);
                double score = Math.abs(adv - want) + 2.5 * Math.abs(perp - off0) + 0.05 * Math.abs(Units.angleFit(u, dirDeg, null)) + 0.25 * r.nextDouble();
                ranked.add(new double[]{score, q});
            }
            if (ranked.isEmpty()) break;
            ranked.sort((x, y) -> Double.compare(x[0], y[0]));
            House h = null;
            Units.Unit u = null;
            int tq = 0;
            // the best design that fits on the chained origin (sharing the party wall); failing that, one cell further in
            int[] in1 = {(int) Math.round(n.x()), (int) Math.round(n.y())};
            // (only the first unit of a row may move in: further on, a unit that does not fit on the chain ends the row and
            // the rest of the side is closed by infill, which shares the unit's wall)
            for (int pass = 0; pass < (out.isEmpty() ? 2 : 1) && h == null; pass++)
                for (double[] rk : ranked) {
                    u = cand.get((int) rk[1]); tq = turns.get((int) rk[1]);
                    h = unitHouse(m, b, u, tq, ai + pass * in1[0], aj + pass * in1[1], ringIdx, s);
                    if (h != null) { if (pass > 0) m.log.count("units:chain-shifted"); break; }
                }
            if (h == null) break;
            out.add(h);
            int[] pv = Units.rot(u.px, u.pz, tq);
            ai = h.ai + pv[0]; aj = h.aj + pv[1];
        }
        if (out.isEmpty()) return null;
        m.log.count("units:placed", out.size());
        return out;
    }

    private static House unitHouse(CityModel m, CityBlock b, Units.Unit u, int tq, int ai, int aj, int ringIdx, Side s) {
        for (int[] c : u.cells) {
            int[] rr = Units.rot(c[0], c[1], tq);
            double x = ai + rr[0], y = aj + rr[1];
            if (!b.buildable.contains(x, y) || m.t.isWater(new P(x, y))) return null;
        }
        int[] pv = Units.rot(u.px, u.pz, tq);
        double[] rv = rotD(u.rx, u.rz, tq);
        P a = new P(ai, aj), p1 = a.add(new P(pv[0], pv[1])), rear = new P(rv[0], rv[1]);
        P[] q = {a, p1, p1.add(rear), a.add(rear)};
        if (m.keepOutIn(q)) return null;
        House h = new House();
        h.q = q; h.block = b; h.ring = ringIdx; h.edgeIdx = s.ringIdx;
        h.frontage = a.dist(p1); h.depth = u.depth; h.c = Geom.centroid(q);
        h.unitKey = u.key; h.unitTurn = tq; h.ai = ai; h.aj = aj;
        return h;
    }

    static double[] rotD(double x, double z, int q) {
        return switch (Math.floorMod(q, 4)) {
            case 1 -> new double[]{-z, x};
            case 2 -> new double[]{-x, -z};
            case 3 -> new double[]{z, -x};
            default -> new double[]{x, z};
        };
    }

    // ------------------------------------------------------------------ closing the street wall

    private static final double STEP = 0.5;

    /** Which sample points along the ring (just inside it) already have a building behind them. */
    private static boolean[][] coverage(CityBlock b, List<P> ring, List<House> extra) {
        int n = ring.size();
        boolean[][] cov = new boolean[n][];
        List<P[]> polys = new ArrayList<>();
        for (House h : b.houses) polys.add(h.q);
        for (House h : b.openUnits) polys.add(h.q);
        for (House h : extra) polys.add(h.q);
        for (int i = 0; i < n; i++) {
            P a = ring.get(i), bb = ring.get((i + 1) % n);
            double len = a.dist(bb);
            int ns = Math.max(1, (int) Math.floor(len / STEP));
            cov[i] = new boolean[ns];
            if (len < 1e-6) continue;
            P e = bb.sub(a).mul(1 / len), in = e.perpL();
            for (int k = 0; k < ns; k++) {
                P q = a.add(e.mul((k + 0.5) * len / ns)).add(in.mul(0.9));
                for (P[] pq : polys) if (Geom.pointInPoly(q, java.util.Arrays.asList(pq))) { cov[i][k] = true; break; }
            }
        }
        return cov;
    }

    /**
     * After subdivision, any stretch of the block outline still without a building gets infill: a corner building
     * where two open stretches meet at a convex corner (a near-square kite, or a narrow "flatiron" across a sharp
     * corner), then rectangular units along the remaining open stretches, as deep as the space allows. These
     * fill wedges, collapsed corners and rejected plots so the street wall is continuous. Infill and corner
     * buildings need no courtyard access; most are used as shops.
     */
    static void infill(CityModel m, CityBlock b, List<P> ring, SplittableRandom r, int ringIdx) {
        Config c = m.cfg;
        int n = ring.size();
        if (n < 3) return;
        double dmin = c.d("block.houseDepthMin"), dmax = c.d("block.houseDepthMax");
        double fmin = c.d("block.houseFrontageMin"), fmax = c.d("block.houseFrontageMax");
        double minA = Math.min(c.d("house.minArea"), 16), minE = Math.min(c.d("house.minEdge"), 2.5);
        double minAng = Math.min(c.d("house.minAngleDeg"), 50);
        double D0 = Geom.clamp(0.5 * (dmin + dmax), 4, 9);
        List<House> added = new ArrayList<>();
        int seq = 10_000 + ringIdx * 1000;
        for (House h : b.houses) seq = Math.max(seq, h.seq + 1);
        // 1. corners: both edges open right at a convex vertex
        boolean[][] cov = coverage(b, ring, added);
        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            P v = ring.get(i), a = ring.get(prev), bb = ring.get((i + 1) % n);
            double la = a.dist(v), lb = v.dist(bb);
            if (la < 2.5 || lb < 2.5) continue;
            boolean[] ca = cov[prev], cb = cov[i];
            // open for at least 2.5 m on both sides of the vertex
            int needA = (int) Math.ceil(2.5 / (la / ca.length)), needB = (int) Math.ceil(2.5 / (lb / cb.length));
            boolean open = true;
            for (int k = Math.max(0, ca.length - needA); k < ca.length; k++) if (ca[k]) open = false;
            for (int k = 0; k < Math.min(cb.length, needB); k++) if (cb[k]) open = false;
            if (!open) continue;
            P e1 = v.sub(a).norm(), e2 = bb.sub(v).norm();
            double turn = Geom.angDiff(e2.angle(), e1.angle());
            if (turn < Math.toRadians(25)) continue; // concave or nearly straight: edge infill handles it
            double theta = Math.PI - turn;             // interior angle at the corner
            if (theta < Math.toRadians(18)) continue;
            // free run on each side from the vertex
            int fa = 0; for (int k = ca.length - 1; k >= 0 && !ca[k]; k--) fa++;
            int fb = 0; for (int k = 0; k < cb.length && !cb[k]; k++) fb++;
            double runA = fa * la / ca.length, runB = fb * lb / cb.length;
            House h = null;
            for (double D = D0; D >= 4.0 && h == null; D -= 1.0) { // at least 4 deep: room to walk inside
                P[] q;
                if (theta >= Math.toRadians(68)) {
                    // kite: fronts on both streets, inner point where the two rear lines meet
                    double L = D / Math.tan(theta / 2) + Math.min(D, 3.0);
                    L = Math.min(L, Math.min(runA, runB));
                    if (L < 2.5) break;
                    P pa = v.sub(e1.mul(L)), pb = v.add(e2.mul(L));
                    P inner = lineInter(pa.add(e1.perpL().mul(D)), e1, pb.add(e2.perpL().mul(D)), e2);
                    if (inner == null) continue;
                    q = new P[]{pa, v, pb, inner};
                } else {
                    // flatiron: the sharp tip is cut back to a short front (left as a little paved nose)
                    double sh = Math.sin(theta / 2);
                    double t = Math.max(1.0, 1.1 / sh);
                    double L = Math.min(Math.min(t + 1.6 * D, 7 / sh), Math.min(runA, runB)); // back no wider than ~14
                    if (L < t + 2.5) break;
                    q = new P[]{v.sub(e1.mul(L)), v.sub(e1.mul(t)), v.add(e2.mul(t)), v.add(e2.mul(L))};
                }
                if (!Geom.validQuad(q, minA, minE, minAng)) continue;
                if (!fits(m, b, q, added)) continue;
                h = new House();
                h.q = q; h.block = b; h.ring = ringIdx; h.edgeIdx = prev; h.seq = seq++; h.corner = true; h.front2 = true; h.infill = true;
                h.frontage = q[0].dist(q[1]); h.depth = D; h.c = Geom.centroid(q);
            }
            if (h != null) { added.add(h); cov = coverage(b, ring, added); m.log.count("infill:corner"); }
        }
        // 2. edges: rectangles along every remaining open stretch
        cov = coverage(b, ring, added);
        for (int i = 0; i < n; i++) {
            P a = ring.get(i), bb = ring.get((i + 1) % n);
            double len = a.dist(bb);
            if (len < 2.5) continue;
            boolean[] ci = cov[i];
            double ds = len / ci.length;
            P e = bb.sub(a).mul(1 / len), in = e.perpL();
            int k = 0;
            while (k < ci.length) {
                if (ci[k]) { k++; continue; }
                int k1 = k;
                while (k1 < ci.length && !ci[k1]) k1++;
                double t0 = k * ds, t1 = k1 * ds;
                // reach right up to the neighbours (sample points sit at cell centres)
                if (k > 0) t0 = Math.max(0, t0 - 0.25 * ds);
                if (k1 < ci.length) t1 = Math.min(len, t1 + 0.25 * ds);
                k = k1;
                double run = t1 - t0;
                if (run < 2.5) { if (run > 0.9) m.log.count("infill:sliver-left"); continue; }
                int cnt = Math.max(1, (int) Math.round(run / (0.5 * (fmin + fmax))));
                while (run / cnt > fmax) cnt++;
                while (cnt > 1 && run / cnt < Math.max(3.0, 0.75 * fmin)) cnt--;
                for (int u = 0; u < cnt; u++) {
                    double s0 = t0 + run * u / cnt, s1 = t0 + run * (u + 1) / cnt;
                    House h = null;
                    // trim the ends a little if a neighbour's back corner is in the way, then reduce depth
                    outer:
                    for (double trim = 0; trim <= 1.5; trim += 0.75)
                        for (double D = D0; D >= 4.0; D -= 1.0) {
                            double a0 = s0 + (u == 0 ? trim : 0), a1 = s1 - (u == cnt - 1 ? trim : 0);
                            if (a1 - a0 < 2.5) break outer;
                            P f0 = a.add(e.mul(a0)), f1 = a.add(e.mul(a1));
                            P[] q = {f0, f1, f1.add(in.mul(D)), f0.add(in.mul(D))};
                            if (!Geom.validQuad(q, minA, minE, minAng) || !fits(m, b, q, added)) continue;
                            h = new House();
                            h.q = q; h.block = b; h.ring = ringIdx; h.edgeIdx = i; h.seq = seq++; h.infill = true;
                            h.frontage = a1 - a0; h.depth = D; h.c = Geom.centroid(q);
                            break outer;
                        }
                    if (h != null) { added.add(h); m.log.count("infill:edge"); } else m.log.count("infill:no-fit");
                }
            }
        }
        if (added.isEmpty()) return;
        b.houses.addAll(added);
        // a ring the regular layout could not subdivide has no courtyard yet: the space behind the infill is one
        boolean hasGarden = false;
        for (List<P> g : b.gardens) if (g.size() >= 3 && Geom.pointInPoly(Geom.centroid(g), ring)) { hasGarden = true; break; }
        if (!hasGarden && Geom.signedArea(ring) > 200) {
            List<P> g = Geom.offsetInwardCollapsing(ring, 3.0, 3.5);
            if (g != null) g = Geom.dedupRing(g);
            if (g == null || g.size() < 3 || !Geom.isSimple(g) || Geom.signedArea(g) < 20) g = ring; // street margin kept by the voxelizer
            {
                b.gardens.add(g);
                b.gardenArea += Geom.signedArea(g);
                m.log.count("infill:courtyard-added");
            }
        }
        // a built corner replaces an open corner square
        b.tips.removeIf(tp -> {
            for (House h : added) if (Geom.polysOverlap(h.q, tp.toArray(new P[0]), 0.5)) return true;
            return false;
        });
        if (!b.rings.contains(ring)) { b.rings.add(ring); b.frontage += Geom.perimeter(ring); }
    }

    /** An infill quad must not overlap other buildings, must stay inside the buildable area, off water and existing structures. */
    private static boolean fits(CityModel m, CityBlock b, P[] q, List<House> added) {
        for (House o : b.houses) if (Geom.polysOverlap(q, o.q, 0.05)) return false;
        for (House o : b.openUnits) if (Geom.polysOverlap(q, o.q, 0.05)) return false;
        for (House o : added) if (Geom.polysOverlap(q, o.q, 0.05)) return false;
        for (P p : Geom.shrink(q, 0.3)) if (!b.buildable.contains(p.x(), p.y()) || m.t.isWater(p)) return false;
        P[] sq = Geom.shrink(q, 0.3);
        for (int i = 0; i < 4; i++) { P mid = sq[i].lerp(sq[(i + 1) % 4], 0.5); if (!b.buildable.contains(mid.x(), mid.y())) return false; }
        return !m.keepOutIn(q);
    }

    /** Keeps each block's houses in order around its outline, so "neighbours" in the list are neighbours on the street. */
    static void orderAlongRings(CityBlock b) {
        if (b.houses.size() < 2 || b.rings.isEmpty()) return;
        java.util.Map<House, double[]> keyOf = new java.util.IdentityHashMap<>();
        for (House h : b.houses) {
            P fm = h.q[0].lerp(h.q[1], 0.5);
            int best = 0; double bestD = 1e18, pos = 0;
            for (int ri = 0; ri < b.rings.size(); ri++) {
                List<P> rg = b.rings.get(ri);
                double acc = 0;
                for (int i = 0; i < rg.size(); i++) {
                    P a = rg.get(i), bb = rg.get((i + 1) % rg.size());
                    double l = a.dist(bb);
                    double dd = Geom.distPointSeg(fm, a, bb);
                    if (dd < bestD) { bestD = dd; best = ri; pos = acc + (l < 1e-9 ? 0 : Geom.clamp(fm.sub(a).dot(bb.sub(a)) / l, 0, l)); }
                    acc += l;
                }
            }
            keyOf.put(h, new double[]{best, pos});
        }
        b.houses.sort((x, y) -> {
            double[] kx = keyOf.get(x), ky = keyOf.get(y);
            return kx[0] != ky[0] ? Double.compare(kx[0], ky[0]) : Double.compare(kx[1], ky[1]);
        });
        for (House h : b.houses) h.ring = (int) keyOf.get(h)[0];
    }

    /** Unit plots run through cell centres and may graze a neighbour by a cell: the voxelizer settles that cell. */
    private static double tol(House a, House b) { return a.unitKey != null || b.unitKey != null ? 1.0 : 0.05; }

    private static void finalizeUnits(CityModel m, CityBlock b, List<House> units) {
        // Hard constraint: units never overlap (collapsed offset corners can twist a quad onto a neighbour).
        List<House> kept = new ArrayList<>();
        List<House> existing = new ArrayList<>(b.houses);
        existing.addAll(b.openUnits);
        for (House h : units) {
            boolean clash = false;
            for (House o : kept) if (Geom.polysOverlap(h.q, o.q, tol(h, o))) { clash = true; break; }
            if (!clash) for (House o : existing) if (Geom.polysOverlap(h.q, o.q, tol(h, o))) { clash = true; break; }
            if (clash) m.log.count("unit:rejected-overlap"); else kept.add(h);
        }
        units = kept;
        for (House h : units) {
            boolean ok = true;
            for (P p : Geom.shrink(h.q, 0.3)) if (!Geom.pointInPoly(p, b.facePoly) || m.t.isWater(p)) { ok = false; break; }
            if (!ok) { m.log.count("unit:rejected-outside-or-water"); continue; }
            if (m.keepOutIn(h.q)) { m.log.count("unit:on-existing-structure"); continue; }
            if (h.yard) b.openUnits.add(h); else b.houses.add(h); // passages are built as gateway houses (archway through the ground floor)
        }
    }

    static void removeLandmarkOverlaps(CityModel m) {
        if (m.landmarks.isEmpty()) return;
        int removed = 0;
        for (CityBlock b : m.blocks) {
            if (b.locked) continue; // already built: monuments are only sited in unbuilt blocks
            List<House> keep = new ArrayList<>();
            for (House h : b.houses) {
                boolean hit = false;
                for (Landmark l : m.landmarks) if (Geom.polysOverlap(h.q, Geom.expandRect(l.fp, 2.0), 0.05)) { hit = true; break; }
                if (hit) removed++; else keep.add(h);
            }
            if (keep.size() != b.houses.size()) { b.houses.clear(); b.houses.addAll(keep); }
            b.openUnits.removeIf(h -> {
                for (Landmark l : m.landmarks) if (Geom.polysOverlap(h.q, Geom.expandRect(l.fp, 2.0), 0.05)) return true;
                return false;
            });
        }
        if (removed > 0) {
            List<House> hs = new ArrayList<>();
            for (CityBlock b : m.blocks) hs.addAll(b.houses);
            for (int i = 0; i < hs.size(); i++) hs.get(i).id = i;
            m.houses = hs;
        }
    }
}
