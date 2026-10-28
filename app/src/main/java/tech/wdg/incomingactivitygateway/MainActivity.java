package tech.wdg.incomingactivitygateway;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String[] CALL_PERMISSIONS = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
    };

    private final ExecutorService pairingExecutor = Executors.newSingleThreadExecutor();
    private EditText pairingCodeInput;
    private MaterialButton connectButton;
    private MaterialButton permissionButton;
    private ProgressBar progressBar;
    private TextView errorText;
    private View pairingPanel;
    private View connectedPanel;
    private View statusIndicator;
    private TextView statusTitle;
    private TextView statusDescription;
    private TextView logEntries;
    private ScrollView logScroll;
    private MaterialButton clearLogsButton;
    private SharedPreferences pairingPreferences;
    private boolean destroyed;
    private final AppLogger.Listener appLogListener = new AppLogger.Listener() {
        @Override
        public void onLogAdded(AppLogger.LogEntry entry) {
            if (!destroyed) {
                renderLogs();
            }
        }

        @Override
        public void onLogsCleared() {
            if (!destroyed) {
                renderLogs();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pairingPanel = findViewById(R.id.pairing_panel);
        connectedPanel = findViewById(R.id.connected_panel);
        pairingCodeInput = findViewById(R.id.pairing_code_input);
        connectButton = findViewById(R.id.connect_button);
        permissionButton = findViewById(R.id.permission_button);
        progressBar = findViewById(R.id.pairing_progress);
        errorText = findViewById(R.id.pairing_error);
        statusIndicator = findViewById(R.id.status_indicator);
        statusTitle = findViewById(R.id.status_title);
        statusDescription = findViewById(R.id.status_description);
        logEntries = findViewById(R.id.log_entries);
        logScroll = findViewById(R.id.log_scroll);
        clearLogsButton = findViewById(R.id.clear_logs_button);

        connectButton.setOnClickListener(view -> pairBusiness());
        findViewById(R.id.disconnect_button).setOnClickListener(view -> disconnectBusiness());
        permissionButton.setOnClickListener(view -> requestCallPermissions());
        clearLogsButton.setOnClickListener(view -> AppLogger.getInstance().clear());

        try {
            pairingPreferences = PairingStore.getPreferences(this);
            if (!TextUtils.isEmpty(pairingPreferences.getString(PairingStore.KEY_BUSINESS_ID, null))) {
                showConnectedState();
            } else {
                showPairingState();
            }
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Unable to open encrypted pairing storage", e);
            showPairingState();
            showPairingError(getString(R.string.pairing_error_response));
        }

        if (!hasCallPermissions()) {
            requestCallPermissions();
        }
        renderLogs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.getInstance().addListener(appLogListener);
        renderLogs();
    }

    @Override
    protected void onStop() {
        AppLogger.getInstance().removeListener(appLogListener);
        super.onStop();
    }

    private void renderLogs() {
        if (logEntries == null || logScroll == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (AppLogger.LogEntry entry : AppLogger.getInstance().getRecentLogs()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(entry.getFormattedMessage());
        }
        if (text.length() == 0) {
            text.append(getString(R.string.log_empty));
        }
        logEntries.setText(text);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void pairBusiness() {
        String pairingCode = pairingCodeInput.getText().toString().trim();
        if (pairingCode.isEmpty()) {
            showPairingError(getString(R.string.pairing_error_empty));
            return;
        }
        if (pairingPreferences == null) {
            showPairingError(getString(R.string.pairing_error_response));
            return;
        }

        errorText.setVisibility(View.GONE);
        setPairingInProgress(true);
        pairingExecutor.execute(() -> {
            try {
                String deviceId = Settings.Secure.getString(
                        getContentResolver(),
                        Settings.Secure.ANDROID_ID);
                if (TextUtils.isEmpty(deviceId)) {
                    throw new IOException("Android did not provide a device identifier");
                }

                String deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
                String businessId = CallbacklyApi.pair(pairingCode, deviceId, deviceName);
                pairingPreferences.edit()
                        .putString(PairingStore.KEY_BUSINESS_ID, businessId)
                        .apply();

                runOnUiThread(() -> {
                    if (destroyed) {
                        return;
                    }
                    setPairingInProgress(false);
                    showConnectedState();
                    requestCallPermissions();
                });
            } catch (CallbacklyApi.PairingRejectedException e) {
                Log.w(TAG, "Pairing request rejected by server", e);
                runOnUiThread(() -> showPairingFailure(getString(R.string.pairing_error_server)));
            } catch (JSONException e) {
                Log.e(TAG, "Pairing response could not be parsed", e);
                runOnUiThread(() -> showPairingFailure(getString(R.string.pairing_error_response)));
            } catch (IOException e) {
                Log.e(TAG, "Pairing request failed", e);
                runOnUiThread(() -> showPairingFailure(getString(R.string.pairing_error_network)));
            }
        });
    }

    private void showPairingFailure(String message) {
        if (destroyed) {
            return;
        }
        setPairingInProgress(false);
        showPairingError(message);
    }

    private void setPairingInProgress(boolean inProgress) {
        connectButton.setEnabled(!inProgress);
        pairingCodeInput.setEnabled(!inProgress);
        connectButton.setText(inProgress
                ? R.string.connecting
                : R.string.connect_business);
        progressBar.setVisibility(inProgress ? View.VISIBLE : View.GONE);
    }

    private void showPairingError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private void showPairingState() {
        pairingPanel.setVisibility(View.VISIBLE);
        connectedPanel.setVisibility(View.GONE);
    }

    private void showConnectedState() {
        pairingPanel.setVisibility(View.GONE);
        connectedPanel.setVisibility(View.VISIBLE);
        updateMonitoringState();
    }

    private void updateMonitoringState() {
        boolean hasCallPermissions = hasCallPermissions();
        if (hasCallPermissions) {
            statusTitle.setText(R.string.connected_status);
            statusDescription.setText(R.string.connected_description);
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_connected);
            permissionButton.setVisibility(View.GONE);
            startMonitoringService();
        } else {
            statusTitle.setText(R.string.connected_paired_only);
            statusDescription.setText(R.string.permission_needed_calls);
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_needs_permission);
            permissionButton.setVisibility(View.VISIBLE);
        }
    }

    private boolean hasCallPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCallPermissions() {
        if (hasCallPermissions()) {
            updateMonitoringState();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.permission_explanation)
                .setPositiveButton(R.string.continue_button, (dialog, which) -> {
                    ArrayList<String> permissions = new ArrayList<>();
                    for (String permission : CALL_PERMISSIONS) {
                        permissions.add(permission);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS);
                    }
                    requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                })
                .setNegativeButton(R.string.not_now, (dialog, which) -> updateMonitoringState())
                .show();
    }

    private void startMonitoringService() {
        Intent serviceIntent = new Intent(this, SmsReceiverService.class);
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
            ContextCompat.startForegroundService(
                    this,
                    new Intent(this, CallForegroundService.class));
            AppLogger.getInstance().success("Call monitoring is active");
            AppLogger.getInstance().success("Background call-monitoring notification started");
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start the CallBackly background service", e);
            AppLogger.getInstance().error("Unable to start a background monitoring service: "
                    + e.getMessage());
            statusDescription.setText(R.string.service_start_error);
        }
    }

    private void disconnectBusiness() {
        try {
            PairingStore.getPreferences(this).edit()
                    .remove(PairingStore.KEY_BUSINESS_ID)
                    .apply();
            stopService(new Intent(this, SmsReceiverService.class));
            stopService(new Intent(this, CallForegroundService.class));
            AppLogger.getInstance().info("Device disconnected from the business");
            showPairingState();
            pairingCodeInput.setText("");
            errorText.setVisibility(View.GONE);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Unable to clear encrypted pairing storage", e);
            statusDescription.setText(R.string.pairing_error_response);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updateMonitoringState();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        pairingExecutor.shutdownNow();
        super.onDestroy();
    }
}
