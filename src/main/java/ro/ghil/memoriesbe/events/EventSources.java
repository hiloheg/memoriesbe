package ro.ghil.memoriesbe.events;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.ghil.memoriesbe.events.EventRepository.Event;

/** Where an event's photos are kept: one folder per camera, then one per guest who still has photos. */
@Component
public class EventSources {
    /** A camera or a guest: `kind` is "camera" or "guest"; `prefix` is the R2 key prefix of their photos. */
    public record Source(UUID id, String label, String kind, String prefix) {}

    private final EventRepository repo;
    private final GuestRepository guests;

    EventSources(EventRepository repo, GuestRepository guests) {
        this.repo = repo;
        this.guests = guests;
    }

    /** Cameras first, then the guests who still have photos here. */
    public List<Source> of(Event event) {
        var out = new ArrayList<Source>();
        repo.cameras(event.id()).forEach(c -> out.add(new Source(c.id(), c.name(), "camera", EventController.keyPrefix(event.id(), c.id()))));
        guests.guestsWithPhotos(event.id()).forEach(g -> out.add(new Source(g.id(), g.name(), "guest", EventController.guestPrefix(event.id(), g.id()))));
        return out;
    }
}
