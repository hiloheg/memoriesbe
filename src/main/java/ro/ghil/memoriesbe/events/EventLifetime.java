package ro.ghil.memoriesbe.events;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.credits.Plan;
import ro.ghil.memoriesbe.events.EventRepository.Limits;

/** What an event may still do given the credit that paid for it: stay online, and how many cameras it can have. An event without a plan is not limited. */
@Component
public class EventLifetime {
    private final EventRepository repo;

    public EventLifetime(EventRepository repo) {
        this.repo = repo;
    }

    public Optional<Limits> limits(UUID eventId) {
        return repo.limitsOf(eventId);
    }

    /** 410 once the event has closed or its images were deleted. */
    public void requireOpen(UUID eventId) {
        limits(eventId).ifPresent(l -> {
            if (l.purgedAt() != null || l.expiresAt().isBefore(OffsetDateTime.now())) {
                throw new ResponseStatusException(HttpStatus.GONE, "This gallery has expired");
            }
        });
    }

    /** 403 when the event already has as many cameras as its plan allows (a trial event: one). */
    public void requireCameraRoom(UUID eventId, int existingCameras) {
        limits(eventId).ifPresent(l -> {
            if (existingCameras >= Plan.of(l.plan()).maxCameras()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trial events support 1 camera. Buy a pack to create full events with more cameras.");
            }
        });
    }
}
