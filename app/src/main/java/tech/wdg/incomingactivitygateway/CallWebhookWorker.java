package tech.wdg.incomingactivitygateway;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;

public class CallWebhookWorker extends Worker {

    private static final String TAG = "CallWebhookWorker";
    private static final String LEAD_NOTIFICATION_CHANNEL_ID = "missed_call_leads";
    private static final String KEY_CALL_STARTED_AT = "call_started_at";
    private static final String KEY_PHONE_NUMBER = "phone_number";

    public CallWebhookWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppLogger logger = AppLogger.getInstance();
        logger.info("Getting the missed call ready to send.");
        long callStartedAt = getInputData().getLong(KEY_CALL_STARTED_AT, 0L);
        if (callStartedAt == 0L) {
            Log.e(TAG, "Missed-call event has no call start time");
            logger.error("We couldn't tell when the missed call happened.");
            return Result.failure();
        }

        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Call-log permission is not available for missed-call delivery");
            logger.error("Call logging is off. Turn on Phone and Call Log permissions in the app.");
            return Result.failure();
        }

        try {
            SharedPreferences pairing = PairingStore.getPreferences(getApplicationContext());
            String businessId = pairing.getString(PairingStore.KEY_BUSINESS_ID, null);
            if (TextUtils.isEmpty(businessId)) {
                Log.e(TAG, "No paired business is available for missed-call delivery");
                logger.error("This phone isn't linked. Please link it in the app.");
                return Result.failure();
            }

            String deviceId = Settings.Secure.getString(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (TextUtils.isEmpty(deviceId)) {
                Log.e(TAG, "Android did not provide a device identifier for missed-call delivery");
                logger.error("We couldn't identify this phone. Please try pairing it again.");
                return Result.failure();
            }

            MissedCall missedCall = findMissedCall(
                    callStartedAt,
                    getInputData().getString(KEY_PHONE_NUMBER));
            if (missedCall == null) {
                Log.w(TAG, "No matching missed call is present in the call log yet");
                logger.info("The missed call isn't ready yet. We'll check again shortly.");
                return Result.retry();
            }

            String contactName = getContactName(missedCall.phoneNumber);
            logger.info("Incoming call missed! Sending to your dashboard...");
            int responseCode = CallbacklyApi.sendMissedCall(
                    missedCall.phoneNumber,
                    contactName,
                    deviceId);
            Log.i(TAG, "Missed-call event delivered with HTTP " + responseCode);
            logger.success("Success! Lead sent to dashboard.");
            if (responseCode == 200) {
                showLeadCapturedNotification(logger);
            }
            return Result.success();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Unable to access encrypted pairing information", e);
            logger.error("Phone unlinked. Please re-pair in the app.");
            return Result.failure();
        } catch (JSONException e) {
            Log.e(TAG, "Unable to format missed-call event", e);
            logger.error("We couldn't prepare the missed call to send.");
            return Result.failure();
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied while reading missed-call details", e);
            logger.error("Call logging is off. Turn on Phone and Call Log permissions in the app.");
            return Result.failure();
        } catch (IOException e) {
            Log.w(TAG, "Missed-call delivery failed; WorkManager will retry", e);
            logger.error(getUserFriendlyError(e));
            return Result.retry();
        }
    }

    private void showLeadCapturedNotification(AppLogger logger) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            logger.info("Lead sent. Turn on notifications to get a follow-up reminder here.");
            return;
        }

        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    LEAD_NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.lead_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.lead_notification_channel_description));
            notificationManager.createNotificationChannel(channel);
        }

        Intent activityIntent = new Intent(context, MainActivity.class);
        activityIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                (int) (System.currentTimeMillis() & 0x7fffffff),
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(
                context,
                LEAD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_f)
                .setContentTitle(context.getString(R.string.lead_notification_title))
                .setContentText(context.getString(R.string.lead_notification_body))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.lead_notification_body)))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat.from(context).notify(
                    (int) (System.currentTimeMillis() & 0x7fffffff),
                    notification.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Lead notification could not be shown", e);
            logger.info("Lead sent, but phone notifications are turned off.");
        }
    }

    private String getUserFriendlyError(IOException error) {
        if (error instanceof CallbacklyApi.HttpResponseException) {
            int statusCode = ((CallbacklyApi.HttpResponseException) error).getStatusCode();
            if (statusCode == 404) {
                return "Phone unlinked. Please re-pair in the app.";
            }
            if (statusCode == 403) {
                return "This phone is unlinked. Please check your CallBackly dashboard.";
            }
            if (statusCode >= 500) {
                return "We couldn't send the lead yet. We'll try again.";
            }
            return "We couldn't send the lead. Please check that this phone is linked.";
        }
        if (error instanceof SocketTimeoutException || error instanceof UnknownHostException) {
            return "No internet connection. Waiting to reconnect...";
        }
        return "No internet connection. Waiting to reconnect...";
    }

    private MissedCall findMissedCall(long callStartedAt, String reportedNumber) {
        String[] projection = {
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE
        };
        String selection = CallLog.Calls.TYPE + " = ? AND " + CallLog.Calls.DATE + " >= ?";
        String[] selectionArgs = {
                String.valueOf(CallLog.Calls.MISSED_TYPE),
                String.valueOf(Math.max(0L, callStartedAt - 1000L))
        };
        String sortOrder = CallLog.Calls.DATE + " ASC";
        Uri callLogUri = CallLog.Calls.CONTENT_URI;

        try (Cursor cursor = getApplicationContext().getContentResolver().query(
                callLogUri,
                projection,
                selection,
                selectionArgs,
                sortOrder)) {
            if (cursor == null) {
                return null;
            }

            boolean hasReportedNumber = !TextUtils.isEmpty(reportedNumber)
                    && !reportedNumber.replaceAll("[^0-9]", "").isEmpty();
            while (cursor.moveToNext()) {
                String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                if (TextUtils.isEmpty(number) || date < callStartedAt - 1000L) {
                    continue;
                }

                if (!hasReportedNumber || samePhoneNumber(number, reportedNumber)) {
                    return new MissedCall(number);
                }
            }
            return null;
        }
    }

    private boolean samePhoneNumber(String first, String second) {
        String normalizedFirst = first.replaceAll("[^0-9]", "");
        String normalizedSecond = second.replaceAll("[^0-9]", "");
        return normalizedFirst.equals(normalizedSecond)
                || normalizedFirst.endsWith(normalizedSecond)
                || normalizedSecond.endsWith(normalizedFirst);
    }

    private String getContactName(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }

        Uri lookupUri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(phoneNumber)
                .build();
        String[] projection = { ContactsContract.PhoneLookup.DISPLAY_NAME };
        try (Cursor cursor = getApplicationContext().getContentResolver().query(
                lookupUri,
                projection,
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME));
            }
        }
        return "";
    }

    private static final class MissedCall {
        final String phoneNumber;

        MissedCall(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }
    }
}
