package tech.wdg.incomingactivitygateway;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class CallBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "CallBroadcastReceiver";
    private static final String PREFS_NAME = "call_state";
    private static final String KEY_CALL_ACTIVE = "call_active";
    private static final String KEY_CALL_ANSWERED = "call_answered";
    private static final String KEY_CALL_STARTED_AT = "call_started_at";
    private static final String KEY_PHONE_NUMBER = "phone_number";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                        != PackageManager.PERMISSION_GRANTED) {
            AppLogger.getInstance().error(
                        "Call logging is off. Turn on Phone and Call Log permissions in the app.");
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            long startedAt = preferences.getLong(KEY_CALL_STARTED_AT, 0L);
            if (!preferences.getBoolean(KEY_CALL_ACTIVE, false) || startedAt == 0L) {
                String phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                AppLogger.getInstance().info("Incoming call. Waiting to see if it's missed.");
                preferences.edit()
                        .putBoolean(KEY_CALL_ACTIVE, true)
                        .putBoolean(KEY_CALL_ANSWERED, false)
                        .putLong(KEY_CALL_STARTED_AT, System.currentTimeMillis())
                        .putString(KEY_PHONE_NUMBER, phoneNumber)
                        .apply();
            }
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            if (preferences.getBoolean(KEY_CALL_ACTIVE, false)) {
                AppLogger.getInstance().info("You answered the call. Nothing to send.");
                preferences.edit().putBoolean(KEY_CALL_ANSWERED, true).apply();
            }
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            finishCall(context, preferences);
        }
    }

    private void finishCall(Context context, SharedPreferences preferences) {
        if (!preferences.getBoolean(KEY_CALL_ACTIVE, false)) {
            return;
        }

        long startedAt = preferences.getLong(KEY_CALL_STARTED_AT, 0L);
        String phoneNumber = preferences.getString(KEY_PHONE_NUMBER, null);
        boolean answered = preferences.getBoolean(KEY_CALL_ANSWERED, false);
        preferences.edit()
                .remove(KEY_CALL_ACTIVE)
                .remove(KEY_CALL_ANSWERED)
                .remove(KEY_CALL_STARTED_AT)
                .remove(KEY_PHONE_NUMBER)
                .apply();

        if (answered || startedAt == 0L) {
            if (answered) {
                AppLogger.getInstance().info("Call ended. No missed call to send.");
            }
            return;
        }

        AppLogger.getInstance().info("Incoming call missed! Sending to your dashboard...");
        Data inputData = new Data.Builder()
                .putLong(KEY_CALL_STARTED_AT, startedAt)
                .putString(KEY_PHONE_NUMBER, phoneNumber)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CallWebhookWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                    "callbackly-missed-call-" + startedAt,
                    ExistingWorkPolicy.KEEP,
                    request);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to schedule missed-call delivery", e);
            AppLogger.getInstance().error("We couldn't send the missed call. Please try again later.");
        }
    }
}
