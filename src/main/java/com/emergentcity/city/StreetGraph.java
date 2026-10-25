package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

final class Node {
    final int id;
    final P p;
    final List<Edge> edges = new ArrayList<>();
    final int born;
    int stamp;

    Node(int id, P p, int born) { this.id = id; this.p = p; this.born = born; }

    int deg() { return edges.size(); }

    Edge edgeTo(Node o) {
        for (Edge e : edges) if (e.other(this) == o) return e;
        return null;
    }

    double maxWidth() {
        double w = 0;
        for (Edge e : edges) w = Math.max(w, e.width);
        return w;
    }
}

/** Street segment. Topology lives in the graph; geometry is the straight centreline a-b plus a width profile. */
final class Edge {
    final int id;
    final Node a, b;
    final int born;
    boolean alive = true;
    /** 1 local, 2 connector, 3 primary, 4 civic approach. */
    int cls = 1, clsPrev = 1;
    double width = 5, wA = 5, wB = 5, flow, demand, meanFacadeH = -1;
    boolean seedRoad, axis, bridge, infill;
    /** A boulevard: laid dead straight from the main centre, with its own fixed width (street.boulevard.width). */
    boolean boulevard;
    /** Width this street was physically laid with (villager mode); -1 = not laid yet. */
    double laidWidth = -1;
    Edge chainA, chainB;
    int chainId = -1, stamp;
    // vista metrics (filled by Vista)
    double vistaMaxRatio, enclosure = -1, proportionR = -1;
    String vistaEndType = "";

    Edge(int id, Node a, Node b, int born) { this.id = id; this.a = a; this.b = b; this.born = born; }

    Node other(Node n) { return n == a ? b : a; }
    double len() { return a.p.dist(b.p); }
    P dir() { return b.p.sub(a.p).norm(); }
    P mid() { return a.p.lerp(b.p, 0.5); }
    double angleFrom(Node n) { P d = other(n).p.sub(n.p); return Math.atan2(d.y(), d.x()); }
    double widthAt(Node n) { return n == a ? wA : wB; }
    Edge chainAt(Node n) { return n == a ? chainA : chainB; }
}

record Face(List<Node> cycle, List<Edge> edges, double area, long key) {
    List<P> poly() {
        List<P> r = new ArrayList<>();
        for (Node n : cycle) r.add(n.p);
        return r;
    }
}

final class Chain {
    int id;
    final List<Edge> edges = new ArrayList<>();
    final List<Node> nodes = new ArrayList<>();

    double length() { double s = 0; for (Edge e : edges) s += e.len(); return s; }
}

/** Planar street graph with a uniform-grid spatial index. Nodes never move; edges are split, never bent. */
final class StreetGraph {
    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    static final double CELL = 40;
    private final HashMap<Long, List<Edge>> eGrid = new HashMap<>();
    private final HashMap<Long, List<Node>> nGrid = new HashMap<>();
    private int stampCounter = 1;

    static long key(int i, int j) { return (((long) i) << 32) ^ (j & 0xffffffffL); }
    static int cell(double v) { return (int) Math.floor(v / CELL); }

    Node addNode(P p, int born) {
        Node n = new Node(nodes.size(), p, born);
        nodes.add(n);
        nGrid.computeIfAbsent(key(cell(p.x()), cell(p.y())), k -> new ArrayList<>()).add(n);
        return n;
    }

    Edge addEdge(Node a, Node b, int born) {
        if (a == b) throw new IllegalStateException("self loop");
        Edge e = new Edge(edges.size(), a, b, born);
        edges.add(e);
        a.edges.add(e);
        b.edges.add(e);
        index(e, true);
        return e;
    }

