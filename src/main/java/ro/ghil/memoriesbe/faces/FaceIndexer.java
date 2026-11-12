package ro.ghil.memoriesbe.faces;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.events.EventSources;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.PhotoStorage.Entry;
import ro.ghil.memoriesbe.storage.ThumbService;

/**
 * Reads the photos of one event that were not read yet (new, or replaced since) and stores the faces found in them, newest photos first.
 * It works on the 1600 px copy, which is already how the gallery shows photos, so the original is not downloaded again.
 * A photo that fails three times is given up on, so one bad file cannot keep the indexer busy forever.
 */
@Component
public class FaceIndexer {
    private static final Logger log = LoggerFactory.getLogger(FaceIndexer.class);
    private static final int WIDTH = 1600;

    /** How many of the event's photos are finished (read, or given up on) and how many there are. */
    public record Progress(int done, int total) {}

    private final EventSources sources;
    private final PhotoStorage storage;
    private final ThumbService thumbs;
    private final FaceEngine engine;
    private final FaceRepository faces;

    public FaceIndexer(EventSources sources, PhotoStorage storage, ThumbService thumbs, FaceEngine engine, FaceRepository faces) {
        this.sources = sources;
        this.storage = storage;
        this.thumbs = thumbs;
        this.engine = engine;
        this.faces = faces;
    }

    private List<Entry> photos(Event event) {
        return sources.of(event).stream().flatMap(src -> storage.entries(src.prefix(), src.label()).stream()).toList();
    }

    private static long version(Entry e) {
        return e.modified().getEpochSecond();
    }

    /** Tries up to `limit` pending photos, newest first, and returns how many were read successfully. */
    public int indexSome(Event event, int limit) {
        Map<String, Long> done = faces.indexedVersions(event.id());
        var pending = photos(event).stream()
                .filter(e -> !Long.valueOf(version(e)).equals(done.get(e.key())))
                .sorted(Comparator.comparing(Entry::modified).reversed().thenComparing(Entry::key))
                .limit(Math.max(limit, 0))
                .toList();
        int ok = 0;
        for (Entry e : pending) {
            if (read(event, e)) ok++;
        }
        return ok;
    }

    private boolean read(Event event, Entry e) {
        long version = version(e);
        try {
            var served = thumbs.serve(e.key(), version, WIDTH);
            if (served == null || served.bytes() == null) throw new IllegalStateException("no small copy to read");
            var found = engine.faces(served.bytes());
            faces.savePhoto(event.id(), e.key(), version, found.stream().map(FaceEngine.Face::embedding).toList());
            return true;
        } catch (RuntimeException | OutOfMemoryError ex) {
            fail(event, e, version, ex);
            return false;
        }
    }

    private void fail(Event event, Entry e, long version, Throwable why) {
        int attempt = faces.attempts(event.id(), e.key()) + 1;
        log.warn("Could not read the faces of {} (attempt {} of {}): {}", e.key(), attempt, FaceRepository.MAX_ATTEMPTS, why.toString());
        if (attempt >= FaceRepository.MAX_ATTEMPTS) faces.savePhoto(event.id(), e.key(), version, List.of());
        else faces.markFailedAttempt(event.id(), e.key(), version);
    }

    public Progress progress(Event event) {
        Map<String, Long> done = faces.indexedVersions(event.id());
        var all = photos(event);
        int finished = (int) all.stream().filter(e -> Long.valueOf(version(e)).equals(done.get(e.key()))).count();
        return new Progress(finished, all.size());
    }
}
