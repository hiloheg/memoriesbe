package ro.ghil.memoriesbe.events;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.PhotoStorage.Photo;

/** Guest gallery: reached by the readable address (photographer/event) or by the older token link. No login. */
@RestController
@RequestMapping("/api/v1/public")
class PublicEventController {
    record Folder(UUID id, String name, List<Photo> photos) {}
    record Gallery(String name, List<Folder> folders) {}
    record StreamPhoto(String name, String url, String camera) {}
    /** One page of an event's photos from all cameras, newest upload first. `next` is the offset to ask for next, or null at the end. */
    record PhotoPage(String name, LocalDate eventDate, int total, List<StreamPhoto> photos, Integer next) {}

    private static final int MAX_PAGE = 60;
    static final String PASSWORD_HEADER = "X-Event-Password";

    private final EventRepository repo;
    private final PhotoStorage storage;
    private final EventAccess access;

    PublicEventController(EventRepository repo, PhotoStorage storage, EventAccess access) {
        this.access = access;
        this.repo = repo;
        this.storage = storage;
    }

    @GetMapping("/photographers/{slug}/events/{eventSlug}")
    Gallery bySlugs(@PathVariable String slug, @PathVariable String eventSlug, @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        return gallery(password, repo.eventBySlugs(slug.toLowerCase(), eventSlug.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/photographers/{slug}/events/{eventSlug}/photos")
    PhotoPage photos(@PathVariable String slug, @PathVariable String eventSlug,
                     @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "24") int limit,
                     @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        Event event = repo.eventBySlugs(slug.toLowerCase(), eventSlug.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.require(event, password);
        var all = repo.cameras(event.id()).stream()
                .flatMap(c -> storage.entries(EventController.keyPrefix(event.id(), c.id()), c.name()).stream())
                .sorted(Comparator.comparing(PhotoStorage.Entry::modified).reversed().thenComparing(PhotoStorage.Entry::key))
                .toList();
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.min(Math.max(limit, 1), MAX_PAGE), all.size());
        var page = all.subList(from, to).stream()
                .map(e -> new StreamPhoto(e.name(), storage.signedUrl(e.key()), e.folder()))
                .toList();
        return new PhotoPage(event.name(), event.eventDate(), all.size(), page, to < all.size() ? to : null);
    }

    @GetMapping("/events/{token}")
    Gallery byToken(@PathVariable String token, @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        return gallery(password, repo.eventByToken(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private Gallery gallery(String password, Event event) {
        access.require(event, password);
        List<Folder> folders = repo.cameras(event.id()).stream()
                .map(c -> new Folder(c.id(), c.name(), storage.list(EventController.keyPrefix(event.id(), c.id()))))
                .toList();
        return new Gallery(event.name(), folders);
    }
}
