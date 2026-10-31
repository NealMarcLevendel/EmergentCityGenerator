package com.emergentcity.city;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal reader/writer for the NBT format of vanilla structure files (.nbt, gzip-compressed). Compounds are
 * LinkedHashMaps, lists are ArrayLists, numbers are boxed Java numbers, int/long arrays are int[]/long[].
 * Self-contained so unit files can be read and written the same way in game and in the offline tools.
 */
final class Nbt {
    private Nbt() {}

    static final class Typed { final byte type; final Object v; Typed(int t, Object v) { this.type = (byte) t; this.v = v; } }

    /** Wraps a value with an explicit NBT type (e.g. a list of ints written as TAG_Int, a short, a byte). */
    static Typed typed(int type, Object v) { return new Typed(type, v); }

    static Map<String, Object> read(InputStream in) throws IOException {
        BufferedInputStream bin = new BufferedInputStream(in);
        bin.mark(2);
        int b0 = bin.read(), b1 = bin.read();
        bin.reset();
        InputStream src = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(bin) : bin;
        DataInputStream d = new DataInputStream(src);
        int t = d.readByte();
        if (t != 10) throw new IOException("root is not a compound");
        d.readUTF();
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) payload(d, 10);
        return m;
    }

    private static Object payload(DataInputStream d, int t) throws IOException {
        switch (t) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: { int n = d.readInt(); byte[] a = new byte[n]; d.readFully(a); return a; }
            case 8: return d.readUTF();
            case 9: {
                int et = d.readByte(), n = d.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(payload(d, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int ct = d.readByte();
                    if (ct == 0) break;
                    String k = d.readUTF();
                    m.put(k, payload(d, ct));
                }
                return m;
            }
            case 11: { int n = d.readInt(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = d.readInt(); return a; }
            case 12: { int n = d.readInt(); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = d.readLong(); return a; }
            default: throw new IOException("bad tag " + t);
        }
    }

    static void write(Map<String, Object> root, OutputStream out) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(out);
        DataOutputStream d = new DataOutputStream(gz);
        d.writeByte(10);
        d.writeUTF("");
        payloadOut(d, root, 10);
        d.flush();
        gz.finish();
    }

    static byte[] bytes(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        write(root, bo);
        return bo.toByteArray();
    }

    private static int typeOf(Object v) {
        if (v instanceof Typed t) return t.type;
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        if (v instanceof Boolean) return 1;
        throw new IllegalArgumentException("not NBT: " + v);
    }

    @SuppressWarnings("unchecked")
    private static void payloadOut(DataOutputStream d, Object v, int t) throws IOException {
        if (v instanceof Typed ty) v = ty.v;
        switch (t) {
            case 1 -> d.writeByte(v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).byteValue());
            case 2 -> d.writeShort(((Number) v).shortValue());
            case 3 -> d.writeInt(((Number) v).intValue());
            case 4 -> d.writeLong(((Number) v).longValue());
            case 5 -> d.writeFloat(((Number) v).floatValue());
            case 6 -> d.writeDouble(((Number) v).doubleValue());
            case 7 -> { byte[] a = (byte[]) v; d.writeInt(a.length); d.write(a); }
            case 8 -> d.write(utf((String) v));
            case 9 -> {
                List<Object> l = (List<Object>) v;
                int et = l.isEmpty() ? 0 : typeOf(l.get(0));
                d.writeByte(et);
                d.writeInt(l.size());
                for (Object o : l) payloadOut(d, o, et);
            }
            case 10 -> {
                for (var e : ((Map<String, Object>) v).entrySet()) {
                    int ct = typeOf(e.getValue());
                    d.writeByte(ct);
                    d.write(utf(e.getKey()));
                    payloadOut(d, e.getValue(), ct);
                }
                d.writeByte(0);
            }
            case 11 -> { int[] a = (int[]) v; d.writeInt(a.length); for (int x : a) d.writeInt(x); }
            case 12 -> { long[] a = (long[]) v; d.writeInt(a.length); for (long x : a) d.writeLong(x); }
            default -> throw new IOException("bad tag " + t);
        }
    }

    /** Modified UTF-8 with length prefix, as DataOutputStream.writeUTF (structure names and block ids are ASCII). */
    private static byte[] utf(String s) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        new DataOutputStream(bo).writeUTF(s);
        return bo.toByteArray();
    }

    static int asInt(Object o) { return o instanceof Typed t ? asInt(t.v) : ((Number) o).intValue(); }
}
