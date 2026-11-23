package io.github.innomaster01del.portalrps;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * A smooth, continuous hand surface rebuilt every frame from the 21 joints:
 * fingers are tapered tubes swept along a spline through the joints (with rounded tips),
 * the palm is a rounded slab, plus a forearm and a sleeve. Layout per vertex: pos3 normal3 uv2.
 */
final class HandMesh {
    private static final int S = 6;          // spline samples per bone
    private static final int N = 12;         // sides per finger ring
    private static final int CAP = 4;        // rings in the fingertip dome
    private static final int RINGS = 3 * S + 1;
    private static final int FINGER_V = RINGS * N + CAP * N + 1;
    private static final int PM = 32;        // palm outline samples
    private static final int PR = 13;        // palm profile rows
    private static final int FN = 16;        // forearm sides
    private static final int ARM_R = 3, SLEEVE_R = 4;

    private static final int[][] CHAIN = {{1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}};
    private static final float[][] RAD = {
        {0.0128f, 0.0118f, 0.0106f, 0.0096f},
        {0.0102f, 0.0094f, 0.0086f, 0.0078f},
        {0.0106f, 0.0097f, 0.0089f, 0.0080f},
        {0.0100f, 0.0091f, 0.0084f, 0.0076f},
        {0.0090f, 0.0083f, 0.0076f, 0.0070f}};

    private final boolean withSleeve;
    private final int vCount, palmV0, armV0, sleeveV0;
    private final float[] v;                 // interleaved
    private final short[] idx;
    final int skinIdxCount, sleeveIdxStart, sleeveIdxCount;
    private final FloatBuffer vb;
    private int vbo = 0, ibo = 0;

    // scratch
    private final float[] a1 = new float[3], a2 = new float[3], n = new float[3], c = new float[3];
    private final float[][] ctl = new float[6][3];
    private final float[] pos = new float[3], tan = new float[3], nrm = new float[3], bin = new float[3], prevN = new float[3];
    private final float[][] outline = new float[8][3];
    private final float[][] oSamp = new float[PM][3];

    HandMesh(boolean withSleeve) {
        this.withSleeve = withSleeve;
        palmV0 = 5 * FINGER_V;
        armV0 = palmV0 + PR * PM;
        sleeveV0 = armV0 + ARM_R * FN;
        vCount = sleeveV0 + (withSleeve ? SLEEVE_R * FN : 0);
        v = new float[vCount * 8];
        vb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        short[] tmp = new short[60000];
        int k = 0;
        for (int f = 0; f < 5; f++) {
            int base = f * FINGER_V;
            int ringsTotal = RINGS + CAP;
            for (int r = 0; r < ringsTotal - 1; r++) k = quadRing(tmp, k, base + r * N, base + (r + 1) * N, N);
            int last = base + (ringsTotal - 1) * N, tip = base + ringsTotal * N;
            for (int s = 0; s < N; s++) { tmp[k++] = (short) (last + s); tmp[k++] = (short) (last + (s + 1) % N); tmp[k++] = (short) tip; }
        }
        for (int r = 0; r < PR - 1; r++) k = quadRing(tmp, k, palmV0 + r * PM, palmV0 + (r + 1) * PM, PM);
        for (int r = 0; r < ARM_R - 1; r++) k = quadRing(tmp, k, armV0 + r * FN, armV0 + (r + 1) * FN, FN);
        skinIdxCount = k;
        sleeveIdxStart = k;
        if (withSleeve) for (int r = 0; r < SLEEVE_R - 1; r++) k = quadRing(tmp, k, sleeveV0 + r * FN, sleeveV0 + (r + 1) * FN, FN);
        sleeveIdxCount = k - sleeveIdxStart;
        idx = new short[k];
        System.arraycopy(tmp, 0, idx, 0, k);
    }

    private static int quadRing(short[] t, int k, int r0, int r1, int n) {
        for (int s = 0; s < n; s++) {
            int s1 = (s + 1) % n;
            // counter-clockwise seen from outside, so the outer surface is front-facing
            t[k++] = (short) (r0 + s); t[k++] = (short) (r0 + s1); t[k++] = (short) (r1 + s);
            t[k++] = (short) (r0 + s1); t[k++] = (short) (r1 + s1); t[k++] = (short) (r1 + s);
        }
        return k;
    }

