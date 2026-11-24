package io.github.innomaster01del.portalrps;

/**
 * Pure math, no Android dependencies (unit-testable on a desktop JVM).
 * Hand points are 21 landmarks packed as float[63] = x0,y0,z0, x1,y1,z1 ...
 */
public final class HandMath {
    private HandMath() {}

    public static final String ROCK = "rock", PAPER = "paper", SCISSORS = "scissors";

    /**
     * A cartoon hand pose in the SENDER's portal frame (metres): fingers point into the portal (-Z),
     * palm faces +X, thumb up. yOff moves the whole hand up/down (the "pump" during the countdown).
     */
    public static float[] poseHand(String g, float yOff) {
        int[] ext;
        if (PAPER.equals(g)) ext = new int[]{1, 1, 1, 1, 1};
        else if (SCISSORS.equals(g)) ext = new int[]{0, 1, 1, 0, 0};
        else ext = new int[]{0, 0, 0, 0, 0};
        final float Z = -0.03f;
        float[][] P = new float[21][];
        P[0] = v(0, -0.005f, 0, yOff, Z);
        P[1] = v(0.012f, 0.02f, -0.03f, yOff, Z);
        if (ext[0] == 1) {
            P[2] = v(0.018f, 0.042f, -0.052f, yOff, Z); P[3] = v(0.02f, 0.056f, -0.075f, yOff, Z); P[4] = v(0.02f, 0.064f, -0.097f, yOff, Z);
        } else {
            P[2] = v(0.024f, 0.034f, -0.058f, yOff, Z); P[3] = v(0.036f, 0.024f, -0.078f, yOff, Z); P[4] = v(0.044f, 0.012f, -0.09f, yOff, Z);
        }
        float[][] mcp = {{0.006f, 0.03f, -0.092f}, {0.004f, 0.01f, -0.097f}, {0.004f, -0.01f, -0.093f}, {0.006f, -0.028f, -0.085f}};
        float[][] lens = {{0.04f, 0.024f, 0.02f}, {0.045f, 0.027f, 0.022f}, {0.042f, 0.026f, 0.021f}, {0.033f, 0.02f, 0.018f}};
        float[] spread = SCISSORS.equals(g) ? new float[]{0.2f, -0.14f, 0, 0} : new float[]{0.08f, 0.02f, -0.05f, -0.12f};
        for (int f = 0; f < 4; f++) {
            int base = 5 + f * 4;
            float[] p = v(mcp[f][0], mcp[f][1], mcp[f][2], yOff, Z);
            P[base] = p;
            boolean straight = ext[f + 1] == 1;
            double th = straight ? 0.05 : 1.3, sp = straight ? spread[f] : 0, a = 0;
            for (int s = 0; s < 3; s++) {
                a += th;
                float L = lens[f][s];
                p = new float[]{(float) (p[0] + L * Math.sin(a)), (float) (p[1] + Math.sin(sp) * L * Math.cos(a)), (float) (p[2] - Math.cos(sp) * L * Math.cos(a))};
                P[base + 1 + s] = p;
            }
        }
        // turn the hand palm-down (thumb to the side), the way people throw in this game, then pump
        float[] out = new float[63];
        for (int i = 0; i < 21; i++) {
            float x = P[i][0], y = P[i][1] - yOff;
            out[i * 3] = y; out[i * 3 + 1] = -x + yOff + 0.02f; out[i * 3 + 2] = P[i][2];
        }
        return out;
    }

    private static float[] v(float x, float y, float z, float yOff, float zOff) { return new float[]{x, y + yOff, z + zOff}; }

