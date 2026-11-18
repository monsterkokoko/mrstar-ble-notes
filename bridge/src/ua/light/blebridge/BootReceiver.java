package ua.light.blebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the bridge after a reboot and after the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BridgeService.start(context);
    }
}
