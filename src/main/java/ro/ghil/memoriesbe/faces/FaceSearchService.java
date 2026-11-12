package ro.ghil.memoriesbe.faces;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.events.EventSources;
import ro.ghil.memoriesbe.events.ImageSniffer;
import ro.ghil.memoriesbe.faces.FaceEngine.Face;
import ro.ghil.memoriesbe.storage.ImageResizer;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/**
 * Finds the photos of one event that show the person in a selfie. The selfie is read in memory, turned into 128 numbers, compared with
 * the faces stored for that event, and forgotten: nothing about it is saved or logged.
 */
@Service
public class FaceSearchService {
    /** One matching photo: the camera or guest folder it is in, its file name there, and how alike the best face was (0 to 1). */
    public record Match(String folderId, String name, double score) {}
    /** `indexed` of `total` photos have been read so far; when it is less, newer photos may still be missing from `matches`. */
    public record FaceSearchResult(List<Match> matches, int indexed, int total) {}

    private static final int SELFIE_SIDE = 1000;
    /** A second face this big (compared with the largest, by area) means the picture is not of one person. */
    private static final double SECOND_FACE_LIMIT = 0.6;
    private static final long WAIT_MS = 3000;

    private final FaceEngine engine;
    private final FaceRepository faces;
    private final FaceIndexer indexer;
    private final EventSources sources;
    private final PhotoStorage storage;
    private final double threshold;
    private final Semaphore one = new Semaphore(1);

    public FaceSearchService(FaceEngine engine, FaceRepository faces, FaceIndexer indexer, EventSources sources, PhotoStorage storage,
                             @Value("${app.faces.match-threshold:0.363}") double threshold) {
        this.engine = engine;
        this.faces = faces;
        this.indexer = indexer;
        this.sources = sources;
        this.storage = storage;
        this.threshold = threshold;
    }

    public FaceSearchResult search(Event event, byte[] selfie) {
        if (ImageSniffer.detect(selfie).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send a photo (JPEG or PNG).");
        }
        float[] mine = embeddingOf(selfie);
        // Only photos that still exist count: a photo deleted after it was read has faces stored but is no longer in the gallery.
        Map<String, Match> current = new HashMap<>();
        for (var src : sources.of(event)) {
            for (var entry : storage.entries(src.prefix(), src.label())) current.put(entry.key(), new Match(src.id().toString(), entry.name(), 0));
        }
        Map<String, Double> best = new HashMap<>();
        for (var stored : faces.faces(event.id())) {
            if (!current.containsKey(stored.objectKey())) continue;
            double score = FaceMath.cosine(mine, stored.embedding());
            if (score >= threshold) best.merge(stored.objectKey(), score, Math::max);
        }
        var matches = new ArrayList<Match>();
        best.forEach((key, score) -> matches.add(new Match(current.get(key).folderId(), current.get(key).name(), score)));
        matches.sort(Comparator.comparingDouble(Match::score).reversed().thenComparing(Match::name));
        var progress = indexer.progress(event);
        return new FaceSearchResult(matches, progress.done(), progress.total());
    }

    /** The numbers of the one face in the selfie; 422 when there is none or the picture shows several people. */
    private float[] embeddingOf(byte[] selfie) {
        byte[] small;
        try {
            small = ImageResizer.resize(selfie, SELFIE_SIDE);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "We could not read that picture. Take a new one.");
        }
        List<Face> found;
        try {
            if (!one.tryAcquire(WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Many people are searching right now. Try again in a moment.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Try again in a moment.");
        }
        try {
            found = engine.faces(small);
        } finally {
            one.release();
        }
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "We could not see a face. Look at the camera in good light and try again.");
        }
        var bySize = found.stream().sorted(Comparator.comparingLong((Face f) -> (long) f.width() * f.height()).reversed()).toList();
        if (bySize.size() > 1 && area(bySize.get(1)) > SECOND_FACE_LIMIT * area(bySize.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "More than one face is in the picture. Take it with only you in it.");
        }
        return bySize.get(0).embedding();
    }

    private static double area(Face f) {
        return (double) f.width() * f.height();
    }
}
