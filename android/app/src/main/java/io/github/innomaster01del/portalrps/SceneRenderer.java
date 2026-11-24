package io.github.innomaster01del.portalrps;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/** All OpenGL drawing: camera background, planes, portal, hands. GL thread only. */
final class SceneRenderer {
    static final float R = 0.15f;             // portal radius (m)
    static final float PORTAL_H = R + 0.06f;  // ring centre above the surface (it hovers)

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
        "varying vec3 vN; varying vec3 vW; varying vec2 vUV; varying float vDepth;" +
        "void main(){ vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = (uModel * vec4(aNormal, 0.0)).xyz; vUV = aUV;" +
        " gl_Position = uMVP * vec4(aPos, 1.0); vDepth = gl_Position.w; }";
    private static final String FS =
        "precision mediump float;" +
        "uniform vec4 uColor; uniform vec3 uEmissive; uniform vec4 uClip; uniform int uMode; uniform float uTime;" +
        "uniform float uActive; uniform float uSpec; uniform vec3 uLight; uniform vec3 uCam; uniform float uSwallow;" +
        "uniform vec2 uEntry; uniform float uRipple;" +
        "varying vec3 vN; varying vec3 vW; varying vec2 vUV; varying float vDepth;" +
        "uniform sampler2D uDepthTex; uniform float uUseDepth; uniform vec2 uViewport; uniform vec2 uUvA; uniform vec2 uUvB; uniform vec2 uUvC;" +
        "float occlusion(float vd){" +                           // 1 = a real object is in front of this pixel
        " if (uUseDepth < 0.5) return 0.0;" +
        " vec2 ndc = gl_FragCoord.xy / uViewport * 2.0 - 1.0; vec2 uv = uUvA + uUvB * ndc.x + uUvC * ndc.y;" +
        " vec4 d = texture2D(uDepthTex, uv); float real = (d.r * 255.0 + d.a * 65280.0) / 1000.0;" +
        " if (real < 0.05) return 0.0;" +
        " return smoothstep(-0.015, 0.035, vd - real); }" +
        "void main(){" +
        " if (dot(vec4(vW, 1.0), uClip) < 0.0) discard;" +
        " float occ = occlusion(vDepth);" +
        " if (uMode == 1) {" +                                   // semi-transparent vortex
        "  vec2 p = vUV * 2.0 - 1.0; float r = length(p); float a = atan(p.y, p.x); float lr = log(r + 0.03);" +
        "  float de = length(p - uEntry);" +
        "  float wave = 0.0; if (uRipple > 0.0) { float rr = uRipple * 2.2; wave = (1.0 - smoothstep(0.0, 0.16, abs(de - rr))) * (1.0 - uRipple); }" +
        "  lr += wave * 0.7;" +
        "  float arms = smoothstep(0.15, 1.0, sin(a * 3.0 + lr * 9.0 - uTime * 3.0)) * (0.35 + 0.65 * r);" +
        "  float fine = sin(a * 11.0 + lr * 22.0 - uTime * 5.5) * 0.5 + 0.5;" +
        "  arms *= 0.7 + 0.3 * fine;" +
        "  float core = pow(max(1.0 - r, 0.0), 5.0);" +
        "  float edge = smoothstep(0.8, 1.0, r);" +
        "  vec3 c = mix(vec3(0.0, 0.07, 0.05), vec3(0.35, 1.0, 0.65), arms);" +
        "  c += vec3(0.7, 1.0, 0.85) * core * 0.7 + vec3(0.25, 1.0, 0.6) * edge * (0.7 + 0.6 * uActive);" +
        "  float alpha = (0.2 + 0.55 * arms + 0.45 * edge + 0.35 * core + 0.1 * uActive) * uColor.a;" +
        "  float front = uSwallow * 2.5;" +                    // solid area grows out from where the hand went in
        "  float solid = 1.0 - smoothstep(front - 0.14, front, de);" +
        "  float rim = (1.0 - smoothstep(0.0, 0.1, abs(de - front))) * step(0.02, uSwallow) * (1.0 - smoothstep(1.7, 2.5, front));" +
        "  c = mix(c, c * 0.85 + vec3(0.0, 0.05, 0.03), solid);" +
        "  c += vec3(0.65, 1.0, 0.82) * (rim * 1.3 + wave * 1.1);" +
        "  alpha = mix(clamp(alpha, 0.0, 0.9) * (1.0 - occ), 1.0, solid);" +
        "  alpha = max(alpha, max(rim, wave * 0.8));" +
        "  gl_FragColor = vec4(c, alpha); return; }" +
        " if (uMode == 2) {" +                                   // soft glow / shadow disc
        "  float r = length(vUV * 2.0 - 1.0); float f = 1.0 - smoothstep(0.0, 1.0, r); f *= f;" +
        "  f *= 1.0 - occ; gl_FragColor = vec4(uColor.rgb * f, uColor.a * f); return; }" +
        " if (uMode == 5) {" +
        "  float w = sin(dot(vW, vec3(55.0, 42.0, 37.0)) - uTime * 7.0) * 0.5 + 0.5;" +
        "  float w2 = sin(dot(vW, vec3(-31.0, 66.0, 21.0)) + uTime * 4.0) * 0.5 + 0.5;" +
        "  vec3 c = mix(vec3(0.02, 0.18, 0.11), vec3(0.4, 1.0, 0.68), w * w2);" +
        "  gl_FragColor = vec4(c, uColor.a); return; }" +
        " if (occ > 0.5) discard;" +
        " if (uMode == 3) {" +                                   // energy ring: pulses running around
        "  float u = vUV.x; float k = pow(0.5 + 0.5 * sin(u * 37.7 - uTime * 6.0), 6.0) + pow(0.5 + 0.5 * sin(u * 18.85 + uTime * 3.0), 10.0);" +
        "  vec3 e = uEmissive * (0.55 + 1.2 * k);" +
        "  gl_FragColor = vec4(e, uColor.a); return; }" +
        " if (uMode == 4) {" +                                   // detected surface: dots
        "  vec2 g = fract(vW.xz * 22.0) - 0.5; float d = length(g);" +
        "  gl_FragColor = vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(0.08, 0.14, d)) * uColor.a * (1.0 - occ)); return; }" +
        " if (!gl_FrontFacing && dot(uClip.xyz, uClip.xyz) > 0.5) {" +   // the cut at the portal face glows
        "  gl_FragColor = vec4(vec3(0.35, 1.0, 0.65) * (0.8 + 0.2 * sin(uTime * 6.0)), 1.0); return; }" +
        " vec3 n = normalize(vN); if (!gl_FrontFacing) n = -n;" +
        " vec3 v = normalize(uCam - vW);" +
        " float wrap = max((dot(n, uLight) + 0.35) / 1.35, 0.0);" +           // soft wrap lighting (skin)
        " vec3 h = normalize(uLight + v);" +
        " float spec = pow(max(dot(n, h), 0.0), 48.0) * uSpec;" +
        " float rim = pow(1.0 - max(dot(n, v), 0.0), 2.5);" +
        " vec3 col = uColor.rgb * (0.38 + 0.7 * wrap) + vec3(spec) + uEmissive;" +
        " col += rim * (uSpec * 0.35 + 0.12) * vec3(0.55, 1.0, 0.75) * uActive;" +   // portal-green rim near the portal
        " col += rim * 0.08;" +
        " gl_FragColor = vec4(col, uColor.a);" +
        "}";

