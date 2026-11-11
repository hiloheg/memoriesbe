package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventLifetime;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.events.EventRepository.Limits;

class EventLifetimeTests {

	private final EventRepository repo = mock(EventRepository.class);
	private final EventLifetime lifetime = new EventLifetime(repo);
	private final UUID id = UUID.randomUUID();

	private Limits limits(String plan, int closesInDays, OffsetDateTime purgedAt) {
		var closes = OffsetDateTime.now().plusDays(closesInDays);
		return new Limits(id, plan, 300, 1, closes, closes.plusDays(14), purgedAt);
	}

	private HttpStatus statusOf(Runnable r) {
		return (HttpStatus) assertThrows(ResponseStatusException.class, r::run).getStatusCode();
	}

	@Test
	void anOpenEventPasses() {
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits("full", 5, null)));
		lifetime.requireOpen(id);
	}

	@Test
	void anEventWithoutAPlanPasses() {
		lifetime.requireOpen(id);
	}

	@Test
	void anExpiredEventIs410() {
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits("full", -1, null)));
		assertEquals(HttpStatus.GONE, statusOf(() -> lifetime.requireOpen(id)));
	}

	@Test
	void aPurgedEventIs410EvenIfItsDateIsStillAhead() {
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits("full", 5, OffsetDateTime.now())));
		assertEquals(HttpStatus.GONE, statusOf(() -> lifetime.requireOpen(id)));
	}

	@Test
	void aTrialEventHasRoomForOneCameraOnly() {
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits("trial", 5, null)));
		lifetime.requireCameraRoom(id, 0);
		assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> lifetime.requireCameraRoom(id, 1)));
	}

	@Test
	void aFullEventHasRoomForManyCameras() {
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits("full", 5, null)));
		lifetime.requireCameraRoom(id, 50);
	}
}
