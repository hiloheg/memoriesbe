package ro.ghil.memoriesbe.events;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/** The event creator's view of photos added by guests, and the only way to remove them. */
@RestController
@RequestMapping("/api/v1/events/{id}")
class GuestPhotosController {
    record GuestPhoto(UUID id, UUID guestId, String url, String guestName, OffsetDateTime createdAt) {}
    record BlockRequest(boolean blocked) {}

    private final EventRepository events;
    private final GuestRepository guests;
    private final PhotoStorage storage;

    GuestPhotosController(EventRepository events, GuestRepository guests, PhotoStorage storage) {
        this.events = events;
        this.guests = guests;
        this.storage = storage;
    }

    @GetMapping("/guest-photos")
    List<GuestPhoto> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        owned(jwt, id);
        return guests.uploads(id).stream()
                .map(u -> new GuestPhoto(u.id(), u.guestId(), storage.signedUrl(u.objectKey()),
                        guests.guest(u.guestId()).map(GuestRepository.Guest::name).orElse("Guest"), u.createdAt()))
                .toList();
    }

    /** Removes the file, then marks the upload deleted. The row stays, so the photo still counts toward the guest's limit. */
    @DeleteMapping("/guest-photos/{uploadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID uploadId) {
        owned(jwt, id);
        var upload = guests.upload(uploadId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            storage.delete(upload.objectKey());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not delete the photo from storage. Try again.");
        }
        guests.markDeleted(upload.id());
    }

    /** Everyone who has uploaded to this event, with their photo count and whether they are blocked. */
    @GetMapping("/guests")
    List<GuestRepository.GuestSummary> guests(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        owned(jwt, id);
        return guests.guestsOf(id);
    }

    /** Blocking stops the guest adding more photos; photos they already added stay until deleted. Unblocking lets them add again. */
    @PutMapping("/guests/{guestId}/blocked")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID guestId, @RequestBody BlockRequest req) {
        owned(jwt, id);
        var guest = guests.guest(guestId).filter(g -> g.eventId().equals(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        guests.setBlocked(guest.id(), req.blocked());
    }

    private void owned(Jwt jwt, UUID id) {
        events.eventOf(id, jwt.getSubject()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
