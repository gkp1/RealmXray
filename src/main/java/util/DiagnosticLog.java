package util;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Always-on structured diagnostic logger, independent of {@link Util#saveLogs}. Appends one JSON
 * object per line to a file, so it survives even when a GUI front-end (e.g. Tomato) disables the
 * regular {@link Util} logging and there is no attached console to see stderr/stdout.
 * <p>
 * Intended for instrumenting candidate causes of the sniffer silently dying/stalling: capture and
 * processing thread lifecycle, TCP packet-loss/stop triggers, listener exceptions during emit, and
 * stream-assembly desync. Not meant to replace {@link Util#printLogs}, only to guarantee visibility
 * for these specific diagnostic events.
 */
public class DiagnosticLog {
    private static final Gson gson = new Gson();
    private static PrintWriter writer;
    private static final Object lock = new Object();

    private static synchronized PrintWriter getWriter() {
        if (writer == null) {
            try {
                String fileName = "realmshark-diagnostics-" +
                        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss").format(java.time.LocalDateTime.now()) +
                        ".jsonl";
                writer = new PrintWriter(new FileWriter(new File(fileName), true));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return writer;
    }

    /**
     * Logs a diagnostic event with no associated exception.
     *
     * @param event   Short machine-readable event tag, e.g. "CAPTURE_THREAD_START".
     * @param message Free-text detail for the event.
     */
    public static void log(String event, String message) {
        log(event, message, null);
    }

    /**
     * Logs a diagnostic event, optionally with an associated exception/stack trace.
     *
     * @param event     Short machine-readable event tag, e.g. "PACKET_LOSS_DETECTED".
     * @param message   Free-text detail for the event.
     * @param throwable Exception tied to this event, or null if none.
     */
    public static void log(String event, String message, Throwable throwable) {
        Entry entry = new Entry();
        entry.timestamp = Instant.now().toString();
        entry.thread = Thread.currentThread().getName();
        entry.event = event;
        entry.message = message;
        if (throwable != null) {
            entry.exceptionClass = throwable.getClass().getName();
            entry.stackTrace = stackTraceToString(throwable);
        }

        synchronized (lock) {
            PrintWriter w = getWriter();
            if (w != null) {
                w.println(gson.toJson(entry));
                w.flush();
            }
        }
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static class Entry {
        String timestamp;
        String thread;
        String event;
        String message;
        String exceptionClass;
        String stackTrace;
    }
}
