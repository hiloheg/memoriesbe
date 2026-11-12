package ro.ghil.memoriesbe.events;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.ThumbLinks;

/** The event creator's view of every photo in the event (cameras and guests together), and a way to remove the camera ones they do not want. */
@RestController
@RequestMapping("/api/v1/events/{id}")
class EventPhotosController {
    private static final int MAX_PAGE = 60;

    /** `kind` is "camera" or "guest". Camera photos are deleted by `sourceId` + `name`; guest photos by `uploadId`. `thumb` is a small copy relative to the API address. */
    record ManagedPhoto(String kind, String sourceId, String source, String name, String uploadId, String url, String thumb, Instant modified) {}
    /** Who photos come from, with how many each; `blocked` only means something for guests. */
    record Source(String id, String label, String kind, int count, boolean blocked) {}
    /** One page, newest first. `next` is the offset to ask for next, or null at the end; `total` counts the photos of the chosen source. */
    record Page(int total, List<ManagedPhoto> items, Integer next, List<Source> sources) {}
    private record Tagged(PhotoStorage.Entry entry, EventSources.Source source) {}

    private final EventRepository events;
    private final GuestRepository guests;
    private final EventSources eventSources;
    private final PhotoStorage storage;
    private final ThumbLinks thumbs;

    EventPhotosController(EventRepository events, GuestRepository guests, EventSources eventSources, PhotoStorage storage, ThumbLinks thumbs) {
        this.events = events;
        this.guests = guests;
        this.eventSources = eventSources;
        this.storage = storage;
        this.thumbs = thumbs;
    }

    @GetMapping("/photos")
    Page list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(defaultValue = "0") int offset,
              @RequestParam(defaultValue = "24") int limit, @RequestParam(required = false) UUID source) {
        var event = events.eventOf(id, jwt.getSubject()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var tagged = eventSources.of(event).stream()
                .flatMap(src -> storage.entries(src.prefix(), src.label()).stream().map(e -> new Tagged(e, src)))
                .toList();
        var blocked = guests.guestsOf(id).stream().filter(GuestRepository.GuestSummary::blocked).map(GuestRepository.GuestSummary::id).collect(Collectors.toSet());
        var sources = eventSources.of(event).stream()
                .map(src -> new Source(src.id().toString(), src.label(), src.kind(), (int) tagged.stream().filter(t -> t.source().equals(src)).count(),
                        "guest".equals(src.kind()) && blocked.contains(src.id())))
                .toList();
        var uploadIds = guests.uploads(id).stream().collect(Collectors.toMap(GuestRepository.Upload::objectKey, GuestRepository.Upload::id, (a, b) -> a));
        var all = tagged.stream()
                .filter(t -> source == null || t.source().id().equals(source))
                .sorted(Comparator.comparing((Tagged t) -> t.entry().modified()).reversed().thenComparing(t -> t.entry().key()))
                .toList();
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.min(Math.max(limit, 1), MAX_PAGE), all.size());
        var items = all.subList(from, to).stream()
                .map(t -> new ManagedPhoto(t.source().kind(), t.source().id().toString(), t.source().label(), t.entry().name(),
                        uploadIds.containsKey(t.entry().key()) ? uploadIds.get(t.entry().key()).toString() : null,
                        storage.signedUrl(t.entry().key()), thumbs.url(t.entry().key(), t.entry().modified().getEpochSecond(), 480), t.entry().modified()))
                .toList();
        return new Page(all.size(), items, to < all.size() ? to : null, sources);
    }

    /** Deletes one photo (and its small copies). The name must be a photo that is really in that camera's folder; nothing else is ever deleted. */
    @DeleteMapping("/camera-photos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam UUID camera, @RequestParam String name) {
        owned(jwt, id);
        var cam = events.cameras(id).stream().filter(c -> c.id().equals(camera)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var entry = storage.entries(EventController.keyPrefix(id, cam.id()), cam.name()).stream()
                .filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            storage.delete(entry.key());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not delete the photo from storage. Try again.");
        }
    }

    private void owned(Jwt jwt, UUID id) {
        events.eventOf(id, jwt.getSubject()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
