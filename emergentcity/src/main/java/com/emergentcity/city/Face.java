package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public record Face(List<Node> cycle, List<Edge> edges, double area, long key) {
    List<P> poly() {
        List<P> r = new ArrayList<>();
        for (Node n : cycle) r.add(n.p);
        return r;
    }
}
