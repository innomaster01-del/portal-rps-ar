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
    static final String BROKER = "tcp://broker.hivemq.com:1883";
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
        try {
            MqttClient c = new MqttClient(BROKER, "prps-" + myId, new MemoryPersistence());
            c.setCallback(new MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String uri) {
                    try { c.subscribe(topic(), 0); } catch (Exception e) { Log.w(TAG, "subscribe", e); }
                    listener.onConnState(true, null);
                }
                @Override public void connectionLost(Throwable cause) { listener.onConnState(false, "החיבור לשרת נותק, מתחבר מחדש…"); }
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
            o.setConnectionTimeout(10);
            c.connect(o);
            client = c;
            c.subscribe(topic(), 0);
            listener.onConnState(true, null);
        } catch (Exception e) {
            Log.w(TAG, "connect", e);
            listener.onConnState(false, "אין חיבור לשרת המשחק. בדוק אינטרנט.");
            if (!closed) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                if (!closed) out.execute(this::connect);
            }
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
