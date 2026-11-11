package ro.ghil.memoriesbe.profile;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository;

/** Page behind the photographer address: picture, name, bio, links and events. No login. */
@RestController
@RequestMapping("/api/v1/public/photographers")
class PublicProfileController {
    record EventItem(String name, String slug, LocalDate eventDate, String icon, boolean locked) {}
    record Page(String name, String slug, String tagline, String bio, String accent, String website,
                String instagram, String picture, boolean followGate,
                List<ProfileRepository.Link> links, ProfileViews.Background background, boolean featured, List<EventItem> events) {}

    private final ProfileRepository profiles;
    private final EventRepository events;
    private final ProfileViews views;

    PublicProfileController(ProfileRepository profiles, EventRepository events, ProfileViews views) {
        this.profiles = profiles;
        this.events = events;
        this.views = views;
    }

    @GetMapping("/{slug}")
    Page page(@PathVariable String slug) {
        var p = profiles.bySlug(slug.toLowerCase()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // A main event is shown on its own; otherwise only the events marked public are listed.
        var main = p.mainEventId() == null ? java.util.Optional.<EventRepository.Event>empty()
                : events.eventById(p.mainEventId()).filter(e -> e.ownerSub().equals(p.ownerSub()));
        var shown = main.isPresent() ? List.of(main.get())
                : events.eventsOf(p.ownerSub()).stream().filter(EventRepository.Event::listed).toList();
        var items = shown.stream().map(e -> new EventItem(e.name(), e.slug(), e.eventDate(), e.icon(), e.accessPasswordEnc() != null)).toList();
        return new Page(p.name(), p.slug(), nz(p.tagline()), nz(p.bio()), ProfileViews.accent(p),
                nz(p.website()), nz(p.instagram()), views.pictureUrl(p), p.followGate(),
                profiles.links(p.ownerSub()), ProfileViews.background(p), main.isPresent(), items);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
