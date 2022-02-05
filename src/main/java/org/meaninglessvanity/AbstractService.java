package org.meaninglessvanity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

public abstract class AbstractService extends Service {
    public static final int MSG_REGISTER_CLIENT = 4;
    public static final int MSG_UNREGISTER_CLIENT = 5;
    final Messenger mMessenger;
    protected Handler mHandler = new Handler();
    protected List<Messenger> mClients = new ArrayList<>();
    protected List<Integer> messageWhats = new ArrayList<Integer>();

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    public AbstractService() {
        mMessenger = new Messenger(new IncomingHandler());
    }

    protected void sendActivityMessage(int what, Object obj) {
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                mClients.get(i).send(Message.obtain(null, what, obj));
            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going through the list from back to front so this is safe to do inside the loop.
                mClients.remove(i);
            }
        }
    }

    public abstract void handleIncomingMessage(Message msg);

    @SuppressLint("HandlerLeak")
    private class IncomingHandler extends Handler { // Handler of incoming messages from clients.
        @Override
        public void handleMessage(Message msg) {
            Log.e(getPackageName(), "got message number " + msg.what);
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    Log.e(getPackageName(), "got register message");
                    mClients.add(msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.replyTo);
                    break;
                default:
                    if (Arrays.asList(messageWhats).contains(msg.what)) {
                        handleIncomingMessage(msg);
                    } else {
                        super.handleMessage(msg);
                    }
            }
        }
    }
}
