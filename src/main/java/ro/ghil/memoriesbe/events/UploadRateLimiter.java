package ro.ghil.memoriesbe.events;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Sliding window: one person may start at most 20 uploads per minute (kept in memory, per server). */
@Component
public class UploadRateLimiter {
    private static final int MAX = 20;
    private static final long WINDOW_MS = 60_000;

    private final ConcurrentMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean tryAcquire(String who) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= MAX) return false;
            q.addLast(now);
            return true;
        }
    }
}
