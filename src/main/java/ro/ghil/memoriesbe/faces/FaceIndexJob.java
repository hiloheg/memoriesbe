package ro.ghil.memoriesbe.faces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventRepository;

/** Every half minute, reads a few more photos for the events that have face search on and whose owner has paid. */
@Component
public class FaceIndexJob {
    private static final Logger log = LoggerFactory.getLogger(FaceIndexJob.class);

    private final EventRepository events;
    private final CreditService credits;
    private final FaceIndexer indexer;
    private final FaceEngine engine;
    private final int batch;

    public FaceIndexJob(EventRepository events, CreditService credits, FaceIndexer indexer, FaceEngine engine,
                        @Value("${app.faces.index-batch:10}") int batch) {
        this.events = events;
        this.credits = credits;
        this.indexer = indexer;
        this.engine = engine;
        this.batch = batch;
    }

    @Scheduled(initialDelayString = "${app.faces.interval-ms:30000}", fixedDelayString = "${app.faces.interval-ms:30000}")
    void run() {
        try {
            tick();
        } catch (RuntimeException ex) {
            log.warn("Face indexing run failed: {}", ex.toString());
        }
    }

    /** One run: a shared budget of `batch` photos is spent on the eligible events in order. One failing event does not stop the others. */
    public void tick() {
        if (!engine.available()) return;
        int left = batch;
        for (var event : events.faceSearchEvents()) {
            if (left <= 0) break;
            if (!credits.unlocked(event.ownerSub())) continue;
            try {
                left -= indexer.indexSome(event, left);
            } catch (RuntimeException ex) {
                log.warn("Could not index the faces of event {}: {}", event.id(), ex.toString());
            }
        }
    }
}
