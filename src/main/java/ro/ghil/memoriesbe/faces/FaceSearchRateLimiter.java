package ro.ghil.memoriesbe.faces;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Sliding window: one client may scan at most 5 times a minute (kept in memory, per server), so nobody can use the search to comb through an event. */
@Component
public class FaceSearchRateLimiter {
    private static final int MAX = 5;
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_CLIENTS = 20_000;

    private final ConcurrentMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean tryAcquire(String who) {
        long now = System.currentTimeMillis();
        if (hits.size() > MAX_CLIENTS) hits.clear();
        Deque<Long> q = hits.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= MAX) return false;
            q.addLast(now);
            return true;
        }
    }
}
