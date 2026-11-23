package io.github.innomaster01del.portalrps;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static io.github.innomaster01del.portalrps.HandMath.PAPER;
import static io.github.innomaster01del.portalrps.HandMath.ROCK;
import static io.github.innomaster01del.portalrps.HandMath.SCISSORS;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer, HandTracker.Listener, Net.Listener {
    private static final String TAG = "PortalRPS";
    private static final float PORTAL_R = SceneRenderer.R;
    private static final float AIR_DIST = 0.42f;     // in-air placement distance: within reach while holding the phone
    private static final int REQ_CAMERA = 7;
    private static final long BEAT_MS = 700, WINDOW_BEFORE = 150, WINDOW_AFTER = 600, RESULT_MS = 3200, OPEN_MS = 1100;

    // ---------------- views (main thread) ----------------
    private GLSurfaceView glView;
    private TextView statusTv, netChip, handChip, scoreChip, bannerTitle, bannerSub, hintTv, roomCodeTv;
    private View banner, lobby, roomInfo, manualBar;
    private Button moveBtn, openLobbyBtn, mInBtn, gRock, gPaper, gScissors;
    private ImageView debugView;
    private EditText codeIn;
    private CheckBox manualChk, debugChk;
    private Vibrator vibrator;

    // ---------------- AR ----------------
    private Session session;
    private boolean installRequested;
    private final SceneRenderer scene = new SceneRenderer();
    private int bgTex = -1, viewW, viewH;
    private volatile boolean geometryDirty;
    private volatile HandTracker tracker;
    private final ConcurrentLinkedQueue<float[]> taps = new ConcurrentLinkedQueue<>();
    private volatile boolean moveRequested, resetRequested;

    // GL-thread state
    private static final int PLACING = 0, PLACED = 1;
    private volatile int mode = PLACING;
    private Anchor anchor;
    private boolean floating;
    private float yaw;
    private final float[] portalM = new float[16], portalInv = new float[16], floorM = new float[16], reticleM = new float[16], ghostM = new float[16];
    private final float[] viewM = new float[16], projM = new float[16], clip = new float[4], planeM = new float[16];
    private HitResult reticleHit;
    private boolean reticleOk, tracking;
    private final long t0 = SystemClock.uptimeMillis();
    private long lastFrame;
    private float glowActive, spin;

    // portal opening
    private boolean portalOpening, portalOpen;
    private long openStart, palmSince, placedAt;
    private float openAmt, flash;

    // my hand (portal-local)
    private final Object handLock = new Object();
    private float[] pendingWorld; private int pendingSeq, consumedSeq; private float pendingDist;
    private final float[] myLocal = new float[63], tmp4 = new float[4], tmp4b = new float[4];
    private final OneEuro myFilter = new OneEuro(1.2, 12, 1.0);
    private final ArrayDeque<String> gestureHistory = new ArrayDeque<>();
    private boolean myHas, myInside; private long myLast; private String myGesture; private float myDist;
    private long lastSubmit, lastSend; private boolean sentInside;

    // opponent hand (portal-local, my frame)
    private final float[] remTarget = new float[63], remCur = new float[63];
    private final OneEuro remFilter = new OneEuro(1.0, 8, 1.0);
    private long remLast, remShownAt; private boolean remShown;
    private final float[] outF = HandMath.outDirection(), remDraw = new float[63];

    // room
    private volatile Net net;
    private volatile boolean solo, manualPref, brokerUp, lobbyAutoClosed;
    private volatile String netError;
    private long oppSeen, lastHi, joinStarted;
    private final ConcurrentLinkedQueue<JSONObject> inbox = new ConcurrentLinkedQueue<>();

    // game (GL thread)
    private String phase = "idle";
    private int gameId, beatShown = -1, scoreMe, scoreOpp;
    private long countStart, shootAt, waitUntil, resultUntil, cooldownUntil, bothSince;
    private final HashMap<String, Integer> votes = new HashMap<>();
    private String myThrow, oppThrow; private boolean myThrowSet, oppThrowSet;
    private String botGesture = ROCK;
    private final Random rnd = new Random();
    private volatile boolean manualInside; private volatile String manualGesture = ROCK;

    // UI shown (GL thread computes, main thread applies)
    private String uiHint = "";
    private String bTitle = "", bSub = "", bCls = "";
    private boolean uiBanner;
    private String shownKey = "";

    /* =============================== lifecycle =============================== */

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        vibrator = getSystemService(Vibrator.class);
        glView = findViewById(R.id.glView);
        statusTv = findViewById(R.id.status); netChip = findViewById(R.id.netChip); handChip = findViewById(R.id.handChip);
        scoreChip = findViewById(R.id.scoreChip); banner = findViewById(R.id.banner); bannerTitle = findViewById(R.id.bannerTitle);
        bannerSub = findViewById(R.id.bannerSub); hintTv = findViewById(R.id.hint); lobby = findViewById(R.id.lobby);
        roomInfo = findViewById(R.id.roomInfo); roomCodeTv = findViewById(R.id.roomCode); codeIn = findViewById(R.id.codeIn);
        manualChk = findViewById(R.id.manualChk); debugChk = findViewById(R.id.debugChk); manualBar = findViewById(R.id.manualBar);
        moveBtn = findViewById(R.id.moveBtn); openLobbyBtn = findViewById(R.id.openLobbyBtn); debugView = findViewById(R.id.debugView);
        mInBtn = findViewById(R.id.mIn); gRock = findViewById(R.id.gRock); gPaper = findViewById(R.id.gPaper); gScissors = findViewById(R.id.gScissors);

        glView.setPreserveEGLContextOnPause(true);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.setRenderer(this);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { dx = e.getX(); dy = e.getY(); }
                else if (e.getAction() == MotionEvent.ACTION_UP && Math.hypot(e.getX() - dx, e.getY() - dy) < 40) { taps.add(new float[]{e.getX(), e.getY()}); v.performClick(); }
                return true;
            }
        });

        findViewById(R.id.soloBtn).setOnClickListener(v -> startSolo());
        findViewById(R.id.createBtn).setOnClickListener(v -> createRoom());
        findViewById(R.id.joinBtn).setOnClickListener(v -> joinRoom(codeIn.getText().toString()));
        findViewById(R.id.shareBtn).setOnClickListener(v -> shareCode());
        findViewById(R.id.closeLobbyBtn).setOnClickListener(v -> closeLobby());
        openLobbyBtn.setOnClickListener(v -> { lobby.setVisibility(View.VISIBLE); openLobbyBtn.setVisibility(View.GONE); });
        moveBtn.setOnClickListener(v -> moveRequested = true);
        manualChk.setOnCheckedChangeListener((c, on) -> manualPref = on);
        debugChk.setOnCheckedChangeListener((c, on) -> {
            HandTracker tr = tracker;
            if (tr != null) tr.debug = on;
            if (!on) debugView.setVisibility(View.GONE);
        });
        mInBtn.setOnClickListener(v -> manualInside = !manualInside);
        gRock.setOnClickListener(v -> { manualGesture = ROCK; manualInside = true; });
        gPaper.setOnClickListener(v -> { manualGesture = PAPER; manualInside = true; });
        gScissors.setOnClickListener(v -> { manualGesture = SCISSORS; manualInside = true; });
    }

    @Override protected void onResume() {
        super.onResume();
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true;
                    return;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    return;
                }
                session = new Session(this);
                Config cfg = new Config(session);
                cfg.setFocusMode(Config.FocusMode.AUTO);
                cfg.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
                cfg.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                cfg.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
                session.configure(cfg);
                tracker = new HandTracker(this, sensorRotation(), this);
                tracker.debug = debugChk.isChecked();
            } catch (UnavailableDeviceNotCompatibleException e) {
                showStatusNow("הטלפון הזה לא תומך ב-ARCore, אז AR לא יעבוד עליו.");
                return;
            } catch (UnavailableUserDeclinedInstallationException e) {
                showStatusNow("צריך להתקין את Google Play Services for AR כדי לשחק.");
                return;
            } catch (Exception e) {
                Log.e(TAG, "AR init", e);
                showStatusNow("לא הצלחנו להפעיל AR: " + e.getMessage());
                return;
            }
        }
        try { session.resume(); } catch (Exception e) {
            showStatusNow("המצלמה לא זמינה. סגור אפליקציות אחרות שמשתמשות במצלמה ונסה שוב.");
            session = null;
            return;
        }
        glView.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        if (session != null) { glView.onPause(); session.pause(); }
    }

    @Override protected void onDestroy() {
        closeNet();
        if (tracker != null) tracker.close();
        if (session != null) { session.close(); session = null; }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CAMERA && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED))
            showStatusNow("בלי הרשאת מצלמה אי אפשר לשחק ב-AR. אפשר לאשר אותה בהגדרות האפליקציה.");
    }

    private int sensorRotation() {
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            Integer o = cm.getCameraCharacteristics(session.getCameraConfig().getCameraId()).get(CameraCharacteristics.SENSOR_ORIENTATION);
            return o == null ? 90 : o;
        } catch (Exception e) { return 90; }
    }

    private void showStatusNow(String s) { statusTv.setText(s); statusTv.setVisibility(View.VISIBLE); }

    private void closeLobby() { lobby.setVisibility(View.GONE); openLobbyBtn.setVisibility(View.VISIBLE); }

    /* =============================== room / network =============================== */

    private void startSolo() {
        closeNet();
        solo = true;
        roomInfo.setVisibility(View.GONE);
        resetRequested = true;
        closeLobby();
    }

    private void createRoom() {
        solo = false;
        closeNet();
        String code = String.valueOf(1000 + rnd.nextInt(9000));
        roomCodeTv.setText(code);
        roomInfo.setVisibility(View.VISIBLE);
        lobbyAutoClosed = false;
        net = new Net(code, true, this);
        resetRequested = true;
    }

    private void joinRoom(String code) {
        code = code.replaceAll("\\D", "");
        if (code.length() != 4) { showStatusNow("הקלד קוד בן 4 ספרות"); return; }
        solo = false;
        closeNet();
        roomInfo.setVisibility(View.GONE);
        lobbyAutoClosed = true;
        net = new Net(code, false, this);
        resetRequested = true;
        closeLobby();
    }

    private void closeNet() {
        Net n = net;
        net = null;
        if (n != null) n.close();
        brokerUp = false;
        netError = null;
    }

    private void shareCode() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, "בוא נשחק אבן נייר ומספריים דרך הפורטל! פתח את האפליקציה, לחץ \"הצטרף\" והקלד את הקוד: " + roomCodeTv.getText());
        startActivity(Intent.createChooser(i, "שלח לחבר"));
    }

    @Override public void onMessage(JSONObject m) { inbox.add(m); }
    @Override public void onConnState(boolean up, String error) { brokerUp = up; netError = error; }

    private void send(JSONObject m) { Net n = net; if (n != null && !solo) n.send(m); }
    private static JSONObject msg(String t) { JSONObject j = new JSONObject(); try { j.put("t", t); } catch (Exception ignored) {} return j; }

    private void sendHand(float[] local) {
        if (solo || net == null) return;
        JSONObject j = msg("h");
        try {
            if (local == null) j.put("p", JSONObject.NULL);
            else { JSONArray a = new JSONArray(); for (float v : local) a.put(Math.round(v * 1000)); j.put("p", a); }
        } catch (Exception ignored) {}
        send(j);
    }

    private boolean isReferee() { return solo || (net != null && net.host); }
    private boolean opponentPresent(long now) { return solo || (net != null && oppSeen > 0 && now - oppSeen < 3500); }

    private void processInbox(long now) {
        JSONObject m;
        while ((m = inbox.poll()) != null) {
            if (solo || net == null) continue;
            oppSeen = now;
            String t = m.optString("t");
            switch (t) {
                case "h": {
                    JSONArray a = m.optJSONArray("p");
                    if (a == null || a.length() != 63) { remLast = 0; break; }
                    float[] p = new float[63];
                    for (int i = 0; i < 63; i++) p[i] = (float) a.optDouble(i) / 1000f;
                    remFilter.filter(p, System.nanoTime());
                    onRemotePose(p, now);
                    break;
                }
                case "go": if (!net.host) beginCount(m.optInt("id"), now); break;
                case "throw":
                    if (net.host && m.optInt("id") == gameId) { oppThrow = nullable(m, "g"); oppThrowSet = true; }
                    break;
                case "result":
                    if (!net.host) {
                        JSONArray s = m.optJSONArray("score");
                        if (s != null) { scoreMe = s.optInt(1); scoreOpp = s.optInt(0); }
                        showResult(nullable(m, "g"), nullable(m, "h"), now);
                    }
                    break;
                default: break;   // "hi"
            }
        }
    }

    private static String nullable(JSONObject m, String k) { return m.isNull(k) ? null : m.optString(k, null); }

    /* =============================== GL renderer =============================== */

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0, 0, 0, 1);
        bgTex = scene.create();
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        viewW = w; viewH = h; geometryDirty = true;
        scene.setViewportHeight(h);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null || bgTex < 0) return;
        if (geometryDirty) { s.setDisplayGeometry(Surface.ROTATION_0, viewW, viewH); geometryDirty = false; }
        s.setCameraTextureName(bgTex);
        Frame frame;
        try { frame = s.update(); } catch (Exception e) { return; }
        Camera camera = frame.getCamera();
        scene.drawBackground(frame);

        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min((now - lastFrame) / 1000f, 0.1f);
        lastFrame = now;
        float t = (now - t0) / 1000f;
        if (resetRequested) { resetRequested = false; resetGame(); }
        if (net != null && !solo) {
            if (now - lastHi > 1000) { lastHi = now; send(msg("hi")); }
            if (joinStarted == 0) joinStarted = now;
        } else joinStarted = 0;
        processInbox(now);

        tracking = camera.getTrackingState() == TrackingState.TRACKING;
        if (moveRequested) {
            moveRequested = false;
            if (anchor != null) { anchor.detach(); anchor = null; }
            mode = PLACING;
            portalOpen = false; portalOpening = false; openAmt = 0;
            if (sentInside) { sendHand(null); sentInside = false; }
        }
        float[] camPos = camera.getPose().getTranslation();
        if (tracking) {
            camera.getProjectionMatrix(projM, 0, 0.01f, 30f);
            camera.getViewMatrix(viewM, 0);
            if (mode == PLACING) updatePlacement(frame, camera, now);
            else handlePlacedTaps(now);
            if (anchor != null) computePortal(anchor.getPose(), t);
            HandTracker tr = tracker;
            if (mode == PLACED && tr != null && tr.ready() && !manualActive() && now - lastSubmit > 35) { lastSubmit = now; tr.submit(frame, camera); }
        } else taps.clear();

        consumeHand(now);
        stepPortal(now, dt);
        if (solo && portalOpen) onRemotePose(HandMath.poseHand(shownGesture(botGesture), pumpY(now)), now);
        stepRemote(dt, now);
        stepGame(now);

        if (tracking) {
            float target = (remShown || myInside) ? 1 : 0;
            glowActive += (target - glowActive) * Math.min(1, dt * 6);
            scene.begin(viewM, projM, camPos, t, glowActive);
            if (mode == PLACED) {
                portalClip();
                if (portalOpen && myHas && now - myLast < 300 && !manualActive()) scene.drawOccluder(toWorld(myLocal), clip);
                scene.drawPortalOpaque(portalM, openAmt, spin);
                if (remShown && openAmt > 0.9f) scene.drawHand(toWorld(remoteForDraw(now)), clip);
                scene.drawPortalTransparent(portalM, floating ? null : floorM, openAmt, spin, flash);
            } else {
                drawPlanes(s);
                if (reticleOk) scene.drawReticle(reticleM);
                else scene.drawGhost(ghostM);
            }
        }
        updateUi(now);
    }

    private void drawPlanes(Session s) {
        for (Plane p : s.getAllTrackables(Plane.class)) {
            if (p.getTrackingState() != TrackingState.TRACKING || p.getSubsumedBy() != null || p.getType() != Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
            p.getCenterPose().toMatrix(planeM, 0);
            scene.drawPlane(planeM, p.getPolygon());
        }
    }

    /** Placement: on a detected surface (reticle), or in the air in front of the phone (ghost ring). */
    private void updatePlacement(Frame frame, Camera camera, long now) {
        reticleOk = false;
        reticleHit = null;
        List<HitResult> hits = frame.hitTest(viewW / 2f, viewH / 2f);
        for (HitResult h : hits) {
            Trackable tr = h.getTrackable();
            if (tr instanceof Plane && ((Plane) tr).getType() == Plane.Type.HORIZONTAL_UPWARD_FACING && ((Plane) tr).isPoseInPolygon(h.getHitPose())) {
                reticleHit = h; reticleOk = true;
                h.getHitPose().toMatrix(reticleM, 0);
                break;
            }
        }
        // where an in-air portal would go: level, facing the phone, AIR_DIST ahead
        Pose cp = camera.getDisplayOrientedPose();
        float[] zAxis = cp.getZAxis();             // the camera looks down -Z
        float[] c = cp.getTranslation();
        float fx = -zAxis[0], fz = -zAxis[2], fl = (float) Math.hypot(fx, fz);
        if (fl < 1e-3f) { fx = 0; fz = -1; fl = 1; }
        fx /= fl; fz /= fl;
        float ax = c[0] + fx * AIR_DIST, ay = c[1] - 0.05f, az = c[2] + fz * AIR_DIST;
        float airYaw = (float) Math.toDegrees(Math.atan2(-fx, -fz));
        Matrix.setIdentityM(ghostM, 0);
        Matrix.translateM(ghostM, 0, ax, ay, az);
        Matrix.rotateM(ghostM, 0, airYaw, 0, 1, 0);

        boolean tapped = false;
        while (taps.poll() != null) tapped = true;
        if (!tapped) return;
        if (reticleOk) {
            anchor = reticleHit.createAnchor();
            floating = false;
            float[] a = anchor.getPose().getTranslation();
            yaw = (float) Math.toDegrees(Math.atan2(c[0] - a[0], c[2] - a[2]));
        } else {
            anchor = session.createAnchor(new Pose(new float[]{ax, ay, az}, new float[]{0, 0, 0, 1}));
            floating = true;
            yaw = airYaw;
        }
        mode = PLACED;
        placedAt = now;
        portalOpen = false; portalOpening = false; openAmt = 0; palmSince = 0;
        vibrate(30);
    }

    private void handlePlacedTaps(long now) {
        boolean tapped = false;
        while (taps.poll() != null) tapped = true;
        if (tapped && !portalOpen && !portalOpening) startOpening(now);   // a tap also opens it
    }

    private void computePortal(Pose p, float t) {
        float[] a = p.getTranslation();
        Matrix.setIdentityM(floorM, 0);
        Matrix.translateM(floorM, 0, a[0], a[1], a[2]);
        Matrix.rotateM(floorM, 0, yaw, 0, 1, 0);
        System.arraycopy(floorM, 0, portalM, 0, 16);
        float bob = 0.007f * (float) Math.sin(t * 1.7);       // gentle hover
        Matrix.translateM(portalM, 0, 0, (floating ? 0 : SceneRenderer.PORTAL_H) + bob, 0);
        Matrix.invertM(portalInv, 0, portalM, 0);
    }

    private void portalClip() {
        float nx = portalM[8], ny = portalM[9], nz = portalM[10];
        clip[0] = nx; clip[1] = ny; clip[2] = nz;
        clip[3] = -(nx * portalM[12] + ny * portalM[13] + nz * portalM[14]);
    }

    private float[] toWorld(float[] local) {
        float[] out = new float[63];
        for (int i = 0; i < 21; i++) {
            tmp4[0] = local[i * 3]; tmp4[1] = local[i * 3 + 1]; tmp4[2] = local[i * 3 + 2]; tmp4[3] = 1;
            Matrix.multiplyMV(tmp4b, 0, portalM, 0, tmp4, 0);
            out[i * 3] = tmp4b[0]; out[i * 3 + 1] = tmp4b[1]; out[i * 3 + 2] = tmp4b[2];
        }
        return out;
    }

    /* =============================== portal opening =============================== */

    private void startOpening(long now) {
        portalOpening = true;
        openStart = now;
        vibrate(new long[]{0, 30, 50, 60});
    }

    private void stepPortal(long now, float dt) {
        if (mode != PLACED) { openAmt = 0; flash = 0; spin = (spin + dt * 20) % 360; return; }
        if (!portalOpen && !portalOpening) {
            // an open palm held up near the portal opens it; in button mode it opens by itself
            boolean palm = myHas && now - myLast < 300 && PAPER.equals(stableGesture()) && handNearPortal();
            if (manualActive() && now - placedAt > 900) startOpening(now);
            else if (palm) { if (palmSince == 0) palmSince = now; if (now - palmSince > 350) startOpening(now); }
            else palmSince = 0;
        }
        float spinSpeed = 28;
        if (portalOpening) {
            float p = Math.min(1, (now - openStart) / (float) OPEN_MS);
            openAmt = easeOutBack(p);
            flash = p < 0.7f ? 1 - p / 0.7f : 0;
            spinSpeed += 520 * (1 - p) * (1 - p);
            if (p >= 1) { portalOpening = false; portalOpen = true; openAmt = 1; flash = 0; }
        }
        spin = (spin + dt * spinSpeed) % 360;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    private boolean handNearPortal() {
        float cx = (myLocal[0] + myLocal[27] + myLocal[51]) / 3, cy = (myLocal[1] + myLocal[28] + myLocal[52]) / 3, cz = (myLocal[2] + myLocal[29] + myLocal[53]) / 3;
        return Math.sqrt(cx * cx + cy * cy + cz * cz) < 0.5;
    }

    /* =============================== hands =============================== */

    @Override public void onHand(float[] worldPts, float distance) {   // worker thread
        synchronized (handLock) { pendingWorld = worldPts; pendingDist = distance; pendingSeq++; }
    }

    @Override public void onDebugImage(Bitmap b) {
        runOnUiThread(() -> {
            if (!debugChk.isChecked()) { b.recycle(); return; }
            debugView.setImageBitmap(b);
            debugView.setVisibility(View.VISIBLE);
        });
    }

    private boolean manualActive() {
        HandTracker tr = tracker;
        return manualPref || tr == null || tr.error != null;
    }

    private String stableGesture() {
        HashMap<String, Integer> c = new HashMap<>();
        String best = null; int n = 0;
        for (String g : gestureHistory) {
            if (!ROCK.equals(g) && !PAPER.equals(g) && !SCISSORS.equals(g)) continue;
            int k = c.containsKey(g) ? c.get(g) + 1 : 1;
            c.put(g, k);
            if (k > n) { n = k; best = g; }
        }
        return n >= 3 ? best : null;
    }

    private void consumeHand(long now) {
        float[] w; float dist; int seq;
        synchronized (handLock) { w = pendingWorld; dist = pendingDist; seq = pendingSeq; }
        if (seq != consumedSeq) {
            consumedSeq = seq;
            if (w == null || mode != PLACED) {
                myHas = false; myInside = false;
                gestureHistory.addLast("-");
                if (gestureHistory.size() > 7) gestureHistory.removeFirst();
            } else {
                // gesture from the raw joints (smoothing would only add lag)
                gestureHistory.addLast(String.valueOf(HandMath.classify(w)));
                if (gestureHistory.size() > 7) gestureHistory.removeFirst();
                for (int i = 0; i < 21; i++) {
                    tmp4[0] = w[i * 3]; tmp4[1] = w[i * 3 + 1]; tmp4[2] = w[i * 3 + 2]; tmp4[3] = 1;
                    Matrix.multiplyMV(tmp4b, 0, portalInv, 0, tmp4, 0);
                    myLocal[i * 3] = tmp4b[0]; myLocal[i * 3 + 1] = tmp4b[1]; myLocal[i * 3 + 2] = tmp4b[2];
                }
                myFilter.filter(myLocal, System.nanoTime());
                boolean inside = handOnPortal(w, dist);
                myHas = true; myLast = now; myInside = inside && portalOpen; myDist = dist;
                myGesture = stableGesture();
            }
        }
        if (myHas && now - myLast > 300) { myHas = false; myInside = false; }
        if (!manualActive()) {
            if (myInside && myHas) {
                if (now - lastSend > 33) { sendHand(myLocal.clone()); lastSend = now; }
                sentInside = true;
            } else if (sentInside) { sendHand(null); sentInside = false; }
        }
    }

    private final float[] vpM = new float[16], hv = new float[4], hcl = new float[4];

    /**
     * "In the portal" = the hand covers the portal on screen and is not right in front of the lens.
     * (A phone held in one hand cannot see a hand pushed straight away from it, so we don't ask for that.)
     */
    private boolean handOnPortal(float[] w, float handDist) {
        Matrix.multiplyMM(vpM, 0, projM, 0, viewM, 0);
        int[] ids = {0, 5, 9, 13, 17};
        hv[0] = hv[1] = hv[2] = 0; hv[3] = 1;
        for (int id : ids) { hv[0] += w[id * 3] / 5; hv[1] += w[id * 3 + 1] / 5; hv[2] += w[id * 3 + 2] / 5; }
        Matrix.multiplyMV(hcl, 0, vpM, 0, hv, 0);
        if (hcl[3] <= 0) return false;
        float hx = hcl[0] / hcl[3] * viewW / 2, hy = hcl[1] / hcl[3] * viewH / 2;
        hv[0] = portalM[12]; hv[1] = portalM[13]; hv[2] = portalM[14];
        Matrix.multiplyMV(hcl, 0, vpM, 0, hv, 0);
        if (hcl[3] <= 0) return false;
        float px = hcl[0] / hcl[3] * viewW / 2, py = hcl[1] / hcl[3] * viewH / 2, portalDist = hcl[3];
        hv[0] = portalM[12] + portalM[0] * PORTAL_R; hv[1] = portalM[13] + portalM[1] * PORTAL_R; hv[2] = portalM[14] + portalM[2] * PORTAL_R;
        Matrix.multiplyMV(hcl, 0, vpM, 0, hv, 0);
        if (hcl[3] <= 0) return false;
        float ex = hcl[0] / hcl[3] * viewW / 2, ey = hcl[1] / hcl[3] * viewH / 2;
        float rScreen = (float) Math.hypot(ex - px, ey - py);
        boolean covers = Math.hypot(hx - px, hy - py) < rScreen * 1.15f;
        return covers && handDist > portalDist - 0.38f;
    }

    /** p: hand in the SENDER's portal frame (metres). Shown turned 180°, so "into theirs" = "out of mine". */
    private void onRemotePose(float[] p, long now) {
        float[] q = HandMath.receiverPose(p);       // same fingers, always coming out toward me
        System.arraycopy(q, 0, remTarget, 0, 63);
        if (!remShown) { System.arraycopy(remTarget, 0, remCur, 0, 63); remShown = true; remShownAt = now; vibrate(25); }
        remLast = now;
    }

    /** Opponent hand for drawing: slides out of the portal during the first 350 ms. */
    private float[] remoteForDraw(long now) {
        float e = Math.min(1, (now - remShownAt) / 350f);
        float back = 0.17f * (1 - e) * (1 - e) * (1 - e);
        for (int i = 0; i < 21; i++) for (int k = 0; k < 3; k++) remDraw[i * 3 + k] = remCur[i * 3 + k] - outF[k] * back;
        return remDraw;
    }

    private void stepRemote(float dt, long now) {
        if (remShown && (now - remLast > 450 || !portalOpen)) { remShown = false; remFilter.reset(); }
        if (!remShown) return;
        float k = 1 - (float) Math.exp(-dt * 24);
        for (int i = 0; i < 63; i++) remCur[i] += (remTarget[i] - remCur[i]) * k;
    }

    /* =============================== game =============================== */

    private void resetGame() {
        phase = "idle"; votes.clear(); myThrowSet = false; oppThrowSet = false; bothSince = 0; beatShown = -1;
        scoreMe = 0; scoreOpp = 0; oppSeen = 0; remShown = false; bTitle = ""; uiBanner = false;
    }

    private boolean myInsideNow(long now) { return manualActive() ? manualInside : (myInside && myHas && now - myLast < 300); }
    private String myGestureNow() { return manualActive() ? manualGesture : myGesture; }

    private float pumpY(long now) {
        if (!"count".equals(phase)) return (float) (0.004 * Math.sin(now / 380.0));
        double ph = ((now - countStart) % BEAT_MS) / (double) BEAT_MS;
        return (float) (0.04 * Math.sin(Math.PI * ph));
    }

    private String shownGesture(String g) { return ("idle".equals(phase) || "count".equals(phase)) ? ROCK : g; }

    private void beginCount(int id, long now) {
        phase = "count"; gameId = id; countStart = now; shootAt = now + BEAT_MS * 3;
        votes.clear(); myThrowSet = false; oppThrowSet = false; beatShown = -1;
        String[] g = {ROCK, PAPER, SCISSORS};
        botGesture = g[rnd.nextInt(3)];
    }

    private void stepGame(long now) {
        boolean ready = mode == PLACED && portalOpen;
        boolean meIn = ready && myInsideNow(now);
        boolean oppIn = solo || remShown;
        boolean present = opponentPresent(now);

        if ("idle".equals(phase)) {
            if (isReferee() && present && meIn && oppIn && now > cooldownUntil) {
                if (bothSince == 0) bothSince = now;
                if (now - bothSince > 700) {
                    bothSince = 0;
                    int id = gameId + 1;
                    beginCount(id, now);
                    JSONObject go = msg("go");
                    try { go.put("id", id); } catch (Exception ignored) {}
                    send(go);
                }
            } else bothSince = 0;
        }
        if ("count".equals(phase) || "collect".equals(phase)) {
            int beat = (int) ((now - countStart) / BEAT_MS);
            if (beat < 3 && beat != beatShown) {
                beatShown = beat;
                banner(new String[]{"אבן…", "נייר…", "ומספריים!"}[beat], "", "beat");
                vibrate(25);
            }
            if (now >= shootAt && "count".equals(phase)) { phase = "collect"; banner("🎯 עכשיו!", "", "beat"); }
            if (now >= shootAt - WINDOW_BEFORE && meIn) {
                String g = myGestureNow();
                if (g != null) votes.put(g, votes.containsKey(g) ? votes.get(g) + 1 : 1);
            }
            if (now >= shootAt + WINDOW_AFTER) {
                myThrow = majority(); myThrowSet = true;
                phase = "wait"; waitUntil = now + 2500;
                if (solo) { oppThrow = botGesture; oppThrowSet = true; }
                else if (net != null && !net.host) {
                    JSONObject th = msg("throw");
                    try { th.put("id", gameId); th.put("g", myThrow == null ? JSONObject.NULL : myThrow); } catch (Exception ignored) {}
                    send(th);
                }
            }
        }
        if ("wait".equals(phase)) {
            if (isReferee()) { if (oppThrowSet || now > waitUntil) resolveRound(now); }
            else if (now > waitUntil + 2000) { phase = "idle"; uiBanner = false; }
        }
        if ("result".equals(phase) && now > resultUntil) { phase = "idle"; cooldownUntil = now + 900; uiBanner = false; }

        // button mode: my hand is a cartoon pose sent to the opponent
        if (manualActive() && !solo) {
            if (manualInside && ready) {
                if (now - lastSend > 33) { sendHand(HandMath.poseHand(shownGesture(manualGesture), pumpY(now))); lastSend = now; sentInside = true; }
            } else if (sentInside) { sendHand(null); sentInside = false; }
        }

        String h = "";
        if ("idle".equals(phase) && tracking && mode == PLACED) {
            if (!portalOpen && !portalOpening) h = manualActive() ? "" : "✋ פתח את כף היד מול הפורטל כדי לפתוח אותו (או הקש על המסך)";
            else if (portalOpening) h = "";
            else if (!solo && net == null) h = "פתח ⚙️ חדר: שחק נגד המחשב או עם חבר";
            else if (!present) h = net != null && net.host ? "מחכים שהחבר יצטרף…" : "מתחבר ליריב…";
            else if (!meIn) h = manualActive() ? "לחץ \"הכנס יד\" ובחר אבן, נייר או מספריים" : "שים את היד על הפורטל במסך, כף היד או הצד מול המצלמה ✋";
            else if (!oppIn) h = "מחכים שהיריב יכניס יד לפורטל…";
            else h = isReferee() ? "מתחילים…" : "מחכים לסיבוב…";
        }
        uiHint = h;
    }

    private String majority() {
        String best = null; int n = 0;
        for (String k : votes.keySet()) if (votes.get(k) > n) { n = votes.get(k); best = k; }
        return best;
    }

    private void resolveRound(long now) {
        String h = myThrowSet ? myThrow : null, g = oppThrowSet ? oppThrow : null;
        int w = HandMath.rps(h, g);
        if (w == 1) scoreMe++; else if (w == -1) scoreOpp++;
        JSONObject r = msg("result");
        try {
            r.put("id", gameId);
            r.put("h", h == null ? JSONObject.NULL : h);
            r.put("g", g == null ? JSONObject.NULL : g);
            r.put("score", new JSONArray().put(scoreMe).put(scoreOpp));
        } catch (Exception ignored) {}
        send(r);
        showResult(h, g, now);
    }

    private static String emo(String g) { return ROCK.equals(g) ? "🪨" : PAPER.equals(g) ? "📄" : "✂️"; }
    private static String why(String g) { return ROCK.equals(g) ? "אבן שוברת מספריים" : SCISSORS.equals(g) ? "מספריים גוזרים נייר" : "נייר עוטף אבן"; }

    private void showResult(String mine, String theirs, long now) {
        int w = HandMath.rps(mine, theirs);
        phase = "result"; resultUntil = now + RESULT_MS;
        String title, sub = "";
        if (w == 2) title = "אף אחד לא שלח יד";
        else if (theirs == null) { title = "ניצחת! 🎉"; sub = "היריב לא הראה יד בזמן"; }
        else if (mine == null) { title = "הפסדת 😅"; sub = "לא זיהינו את היד שלך בזמן"; }
        else if (w == 0) { title = "תיקו!"; sub = emo(mine) + " מול " + emo(theirs); }
        else if (w == 1) { title = "ניצחת! 🎉"; sub = emo(mine) + " " + why(mine); }
        else { title = "הפסדת 😅"; sub = emo(theirs) + " " + why(theirs); }
        banner(title, sub, w == 1 ? "win" : w == -1 ? "lose" : "");
        if (w == 1) vibrate(new long[]{0, 40, 60, 40, 60, 90}); else vibrate(70);
    }

    private void banner(String title, String sub, String cls) { bTitle = title; bSub = sub; bCls = cls; uiBanner = true; }

    /* =============================== UI =============================== */

    private void updateUi(long now) {
        String status;
        if (!tracking) status = "הזז את הטלפון לאט מצד לצד כדי שהמצלמה תכיר את החדר";
        else if (mode == PLACING) status = reticleOk ? "הקש על המסך כדי להציב את הפורטל על המשטח" : "הקש כדי להציב את הפורטל באוויר, או כוון לשולחן";
        else status = "";
        String netS;
        if (solo) netS = "🤖 נגד המחשב";
        else if (net == null) netS = "לא מחובר";
        else if (!brokerUp) netS = netError != null ? "⚠️ " + netError : "⏳ מתחבר לשרת…";
        else if (opponentPresent(now)) netS = "🟢 מחובר · חדר " + net.code;
        else if (net.host) netS = "⏳ מחכה לחבר · " + net.code;
        else netS = (now - joinStarted > 8000) ? "⚠️ לא נמצא חדר " + net.code : "⏳ מחפש חדר " + net.code + "…";
        String hand = "";
        boolean hot = false;
        if (mode == PLACED) {
            HandTracker tr = tracker;
            if (manualActive()) hand = tr != null && tr.error != null ? "זיהוי יד לא נטען · משחקים בכפתורים" : "🎮 כפתורים";
            else if (tr == null || !tr.ready()) hand = "טוען זיהוי יד…";
            else if (!(myHas && now - myLast < 300)) hand = "לא רואה יד";
            else if (myInside) { hand = "🌀 בפורטל · " + (myGesture == null ? "?" : name(myGesture)); hot = true; }
            else hand = "✋ " + (myGesture == null ? "יד זוהתה" : name(myGesture)) + " · הזז אותה על הפורטל";
        }
        String score = opponentPresent(now) ? "אתה " + scoreMe + " : " + scoreOpp + (solo ? " מחשב" : " יריב") : "";
        boolean manual = manualActive() && mode == PLACED && portalOpen && opponentPresent(now);
        boolean move = mode == PLACED;
        boolean autoCloseLobby = !lobbyAutoClosed && net != null && net.host && opponentPresent(now);
        if (autoCloseLobby) lobbyAutoClosed = true;
        String key = status + "|" + netS + "|" + hand + hot + "|" + score + "|" + uiHint + "|" + uiBanner + bTitle + bSub + bCls + "|" + manual + manualInside + manualGesture + "|" + move + autoCloseLobby;
        if (key.equals(shownKey)) return;
        boolean bannerChanged = !shownKey.contains("|" + uiBanner + bTitle + bSub + bCls + "|");
        shownKey = key;
        final String fStatus = status, fNet = netS, fHand = hand, fScore = score, fHint = uiHint, fT = bTitle, fS = bSub, fC = bCls;
        final boolean fHot = hot, fBanner = uiBanner, fManual = manual, fMove = move, fIn = manualInside, fPop = bannerChanged, fClose = autoCloseLobby;
        final String fG = manualGesture;
        runOnUiThread(() -> {
            setText(statusTv, fStatus);
            netChip.setText(fNet);
            netChip.setTextColor(fNet.startsWith("🟢") || fNet.startsWith("🤖") ? 0xFF4DFF9A : 0xFFA9BDB2);
            setText(handChip, fHand);
            handChip.setBackgroundResource(fHot ? R.drawable.bg_chip_hot : R.drawable.bg_chip);
            handChip.setTextColor(fHot ? 0xFF062413 : 0xFFA9BDB2);
            setText(scoreChip, fScore);
            setText(hintTv, fHint);
            if (fBanner) {
                bannerTitle.setText(fT);
                bannerTitle.setTextColor("win".equals(fC) ? 0xFF4DFF9A : "lose".equals(fC) ? 0xFFFF8A7A : 0xFFEEF7F1);
                bannerSub.setText(fS);
                bannerSub.setVisibility(fS.isEmpty() ? View.GONE : View.VISIBLE);
                banner.setVisibility(View.VISIBLE);
                if (fPop) { banner.setScaleX(0.7f); banner.setScaleY(0.7f); banner.setAlpha(0.3f);
                    banner.animate().scaleX(1).scaleY(1).alpha(1).setDuration(260).start(); }
            } else banner.setVisibility(View.GONE);
            manualBar.setVisibility(fManual ? View.VISIBLE : View.GONE);
            mInBtn.setText(fIn ? "✋ הוצא יד" : "✋ הכנס יד");
            mInBtn.setSelected(fIn);
            gRock.setSelected(ROCK.equals(fG)); gPaper.setSelected(PAPER.equals(fG)); gScissors.setSelected(SCISSORS.equals(fG));
            moveBtn.setVisibility(fMove ? View.VISIBLE : View.GONE);
            if (fClose) closeLobby();
        });
    }

    private static String name(String g) { return ROCK.equals(g) ? "אבן 🪨" : PAPER.equals(g) ? "נייר 📄" : "מספריים ✂️"; }

    private static void setText(TextView v, String s) {
        v.setText(s);
        v.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void vibrate(long ms) {
        try { if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); } catch (Exception ignored) {}
    }

    private void vibrate(long[] pattern) {
        try { if (vibrator != null) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)); } catch (Exception ignored) {}
    }
}
