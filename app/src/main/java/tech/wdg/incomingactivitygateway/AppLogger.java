package tech.wdg.incomingactivitygateway;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AppLogger {

    public enum Status {
        INFO,
        SUCCESS,
        ERROR
    }

    public interface Listener {
        void onLogAdded(LogEntry entry);

        default void onLogsCleared() {
        }
    }

    public static final class LogEntry {
        private final long timestamp;
        private final Status status;
        private final String message;

        private LogEntry(long timestamp, Status status, String message) {
            this.timestamp = timestamp;
            this.status = status;
            this.message = message;
        }

        public String getFormattedMessage() {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(timestamp));
            return time + " [" + status + "] " + message;
        }
    }

    private static final int MAX_ENTRIES = 100;
    private static final AppLogger INSTANCE = new AppLogger();

    private final ArrayDeque<LogEntry> entries = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppLogger() {
    }

    public static AppLogger getInstance() {
        return INSTANCE;
    }

    public void info(String message) {
        add(Status.INFO, message);
    }

    public void success(String message) {
        add(Status.SUCCESS, message);
    }

    public void error(String message) {
        add(Status.ERROR, message);
    }

    public synchronized List<LogEntry> getRecentLogs() {
        return new ArrayList<>(entries);
    }

    public void clear() {
        synchronized (this) {
            entries.clear();
        }
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onLogsCleared();
            }
        });
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void add(Status status, String message) {
        LogEntry entry = new LogEntry(System.currentTimeMillis(), status, message);
        synchronized (this) {
            if (entries.size() == MAX_ENTRIES) {
                entries.removeFirst();
            }
            entries.addLast(entry);
        }
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onLogAdded(entry);
            }
        });
    }
}
