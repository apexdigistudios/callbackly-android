package tech.wdg.incomingactivitygateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        try {
            String businessId = PairingStore.getPreferences(context)
                    .getString(PairingStore.KEY_BUSINESS_ID, null);
            if (TextUtils.isEmpty(businessId)) {
                AppLogger.getInstance().info("Open the app and link your phone to start call watching.");
                return;
            }

            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, SmsReceiverService.class));
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, CallForegroundService.class));
            AppLogger.getInstance().success("Phone restarted — call watching started automatically.");
            Log.i(TAG, "Started background services after boot");
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Unable to check phone pairing after boot", e);
            AppLogger.getInstance().error("Phone unlinked. Open the app and pair it again.");
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start background services after boot", e);
            AppLogger.getInstance().error("Phone restarted, but call watching couldn't start. Open the app.");
        }
    }
}