    private static final String P_VS =
        "uniform mat4 uMVP; uniform float uTime; uniform float uSize; uniform float uR; uniform float uMaxSize; uniform float uBurst;" +
        "attribute vec3 aSeed; varying float vA; varying float vDepth;" +
        "void main(){" +
        " float life = fract(uTime * 0.33 + aSeed.x * 0.159);" +
        " float a = aSeed.x + uTime * aSeed.z;" +
        " float inward = step(0.62, fract(aSeed.x * 3.7));" +
        " float r = (uR + aSeed.y) * mix(1.0, 0.15 + 0.85 * (1.0 - life), inward);" +
        " r *= 1.0 + uBurst * (0.25 + 0.45 * fract(aSeed.x * 9.1));" +
        " vec3 p = vec3(cos(a) * r, sin(a) * r, (fract(aSeed.x * 7.3) - 0.5) * 0.03 * (1.0 - inward) + uBurst * 0.06 * fract(aSeed.x * 4.3));" +
        " gl_Position = uMVP * vec4(p, 1.0);" +
        " gl_PointSize = min(uSize * (0.5 + fract(aSeed.x * 5.1)) / max(gl_Position.w, 0.05), uMaxSize);" +
        " vA = sin(life * 3.14159) * (1.0 + uBurst * 2.0); vDepth = gl_Position.w;" +
        "}";
    private static final String P_FS =
        "precision mediump float; uniform vec3 uColor; uniform float uAlpha; varying float vA; varying float vDepth;" +
        "uniform sampler2D uDepthTex; uniform float uUseDepth; uniform vec2 uViewport; uniform vec2 uUvA; uniform vec2 uUvB; uniform vec2 uUvC;" +
        "float occlusion(float vd){" +                           // 1 = a real object is in front of this pixel
        " if (uUseDepth < 0.5) return 0.0;" +
        " vec2 ndc = gl_FragCoord.xy / uViewport * 2.0 - 1.0; vec2 uv = uUvA + uUvB * ndc.x + uUvC * ndc.y;" +
        " vec4 d = texture2D(uDepthTex, uv); float real = (d.r * 255.0 + d.a * 65280.0) / 1000.0;" +
        " if (real < 0.05) return 0.0;" +
        " return smoothstep(-0.015, 0.035, vd - real); }" +
        "void main(){ float d = length(gl_PointCoord - 0.5); float a = (1.0 - smoothstep(0.0, 0.5, d)) * vA * uAlpha * (1.0 - occlusion(vDepth));" +
        " gl_FragColor = vec4(uColor * a, a); }";

