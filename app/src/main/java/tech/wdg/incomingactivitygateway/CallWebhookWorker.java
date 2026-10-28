package tech.wdg.incomingactivitygateway;

import android.Manifest;
import android.content.Context;
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
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;

public class CallWebhookWorker extends Worker {

    private static final String TAG = "CallWebhookWorker";
    private static final String KEY_CALL_STARTED_AT = "call_started_at";
    private static final String KEY_PHONE_NUMBER = "phone_number";

    public CallWebhookWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppLogger logger = AppLogger.getInstance();
        logger.info("Preparing missed-call webhook");
        long callStartedAt = getInputData().getLong(KEY_CALL_STARTED_AT, 0L);
        if (callStartedAt == 0L) {
            Log.e(TAG, "Missed-call event has no call start time");
            logger.error("Missed-call event is missing its call start time");
            return Result.failure();
        }

        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Call-log permission is not available for missed-call delivery");
            logger.error("Call logging blocked: Required Phone or Call Log permissions are disabled.");
            return Result.failure();
        }

        try {
            SharedPreferences pairing = PairingStore.getPreferences(getApplicationContext());
            String businessId = pairing.getString(PairingStore.KEY_BUSINESS_ID, null);
            if (TextUtils.isEmpty(businessId)) {
                Log.e(TAG, "No paired business is available for missed-call delivery");
                logger.error("No paired business is available for missed-call delivery");
                return Result.failure();
            }

            String deviceId = Settings.Secure.getString(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (TextUtils.isEmpty(deviceId)) {
                Log.e(TAG, "Android did not provide a device identifier for missed-call delivery");
                logger.error("Android did not provide a device identifier");
                return Result.failure();
            }

            MissedCall missedCall = findMissedCall(
                    callStartedAt,
                    getInputData().getString(KEY_PHONE_NUMBER));
            if (missedCall == null) {
                Log.w(TAG, "No matching missed call is present in the call log yet");
                logger.info("Missed call is not in the call log yet; delivery will retry");
                return Result.retry();
            }

            String contactName = getContactName(missedCall.phoneNumber);
            logger.info("Sending missed-call webhook to Vercel");
            int responseCode = CallbacklyApi.sendMissedCall(
                    missedCall.phoneNumber,
                    contactName,
                    deviceId);
            Log.i(TAG, "Missed-call event delivered with HTTP " + responseCode);
            logger.success("Webhook success: HTTP " + responseCode + " OK");
            return Result.success();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Unable to access encrypted pairing information", e);
            logger.error("Could not read the saved CallBackly pairing. Please reconnect this phone.");
            return Result.failure();
        } catch (JSONException e) {
            Log.e(TAG, "Unable to format missed-call event", e);
            logger.error("Could not format missed-call webhook: " + e.getMessage());
            return Result.failure();
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied while reading missed-call details", e);
            logger.error("Permission denied while reading missed-call details");
            return Result.failure();
        } catch (IOException e) {
            Log.w(TAG, "Missed-call delivery failed; WorkManager will retry", e);
            logger.error(getUserFriendlyError(e));
            return Result.retry();
        }
    }

    private String getUserFriendlyError(IOException error) {
        if (error instanceof CallbacklyApi.HttpResponseException) {
            int statusCode = ((CallbacklyApi.HttpResponseException) error).getStatusCode();
            if (statusCode == 404) {
                return "Device registration missing. Please re-pair this phone with your CallBackly account.";
            }
            if (statusCode == 403) {
                return "This phone's access has been revoked in the dashboard.";
            }
            if (statusCode >= 500) {
                return "Server error while saving the call. Will retry automatically.";
            }
            return "The server could not save the call (HTTP " + statusCode
                    + "). Please check the device pairing.";
        }
        if (error instanceof SocketTimeoutException || error instanceof UnknownHostException) {
            return "Could not reach the server. Please check your phone's internet connection.";
        }
        return "Could not reach the server. Please check your phone's internet connection.";
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
