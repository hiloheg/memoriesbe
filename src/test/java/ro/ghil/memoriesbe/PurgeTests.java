package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import ro.ghil.memoriesbe.events.EventPurger;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.events.EventRepository.Camera;
import ro.ghil.memoriesbe.events.GuestRepository;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;

class PurgeTests {

	private final EventRepository repo = mock(EventRepository.class);
	private final SftpGoClient sftpgo = mock(SftpGoClient.class);
	private final PhotoStorage storage = mock(PhotoStorage.class);
	private final GuestRepository guests = mock(GuestRepository.class);
	private final EventPurger purger = new EventPurger(repo, sftpgo, storage, guests);
	private final OffsetDateTime now = OffsetDateTime.parse("2027-03-01T03:30:00Z");

	private Camera camera(UUID event, String user) {
		return new Camera(UUID.randomUUID(), event, "Cam", user, now, null);
	}

	@Test
	void purgesPhotosCamerasAndGuestsButKeepsTheEvent() {
		UUID id = UUID.randomUUID();
		var cam = camera(id, "cam_x");
		when(repo.dueForPurge(now, 50)).thenReturn(List.of(id));
		when(repo.cameras(id)).thenReturn(List.of(cam));
		assertEquals(1, purger.purgeDue(now));
		var order = inOrder(sftpgo, storage, repo, guests);
		order.verify(sftpgo).deleteUser("cam_x");
		order.verify(storage).deleteAll("events/" + id + "/");
		order.verify(repo).deleteCamera(cam.id());
		order.verify(guests).deleteAllFor(id);
		order.verify(repo).markPurged(id);
		verify(repo, never()).deleteEvent(any());
	}

	@Test
	void oneFailureDoesNotStopTheBatch() {
		UUID bad = UUID.randomUUID(), good = UUID.randomUUID();
		when(repo.dueForPurge(now, 50)).thenReturn(List.of(bad, good));
		when(repo.cameras(any())).thenReturn(List.of());
		doThrow(new IllegalStateException("R2 refused")).when(storage).deleteAll("events/" + bad + "/");
		assertEquals(1, purger.purgeDue(now));
		verify(repo, never()).markPurged(bad);
		verify(repo).markPurged(good);
	}

	@Test
	void nothingDueDoesNothing() {
		when(repo.dueForPurge(now, 50)).thenReturn(List.of());
		assertEquals(0, purger.purgeDue(now));
		verify(storage, never()).deleteAll(anyString());
	}

	@Test
	void anAlreadyGoneCameraUserIsIgnored() {
		UUID id = UUID.randomUUID();
		when(repo.dueForPurge(now, 50)).thenReturn(List.of(id));
		when(repo.cameras(id)).thenReturn(List.of(camera(id, "cam_gone")));
		doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "gone", new HttpHeaders(), new byte[0], null))
				.when(sftpgo).deleteUser("cam_gone");
		assertEquals(1, purger.purgeDue(now));
		verify(repo).markPurged(id);
	}
}