    /** Rebuilds the surface. p: 21 joints (float[63], metres). inflate: 1 = normal, >1 fatter (occluder). */
    void update(float[] p, float inflate) {
        palmFrame(p);
        for (int f = 0; f < 5; f++) finger(p, f, inflate);
        palm(p, inflate);
        arm(p, inflate);
        normals();
    }

    /* ------------------------------ palm frame ------------------------------ */

    private void palmFrame(float[] p) {
        sub(p, 9, 0, a1); normalize(a1);
        sub(p, 5, 17, a2);
        float d = dot(a2, a1);
        for (int i = 0; i < 3; i++) a2[i] -= a1[i] * d;
        normalize(a2);
        cross(a1, a2, n); normalize(n);
    }

    /* ------------------------------ fingers ------------------------------ */

    private void finger(float[] p, int f, float inflate) {
        int[] ch = CHAIN[f];
        for (int i = 0; i < 4; i++) { ctl[i + 1][0] = p[ch[i] * 3]; ctl[i + 1][1] = p[ch[i] * 3 + 1]; ctl[i + 1][2] = p[ch[i] * 3 + 2]; }
        for (int i = 0; i < 3; i++) { ctl[0][i] = 2 * ctl[1][i] - ctl[2][i]; ctl[5][i] = 2 * ctl[4][i] - ctl[3][i]; }
        int base = f * FINGER_V;
        int ring = 0;
        float lastR = 0;
        for (int seg = 0; seg < 3; seg++) {
            for (int s = 0; s <= S; s++) {
                if (seg > 0 && s == 0) continue;
                float t = (float) s / S;
                spline(ctl[seg], ctl[seg + 1], ctl[seg + 2], ctl[seg + 3], t, pos, tan);
                normalize(tan);
                if (ring == 0) {
                    // start the frame from the palm normal so fingers don't twist
                    float d = dot(n, tan);
                    for (int i = 0; i < 3; i++) nrm[i] = n[i] - tan[i] * d;
                    if (len(nrm) < 1e-5f) { nrm[0] = a2[0]; nrm[1] = a2[1]; nrm[2] = a2[2]; }
                } else {
                    float d = dot(prevN, tan);
                    for (int i = 0; i < 3; i++) nrm[i] = prevN[i] - tan[i] * d;
                }
                normalize(nrm);
                prevN[0] = nrm[0]; prevN[1] = nrm[1]; prevN[2] = nrm[2];
                cross(tan, nrm, bin);
                float r = (RAD[f][seg] + (RAD[f][seg + 1] - RAD[f][seg]) * t) * inflate;
                lastR = r;
                writeRing(base + ring * N, pos, nrm, bin, r * 0.86f, r, N);
                ring++;
            }
        }
        // rounded fingertip
        for (int j = 1; j <= CAP; j++) {
            double a = (Math.PI / 2) * j / (CAP + 1);
            float off = (float) (lastR * Math.sin(a)), rr = (float) (lastR * Math.cos(a));
            float[] cpos = {pos[0] + tan[0] * off, pos[1] + tan[1] * off, pos[2] + tan[2] * off};
            writeRing(base + (RINGS + j - 1) * N, cpos, nrm, bin, rr * 0.86f, rr, N);
        }
        int tip = base + (RINGS + CAP) * N;
        put(tip, pos[0] + tan[0] * lastR, pos[1] + tan[1] * lastR, pos[2] + tan[2] * lastR);
    }

    private void writeRing(int v0, float[] center, float[] ax1, float[] ax2, float r1, float r2, int sides) {
        for (int s = 0; s < sides; s++) {
            double ph = 2 * Math.PI * s / sides;
            float cs = (float) Math.cos(ph), sn = (float) Math.sin(ph);
            put(v0 + s, center[0] + ax1[0] * cs * r1 + ax2[0] * sn * r2,
                        center[1] + ax1[1] * cs * r1 + ax2[1] * sn * r2,
                        center[2] + ax1[2] * cs * r1 + ax2[2] * sn * r2);
        }
    }

