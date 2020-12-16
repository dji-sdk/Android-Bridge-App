package com.dji.wsbridge.lib.connection;

import com.dji.wsbridge.lib.BridgeApplication;
import com.dji.wsbridge.lib.DJILogger;
import com.dji.wsbridge.lib.NetworkServerInputStream;
import com.dji.wsbridge.lib.NetworkServerOutputStream;

import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.FramedataImpl1;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

//import static com.dji.wsbridge.lib.Utils.recordExceptionToFirebase;


public class WSConnectionManager extends WebSocketServer implements ConnectionManager {

    private static final String TAG = "NW";
    private static final String KEEP_ALIVE = "KeepAlive";
    private static final int DISCONNECT_TIMEOUT = 30000; //Milliseconds
    private static final int KEEP_ALIVE_TIMER_INTERVAL = 1000; //Milliseconds
    private static final int MAX_BUFFER_SIZE = 100;// To avoid OOM crash, ideally we should be more dynamic with this value
    private static WSConnectionManager instance;
    public StreamFilter streamFilter = StreamFilter.FILTER_NONE;
    private final LinkedBlockingDeque<ByteBuffer> mQueue;
    private ByteBuffer mLast;
    private Timer keepAlivePollTimer;
    private TimerTask keepAlivePollTimerTask;
    private long lastPingTime = 0;
    private InputStream mInStream;
    private OutputStream mOutStream;
    private ByteStatCounter rxStatTracker;
    private ByteStatCounter txStatTracker;
    private final AtomicBoolean isSettingUpTimer = new AtomicBoolean(false);

    public WSConnectionManager(int port) throws UnknownHostException {
        super(new InetSocketAddress(port));
        mQueue = new LinkedBlockingDeque<>(MAX_BUFFER_SIZE);
    }

    public static WSConnectionManager getInstance() {
        return instance;
    }

