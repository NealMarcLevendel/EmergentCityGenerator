package com.emergentcity.city;

/** Immutable 2D point / vector in world metres. x = east, y = north. */
public record P(double x, double y) {
    public P add(P o) { return new P(x + o.x, y + o.y); }
    public P sub(P o) { return new P(x - o.x, y - o.y); }
    public P mul(double s) { return new P(x * s, y * s); }
    public double dot(P o) { return x * o.x + y * o.y; }
    public double cross(P o) { return x * o.y - y * o.x; }
    public double len() { return Math.hypot(x, y); }
    public double dist(P o) { return Math.hypot(x - o.x, y - o.y); }
    public P norm() { double l = len(); return l < 1e-12 ? new P(0, 0) : new P(x / l, y / l); }
    /** Left-hand perpendicular (rotate +90 degrees). */
    public P perpL() { return new P(-y, x); }
    public double angle() { return Math.atan2(y, x); }
    public P lerp(P o, double t) { return new P(x + (o.x - x) * t, y + (o.y - y) * t); }
    public static P dir(double a) { return new P(Math.cos(a), Math.sin(a)); }
}
