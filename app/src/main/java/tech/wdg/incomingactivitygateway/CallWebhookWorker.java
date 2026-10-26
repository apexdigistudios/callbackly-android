package tech.wdg.incomingactivitygateway;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONException;

import java.io.IOException;
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
        long callStartedAt = getInputData().getLong(KEY_CALL_STARTED_AT, 0L);
        if (callStartedAt == 0L) {
            Log.e(TAG, "Missed-call event has no call start time");
            return Result.failure();
        }

        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Call-log permission is not available for missed-call delivery");
            return Result.failure();
        }

        try {
            SharedPreferences pairing = PairingStore.getPreferences(getApplicationContext());
            String businessId = pairing.getString(PairingStore.KEY_BUSINESS_ID, null);
            if (TextUtils.isEmpty(businessId)) {
                Log.e(TAG, "No paired business is available for missed-call delivery");
                return Result.failure();
            }

            MissedCall missedCall = findMissedCall(
                    callStartedAt,
                    getInputData().getString(KEY_PHONE_NUMBER));
            if (missedCall == null) {
                Log.w(TAG, "No matching missed call is present in the call log yet");
                return Result.retry();
            }

            String contactName = getContactName(missedCall.phoneNumber);
            CallbacklyApi.sendMissedCall(missedCall.phoneNumber, contactName, businessId);
            Log.i(TAG, "Missed-call event delivered to the paired business");
            return Result.success();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Unable to access encrypted pairing information", e);
            return Result.failure();
        } catch (JSONException e) {
            Log.e(TAG, "Unable to format missed-call event", e);
            return Result.failure();
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied while reading missed-call details", e);
            return Result.failure();
        } catch (IOException e) {
            Log.w(TAG, "Missed-call delivery failed; WorkManager will retry", e);
            return Result.retry();
        }
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
