package com.emergentcity.city;

import java.util.Arrays;

/**
 * Heightmap + water mask on a regular grid. Grid node (i,j) sits at (i*cell, j*cell); row 0 is south.
 * File format "FICTIONAL_TERRAIN 1" is plain text so test terrains can be inspected and edited.
 */
public final class Terrain {
    String name, description = "";
    double cell, waterLevel;
    int cols, rows;
    public float[] h;
    boolean[] water;
    private float[] wdist;

    Terrain(String name, int cols, int rows, double cell) {
        this.name = name; this.cols = cols; this.rows = rows; this.cell = cell;
        h = new float[cols * rows];
        water = new boolean[cols * rows];
    }

    double widthM() { return (cols - 1) * cell; }
    double heightM() { return (rows - 1) * cell; }
    int idx(int i, int j) { return j * cols + i; }

    double height(double x, double y) {
        double fx = Geom.clamp(x / cell, 0, cols - 1.0001), fy = Geom.clamp(y / cell, 0, rows - 1.0001);
        int i = (int) fx, j = (int) fy;
        double tx = fx - i, ty = fy - j;
        double a = h[idx(i, j)], b = h[idx(i + 1, j)], c = h[idx(i, j + 1)], d = h[idx(i + 1, j + 1)];
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }

    double height(P p) { return height(p.x(), p.y()); }

    boolean isWater(double x, double y) {
        int i = (int) Math.round(x / cell), j = (int) Math.round(y / cell);
        if (i < 0 || j < 0 || i >= cols || j >= rows) return true;
        return water[idx(i, j)];
    }

    boolean isWater(P p) { return isWater(p.x(), p.y()); }

    boolean inside(P p, double margin) {
        return p.x() >= margin && p.y() >= margin && p.x() <= widthM() - margin && p.y() <= heightM() - margin;
    }

    double slope(double x, double y) {
        double d = Math.max(cell, 4); // Minecraft heights are 1-block steps; measure over >= 4 m
        double gx = (height(x + d, y) - height(x - d, y)) / (2 * d), gy = (height(x, y + d) - height(x, y - d)) / (2 * d);
        return Math.hypot(gx, gy);
    }

    /** Maximum grade along a segment, sampled every ~6 m. */
    double maxGrade(P a, P b) {
        double L = a.dist(b);
        if (L < 1e-6) return 0;
        int n = Math.max(1, (int) Math.ceil(L / 6));
        double g = 0, prev = height(a);
        for (int k = 1; k <= n; k++) {
            double hh = height(a.lerp(b, k / (double) n));
            g = Math.max(g, Math.abs(hh - prev) / (L / n));
            prev = hh;
        }
        return g;
    }

    double waterLength(P a, P b) {
        double L = a.dist(b);
        int n = Math.max(2, (int) Math.ceil(L / 2));
        int cnt = 0;
        for (int k = 0; k <= n; k++) if (isWater(a.lerp(b, k / (double) n))) cnt++;
        return cnt * L / (n + 1);
    }

    /** Distance (m) to nearest water cell; very large if the terrain has no water. */
    double waterDist(double x, double y) {
        ensureDist();
        int i = (int) Geom.clamp(Math.round(x / cell), 0, cols - 1), j = (int) Geom.clamp(Math.round(y / cell), 0, rows - 1);
        return wdist[idx(i, j)];
    }

    private void ensureDist() {
        if (wdist != null) return;
        float INF = 1e9f;
        wdist = new float[cols * rows];
        Arrays.fill(wdist, INF);
        for (int k = 0; k < water.length; k++) if (water[k]) wdist[k] = 0;
        float c1 = (float) cell, c2 = (float) (cell * Math.sqrt(2));
        for (int j = 0; j < rows; j++) for (int i = 0; i < cols; i++) {
            int k = idx(i, j);
            if (i > 0) wdist[k] = Math.min(wdist[k], wdist[k - 1] + c1);
            if (j > 0) wdist[k] = Math.min(wdist[k], wdist[k - cols] + c1);
            if (i > 0 && j > 0) wdist[k] = Math.min(wdist[k], wdist[k - cols - 1] + c2);
            if (i < cols - 1 && j > 0) wdist[k] = Math.min(wdist[k], wdist[k - cols + 1] + c2);
        }
        for (int j = rows - 1; j >= 0; j--) for (int i = cols - 1; i >= 0; i--) {
            int k = idx(i, j);
            if (i < cols - 1) wdist[k] = Math.min(wdist[k], wdist[k + 1] + c1);
            if (j < rows - 1) wdist[k] = Math.min(wdist[k], wdist[k + cols] + c1);
            if (i < cols - 1 && j < rows - 1) wdist[k] = Math.min(wdist[k], wdist[k + cols + 1] + c2);
            if (i > 0 && j < rows - 1) wdist[k] = Math.min(wdist[k], wdist[k + cols - 1] + c2);
        }
    }

    boolean hasWater() { for (boolean b : water) if (b) return true; return false; }

}
