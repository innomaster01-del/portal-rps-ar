package io.github.innomaster01del.portalrps;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Room-based messaging between the two phones through a public MQTT broker.
 * Every message is a small JSON object; each phone ignores its own messages.
 */
final class Net {
    interface Listener {
        void onMessage(JSONObject m);
        void onConnState(boolean brokerConnected, String error);
    }

    private static final String TAG = "Net";
    /**
     * Tried in order until one connects. Same broker first over three transports, because both phones
     * must end up on the same broker: secure WebSocket (port 8884, rarely blocked), plain TCP 1883
     * (often blocked on filtered/mobile networks), plain WebSocket 8000. Then a second broker.
     */
    static final String[] BROKERS = {
        "wss://broker.hivemq.com:8884/mqtt",
        "tcp://broker.hivemq.com:1883",
        "ws://broker.hivemq.com:8000/mqtt",
        "wss://broker.emqx.io:8084/mqtt",
        "tcp://broker.emqx.io:1883",
    };
    volatile String connectedVia;
    private static final String TOPIC_PREFIX = "portalrps/v1/room/";

    final String code;
    final boolean host;
    private final String myId = UUID.randomUUID().toString().substring(0, 8);
    private final Listener listener;
    private final ThreadPoolExecutor out = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private volatile MqttClient client;
    private volatile boolean closed;

    Net(String code, boolean host, Listener l) {
        this.code = code;
        this.host = host;
        this.listener = l;
        out.execute(this::connect);
    }

    private String topic() { return TOPIC_PREFIX + code; }

    private void connect() {
        StringBuilder errs = new StringBuilder();
        for (String uri : BROKERS) {
            if (closed) return;
            try {
                MqttClient c = new MqttClient(uri, "prps-" + myId, new MemoryPersistence());
                c.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String u) {
                        try { c.subscribe(topic(), 0); } catch (Exception e) { Log.w(TAG, "subscribe", e); }
                        listener.onConnState(true, null);
                    }
                    @Override public void connectionLost(Throwable cause) { listener.onConnState(false, "החיבור נותק, מתחבר מחדש…"); }
                    @Override public void messageArrived(String t, MqttMessage msg) {
                        try {
                            JSONObject j = new JSONObject(new String(msg.getPayload(), StandardCharsets.UTF_8));
                            if (myId.equals(j.optString("from"))) return;
                            listener.onMessage(j);
                        } catch (Exception e) { Log.w(TAG, "bad message", e); }
                    }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) {}
                });
                MqttConnectOptions o = new MqttConnectOptions();
                o.setCleanSession(true);
                o.setAutomaticReconnect(true);
                o.setKeepAliveInterval(20);
                o.setConnectionTimeout(8);
                c.connect(o);
                c.subscribe(topic(), 0);
                client = c;
                connectedVia = uri;
                Log.i(TAG, "connected via " + uri);
                listener.onConnState(true, null);
                return;
            } catch (Exception e) {
                Log.w(TAG, "connect failed: " + uri, e);
                String why = e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName();
                errs.append(uri.substring(0, uri.indexOf(':'))).append(':').append(why).append(' ');
            }
        }
        listener.onConnState(false, "אין חיבור לשרת המשחק (" + errs.toString().trim() + ")");
        if (!closed) {
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            if (!closed) out.execute(this::connect);
        }
    }

    /** Fire-and-forget. Drops messages if the link is slow, so hand updates never pile up. */
    void send(JSONObject m) {
        if (closed) return;
        if (out.getQueue().size() > 8) return;
        out.execute(() -> {
            MqttClient c = client;
            if (c == null || !c.isConnected()) return;
            try {
                m.put("from", myId);
                MqttMessage msg = new MqttMessage(m.toString().getBytes(StandardCharsets.UTF_8));
                msg.setQos(0);
                c.publish(topic(), msg);
            } catch (Exception e) { Log.w(TAG, "publish", e); }
        });
    }

    void close() {
        closed = true;
        out.execute(() -> {
            try { if (client != null) { client.disconnect(500); client.close(); } } catch (Exception ignored) {}
            client = null;
        });
        out.shutdown();
    }
}
