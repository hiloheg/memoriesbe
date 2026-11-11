package ro.ghil.memoriesbe.events;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/**
 * Deletes the images of events whose time is up and keeps only their details (name, date, icon, address), marked as purged.
 * Camera logins, camera rows and guests (names and photos) go with the images. One failing event is skipped and retried the next night.
 */
@Component
public class EventPurger {
    private static final Logger log = LoggerFactory.getLogger(EventPurger.class);
    private static final int BATCH = 50;

    private final EventRepository repo;
    private final SftpGoClient sftpgo;
    private final PhotoStorage storage;
    private final GuestRepository guests;

    public EventPurger(EventRepository repo, SftpGoClient sftpgo, PhotoStorage storage, GuestRepository guests) {
        this.repo = repo;
        this.sftpgo = sftpgo;
        this.storage = storage;
        this.guests = guests;
    }

    /** Returns how many events were purged now. */
    public int purgeDue(OffsetDateTime now) {
        int purged = 0;
        for (var id : repo.dueForPurge(now, BATCH)) {
            try {
                var cameras = repo.cameras(id);
                for (var c : cameras) {
                    try {
                        sftpgo.deleteUser(c.sftpUsername());
                    } catch (HttpClientErrorException.NotFound ignored) {
                        // already gone
                    }
                }
                storage.deleteAll("events/" + id + "/");
                for (var c : cameras) repo.deleteCamera(c.id());
                guests.deleteAllFor(id);
                repo.markPurged(id);
                purged++;
            } catch (RuntimeException ex) {
                log.warn("Could not purge event {}: {}. It will be tried again tomorrow.", id, ex.toString());
            }
        }
        return purged;
    }
}