    private static float dist(float[] p, int a, int b) {
        float dx = p[a * 3] - p[b * 3], dy = p[a * 3 + 1] - p[b * 3 + 1], dz = p[a * 3 + 2] - p[b * 3 + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float angleDeg(float[] p, int a, int b, int c, int d) {
        float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        float vx = p[d * 3] - p[c * 3], vy = p[d * 3 + 1] - p[c * 3 + 1], vz = p[d * 3 + 2] - p[c * 3 + 2];
        double lu = Math.sqrt(ux * ux + uy * uy + uz * uz), lv = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (lu < 1e-6 || lv < 1e-6) return 90;
        double cos = (ux * vx + uy * vy + uz * vz) / (lu * lv);
        return (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /**
     * Finger state from joint angles (independent of hand size, distance and rotation):
     * 1 = straight, -1 = curled, 0 = unclear. f: 0 index, 1 middle, 2 ring, 3 pinky.
     */
    public static int fingerState(float[] p, int f) {
        int m = 5 + f * 4, pip = m + 1, tip = m + 3;
        float bend = angleDeg(p, m, pip, pip, tip);      // how bent the finger is
        float align = angleDeg(p, 0, m, m, tip);         // does it point away from the wrist
        float reach = dist(p, tip, 0) / Math.max(dist(p, m, 0), 1e-6f);
        // lenient: real hands seen from awkward angles look shorter and more bent than they are
        if (bend < 65 && align < 70 && reach > 1.3f) return 1;
        if (bend > 80 || reach < 1.15f) return -1;
        return 0;
    }

    /** Rock / paper / scissors from 21 points (3D, or 2D image points with z = 0). null when unclear. */
    public static String classify(float[] p) {
        int[] st = {fingerState(p, 0), fingerState(p, 1), fingerState(p, 2), fingerState(p, 3)};
        int up = 0, down = 0;
        for (int v : st) { if (v == 1) up++; if (v == -1) down++; }
        if (st[0] == 1 && st[1] == 1 && st[2] != 1 && st[3] != 1) return SCISSORS;
        if (up >= 3) return PAPER;
        if (up == 0 && down >= 2) return ROCK;
        return null;
    }

    /**
     * Places MediaPipe's metric hand (world landmarks: origin ≈ hand centre, x right, y down, z forward
     * in the same image axes) by solving only for its translation, so that it projects onto the 2D
     * landmarks. nx/ny are normalised camera rays: (u - cx) / fx, (v - cy) / fy.
     * Returns t = {tx, ty, tz} in that image-camera frame (z forward), or null.
     */
    public static double[] solveTranslation(float[] nx, float[] ny, float[] w) {
        double a00 = 0, a01 = 0, a02 = 0, a11 = 0, a12 = 0, a22 = 0, b0 = 0, b1 = 0, b2 = 0;
        for (int i = 0; i < 21; i++) {
            double wx = w[i * 3], wy = w[i * 3 + 1], wz = w[i * 3 + 2];
            // row (1, 0, -nx) · t = nx*wz - wx
            double c = -nx[i], b = nx[i] * wz - wx;
            a00 += 1; a02 += c; a22 += c * c; b0 += b; b2 += c * b;
            // row (0, 1, -ny) · t = ny*wz - wy
            c = -ny[i]; b = ny[i] * wz - wy;
            a11 += 1; a12 += c; a22 += c * c; b1 += b; b2 += c * b;
        }
        double[][] A = {{a00, a01, a02}, {a01, a11, a12}, {a02, a12, a22}};
        double det = det3(A);
        if (Math.abs(det) < 1e-12) return null;
        double[] t = new double[3];
        double[] B = {b0, b1, b2};
        for (int k = 0; k < 3; k++) {
            double[][] M = {A[0].clone(), A[1].clone(), A[2].clone()};
            for (int r = 0; r < 3; r++) M[r][k] = B[r];
            t[k] = det3(M) / det;
        }
        if (!(t[2] > 0.06 && t[2] < 3.0)) return null;
        return t;
    }

    private static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
             - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
             + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /** Direction the opponent's hand comes out of my portal (portal space): toward me and a little down. */
    private static final float[] OUT_F = norm3(0f, -0.28f, 0.96f);  // straight out of the centre, tilted a little down
    /** Where its wrist sits: in front of the portal face, in the middle. */
    private static final float[] WRIST_AT = {0f, -0.01f, 0.05f};       // centred, a bit low; whole hand in front, the arm comes out of the swirl
    public static float[] outDirection() { return OUT_F.clone(); }

    /**
     * Turns the opponent's hand (in THEIR portal space, any position or angle) into a hand coming out of
     * MY portal: same finger shapes and palm roll, but always emerging toward me at a natural angle.
     */
    public static float[] receiverPose(float[] p) {
        float[] f = sub3(p, 9, 0), s = sub3(p, 5, 17);
        normalize3(f);
        float d = dot3(s, f);
        for (int i = 0; i < 3; i++) s[i] -= f[i] * d;
        normalize3(s);
        float[] u = cross3(f, s);
        // palm roll around the finger direction, measured from "up" of the sender's portal
        float[] yRef = {-f[1] * f[0], 1 - f[1] * f[1], -f[1] * f[2]};
        if (len3(yRef) < 1e-3f) yRef = new float[]{1, 0, 0};
        normalize3(yRef);
        float[] xRef = cross3(f, yRef);
        double roll = Math.atan2(dot3(u, xRef), dot3(u, yRef));
        // my side: the 180° turn through the portal flips the roll
        float[] f2 = OUT_F;
        float[] y2 = {-f2[1] * f2[0], 1 - f2[1] * f2[1], -f2[1] * f2[2]};
        normalize3(y2);
        float[] x2 = cross3(f2, y2);
        float cr = (float) Math.cos(-roll), sr = (float) Math.sin(-roll);
        float[] u2 = {cr * y2[0] + sr * x2[0], cr * y2[1] + sr * x2[1], cr * y2[2] + sr * x2[2]};
        float[] s2 = cross3(u2, f2);
        float[] out = new float[63];
        for (int i = 0; i < 21; i++) {
            float dx = p[i * 3] - p[0], dy = p[i * 3 + 1] - p[1], dz = p[i * 3 + 2] - p[2];
            float qx = dx * s[0] + dy * s[1] + dz * s[2];
            float qy = dx * u[0] + dy * u[1] + dz * u[2];
            float qz = dx * f[0] + dy * f[1] + dz * f[2];
            for (int k = 0; k < 3; k++) out[i * 3 + k] = WRIST_AT[k] + qx * s2[k] + qy * u2[k] + qz * f2[k];
        }
        return out;
    }

    private static float[] norm3(float x, float y, float z) { float[] v = {x, y, z}; normalize3(v); return v; }
    private static float[] sub3(float[] p, int a, int b) { return new float[]{p[a * 3] - p[b * 3], p[a * 3 + 1] - p[b * 3 + 1], p[a * 3 + 2] - p[b * 3 + 2]}; }
    private static float dot3(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static float len3(float[] a) { return (float) Math.sqrt(dot3(a, a)); }
    private static void normalize3(float[] a) { float l = len3(a); if (l > 1e-9f) { a[0] /= l; a[1] /= l; a[2] /= l; } }
    private static float[] cross3(float[] a, float[] b) { return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }

    /** From a's point of view: 1 win, -1 loss, 0 draw, 2 nobody played. null = no hand. */
    public static int rps(String a, String b) {
        if (a == null && b == null) return 2;
        if (b == null) return 1;
        if (a == null) return -1;
        if (a.equals(b)) return 0;
        boolean aWins = (ROCK.equals(a) && SCISSORS.equals(b)) || (SCISSORS.equals(a) && PAPER.equals(b)) || (PAPER.equals(a) && ROCK.equals(b));
        return aWins ? 1 : -1;
    }
}
