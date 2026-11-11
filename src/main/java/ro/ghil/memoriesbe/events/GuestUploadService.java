package ro.ghil.memoriesbe.events;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.events.GuestRepository.Guest;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/** Rules for photos added by guests: limits, file type, rate and where the file is stored. */
@Service
public class GuestUploadService {
    /** `blocked`: the event creator stopped this guest from adding photos. */
    public record Status(boolean enabled, int limit, int used, boolean blocked) {}

    static final long MAX_BYTES = 15L * 1024 * 1024;
    /** Most guest photos one event can hold, whoever added them. */
    static final int EVENT_CAP = 2000;

    private final GuestRepository guests;
    private final PhotoStorage storage;
    private final TransactionTemplate tx;
    private final UploadRateLimiter limiter;

    public GuestUploadService(GuestRepository guests, PhotoStorage storage, PlatformTransactionManager txManager, UploadRateLimiter limiter) {
        this.guests = guests;
        this.storage = storage;
        this.tx = new TransactionTemplate(txManager);
        this.limiter = limiter;
    }

    public Status status(Event event, String sub) {
        boolean enabled = event.guestUploadsEnabled() && event.guestUploadLimit() > 0;
        var guest = guests.guestBySub(event.id(), sub);
        int used = guest.map(g -> guests.usedBy(g.id())).orElse(0);
        return new Status(enabled, enabled ? event.guestUploadLimit() : 0, used, guest.map(g -> g.blockedAt() != null).orElse(false));
    }

    /** Stores one photo and returns how many more this guest may add. */
    public int upload(Event event, String sub, String name, byte[] bytes) {
        if (!event.guestUploadsEnabled() || event.guestUploadLimit() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Uploads are switched off for this event");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "That photo is too large. The limit is 15 MB");
        }
        String ext = ImageSniffer.detect(bytes)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a JPEG, PNG or WebP photo"));
        if (!limiter.tryAcquire(sub)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many uploads. Wait a minute and try again");
        }
        return tx.execute(status -> {
            Guest guest = guests.ensureGuest(event.id(), sub, name);
            Guest locked = guests.lockGuest(guest.id()); // one upload at a time per guest, so the count below cannot be raced
            if (locked.blockedAt() != null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The organizer has stopped you from adding photos to this event");
            }
            int used = guests.usedBy(guest.id());
            if (used >= event.guestUploadLimit() || guests.totalFor(event.id()) >= EVENT_CAP) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You've used all your uploads");
            }
            UUID id = UUID.randomUUID();
            String key = EventController.guestPrefix(event.id(), guest.id()) + id + "." + ext;
            guests.insertUpload(id, event.id(), guest.id(), key);
            storage.put(key, bytes, ImageSniffer.contentType(ext));
            return event.guestUploadLimit() - used - 1;
        });
    }
}
