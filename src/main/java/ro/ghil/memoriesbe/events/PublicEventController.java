package ro.ghil.memoriesbe.events;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.events.EventSources.Source;
import ro.ghil.memoriesbe.faces.FaceEngine;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.profile.ProfileViews;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.ThumbLinks;

/** Guest gallery: reached by the readable address (photographer/event) or by the older token link. No login. */
@RestController
@RequestMapping("/api/v1/public")
class PublicEventController {
    /** A camera or a guest: `kind` is "camera" or "guest". */
    record Folder(UUID id, String name, String kind, List<GalleryPhoto> photos) {}
    /** `url` is the original; `thumb` (grid) and `view` (full screen) are small copies, relative to the API address, or null when we cannot make links. */
    record GalleryPhoto(String name, String url, String thumb, String view) {}
    /** `guestLimit` is how many photos each guest may add; 0 when guest uploads are off. `faceSearch` is true when guests may find their photos by scanning their face. */
    record Gallery(String name, int guestLimit, List<Folder> folders, String logoUrl, Features features, boolean faceSearch) {}
    /** What the owner has unlocked: their page look, the logo on downloads, and the photo editor for guests. All come with the first purchase. */
    record Features(boolean personalized, boolean editor, boolean logo) {}
    /** `camera` is the tag label (camera or guest name); `byId` is the camera or guest id used for filtering. */
    record StreamPhoto(String name, String url, String camera, String byId, String kind, String thumb, String view) {}
    /** Something photos can be filtered by: one camera or one guest. */
    record Tag(String id, String label, String kind, int count) {}
    /** One page of an event's photos from all cameras and guests, newest upload first. `next` is the offset to ask for next, or null at the end. `total` counts the filtered photos, `tags` always cover the whole event. */
    record PhotoPage(String name, LocalDate eventDate, int total, List<StreamPhoto> photos, Integer next, List<Tag> tags, int guestLimit, String logoUrl, Features features, boolean faceSearch) {}
    private record Tagged(PhotoStorage.Entry entry, Source source) {}

    private static final int MAX_PAGE = 60;
    static final String PASSWORD_HEADER = "X-Event-Password";

    private final EventRepository repo;
    private final PhotoStorage storage;
    private final EventAccess access;
    private final GuestRepository guests;
    private final ProfileRepository profiles;
    private final ProfileViews views;
    private final ThumbLinks thumbs;

    private final EventLifetime lifetime;
    private final CreditService credits;
    private final FaceEngine faceEngine;
    private final EventSources eventSources;

    PublicEventController(EventRepository repo, PhotoStorage storage, EventAccess access, GuestRepository guests,
                          ProfileRepository profiles, ProfileViews views, ThumbLinks thumbs, EventLifetime lifetime, CreditService credits, EventSources eventSources, FaceEngine faceEngine) {
        this.faceEngine = faceEngine;
        this.eventSources = eventSources;
        this.credits = credits;
        this.lifetime = lifetime;
        this.thumbs = thumbs;
        this.profiles = profiles;
        this.views = views;
        this.guests = guests;
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
                     @RequestParam(value = "by", required = false) List<String> by,
                     @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        Event event = repo.eventBySlugs(slug.toLowerCase(), eventSlug.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        lifetime.requireOpen(event.id());
        access.require(event, password);
        var sources = sources(event);
        var tagged = sources.stream()
                .flatMap(src -> storage.entries(src.prefix(), src.label()).stream().map(e -> new Tagged(e, src)))
                .toList();
        var tags = sources.stream()
                .map(src -> new Tag(src.id().toString(), src.label(), src.kind(), (int) tagged.stream().filter(t -> t.source().equals(src)).count()))
                .toList();
        Set<String> wanted = by == null ? Set.of() : Set.copyOf(by);
        var all = tagged.stream()
                .filter(t -> wanted.isEmpty() || wanted.contains(t.source().id().toString()))
                .sorted(Comparator.comparing((Tagged t) -> t.entry().modified()).reversed().thenComparing(t -> t.entry().key()))
                .toList();
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.min(Math.max(limit, 1), MAX_PAGE), all.size());
        var page = all.subList(from, to).stream()
                .map(t -> new StreamPhoto(t.entry().name(), storage.signedUrl(t.entry().key()), t.source().label(), t.source().id().toString(), t.source().kind(),
                        thumbs.url(t.entry().key(), t.entry().modified().getEpochSecond(), 480), thumbs.url(t.entry().key(), t.entry().modified().getEpochSecond(), 1600)))
                .toList();
        return new PhotoPage(event.name(), event.eventDate(), all.size(), page, to < all.size() ? to : null, tags, guestLimit(event), logoUrl(event), features(event), faceSearchOffered(event));
    }

    private List<Source> sources(Event event) {
        return eventSources.of(event);
    }

    private static int guestLimit(Event event) {
        return event.guestUploadsEnabled() ? event.guestUploadLimit() : 0;
    }

    @GetMapping("/events/{token}")
    Gallery byToken(@PathVariable String token, @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        return gallery(password, repo.eventByToken(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private Gallery gallery(String password, Event event) {
        lifetime.requireOpen(event.id());
        access.require(event, password);
        List<Folder> folders = sources(event).stream()
                .map(src -> new Folder(src.id(), src.label(), src.kind(), storage.list(src.prefix()).stream().map(this::galleryPhoto).toList()))
                .toList();
        return new Gallery(event.name(), guestLimit(event), folders, logoUrl(event), features(event), faceSearchOffered(event));
    }

    /** Guests may find their photos by face when the owner switched it on and paid, and the server has the feature and its models. */
    private boolean faceSearchOffered(Event event) {
        return event.faceSearchEnabled() && faceEngine.available() && credits.unlocked(event.ownerSub());
    }

    private Features features(Event event) {
        boolean unlocked = credits.unlocked(event.ownerSub());
        return new Features(unlocked, unlocked, unlocked);
    }

    /** The owner's logo link, or empty when they have no logo or no profile. */
    private String logoUrl(Event event) {
        if (!credits.unlocked(event.ownerSub())) return "";
        return profiles.bySub(event.ownerSub()).map(views::logoUrl).orElse("");
    }

    private GalleryPhoto galleryPhoto(PhotoStorage.Photo p) {
        if (p.key() == null) return new GalleryPhoto(p.name(), p.url(), null, null);
        return new GalleryPhoto(p.name(), p.url(), thumbs.url(p.key(), p.modified(), 480), thumbs.url(p.key(), p.modified(), 1600));
    }
}