    private static void spline(float[] p0, float[] p1, float[] p2, float[] p3, float t, float[] out, float[] d) {
        float t2 = t * t, t3 = t2 * t;
        for (int i = 0; i < 3; i++) {
            out[i] = 0.5f * (2 * p1[i] + (-p0[i] + p2[i]) * t + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2 + (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3);
            d[i] = 0.5f * ((-p0[i] + p2[i]) + 2 * (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t + 3 * (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t2);
        }
    }

    /* ------------------------------ palm ------------------------------ */

    private void palm(float[] p, float inflate) {
        float halfW = dist(p, 5, 17) * 0.5f + 0.004f;
        setPt(outline[0], p, 5, a1, RAD[1][0] * 0.7f, a2, RAD[1][0] * 0.5f);
        setPt(outline[1], p, 9, a1, RAD[2][0] * 0.75f, a2, 0);
        setPt(outline[2], p, 13, a1, RAD[3][0] * 0.75f, a2, 0);
        setPt(outline[3], p, 17, a1, RAD[4][0] * 0.5f, a2, -RAD[4][0] * 0.7f);
        setPt(outline[4], p, 0, a1, 0.012f, a2, -halfW * 0.85f);
        setPt(outline[5], p, 0, a1, 0.006f, a2, halfW * 0.7f);
        setPt(outline[6], p, 1, a1, 0, a2, 0.006f);
        for (int i = 0; i < 3; i++) outline[7][i] = p[2 * 3 + i] * 0.45f + p[5 * 3 + i] * 0.55f + a2[i] * 0.002f;
        // centre and projection onto the palm plane
        c[0] = c[1] = c[2] = 0;
        for (float[] o : outline) { c[0] += o[0] / 8; c[1] += o[1] / 8; c[2] += o[2] / 8; }
        float grow = 1 + (inflate - 1) * 0.35f;
        for (float[] o : outline) {
            float d = (o[0] - c[0]) * n[0] + (o[1] - c[1]) * n[1] + (o[2] - c[2]) * n[2];
            for (int i = 0; i < 3; i++) o[i] = c[i] + (o[i] - n[i] * d - c[i]) * grow;
        }
        // closed spline through the outline
        int per = PM / 8;
        for (int seg = 0; seg < 8; seg++) for (int s = 0; s < per; s++)
            spline(outline[(seg + 7) % 8], outline[seg], outline[(seg + 1) % 8], outline[(seg + 2) % 8], (float) s / per, oSamp[seg * per + s], tan);
        float T = 0.0125f * inflate;
        float[] q = new float[3], d = new float[3];
        for (int col = 0; col < PM; col++) {
            for (int i = 0; i < 3; i++) d[i] = oSamp[col][i] - c[i];
            float L = len(d);
            if (L < 1e-5f) L = 1e-5f;
            for (int i = 0; i < 3; i++) d[i] /= L;
            float Li = Math.max(L - T, L * 0.4f);
            for (int row = 0; row < PR; row++) {
                float rad, up;
                if (row == 0) { rad = 0; up = T; }
                else if (row == 1) { rad = Li * 0.5f; up = T; }
                else if (row == 2) { rad = Li; up = T; }
                else if (row <= 9) { double th = Math.toRadians(22.5 * (row - 2)); rad = (float) (Li + T * Math.sin(th)); up = (float) (T * Math.cos(th)); }
                else if (row == 10) { rad = Li; up = -T; }
                else if (row == 11) { rad = Li * 0.5f; up = -T; }
                else { rad = 0; up = -T; }
                for (int i = 0; i < 3; i++) q[i] = c[i] + d[i] * rad + n[i] * up;
                put(palmV0 + row * PM + col, q[0], q[1], q[2]);
            }
        }
    }

    private static void setPt(float[] out, float[] p, int j, float[] u, float su, float[] w, float sw) {
        for (int i = 0; i < 3; i++) out[i] = p[j * 3 + i] + u[i] * su + w[i] * sw;
    }

    /* ------------------------------ forearm + sleeve ------------------------------ */

    private void arm(float[] p, float inflate) {
        float halfW = dist(p, 5, 17) * 0.5f;
        float[] w0 = {p[0] - a1[0] * 0.004f, p[1] - a1[1] * 0.004f, p[2] - a1[2] * 0.004f};
        float[] dist = {0f, 0.02f, 0.04f};
        float[] scale = {1f, 0.93f, 0.9f};
        float[] ctr = new float[3];
        for (int r = 0; r < ARM_R; r++) {
            for (int i = 0; i < 3; i++) ctr[i] = w0[i] - a1[i] * dist[r];
            writeRing(armV0 + r * FN, ctr, a2, n, halfW * 0.82f * scale[r] * inflate, 0.019f * scale[r] * inflate, FN);
        }
        if (!withSleeve) return;
        float[] sd = {0.028f, 0.034f, 0.05f, 0.2f};
        float[] ss = {0.98f, 1.12f, 1.1f, 1.16f};
        for (int r = 0; r < SLEEVE_R; r++) {
            for (int i = 0; i < 3; i++) ctr[i] = w0[i] - a1[i] * sd[r];
            writeRing(sleeveV0 + r * FN, ctr, a2, n, halfW * 0.82f * ss[r] * inflate, 0.021f * ss[r] * inflate, FN);
        }
    }

    /* ------------------------------ normals + upload ------------------------------ */

    private void normals() {
        for (int i = 0; i < vCount; i++) { v[i * 8 + 3] = 0; v[i * 8 + 4] = 0; v[i * 8 + 5] = 0; }
        for (int k = 0; k < idx.length; k += 3) {
            int ia = idx[k] * 8, ib = idx[k + 1] * 8, ic = idx[k + 2] * 8;
            float ux = v[ib] - v[ia], uy = v[ib + 1] - v[ia + 1], uz = v[ib + 2] - v[ia + 2];
            float wx = v[ic] - v[ia], wy = v[ic + 1] - v[ia + 1], wz = v[ic + 2] - v[ia + 2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            for (int o : new int[]{ia, ib, ic}) { v[o + 3] += nx; v[o + 4] += ny; v[o + 5] += nz; }
        }
        for (int i = 0; i < vCount; i++) {
            int o = i * 8;
            float l = (float) Math.sqrt(v[o + 3] * v[o + 3] + v[o + 4] * v[o + 4] + v[o + 5] * v[o + 5]);
            if (l > 1e-12f) { v[o + 3] /= l; v[o + 4] /= l; v[o + 5] /= l; }
        }
    }

    /** GL thread: upload and draw. part: 0 = skin, 1 = sleeve. */
    void upload() {
        if (vbo == 0) {
            int[] b = new int[2];
            GLES20.glGenBuffers(2, b, 0);
            vbo = b[0]; ibo = b[1];
            ShortBuffer sb = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(idx).position(0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, sb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.length * 4, null, GLES20.GL_DYNAMIC_DRAW);
        }
        vb.position(0);
        vb.put(v).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, v.length * 4, vb);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    void draw(int aPos, int aNormal, int aUV, int first, int count) {
        if (count <= 0) return;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, 0);
        if (aNormal >= 0) { GLES20.glEnableVertexAttribArray(aNormal); GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 32, 12); }
        if (aUV >= 0) { GLES20.glEnableVertexAttribArray(aUV); GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, 24); }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, first * 2);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /* ------------------------------ tiny vector helpers ------------------------------ */

    private void put(int i, float x, float y, float z) { int o = i * 8; v[o] = x; v[o + 1] = y; v[o + 2] = z; }
    private static void sub(float[] p, int a, int b, float[] o) { for (int i = 0; i < 3; i++) o[i] = p[a * 3 + i] - p[b * 3 + i]; }
    private static float dist(float[] p, int a, int b) {
        float x = p[a * 3] - p[b * 3], y = p[a * 3 + 1] - p[b * 3 + 1], z = p[a * 3 + 2] - p[b * 3 + 2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static float len(float[] a) { return (float) Math.sqrt(dot(a, a)); }
    private static void normalize(float[] a) { float l = len(a); if (l > 1e-9f) { a[0] /= l; a[1] /= l; a[2] /= l; } }
    private static void cross(float[] a, float[] b, float[] o) {
        float x = a[1] * b[2] - a[2] * b[1], y = a[2] * b[0] - a[0] * b[2], z = a[0] * b[1] - a[1] * b[0];
        o[0] = x; o[1] = y; o[2] = z;
    }

    /** Vertex array for tests. */
    float[] vertices() { return v; }
    short[] indices() { return idx; }
}
