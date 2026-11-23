package io.github.innomaster01del.portalrps;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** All OpenGL drawing: camera background, portal, hands, reticle. GL thread only. */
final class SceneRenderer {
    static final float R = 0.14f;            // portal radius (m)
    static final float PORTAL_H = R + 0.04f; // ring centre above the table

    private static final String BG_VS =
        "attribute vec4 a_Pos; attribute vec2 a_UV; varying vec2 v_UV;" +
        "void main(){ gl_Position = a_Pos; v_UV = a_UV; }";
    private static final String BG_FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float; varying vec2 v_UV; uniform samplerExternalOES u_Tex;" +
        "void main(){ gl_FragColor = texture2D(u_Tex, v_UV); }";

    private static final String VS =
        "uniform mat4 uMVP; uniform mat4 uModel;" +
        "attribute vec3 aPos; attribute vec3 aNormal; attribute vec2 aUV;" +
        "varying vec3 vN; varying vec3 vW; varying vec2 vUV;" +
        "void main(){ vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = (uModel * vec4(aNormal, 0.0)).xyz; vUV = aUV;" +
        " gl_Position = uMVP * vec4(aPos, 1.0); }";
    private static final String FS =
        "precision mediump float;" +
        "uniform vec4 uColor; uniform vec3 uEmissive; uniform vec4 uClip; uniform int uMode; uniform float uTime;" +
        "uniform float uActive; uniform float uSpec; uniform vec3 uLight; uniform vec3 uCam;" +
        "varying vec3 vN; varying vec3 vW; varying vec2 vUV;" +
        "void main(){" +
        " if (dot(vec4(vW, 1.0), uClip) < 0.0) discard;" +
        " if (uMode == 1) {" +                                   // portal swirl
        "  vec2 p = vUV * 2.0 - 1.0; float r = length(p); float a = atan(p.y, p.x);" +
        "  float s = sin(a * 5.0 + r * 15.0 - uTime * 4.0) * 0.5 + 0.5;" +
        "  float s2 = sin(a * 3.0 - r * 9.0 + uTime * 2.3) * 0.5 + 0.5;" +
        "  vec3 c = mix(vec3(0.01, 0.12, 0.07), vec3(0.35, 1.0, 0.6), s * s2 * (1.0 - r * 0.7));" +
        "  c += vec3(0.1, 0.5, 0.3) * pow(1.0 - r, 2.0) * (0.5 + uActive);" +
        "  gl_FragColor = vec4(c, 1.0); return; }" +
        " if (uMode == 2) {" +                                   // soft contact shadow
        "  float r = length(vUV * 2.0 - 1.0); gl_FragColor = vec4(0.0, 0.0, 0.0, uColor.a * (1.0 - smoothstep(0.2, 1.0, r))); return; }" +
        " vec3 n = normalize(vN); if (!gl_FrontFacing) n = -n;" +
        " float diff = max(dot(n, uLight), 0.0);" +
        " vec3 v = normalize(uCam - vW); vec3 h = normalize(uLight + v);" +
        " float spec = pow(max(dot(n, h), 0.0), 40.0) * uSpec;" +
        " float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.25;" +
        " gl_FragColor = vec4(uColor.rgb * (0.42 + diff * 0.72) + vec3(spec + rim * uSpec) + uEmissive, uColor.a);" +
        "}";

    private int bgTex, bgProg, bgPos, bgUV, bgSampler;
    private FloatBuffer quad, quadUV;
    private int prog, aPos, aNormal, aUV, uMVP, uModel, uColor, uEmissive, uClip, uMode, uTime, uActive, uSpec, uLight, uCam;
    private Mesh sphere, cyl, solidCyl, ring, ringGlow, disc, base, reticle, shadow;
    private final float[] view = new float[16], proj = new float[16], vp = new float[16], mvp = new float[16], tmp = new float[16], tmp2 = new float[16];
    private final float[] cam = new float[3];
    private float time, active;
    static final float[] NO_CLIP = {0, 0, 0, 1};