    private void index(Edge e, boolean add) {
        int i0 = cell(Math.min(e.a.p.x(), e.b.p.x())), i1 = cell(Math.max(e.a.p.x(), e.b.p.x()));
        int j0 = cell(Math.min(e.a.p.y(), e.b.p.y())), j1 = cell(Math.max(e.a.p.y(), e.b.p.y()));
        for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
            if (add) eGrid.computeIfAbsent(key(i, j), k -> new ArrayList<>()).add(e);
            else { List<Edge> l = eGrid.get(key(i, j)); if (l != null) l.remove(e); }
        }
    }

    void removeEdge(Edge e) {
        e.alive = false;
        e.a.edges.remove(e);
        e.b.edges.remove(e);
        index(e, false);
    }

    Node splitEdge(Edge e, P at, int born) {
        Node m = addNode(at, born);
        Edge e1 = addEdge(e.a, m, e.born), e2 = addEdge(m, e.b, e.born);
        for (Edge x : new Edge[]{e1, e2}) {
            x.cls = e.cls; x.clsPrev = e.clsPrev; x.width = e.width; x.wA = e.width; x.wB = e.width;
            x.flow = e.flow; x.demand = e.demand; x.seedRoad = e.seedRoad; x.axis = e.axis; x.boulevard = e.boulevard; x.bridge = e.bridge;
            x.infill = e.infill; x.meanFacadeH = e.meanFacadeH; x.laidWidth = e.laidWidth;
        }
        removeEdge(e);
        return m;
    }

    List<Edge> edgesNear(double x0, double y0, double x1, double y1) {
        int st = ++stampCounter;
        List<Edge> r = new ArrayList<>();
        for (int i = cell(Math.min(x0, x1)); i <= cell(Math.max(x0, x1)); i++)
            for (int j = cell(Math.min(y0, y1)); j <= cell(Math.max(y0, y1)); j++) {
                List<Edge> l = eGrid.get(key(i, j));
                if (l == null) continue;
                for (Edge e : l) if (e.alive && e.stamp != st) { e.stamp = st; r.add(e); }
            }
        r.sort(Comparator.comparingInt(e -> e.id));
        return r;
    }

    List<Node> nodesNear(P p, double rad) {
        List<Node> r = new ArrayList<>();
        for (int i = cell(p.x() - rad); i <= cell(p.x() + rad); i++)
            for (int j = cell(p.y() - rad); j <= cell(p.y() + rad); j++) {
                List<Node> l = nGrid.get(key(i, j));
                if (l == null) continue;
                for (Node n : l) if (n.deg() > 0 && n.p.dist(p) <= rad) r.add(n);
            }
        r.sort(Comparator.comparingInt(n -> n.id));
        return r;
    }

    List<Node> aliveNodes() {
        List<Node> r = new ArrayList<>();
        for (Node n : nodes) if (n.deg() > 0) r.add(n);
        return r;
    }

    List<Edge> aliveEdges() {
        List<Edge> r = new ArrayList<>();
        for (Edge e : edges) if (e.alive) r.add(e);
        return r;
    }

    double totalLength() { double s = 0; for (Edge e : aliveEdges()) s += e.len(); return s; }

    /** Extracts bounded faces (blocks) by half-edge traversal; faces are CCW, the outer face is dropped. */
    List<Face> faces() {
        List<Edge> es = aliveEdges();
        HashMap<Integer, Edge[]> sorted = new HashMap<>();
        for (Node n : aliveNodes()) {
            Edge[] arr = n.edges.toArray(new Edge[0]);
            Arrays.sort(arr, Comparator.comparingDouble((Edge e) -> e.angleFrom(n)).thenComparingInt(e -> e.id));
            sorted.put(n.id, arr);
        }
        HashSet<Long> visited = new HashSet<>();
        List<Face> faces = new ArrayList<>();
        for (Edge e0 : es) for (int dir = 0; dir < 2; dir++) {
            Node u0 = dir == 0 ? e0.a : e0.b;
            if (visited.contains(e0.id * 2L + dir)) continue;
            List<Node> cyc = new ArrayList<>();
            List<Edge> ce = new ArrayList<>();
            Node u = u0, v = e0.other(u0);
            Edge e = e0;
            int guard = 0;
            while (true) {
                visited.add(e.id * 2L + (u == e.a ? 0 : 1));
                cyc.add(u);
                ce.add(e);
                Edge[] arr = sorted.get(v.id);
                int ix = 0;
                while (arr[ix] != e) ix++;
                Edge nx = arr[(ix - 1 + arr.length) % arr.length];
                u = v; v = nx.other(u); e = nx;
                if (e == e0 && u == u0) break;
                if (++guard > 50000) break;
            }
            List<P> pts = new ArrayList<>();
            for (Node n : cyc) pts.add(n.p);
            double area = Geom.signedArea(pts);
            if (area > 1e-6) faces.add(new Face(cyc, ce, area, faceKey(cyc)));
        }
        return faces;
    }

    static long faceKey(List<Node> cyc) {
        int k = 0;
        for (int i = 1; i < cyc.size(); i++) if (cyc.get(i).id < cyc.get(k).id) k = i;
        long h = 1469598103934665603L;
        for (int i = 0; i < cyc.size(); i++) {
            h ^= cyc.get((k + i) % cyc.size()).id;
            h *= 1099511628211L;
        }
        return h;
    }

    /** Groups edges into continuous "streets" by pairing the straightest continuations at each node. */
    List<Chain> chains(double maxDeflect) {
        List<Edge> es = aliveEdges();
        for (Edge e : es) { e.chainA = null; e.chainB = null; e.chainId = -1; }
        for (Node n : aliveNodes()) {
            if (n.deg() < 2) continue;
            List<double[]> pairs = new ArrayList<>();
            for (int i = 0; i < n.deg(); i++) for (int j = i + 1; j < n.deg(); j++) {
                double d = Math.PI - Math.abs(Geom.angDiff(n.edges.get(i).angleFrom(n), n.edges.get(j).angleFrom(n)));
                pairs.add(new double[]{d, i, j});
            }
            pairs.sort(Comparator.comparingDouble((double[] x) -> x[0]).thenComparingDouble(x -> x[1]).thenComparingDouble(x -> x[2]));
            boolean[] used = new boolean[n.deg()];
            for (double[] pr : pairs) {
                int i = (int) pr[1], j = (int) pr[2];
                if (pr[0] > maxDeflect || used[i] || used[j]) continue;
                used[i] = used[j] = true;
                Edge ei = n.edges.get(i), ej = n.edges.get(j);
                if (ei.a == n) ei.chainA = ej; else ei.chainB = ej;
                if (ej.a == n) ej.chainA = ei; else ej.chainB = ei;
            }
        }
        List<Chain> chains = new ArrayList<>();
        for (Edge e : es) {
            if (e.chainId >= 0) continue;
            Edge cur = e;
            Node node = e.a;
            int guard = 0;
            while (true) {
                Edge prev = cur.chainAt(node);
                if (prev == null || prev == e || ++guard > 100000) break;
                node = prev.other(node);
                cur = prev;
            }
            Chain ch = new Chain();
            ch.id = chains.size();
            ch.nodes.add(node);
            while (cur != null && cur.chainId < 0) {
                cur.chainId = ch.id;
                Node n2 = cur.other(node);
                ch.edges.add(cur);
                ch.nodes.add(n2);
                cur = cur.chainAt(n2);
                node = n2;
            }
            chains.add(ch);
        }
        return chains;
    }
}
