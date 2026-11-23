package io.github.innomaster01del.portalrps;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;

import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Landmark;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds the player's hand in ARCore's CPU camera image (MediaPipe, on a worker thread) and places its
 * 21 joints in AR world space. Results arrive through {@link Listener} on the worker thread.
 */
final class HandTracker {
    interface Listener {
        /** worldPts: float[63] in ARCore world metres, or null when no hand. distance in metres. */
        void onHand(float[] worldPts, float distance);
        /** Optional: the picture the detector saw, with the found joints drawn on it. */
        default void onDebugImage(Bitmap b) {}
    }

    volatile boolean debug;

    private static final String TAG = "HandTracker";
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Listener listener;
    private final int rotation;            // degrees clockwise to turn the sensor image upright
    private volatile HandLandmarker landmarker;
    volatile String error;

    // plane copies (reused)
    private byte[] yB, uB, vB;
    private int[] argb;

    HandTracker(Context ctx, int sensorRotationDeg, Listener l) {
        this.listener = l;
        this.rotation = ((sensorRotationDeg % 360) + 360) % 360;
        Context app = ctx.getApplicationContext();
        exec.execute(() -> {
            try {
                HandLandmarker.HandLandmarkerOptions opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.35f)
                    .setMinHandPresenceConfidence(0.35f)
                    .setMinTrackingConfidence(0.35f)
                    .build();
                landmarker = HandLandmarker.createFromOptions(app, opts);
            } catch (Throwable t) {
                Log.e(TAG, "init failed", t);
                error = String.valueOf(t.getMessage());
            }
        });
    }

    boolean ready() { return landmarker != null; }

    /** GL thread: grab the current camera image if the worker is free. */
    void submit(Frame frame, Camera camera) {
        if (landmarker == null || busy.get()) return;
        Image img;
        try { img = frame.acquireCameraImage(); } catch (Exception e) { return; }
        final int w, h, yRow, uvRow, uvPix;
        final Pose pose;
        final float fx, fy, cx, cy, iw, ih;
        try {
            w = img.getWidth(); h = img.getHeight();
            Image.Plane[] pl = img.getPlanes();
            yRow = pl[0].getRowStride(); uvRow = pl[1].getRowStride(); uvPix = pl[1].getPixelStride();
            yB = copy(pl[0].getBuffer(), yB);
            uB = copy(pl[1].getBuffer(), uB);
            vB = copy(pl[2].getBuffer(), vB);
            CameraIntrinsics in = camera.getImageIntrinsics();
            float[] f = in.getFocalLength(), c = in.getPrincipalPoint();
            int[] d = in.getImageDimensions();
            fx = f[0]; fy = f[1]; cx = c[0]; cy = c[1]; iw = d[0]; ih = d[1];
            pose = camera.getPose();
        } finally {
            img.close();
        }
        busy.set(true);
        final byte[] Y = yB, U = uB, V = vB;
        exec.execute(() -> {
            try { process(Y, U, V, w, h, yRow, uvRow, uvPix, fx, fy, cx, cy, iw, ih, pose); }
            catch (Throwable t) { Log.w(TAG, "process", t); }
            finally { busy.set(false); }
        });
    }

    private static byte[] copy(ByteBuffer b, byte[] dst) {
        b.rewind();
        int n = b.remaining();
        if (dst == null || dst.length != n) dst = new byte[n];
        b.get(dst);
        return dst;
    }

    private void process(byte[] Y, byte[] U, byte[] V, int w, int h, int yRow, int uvRow, int uvPix,
                         float fx, float fy, float cx, float cy, float iw, float ih, Pose pose) {
        // full-resolution RGB, turned upright so the hand detector sees a normal picture
        int sw = w, sh = h;
        boolean swap = rotation == 90 || rotation == 270;
        int ow = swap ? sh : sw, oh = swap ? sw : sh;
        if (argb == null || argb.length != ow * oh) argb = new int[ow * oh];
        for (int sy = 0; sy < sh; sy++) {
            int fyy = sy;
            for (int sx = 0; sx < sw; sx++) {
                int fxx = sx;
                int yy = Y[fyy * yRow + fxx] & 0xff;
                int uvIdx = (fyy / 2) * uvRow + (fxx / 2) * uvPix;
                int uu = (U[Math.min(uvIdx, U.length - 1)] & 0xff) - 128;
                int vv = (V[Math.min(uvIdx, V.length - 1)] & 0xff) - 128;
                int r = clamp(yy + (int) (1.402f * vv));
                int g = clamp(yy - (int) (0.344f * uu + 0.714f * vv));
                int b = clamp(yy + (int) (1.772f * uu));
                int ox, oy;
                switch (rotation) {
                    case 90:  ox = sh - 1 - sy; oy = sx; break;
                    case 180: ox = sw - 1 - sx; oy = sh - 1 - sy; break;
                    case 270: ox = sy; oy = sw - 1 - sx; break;
                    default:  ox = sx; oy = sy;
                }
                argb[oy * ow + ox] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(argb, ow, oh, Bitmap.Config.ARGB_8888);
        MPImage mp = new BitmapImageBuilder(bmp).build();
        HandLandmarkerResult res = landmarker.detectForVideo(mp, SystemClock.uptimeMillis());
        boolean found = !res.landmarks().isEmpty() && !res.worldLandmarks().isEmpty();
        if (debug) sendDebug(bmp, found ? res.landmarks().get(0) : null);
        bmp.recycle();
        if (!found) { listener.onHand(null, 0); return; }
        List<NormalizedLandmark> lm = res.landmarks().get(0);
        List<Landmark> wl = res.worldLandmarks().get(0);
        float[] nx = new float[21], ny = new float[21], wp = new float[63];
        for (int i = 0; i < 21; i++) {
            float xu = lm.get(i).x(), yu = lm.get(i).y();          // upright, normalised
            float wx = wl.get(i).x(), wy = wl.get(i).y(), wz = wl.get(i).z();
            float u, v, wu, wv;                                     // back to sensor axes
            switch (rotation) {
                case 90:  u = yu; v = 1 - xu; wu = wy; wv = -wx; break;
                case 180: u = 1 - xu; v = 1 - yu; wu = -wx; wv = -wy; break;
                case 270: u = 1 - yu; v = xu; wu = -wy; wv = wx; break;
                default:  u = xu; v = yu; wu = wx; wv = wy;
            }
            nx[i] = (u * iw - cx) / fx;
            ny[i] = (v * ih - cy) / fy;
            wp[i * 3] = wu; wp[i * 3 + 1] = wv; wp[i * 3 + 2] = wz;
        }
        double[] t = HandMath.solveTranslation(nx, ny, wp);
        if (t == null) { listener.onHand(null, 0); return; }
        float[] out = new float[63];
        float[] pc = new float[3];
        for (int i = 0; i < 21; i++) {
            // image camera (x right, y down, z forward) -> ARCore camera (x right, y up, -z forward)
            pc[0] = (float) (wp[i * 3] + t[0]);
            pc[1] = (float) -(wp[i * 3 + 1] + t[1]);
            pc[2] = (float) -(wp[i * 3 + 2] + t[2]);
            float[] wpt = pose.transformPoint(pc);
            out[i * 3] = wpt[0]; out[i * 3 + 1] = wpt[1]; out[i * 3 + 2] = wpt[2];
        }
        listener.onHand(out, (float) t[2]);
    }

    private long lastDebug;
    private static final int[][] BONES = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8}, {5, 9}, {9, 10}, {10, 11}, {11, 12},
        {9, 13}, {13, 14}, {14, 15}, {15, 16}, {13, 17}, {0, 17}, {17, 18}, {18, 19}, {19, 20}};

    private void sendDebug(Bitmap src, List<NormalizedLandmark> lm) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDebug < 150) return;
        lastDebug = now;
        int dw = 240, dh = Math.round(240f * src.getHeight() / src.getWidth());
        Bitmap b = Bitmap.createScaledBitmap(src, dw, dh, true);
        if (lm != null) {
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            line.setColor(0xFF4DFF9A); line.setStrokeWidth(3);
            for (int[] bn : BONES)
                cv.drawLine(lm.get(bn[0]).x() * dw, lm.get(bn[0]).y() * dh, lm.get(bn[1]).x() * dw, lm.get(bn[1]).y() * dh, line);
            android.graphics.Paint dot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFFFFFFFF);
            for (NormalizedLandmark l : lm) cv.drawCircle(l.x() * dw, l.y() * dh, 3.5f, dot);
        }
        listener.onDebugImage(b);
    }

    private static int clamp(int x) { return x < 0 ? 0 : (x > 255 ? 255 : x); }

    void close() {
        exec.execute(() -> { if (landmarker != null) landmarker.close(); landmarker = null; });
        exec.shutdown();
    }
}