    public static void setupInstance(int port) throws UnknownHostException {
        DJILogger.d(TAG, "setupInstance ");
        instance = new WSConnectionManager(port);
        instance.start();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        DJILogger.d(TAG, "onOpen ");
        sendConnectivityStatus(true, conn);
        lastPingTime = System.currentTimeMillis();
        setupKeepAlivePolling();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        DJILogger.d(TAG, "onClose ");
        sendConnectivityStatus(false, conn);
        if (activeConnectionCount() <= 0) {
            stopKeepAlivePolling();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        DJILogger.d(TAG, "onError " + ex.getMessage());
        sendConnectivityStatus(false, conn);
        if (activeConnectionCount() <= 0) {
            stopKeepAlivePolling();
        }
    }

    @Override
    public void onStart() {

    }

    @Override
    public void onWebsocketPing(WebSocket webSocket, Framedata framedata) {
        ByteBuffer buffer = framedata.getPayloadData();
        String message = new String(buffer.array(), StandardCharsets.UTF_8);
        try {
            //Expected JSON Format: {"Type": "KeepAlive", "Platform" : "iOS" or "Android", "Filter": 0 - None, 1 - filter video}
            JSONObject receivedData = new JSONObject(message);

            if (receivedData.get("Type").equals(KEEP_ALIVE)) {
                lastPingTime = System.currentTimeMillis();
            }
            streamFilter = (receivedData.getInt("Filter") == 1) ? StreamFilter.FILTER_VIDEO : StreamFilter.FILTER_NONE;
        } catch (Exception e) {
            //recordExceptionToFirebase(e);
            //e.printStackTrace();
            //Log.e(TAG, e.getMessage());
        }
        try {
            //Sent JSON Format: {"rx_kbps" : 0.0, "rx_bytecount" : 0, "tx_kbps" : 0.0, "tx_bytecount" : 0}
            JSONObject sentJSON = new JSONObject();
            sentJSON.put("rx_kbps", rxStatTracker.recentKBps);
            sentJSON.put("rx_bytecount", rxStatTracker.recentByteCount);
            sentJSON.put("tx_kbps", txStatTracker.recentKBps);
            sentJSON.put("tx_bytecount", txStatTracker.recentByteCount);
            ((FramedataImpl1) framedata).setPayload(ByteBuffer.wrap(sentJSON.toString()
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            //recordExceptionToFirebase(e);
            //e.printStackTrace();
            //Log.e(TAG,e.getMessage());
        }

        super.onWebsocketPing(webSocket, framedata);
    }

    private void sendConnectivityStatus(boolean isConnected, WebSocket conn) {
        String hostName = "Unknown";
        if (conn != null && conn.getRemoteSocketAddress() != null) {
            hostName = conn.getRemoteSocketAddress().getHostName();
        }
        BridgeApplication.getInstance()
                .getBus()
                .post(new WSConnectionEvent(isConnected, hostName, activeConnectionCount()));
        if (isConnected) {
            txStatTracker = new ByteStatCounter();
            rxStatTracker = new ByteStatCounter();
        } else {
            txStatTracker = null;
            rxStatTracker = null;
        }
    }

    private void setupKeepAlivePolling() {
        if (isSettingUpTimer.compareAndSet(false, true)) {
            stopKeepAlivePolling();
            keepAlivePollTimerTask = new TimerTask() {
                @Override
                public void run() {
                    long currentTime = System.currentTimeMillis();
                    long delta = currentTime - lastPingTime;
                    if (lastPingTime > 0 && delta > DISCONNECT_TIMEOUT) {
                        for (Iterator<WebSocket> iterator = connections().iterator(); iterator.hasNext(); ) {
                            final WebSocket eachConnection = iterator.next();
                            sendConnectivityStatus(false, eachConnection);
                            eachConnection.close();
                            DJILogger.e(TAG,
                                    "Disconnecting Network. No Pings for "
                                            + String.format(Locale.US,
                                            "%.2f",
                                            delta / 1000.0)
                                            + "secs.");
                        }
                        stopKeepAlivePolling();
                    }
                }
            };

            keepAlivePollTimer = new Timer();
            keepAlivePollTimer.scheduleAtFixedRate(keepAlivePollTimerTask,
                    KEEP_ALIVE_TIMER_INTERVAL,
                    KEEP_ALIVE_TIMER_INTERVAL);
            isSettingUpTimer.set(false);
        }
    }

    private void stopKeepAlivePolling() {
        lastPingTime = 0;
        if (keepAlivePollTimer != null) {
            keepAlivePollTimer.cancel();
            keepAlivePollTimer = null;
        }
    }

    @Override
    public ArrayList<Object> getStreams() {
        mInStream = new NetworkServerInputStream(this);
        mOutStream = new NetworkServerOutputStream(this);
        ArrayList<Object> array = new ArrayList<>();
        array.add(mInStream);
        array.add(mOutStream);
        return array;
    }

    @Override
    public void closeStreams() {
        try {
            if (mOutStream != null) {
                mOutStream.close();
                mOutStream = null;
            }
            if (mInStream != null) {
                mInStream.close();
                mInStream = null;
            }
        } catch (IOException e) {
            //recordExceptionToFirebase(e);
            //e.printStackTrace();
            //Log.e(TAG,e.getMessage());
        }
    }

    //region -------------------------------------------- Read Write ----------------------------------------------
    @Override
    public void onMessage(WebSocket conn, ByteBuffer buffer) {
        // Drop the first package in the queue if it is full
        if (mQueue.remainingCapacity() <= 0) {
            mQueue.removeFirst();
        }
        mQueue.add(buffer);
        //DJILogger.d(TAG, "onMessage" + buffer.array().length);
        if (rxStatTracker != null) {
            rxStatTracker.increaseByteCount(buffer.limit());
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        //DJILogger.d(TAG, "onMessage "+message);
        System.out.println(conn + ": " + message);
    }

    public ByteBuffer read() {
        if (mLast == null || mLast.remaining() <= 0) {
            try {
                mLast = mQueue.take();
            } catch (InterruptedException e) {
                //recordExceptionToFirebase(e);
                //Log.e(TAG,e.getMessage());
                //e.printStackTrace();
            }
        }
        return mLast;
    }

    public void send(byte[] b) {
        Collection<WebSocket> con = connections();
        if (txStatTracker != null) {
            txStatTracker.increaseByteCount(b.length);
        }
        if (activeConnectionCount() > 0) {
            final int maxBufferSizePerConnection = MAX_BUFFER_SIZE / con.size();
            synchronized (con) {
                for (WebSocket conn : con) {
                    if (conn.isOpen()) {
                        final boolean isSlowTraffic;
                        if (conn instanceof WebSocketImpl && ((WebSocketImpl) conn).outQueue.size() > maxBufferSizePerConnection) {
                            // Do nothing because we don't want to over flow the internal buffer of WebSocket
                            // This could happen when usb is fast but the connection is slow ( producer/consumer problem)
                            isSlowTraffic = true;
                        } else {
                            conn.send(b);
                            isSlowTraffic = false;
                        }
                        if (isSlowTraffic) {
                            String hostName = "";
                            if (conn != null && conn.getRemoteSocketAddress() != null) {
                                hostName = conn.getRemoteSocketAddress().getHostName();
                            }
                            BridgeApplication.getInstance()
                                    .getBus()
                                    .post(new WSTrafficEvent(isSlowTraffic, hostName));
                        }
                        //DJILogger.d("SOURCE", DJILogger.sha1Hash(b) + " -- " + DJILogger.bytesToHex(b));
                    }
                }
            }
        }
    }

    private int activeConnectionCount() {
        return connections().size();
    }

    public enum StreamFilter {
        FILTER_NONE, FILTER_VIDEO
    }
    //endregion --------------------------------------------------------------------------------------------------

    /**
     * Event to send changes in WebSocket Connection
     */
    public static final class WSConnectionEvent {
        final boolean isConnected;
        final String message;
        final int activeConnectionCount;

        public WSConnectionEvent(boolean isConnected, String message, int activeConnectionCount) {
            this.isConnected = isConnected;
            this.message = message;
            this.activeConnectionCount = activeConnectionCount;
        }

        public boolean isConnected() {
            return isConnected;
        }

        public String getMessage() {
            return message;
        }

        public int getActiveConnectionCount() {
            return activeConnectionCount;
        }
    }

    /**
     * Event to notify changes in WebSocket Traffic
     */
    public static final class WSTrafficEvent {
        final boolean isSlowConnection;
        final String message;

        public WSTrafficEvent(boolean isSlowConnection, String message) {
            this.isSlowConnection = isSlowConnection;
            this.message = message;
        }

        public boolean isSlowConnection() {
            return isSlowConnection;
        }

        public String getMessage() {
            return message;
        }
    }

    private class ByteStatCounter {

        public long recentByteCount = 0;
        public double recentKBps = 0.0;

        private long interval = 0;
        private long currByteCount = 0;

        void increaseByteCount(long byteCount) {
            currByteCount += byteCount;

            long currentInterval = System.currentTimeMillis();
            long delta = currentInterval - interval;

            if (interval == 0) {
                interval = currentInterval;
            } else if (delta > 10000) {
                interval = currentInterval;
                recentByteCount = currByteCount;
                recentKBps = recentByteCount / delta;
                currByteCount = 0;
            }
        }
    }
}
