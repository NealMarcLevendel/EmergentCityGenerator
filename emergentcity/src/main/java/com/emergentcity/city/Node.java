package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public final class Node {
    public final int id;
    public final P p;
    public final List<Edge> edges = new ArrayList<>();
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