    int create() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        bgTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        bgProg = program(BG_VS, BG_FS);
        bgPos = GLES20.glGetAttribLocation(bgProg, "a_Pos");
        bgUV = GLES20.glGetAttribLocation(bgProg, "a_UV");
        bgSampler = GLES20.glGetUniformLocation(bgProg, "u_Tex");
        quad = fbuf(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        quadUV = fbuf(new float[8]);

        prog = program(VS, FS);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal");
        aUV = GLES20.glGetAttribLocation(prog, "aUV");
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP");
        uModel = GLES20.glGetUniformLocation(prog, "uModel");
        uColor = GLES20.glGetUniformLocation(prog, "uColor");
        uEmissive = GLES20.glGetUniformLocation(prog, "uEmissive");
        uClip = GLES20.glGetUniformLocation(prog, "uClip");
        uMode = GLES20.glGetUniformLocation(prog, "uMode");
        uTime = GLES20.glGetUniformLocation(prog, "uTime");
        uActive = GLES20.glGetUniformLocation(prog, "uActive");
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec");
        uLight = GLES20.glGetUniformLocation(prog, "uLight");
        uCam = GLES20.glGetUniformLocation(prog, "uCam");

        sphere = Mesh.sphere(14, 18);
        cyl = Mesh.cylinder(16);
        solidCyl = Mesh.solidCylinder(24);
        ring = Mesh.torus(R, 0.017f, 80, 16);
        ringGlow = Mesh.torus(R - 0.013f, 0.0055f, 80, 8);
        disc = Mesh.disc(R - 0.006f, 72);
        base = Mesh.box(0.13f, 0.014f, 0.075f);
        reticle = Mesh.flatRing(0.06f, 0.075f, 48);
        shadow = Mesh.disc(1f, 32);
        return bgTex;
    }

