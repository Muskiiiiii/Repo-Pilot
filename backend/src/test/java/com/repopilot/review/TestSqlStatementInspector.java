package com.repopilot.review;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestSqlStatementInspector implements StatementInspector {

    private static final List<String> capturedSqls = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicBoolean recording = new AtomicBoolean(false);

    public static void startRecording() {
        synchronized (capturedSqls) {
            capturedSqls.clear();
        }
        recording.set(true);
    }

    public static void stopRecording() {
        recording.set(false);
    }

    public static List<String> getCapturedSqls() {
        synchronized (capturedSqls) {
            return new ArrayList<>(capturedSqls);
        }
    }

    public static long countInserts() {
        synchronized (capturedSqls) {
            return capturedSqls.stream()
                    .filter(sql -> sql != null && sql.trim().toUpperCase().startsWith("INSERT"))
                    .count();
        }
    }

    public static long countUpdates() {
        synchronized (capturedSqls) {
            return capturedSqls.stream()
                    .filter(sql -> sql != null && sql.trim().toUpperCase().startsWith("UPDATE"))
                    .count();
        }
    }

    public static long countDeletes() {
        synchronized (capturedSqls) {
            return capturedSqls.stream()
                    .filter(sql -> sql != null && sql.trim().toUpperCase().startsWith("DELETE"))
                    .count();
        }
    }

    @Override
    public String inspect(String sql) {
        if (recording.get() && sql != null) {
            capturedSqls.add(sql);
        }
        return sql;
    }
}