    private int bgTex, bgProg, bgPos, bgUV, bgSampler;
    private FloatBuffer quad, quadUV;
    private int prog, aPos, aNormal, aUV, uMVP, uModel, uColor, uEmissive, uClip, uMode, uTime, uActive, uSpec, uLight, uCam;
    private int pProg, pSeed, pMVP, pTime, pSize, pR, pColor, pAlpha, pVbo, pCount;
    private int depthTex, depthW, depthH;
    private boolean depthOk;
    private final float[] uvA = new float[2], uvB = new float[2], uvC = new float[2];
    private java.nio.ByteBuffer depthBuf;
    private float vpW = 1, vpH = 1;
    private Mesh sphere, ring, ringGlow, disc, reticle, glowDisc, notch, flashRing;
    private final HandMesh remoteMesh = new HandMesh(true), occMesh = new HandMesh(false), energyMesh = new HandMesh(false);
    private final float[] view = new float[16], proj = new float[16], vp = new float[16], mvp = new float[16];
    private final float[] tmp = new float[16], tmp2 = new float[16], ident = new float[16];
    private final float[] cam = new float[3];
    private float time, active, pointScale = 1;
    private FloatBuffer planeBuf = ByteBuffer.allocateDirect(4 * 3 * 512).order(ByteOrder.nativeOrder()).asFloatBuffer();
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

