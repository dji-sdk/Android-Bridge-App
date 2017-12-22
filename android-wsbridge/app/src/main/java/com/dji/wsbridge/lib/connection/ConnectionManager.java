package com.dji.wsbridge.lib.connection;

import java.util.ArrayList;

public interface ConnectionManager {

    interface ConnectionCallback {
        void onConnect(String message);
        void onDisconnect(String message);
    }

    ArrayList<Object> getStreams(); // returns [InputStream, OutputStream]
    void closeStreams();


}
