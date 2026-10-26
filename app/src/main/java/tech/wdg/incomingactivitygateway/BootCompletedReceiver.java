package tech.wdg.incomingactivitygateway;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent argIntent) {
        if (!isBusinessPaired(context)) {
            Log.d(TAG, "No paired business; skipping background service startup");
            return;
        }

        Log.d(TAG, "Boot completed - starting CallBackly background service");

        try {
            Intent intent = new Intent(context, SmsReceiverService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
                Log.d(TAG, "Foreground service start requested");
            } else {
                context.startService(intent);
                Log.d(TAG, "Service start requested");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service on boot", e);
        }
    }

    private boolean isBusinessPaired(Context context) {
        try {
            return !android.text.TextUtils.isEmpty(
                    PairingStore.getPreferences(context).getString(PairingStore.KEY_BUSINESS_ID, null));
        } catch (Exception e) {
            Log.e(TAG, "Unable to verify pairing on boot", e);
            return false;
        }
    }
}
