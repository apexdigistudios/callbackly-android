package tech.wdg.incomingactivitygateway;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class CallbacklyApi {

    public static final String BASE_URL = "https://bcall.vercel.app";

    private CallbacklyApi() {
    }

    public static String pair(String pairingCode, String deviceId, String deviceName)
            throws IOException, JSONException {
        JSONObject payload = new JSONObject();
        payload.put("pairing_code", pairingCode);
        payload.put("device_id", deviceId);
        payload.put("device_name", deviceName);

        HttpURLConnection connection = post("/api/android/pair", payload);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new PairingRejectedException(responseCode);
            }

            JSONObject response = new JSONObject(readResponse(connection.getInputStream()));
            String businessId = response.optString("business_id", "").trim();
            if (businessId.isEmpty()) {
                throw new JSONException("Pairing response did not include business_id");
            }
            return businessId;
        } finally {
            connection.disconnect();
        }
    }

    public static void sendMissedCall(String phoneNumber, String contactName, String businessId)
            throws IOException, JSONException {
        JSONObject payload = new JSONObject();
        payload.put("phone_number", phoneNumber);
        payload.put("contact_name", contactName == null ? "" : contactName);
        payload.put("business_id", businessId);

        HttpURLConnection connection = post("/api/android/calls", payload);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Missed-call request failed with HTTP " + responseCode);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection post(String path, JSONObject payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        } catch (IOException e) {
            connection.disconnect();
            throw e;
        }
        return connection;
    }

    private static String readResponse(InputStream input) throws IOException {
        try (InputStream response = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = response.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static final class PairingRejectedException extends IOException {
        private final int statusCode;

        PairingRejectedException(int statusCode) {
            super("Pairing request failed with HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