        pProg = program(P_VS, P_FS);
        pSeed = GLES20.glGetAttribLocation(pProg, "aSeed");
        pMVP = GLES20.glGetUniformLocation(pProg, "uMVP");
        pTime = GLES20.glGetUniformLocation(pProg, "uTime");
        pSize = GLES20.glGetUniformLocation(pProg, "uSize");
        pR = GLES20.glGetUniformLocation(pProg, "uR");
        pColor = GLES20.glGetUniformLocation(pProg, "uColor");
        pAlpha = GLES20.glGetUniformLocation(pProg, "uAlpha");
        Random rnd = new Random(7);
        pCount = 90;
        float[] seeds = new float[pCount * 3];
        for (int i = 0; i < pCount; i++) {
            seeds[i * 3] = (float) (rnd.nextFloat() * Math.PI * 2);
            seeds[i * 3 + 1] = (rnd.nextFloat() - 0.4f) * 0.035f;
            seeds[i * 3 + 2] = (0.4f + rnd.nextFloat() * 1.1f) * (rnd.nextBoolean() ? 1 : 0.7f);
        }
        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        pVbo = b[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, seeds.length * 4, fbuf(seeds), GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        sphere = Mesh.sphere(16, 20);
        ring = Mesh.torus(R, 0.011f, 96, 16);
        ringGlow = Mesh.torus(R - 0.012f, 0.0042f, 96, 8);
        flashRing = Mesh.torus(R, 0.006f, 64, 6);
        disc = Mesh.disc(R - 0.004f, 80);
        reticle = Mesh.flatRing(0.06f, 0.072f, 48);
        glowDisc = Mesh.disc(1f, 40);
        notch = Mesh.box(0.007f, 0.016f, 0.009f);
        Matrix.setIdentityM(ident, 0);
        int[] dt = new int[1];
        GLES20.glGenTextures(1, dt, 0);
        depthTex = dt[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex);
        // NEAREST: the two bytes of a 16-bit depth value must never be blended with neighbours
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return bgTex;
    }

    void setViewportHeight(int h) { pointScale = h / 1000f; }
    void setViewportSize(int w, int h) { vpW = w; vpH = h; pointScale = h / 1000f; }

    /** Screen NDC -> depth-image UV, as uv = A + B * ndc.x + C * ndc.y. */
    void setDepthUvTransform(float[] a, float[] b, float[] c) {
        uvA[0] = a[0]; uvA[1] = a[1]; uvB[0] = b[0]; uvB[1] = b[1]; uvC[0] = c[0]; uvC[1] = c[1];
    }

