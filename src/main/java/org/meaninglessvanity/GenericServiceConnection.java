package org.meaninglessvanity;

import static android.content.Context.ACTIVITY_SERVICE;
import static android.content.Context.BIND_AUTO_CREATE;

import java.util.Map;
import java.util.function.Consumer;

import org.meaninglessvanity.player.PlayerService;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

public class GenericServiceConnection implements ServiceConnection {
    Class serviceClass;
    Context context;
    Messenger mService = null;
    final Messenger mMessenger;

    public GenericServiceConnection(Class c, Context con, Handler h) {
        this.serviceClass = c;
        this.context = con;
        this.mMessenger = new Messenger(h);
    }

    public boolean start(Map<String,Object> extraMap) {
        Intent startIntent = new Intent(context, serviceClass);
        for (String key : extraMap.keySet()) {
            if (extraMap.get(key) instanceof Bundle) {
                startIntent.putExtra(key, (Bundle) extraMap.get(key));
            } else if (extraMap.get(key) instanceof Long) {
                startIntent.putExtra(key, (Long) extraMap.get(key));
            } else if (extraMap.get(key) instanceof Integer) {
                startIntent.putExtra(key, (Integer) extraMap.get(key));
            } else {
                throw new IllegalArgumentException("unsupported type in startService");
            }
        }
        ComponentName compName = context.startService(startIntent);
        if (compName != null) {
            return doBind();
        } else {
            return false;
        }
    }

    public void doUnbind() {
        if (isServiceRunning()) {
            // If we have received the service, and hence registered with it, then now is the time to unregister.
            if (mService != null) {
                try {
                    // BJT TODO need to make this generic -- need an interface for each service that
                    //          defines the messsages.  All services probably need REGISTER/UNREGISTER
                    Message msg = Message.obtain(null, AbstractService.MSG_UNREGISTER_CLIENT);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                } catch (RemoteException e) {
                    // There is nothing special we need to do if the service has crashed.
                }
            }
            // Detach our existing connection.
            context.unbindService(this);

            mService = null;
        }
    }

    public boolean isServiceRunning() {
        boolean serviceRunning = false;
        ActivityManager am = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo runningServiceInfo : am.getRunningServices(Integer.MAX_VALUE)) {
            if (runningServiceInfo.service.getClassName().equals(serviceClass.getName())) {
                serviceRunning = true;
            }
        }
        return serviceRunning;
    }

    public void sendMessage(int what, int arg1, int arg2, Object obj) {
        if (isServiceRunning()) {
            if (mService != null) {
                try {
                    Message msg = Message.obtain(null, what, arg1, arg2, obj);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                } catch (RemoteException e) {
                    // do something
                }
            }
        }
    }

    public boolean doBind() {
        return context.bindService(new Intent(context, serviceClass), this, BIND_AUTO_CREATE);
    }


    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
        sendMessage(AbstractService.MSG_REGISTER_CLIENT, 0,0, null);
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
        // This is called when the connection with the service has been unexpectedly disconnected - process crashed.
        mService = null;
    }
}
