package io.github.innomaster01del.portalrps;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;

/** Indexed triangle mesh, interleaved position(3) normal(3) uv(2). */
final class Mesh {
    private final int vbo, ibo, count;

    private Mesh(float[] v, short[] idx) {
        int[] b = new int[2];
        GLES20.glGenBuffers(2, b, 0);
        vbo = b[0]; ibo = b[1];
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.length * 4, fb, GLES20.GL_STATIC_DRAW);
        ShortBuffer sb = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(idx).position(0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, sb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        count = idx.length;
    }

    void draw(int aPos, int aNormal, int aUV) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, 0);
        if (aNormal >= 0) { GLES20.glEnableVertexAttribArray(aNormal); GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 32, 12); }
        if (aUV >= 0) { GLES20.glEnableVertexAttribArray(aUV); GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, 24); }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /* ------------------------------ builders ------------------------------ */

    private static final class B {
        final ArrayList<Float> v = new ArrayList<>();
        final ArrayList<Short> i = new ArrayList<>();
        int n = 0;
        int vert(float x, float y, float z, float nx, float ny, float nz, float u, float w) {
            v.add(x); v.add(y); v.add(z); v.add(nx); v.add(ny); v.add(nz); v.add(u); v.add(w);
            return n++;
        }
        void tri(int a, int b, int c) { i.add((short) a); i.add((short) b); i.add((short) c); }
        Mesh build() {
            float[] fv = new float[v.size()];
            for (int k = 0; k < fv.length; k++) fv[k] = v.get(k);
            short[] si = new short[i.size()];
            for (int k = 0; k < si.length; k++) si[k] = i.get(k);
            return new Mesh(fv, si);
        }
    }

    /** Unit sphere. */
    static Mesh sphere(int lat, int lon) {
        B b = new B();
        for (int y = 0; y <= lat; y++) {
            double th = Math.PI * y / lat;
            for (int x = 0; x <= lon; x++) {
                double ph = 2 * Math.PI * x / lon;
                float nx = (float) (Math.sin(th) * Math.cos(ph)), ny = (float) Math.cos(th), nz = (float) (Math.sin(th) * Math.sin(ph));
                b.vert(nx, ny, nz, nx, ny, nz, (float) x / lon, (float) y / lat);
            }
        }
        for (int y = 0; y < lat; y++) for (int x = 0; x < lon; x++) {
            int a = y * (lon + 1) + x, c = a + lon + 1;
            b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1);
        }
        return b.build();
    }

    /** Open cylinder, radius 1, from y=-0.5 to y=0.5. */
    static Mesh cylinder(int seg) {
        B b = new B();
        for (int s = 0; s <= seg; s++) {
            double a = 2 * Math.PI * s / seg;
            float x = (float) Math.cos(a), z = (float) Math.sin(a);
            b.vert(x, -0.5f, z, x, 0, z, (float) s / seg, 0);
            b.vert(x, 0.5f, z, x, 0, z, (float) s / seg, 1);
        }
        for (int s = 0; s < seg; s++) { int k = s * 2; b.tri(k, k + 1, k + 2); b.tri(k + 2, k + 1, k + 3); }
        return b.build();
    }

    /** Closed cylinder (with caps), radius 1, height 1, centred. */
    static Mesh solidCylinder(int seg) {
        B b = new B();
        for (int s = 0; s <= seg; s++) {
            double a = 2 * Math.PI * s / seg;
            float x = (float) Math.cos(a), z = (float) Math.sin(a);
            b.vert(x, -0.5f, z, x, 0, z, 0, 0);
            b.vert(x, 0.5f, z, x, 0, z, 0, 1);
        }
        for (int s = 0; s < seg; s++) { int k = s * 2; b.tri(k, k + 1, k + 2); b.tri(k + 2, k + 1, k + 3); }
        for (int cap = 0; cap < 2; cap++) {
            float y = cap == 0 ? -0.5f : 0.5f, ny = cap == 0 ? -1 : 1;
            int c = b.vert(0, y, 0, 0, ny, 0, 0.5f, 0.5f);
            int first = b.n;
            for (int s = 0; s <= seg; s++) {
                double a = 2 * Math.PI * s / seg;
                b.vert((float) Math.cos(a), y, (float) Math.sin(a), 0, ny, 0, 0, 0);
            }
            for (int s = 0; s < seg; s++) { if (cap == 0) b.tri(c, first + s, first + s + 1); else b.tri(c, first + s + 1, first + s); }
        }
        return b.build();
    }

    /** Torus in the XY plane (axis = Z). */
    static Mesh torus(float R, float r, int seg, int side) {
        B b = new B();
        for (int s = 0; s <= seg; s++) {
            double u = 2 * Math.PI * s / seg;
            for (int t = 0; t <= side; t++) {
                double w = 2 * Math.PI * t / side;
                float nx = (float) (Math.cos(w) * Math.cos(u)), ny = (float) (Math.cos(w) * Math.sin(u)), nz = (float) Math.sin(w);
                float rr = (float) (R + r * Math.cos(w));
                b.vert((float) (rr * Math.cos(u)), (float) (rr * Math.sin(u)), (float) (r * Math.sin(w)), nx, ny, nz, (float) s / seg, (float) t / side);
            }
        }
        for (int s = 0; s < seg; s++) for (int t = 0; t < side; t++) {
            int a = s * (side + 1) + t, c = a + side + 1;
            b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1);
        }
        return b.build();
    }

    /** Disc in the XY plane facing +Z, uv = (x, y) mapped to 0..1. */
    static Mesh disc(float r, int seg) {
        B b = new B();
        int c = b.vert(0, 0, 0, 0, 0, 1, 0.5f, 0.5f);
        for (int s = 0; s <= seg; s++) {
            double a = 2 * Math.PI * s / seg;
            float x = (float) Math.cos(a), y = (float) Math.sin(a);
            b.vert(x * r, y * r, 0, 0, 0, 1, 0.5f + x * 0.5f, 0.5f + y * 0.5f);
        }
        for (int s = 0; s < seg; s++) b.tri(c, c + 1 + s, c + 2 + s);
        return b.build();
    }

    /** Flat ring in the XZ plane facing +Y. */
    static Mesh flatRing(float r0, float r1, int seg) {
        B b = new B();
        for (int s = 0; s <= seg; s++) {
            double a = 2 * Math.PI * s / seg;
            float x = (float) Math.cos(a), z = (float) Math.sin(a);
            b.vert(x * r0, 0, z * r0, 0, 1, 0, 0, 0);
            b.vert(x * r1, 0, z * r1, 0, 1, 0, 1, 0);
        }
        for (int s = 0; s < seg; s++) { int k = s * 2; b.tri(k, k + 2, k + 1); b.tri(k + 1, k + 2, k + 3); }
        return b.build();
    }

    /** Axis-aligned box centred at the origin. */
    static Mesh box(float sx, float sy, float sz) {
        B b = new B();
        float x = sx / 2, y = sy / 2, z = sz / 2;
        float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (float[] f : n) {
            // two tangent axes for the face
            float[] u = Math.abs(f[1]) > 0 ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] w = {f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
            int k = b.n;
            for (int c = 0; c < 4; c++) {
                float su = (c == 1 || c == 2) ? 1 : -1, sw = (c >= 2) ? 1 : -1;
                b.vert((f[0] + u[0] * su + w[0] * sw) * x, (f[1] + u[1] * su + w[1] * sw) * y, (f[2] + u[2] * su + w[2] * sw) * z, f[0], f[1], f[2], 0, 0);
            }
            b.tri(k, k + 1, k + 2); b.tri(k, k + 2, k + 3);
        }
        return b.build();
    }
}
