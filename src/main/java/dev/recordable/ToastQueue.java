package dev.recordable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Version-neutral notification queue ported from V1-0.09.
 */
public final class ToastQueue {
    public static final long DEFAULT_DURATION_MS = 5000L;
    public static final long ANIM_IN_MS = 250L;
    public static final long ANIM_OUT_MS = 400L;
    public static final int MAX_VISIBLE = 4;

    public static final class Entry {
        public final String message;
        public final long createdAtMs;
        public final long durationMs;

        Entry(String message, long createdAtMs, long durationMs) {
            this.message = message;
            this.createdAtMs = createdAtMs;
            this.durationMs = durationMs;
        }

        public long totalLifeMs() {
            return durationMs + ANIM_OUT_MS;
        }

        public boolean isExpired(long now) {
            return now - createdAtMs >= totalLifeMs();
        }

        public float alpha(long now) {
            long age = now - createdAtMs;
            if (age < 0L) return 0.0F;
            if (age < ANIM_IN_MS) {
                return clamp01(age / (float) ANIM_IN_MS);
            }
            if (age >= durationMs) {
                return clamp01(
                        1.0F - (age - durationMs)
                                / (float) ANIM_OUT_MS);
            }
            return 1.0F;
        }

        public float slideProgress(long now) {
            long age = now - createdAtMs;
            if (age <= 0L) return 0.0F;
            if (age >= ANIM_IN_MS) return 1.0F;
            float progress = age / (float) ANIM_IN_MS;
            return 1.0F
                    - (float) Math.pow(1.0F - progress, 3.0D);
        }

        private static float clamp01(float value) {
            return value < 0.0F
                    ? 0.0F
                    : Math.min(1.0F, value);
        }
    }

    private static final CopyOnWriteArrayList<Entry> ENTRIES =
            new CopyOnWriteArrayList<Entry>();

    private ToastQueue() {
    }

    public static void push(String message) {
        push(message, DEFAULT_DURATION_MS);
    }

    public static void push(String message, long durationMs) {
        if (message == null) return;
        String trimmed = message.trim();
        if (trimmed.isEmpty()) return;
        long now = System.currentTimeMillis();
        ENTRIES.add(new Entry(
                trimmed,
                now,
                Math.max(1000L, durationMs)));
        pruneExpired(now);
        while (ENTRIES.size() > MAX_VISIBLE) {
            ENTRIES.remove(0);
        }
    }

    public static List<Entry> active() {
        long now = System.currentTimeMillis();
        pruneExpired(now);
        return new ArrayList<Entry>(ENTRIES);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private static void pruneExpired(long now) {
        for (Entry entry : ENTRIES) {
            if (entry.isExpired(now)) {
                ENTRIES.remove(entry);
            }
        }
    }
}