    /** Uploads ARCore's 16-bit depth image (millimetres). Pass null when there is none this frame. */
    void updateDepth(android.media.Image img) {
        if (img == null) return;
        android.media.Image.Plane pl = img.getPlanes()[0];
        int w = img.getWidth(), h = img.getHeight(), row = pl.getRowStride();
        java.nio.ByteBuffer src = pl.getBuffer();
        if (depthBuf == null || depthBuf.capacity() < w * h * 2) depthBuf = java.nio.ByteBuffer.allocateDirect(w * h * 2).order(java.nio.ByteOrder.nativeOrder());
        depthBuf.clear();
        if (row == w * 2) { src.rewind(); src.limit(Math.min(src.capacity(), w * h * 2)); depthBuf.put(src); }
        else for (int y = 0; y < h; y++) { src.limit(y * row + w * 2); src.position(y * row); depthBuf.put(src); }
        depthBuf.flip();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2);
        if (w != depthW || h != depthH) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, depthBuf);
            depthW = w; depthH = h;
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, depthBuf);
        }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        depthOk = true;
    }

    void setDepthAvailable(boolean ok) { if (!ok) depthOk = false; }

    private boolean occlusionOn = true;
    private float swallow;

    /** Off while the player's hand is in the portal: then the portal must hide it, not the other way round. */
    void setOcclusion(boolean on) {
        occlusionOn = on;
        GLES20.glUseProgram(prog);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uUseDepth"), depthOk && on ? 1f : 0f);
    }

    private float burst;

    /** Entry effect: ex, ey = where the hand went in (portal disc, -1..1); ripple 0..1 progress, 0 = none. */
    void setEntry(float ex, float ey, float ripple) {
        burst = ripple > 0 ? (1 - ripple) * (1 - ripple) : 0;
        GLES20.glUseProgram(prog);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uEntry"), ex, ey);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRipple"), ripple);
    }

    void setSwallow(float s) {
        swallow = s;
        GLES20.glUseProgram(prog);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSwallow"), s);
    }

    /** My tracked hand drawn as portal energy, covering the real hand on screen. */
    void drawEnergyHand(float[] worldPts, float alpha) {
        energyMesh.update(worldPts, 1.3f);
        energyMesh.upload();
        blend(true, false);
        setCommon(ident, 1, 1, 1, alpha, 0, 0, 0, 5, NO_CLIP, 0);
        energyMesh.draw(aPos, aNormal, aUV, 0, energyMesh.skinIdxCount);
        blend(false, false);
    }

    /** Lets the opponent's hand draw over everything already on screen. */
    void clearDepth() { GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT); }

    private void bindOcclusion(int program) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDepthTex"), 1);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uUseDepth"), depthOk && occlusionOn ? 1f : 0f);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uViewport"), vpW, vpH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvA"), uvA[0], uvA[1]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvB"), uvB[0], uvB[1]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvC"), uvC[0], uvC[1]);
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
        useMain();
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    private void useMain() {
        GLES20.glUseProgram(prog);
        float lx = 0.3f, ly = 1f, lz = 0.55f, l = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        GLES20.glUniform3f(uLight, lx / l, ly / l, lz / l);
        GLES20.glUniform3f(uCam, cam[0], cam[1], cam[2]);
        GLES20.glUniform1f(uTime, time);
        GLES20.glUniform1f(uActive, active);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSwallow"), swallow);
        bindOcclusion(prog);
    }

    private void setCommon(float[] model, float r, float g, float b, float a, float er, float eg, float eb, int mode, float[] clip, float spec) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform4f(uColor, r, g, b, a);
        GLES20.glUniform3f(uEmissive, er, eg, eb);
        GLES20.glUniform4f(uClip, clip[0], clip[1], clip[2], clip[3]);
        GLES20.glUniform1i(uMode, mode);
        GLES20.glUniform1f(uSpec, spec);
    }

    private void draw(Mesh m, float[] model, float r, float g, float b, float a, float er, float eg, float eb, int mode, float[] clip, float spec) {
        setCommon(model, r, g, b, a, er, eg, eb, mode, clip, spec);
        m.draw(aPos, aNormal, aUV);
    }

    private void blend(boolean on, boolean additive) {
        if (on) {
            GLES20.glEnable(GLES20.GL_BLEND);
            if (additive) GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            else GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDepthMask(false);
        } else {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDepthMask(true);
        }
    }

    /* ------------------------------ surfaces + placement ------------------------------ */

    /** Dots on a detected surface. poly: x,z pairs in plane space; centerM: plane centre pose matrix. */
    void drawPlane(float[] centerM, FloatBuffer poly) {
        int n = poly.limit() / 2;
        if (n < 3 || n > 500) return;
        planeBuf.clear();
        for (int i = 0; i < n; i++) { planeBuf.put(poly.get(i * 2)); planeBuf.put(0); planeBuf.put(poly.get(i * 2 + 1)); }
        planeBuf.position(0);
        blend(true, false);
        setCommon(centerM, 1, 1, 1, 0.55f, 0, 0, 0, 4, NO_CLIP, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, planeBuf);
        GLES20.glDisableVertexAttribArray(aNormal);
        GLES20.glVertexAttrib3f(aNormal, 0, 1, 0);
        GLES20.glDisableVertexAttribArray(aUV);
        GLES20.glVertexAttrib2f(aUV, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, n);
        GLES20.glDisableVertexAttribArray(aPos);
        blend(false, false);
    }

    void drawReticle(float[] poseM) {
        Matrix.translateM(tmp, 0, poseM, 0, 0, 0.003f, 0);
        draw(reticle, tmp, 0.2f, 1f, 0.6f, 1, 0.15f, 0.9f, 0.45f, 0, NO_CLIP, 0);
    }

    /** Semi-transparent preview of the portal where a tap will place it. */
    void drawGhost(float[] portalM, float spin) {
        float s = 1 + 0.02f * (float) Math.sin(time * 3);
        blend(true, false);
        Matrix.rotateM(tmp2, 0, portalM, 0, spin, 0, 0, 1);
        Matrix.scaleM(tmp, 0, tmp2, 0, s, s, s);
        draw(ring, tmp, 0.3f, 0.33f, 0.35f, 0.45f, 0, 0, 0, 0, NO_CLIP, 0.8f);
        draw(disc, tmp, 1, 1, 1, 0.3f, 0, 0, 0, 1, NO_CLIP, 0);
        blend(true, true);
        draw(ringGlow, tmp, 0, 0, 0, 1, 0.05f, 0.45f, 0.22f, 3, NO_CLIP, 0);
        drawParticles(tmp, 0.35f);
        blend(false, false);
        useMain();
    }

    /* ------------------------------ portal ------------------------------ */

    /**
     * open: 0 = closed seed, 1 = fully open. spin: ring rotation (degrees). flash: 0..1 opening shockwave.
     * floorM: matrix on the surface below (null when floating in the air).
     */
    void drawPortalOpaque(float[] portalM, float open, float spin) {
        if (open < 0.02f) {
            float pulse = 1 + 0.18f * (float) Math.sin(time * 5);
            Matrix.scaleM(tmp, 0, portalM, 0, 0.016f * pulse, 0.016f * pulse, 0.016f * pulse);
            draw(sphere, tmp, 0.1f, 0.5f, 0.3f, 1, 0.3f, 1.3f, 0.7f, 0, NO_CLIP, 0);
            return;
        }
        // metal ring with running energy, rotating in its plane
        Matrix.rotateM(tmp2, 0, portalM, 0, spin, 0, 0, 1);
        Matrix.scaleM(tmp, 0, tmp2, 0, open, open, open);
        draw(ring, tmp, 0.18f, 0.2f, 0.21f, 1, 0, 0, 0, 0, NO_CLIP, 1.0f);
        float g = 0.9f + active * 0.9f;
        draw(ringGlow, tmp, 0, 0, 0, 1, 0.12f * g, 1.0f * g, 0.5f * g, 3, NO_CLIP, 0);
        // little runes around the ring turning the other way
        for (int i = 0; i < 8; i++) {
            Matrix.rotateM(tmp2, 0, portalM, 0, -spin * 0.6f + i * 45f, 0, 0, 1);
            Matrix.scaleM(tmp2, 0, open, open, open);
            Matrix.translateM(tmp, 0, tmp2, 0, R + 0.013f, 0, 0);
            draw(notch, tmp, 0.25f, 0.27f, 0.28f, 1, 0.02f * g, 0.2f * g, 0.1f * g, 0, NO_CLIP, 0.8f);
        }
    }

    void drawPortalTransparent(float[] portalM, float[] floorM, float open, float spin, float flash) {
        if (floorM != null) {
            blend(true, false);
            Matrix.rotateM(tmp, 0, floorM, 0, -90, 1, 0, 0);
            Matrix.scaleM(tmp, 0, 0.14f, 0.07f, 1);
            Matrix.translateM(tmp, 0, 0, 0, 0.002f);
            draw(glowDisc, tmp, 0, 0, 0, 0.35f, 0, 0, 0, 2, NO_CLIP, 0);
            blend(true, true);
            Matrix.rotateM(tmp, 0, floorM, 0, -90, 1, 0, 0);
            float gs = 0.05f + 0.08f * open, gi = 0.25f + 0.35f * open + 0.25f * active;
            Matrix.scaleM(tmp, 0, gs, gs * 0.55f, 1);
            Matrix.translateM(tmp, 0, 0, 0, 0.003f);
            draw(glowDisc, tmp, 0.12f * gi, 0.8f * gi, 0.45f * gi, 1, 0, 0, 0, 2, NO_CLIP, 0);
        }
        if (open >= 0.02f) {
            blend(true, false);
            Matrix.rotateM(tmp2, 0, portalM, 0, spin * 1.5f, 0, 0, 1);
            Matrix.scaleM(tmp, 0, tmp2, 0, open, open, open);
            draw(disc, tmp, 1, 1, 1, Math.min(1, open * 1.2f), 0, 0, 0, 1, NO_CLIP, 0);
            if (flash > 0) {
                blend(true, true);
                float s = open * (1 + (1 - flash) * 0.9f);
                Matrix.scaleM(tmp, 0, portalM, 0, s, s, s);
                draw(flashRing, tmp, 0, 0, 0, 1, 0.3f * flash, 1.2f * flash, 0.7f * flash, 3, NO_CLIP, 0);
            }
        }
        // particles (also around the closed seed)
        float ps = open < 0.02f ? 0.12f : open;
        Matrix.scaleM(tmp, 0, portalM, 0, ps, ps, ps);
        drawParticles(tmp, open < 0.02f ? 0.8f : 0.6f + 0.6f * active);
        blend(false, false);
        useMain();
    }

    private void drawParticles(float[] model, float alpha) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(pProg);
        GLES20.glUniformMatrix4fv(pMVP, 1, false, mvp, 0);
        GLES20.glUniform1f(pTime, time);
        GLES20.glUniform1f(pSize, 7f * pointScale);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pProg, "uMaxSize"), 11f * pointScale);
        GLES20.glUniform1f(pR, R);
        GLES20.glUniform3f(pColor, 0.45f, 1.0f, 0.7f);
        GLES20.glUniform1f(pAlpha, alpha);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pProg, "uBurst"), burst);
        bindOcclusion(pProg);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pVbo);
        GLES20.glEnableVertexAttribArray(pSeed);
        GLES20.glVertexAttribPointer(pSeed, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pCount);
        GLES20.glDisableVertexAttribArray(pSeed);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    /* ------------------------------ hands ------------------------------ */

    /** Opponent's hand: smooth skin + white sleeve, clipped at the portal face. */
    void drawHand(float[] worldPts, float[] clip) {
        remoteMesh.update(worldPts, 1f);
        remoteMesh.upload();
        setCommon(ident, 0.95f, 0.74f, 0.6f, 1, 0.03f, 0.012f, 0.008f, 0, clip, 0.22f);
        remoteMesh.draw(aPos, aNormal, aUV, 0, remoteMesh.skinIdxCount);
        setCommon(ident, 0.93f, 0.95f, 0.96f, 1, 0, 0, 0, 0, clip, 0.15f);
        remoteMesh.draw(aPos, aNormal, aUV, remoteMesh.sleeveIdxStart, remoteMesh.sleeveIdxCount);
    }

    /** My hand, depth only: the swirl is not drawn over the part of my real hand still in front of the portal. */
    void drawOccluder(float[] worldPts, float[] clip) {
        occMesh.update(worldPts, 1.45f);
        occMesh.upload();
        GLES20.glColorMask(false, false, false, false);
        setCommon(ident, 0, 0, 0, 1, 0, 0, 0, 0, clip, 0);
        occMesh.draw(aPos, aNormal, aUV, 0, occMesh.skinIdxCount);
        GLES20.glColorMask(true, true, true, true);
    }

    /* ------------------------------ GL helpers ------------------------------ */

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
