package ro.ghil.memoriesbe.storage;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Small copies of photos, made the first time someone asks for them and kept in R2 under {@code thumbs/<width>/<key>@<version>.jpg}
 * (outside every camera's folder). Decoding a camera photo takes memory and time, so only a few are made at a time; a request that
 * cannot get a turn quickly, and any photo we cannot resize, is sent to the original instead of holding a server thread.
 */
@Component
public class ThumbService {
    private static final Logger log = LoggerFactory.getLogger(ThumbService.class);
    private static final int AT_ONCE = 3;
    private static final long WAIT_MS = 3000;
    private static final long REMEMBER_FAILURE_MS = 60 * 60 * 1000L;
    private static final int MAX_FAILURES = 10_000;

    /** Either the JPEG bytes, or a link to the original (a photo we cannot, or cannot yet, resize). */
    public record Served(byte[] bytes, String redirect) {}

    private final PhotoStorage storage;
    private final Semaphore gate;
    private final long waitMs;
    /** Thumb keys that failed to build, so the same broken photo is not downloaded and decoded again on every request. */
    private final ConcurrentHashMap<String, Long> failed = new ConcurrentHashMap<>();

    @Autowired
    public ThumbService(PhotoStorage storage) {
        this(storage, AT_ONCE, WAIT_MS);
    }

    public ThumbService(PhotoStorage storage, int atOnce, long waitMs) {
        this.storage = storage;
        this.gate = new Semaphore(atOnce);
        this.waitMs = waitMs;
    }

    public static String thumbKey(String key, long version, int width) {
        return "thumbs/" + width + "/" + key + "@" + version + ".jpg";
    }

    /** Null when the original does not exist (any more). */
    public Served serve(String key, long version, int width) {
        String thumb = thumbKey(key, version, width);
        if (recentlyFailed(thumb) || !resizable(key)) return original(key);
        byte[] have = storage.tryGet(thumb);
        if (have != null) return new Served(have, null);
        boolean got;
        try {
            got = gate.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return original(key);
        }
        if (!got) return original(key); // busy: the original now, the small copy on a later visit
        try {
            have = storage.tryGet(thumb); // another request may have made it while this one waited
            if (have != null) return new Served(have, null);
            byte[] source = storage.tryGet(key);
            if (source == null) return null;
            try {
                byte[] made = ImageResizer.resize(source, width);
                storage.put(thumb, made, "image/jpeg");
                return new Served(made, null);
            } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                log.warn("Could not make a small copy of {}: {}", key, ex.toString());
                remember(thumb);
                return original(key);
            }
        } finally {
            gate.release();
        }
    }

    private Served original(String key) {
        return new Served(null, storage.signedUrl(key));
    }

    /** Java reads JPEG and PNG; WebP and GIF go straight to the original without downloading anything. */
    private static boolean resizable(String key) {
        String k = key.toLowerCase();
        return k.endsWith(".jpg") || k.endsWith(".jpeg") || k.endsWith(".png");
    }

    private boolean recentlyFailed(String thumb) {
        Long at = failed.get(thumb);
        if (at == null) return false;
        if (System.currentTimeMillis() - at < REMEMBER_FAILURE_MS) return true;
        failed.remove(thumb);
        return false;
    }

    private void remember(String thumb) {
        if (failed.size() > MAX_FAILURES) failed.clear();
        failed.put(thumb, System.currentTimeMillis());
    }
}