    void drawBackground(Frame frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); quadUV.position(0);
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, quadUV);
        }
        if (frame.getTimestamp() == 0) return;
        quad.position(0); quadUV.position(0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(bgProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex);
        GLES20.glUniform1i(bgSampler, 0);
        GLES20.glEnableVertexAttribArray(bgPos);
        GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(bgUV);
        GLES20.glVertexAttribPointer(bgUV, 2, GLES20.GL_FLOAT, false, 0, quadUV);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(bgPos);
        GLES20.glDisableVertexAttribArray(bgUV);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    void begin(float[] viewM, float[] projM, float[] camPos, float t, float act) {
        System.arraycopy(viewM, 0, view, 0, 16);
        System.arraycopy(projM, 0, proj, 0, 16);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        cam[0] = camPos[0]; cam[1] = camPos[1]; cam[2] = camPos[2];
        time = t; active = act;
        GLES20.glUseProgram(prog);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        float lx = 0.3f, ly = 1f, lz = 0.45f, l = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        GLES20.glUniform3f(uLight, lx / l, ly / l, lz / l);
        GLES20.glUniform3f(uCam, cam[0], cam[1], cam[2]);
        GLES20.glUniform1f(uTime, time);
        GLES20.glUniform1f(uActive, active);
    }

    private void draw(Mesh m, float[] model, float r, float g, float b, float a, float er, float eg, float eb, int mode, float[] clip, float spec) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform4f(uColor, r, g, b, a);
        GLES20.glUniform3f(uEmissive, er, eg, eb);
        GLES20.glUniform4f(uClip, clip[0], clip[1], clip[2], clip[3]);
        GLES20.glUniform1i(uMode, mode);
        GLES20.glUniform1f(uSpec, spec);
        m.draw(aPos, aNormal, aUV);
    }

    /** portalM: world matrix of the ring centre (+Z faces the player). */
    void drawPortal(float[] portalM, float[] floorM, float glowPulse) {
        // soft shadow on the table
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        Matrix.setRotateM(tmp, 0, -90, 1, 0, 0);
        Matrix.scaleM(tmp, 0, 0.11f, 0.06f, 1);
        Matrix.multiplyMM(tmp2, 0, floorM, 0, tmp, 0);
        Matrix.translateM(tmp2, 0, 0, 0, 0.002f);
        draw(shadow, tmp2, 0, 0, 0, 0.45f, 0, 0, 0, 2, NO_CLIP, 0);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);

        draw(ring, portalM, 0.6f, 0.64f, 0.66f, 1, 0, 0, 0, 0, NO_CLIP, 0.9f);
        float gi = 0.75f + glowPulse;
        draw(ringGlow, portalM, 0.05f, 0.4f, 0.2f, 1, 0.1f * gi, 1.0f * gi, 0.48f * gi, 0, NO_CLIP, 0);
        draw(disc, portalM, 1, 1, 1, 1, 0, 0, 0, 1, NO_CLIP, 0);
        // neck + base
        System.arraycopy(portalM, 0, tmp, 0, 16);
        Matrix.translateM(tmp, 0, 0, -R - 0.017f, 0);
        Matrix.scaleM(tmp, 0, 0.014f, 0.03f, 0.014f);
        draw(solidCyl, tmp, 0.6f, 0.64f, 0.66f, 1, 0, 0, 0, 0, NO_CLIP, 0.9f);
        System.arraycopy(portalM, 0, tmp, 0, 16);
        Matrix.translateM(tmp, 0, 0, -R - 0.033f, 0);
        draw(base, tmp, 0.55f, 0.59f, 0.61f, 1, 0, 0, 0, 0, NO_CLIP, 0.9f);
    }

    void drawReticle(float[] poseM) {
        draw(reticle, poseM, 0.2f, 1f, 0.6f, 1, 0.1f, 0.6f, 0.3f, 0, NO_CLIP, 0);
    }

    private static final float[] JOINT_R = {0.021f, 0.015f, 0.0125f, 0.011f, 0.0095f, 0.0115f, 0.0105f, 0.0095f, 0.0085f,
        0.012f, 0.011f, 0.0098f, 0.0088f, 0.0112f, 0.0102f, 0.0092f, 0.0084f, 0.0102f, 0.0092f, 0.0084f, 0.0078f};
    private static final int[][] LINKS = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8}, {5, 9}, {9, 10}, {10, 11}, {11, 12},
        {9, 13}, {13, 14}, {14, 15}, {15, 16}, {13, 17}, {17, 18}, {18, 19}, {19, 20}, {0, 17}};

    /**
     * Cartoon hand from 21 WORLD points. occluder = depth only (hides the swirl where my real hand is
     * still in front of the portal). clip = world plane; only the positive side is drawn.
     */
    void drawHand(float[] p, float inflate, boolean occluder, float[] clip) {
        if (occluder) GLES20.glColorMask(false, false, false, false);
        float sr = 0.96f, sg = 0.77f, sb = 0.63f;
        for (int i = 0; i < 21; i++) {
            float r = JOINT_R[i] * inflate;
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
            Matrix.scaleM(tmp, 0, r, r, r);
            draw(sphere, tmp, sr, sg, sb, 1, 0, 0, 0, 0, clip, 0.25f);
        }
        for (int[] l : LINKS) {
            float r = Math.min(JOINT_R[l[0]], JOINT_R[l[1]]) * inflate;
            if (segment(p, l[0], l[1], r, r)) draw(cyl, tmp, sr, sg, sb, 1, 0, 0, 0, 0, clip, 0.25f);
        }
        // palm: flattened ellipsoid spanning wrist and knuckles
        float[] a1 = sub(p, 9, 0), a2 = sub(p, 5, 17);
        float l1 = len(a1), l2 = len(a2);
        if (l1 > 1e-4f && l2 > 1e-4f) {
            norm(a1);
            float d = dot(a2, a1);
            a2[0] -= a1[0] * d; a2[1] -= a1[1] * d; a2[2] -= a1[2] * d;
            norm(a2);
            float[] a3 = cross(a1, a2);
            float cx = 0, cy = 0, cz = 0;
            int[] ids = {0, 5, 9, 13, 17, 9};
            for (int id : ids) { cx += p[id * 3]; cy += p[id * 3 + 1]; cz += p[id * 3 + 2]; }
            cx /= ids.length; cy /= ids.length; cz /= ids.length;
            float hl = l1 * 0.58f, hw = l2 * 0.62f + 0.008f, ht = 0.0135f * inflate;
            hw *= inflate; hl *= (1 + (inflate - 1) * 0.5f);
            float[] m = {a2[0] * hw, a2[1] * hw, a2[2] * hw, 0, a1[0] * hl, a1[1] * hl, a1[2] * hl, 0, a3[0] * ht, a3[1] * ht, a3[2] * ht, 0, cx, cy, cz, 1};
            draw(sphere, m, sr, sg, sb, 1, 0, 0, 0, 0, clip, 0.25f);
        }
        if (!occluder) {
            // white cuff at the wrist
            float[] d = sub(p, 9, 0);
            if (len(d) > 1e-4f) {
                norm(d);
                float[] w0 = {p[0] - d[0] * 0.03f, p[1] - d[1] * 0.03f, p[2] - d[2] * 0.03f};
                axisMatrix(w0, d, 0.034f * inflate, 0.045f, 0.034f * inflate);
                draw(solidCyl, tmp, 0.97f, 0.97f, 0.97f, 1, 0, 0, 0, 0, clip, 0.2f);
            }
        }
        if (occluder) GLES20.glColorMask(true, true, true, true);
    }

    /** Fills tmp with a cylinder transform from point a to point b. */
    private boolean segment(float[] p, int a, int b, float r, float r2) {
        float[] d = sub(p, b, a);
        float L = len(d);
        if (L < 1e-5f) return false;
        d[0] /= L; d[1] /= L; d[2] /= L;
        float[] mid = {(p[a * 3] + p[b * 3]) / 2, (p[a * 3 + 1] + p[b * 3 + 1]) / 2, (p[a * 3 + 2] + p[b * 3 + 2]) / 2};
        axisMatrix(mid, d, r, L, r2);
        return true;
    }

    /** tmp = translate(c) * rotate(Y -> dir) * scale(sx, sy, sz). */
    private void axisMatrix(float[] c, float[] dir, float sx, float sy, float sz) {
        Matrix.setIdentityM(tmp, 0);
        Matrix.translateM(tmp, 0, c[0], c[1], c[2]);
        // axis = Y x dir, angle = acos(Y . dir)
        float ax = dir[2], ay = 0, az = -dir[0];
        float s = (float) Math.sqrt(ax * ax + az * az);
        float ang = (float) Math.toDegrees(Math.atan2(s, dir[1]));
        if (s > 1e-6f) Matrix.rotateM(tmp, 0, ang, ax / s, ay, az / s);
        else if (dir[1] < 0) Matrix.rotateM(tmp, 0, 180, 1, 0, 0);
        Matrix.scaleM(tmp, 0, sx, sy, sz);
    }

    private static float[] sub(float[] p, int a, int b) { return new float[]{p[a * 3] - p[b * 3], p[a * 3 + 1] - p[b * 3 + 1], p[a * 3 + 2] - p[b * 3 + 2]}; }
    private static float len(float[] v) { return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }
    private static void norm(float[] v) { float l = len(v); if (l > 0) { v[0] /= l; v[1] /= l; v[2] /= l; } }
    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static float[] cross(float[] a, float[] b) { return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }

    private static FloatBuffer fbuf(float[] a) {
        FloatBuffer f = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        f.put(a).position(0);
        return f;
    }

    private static int program(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
