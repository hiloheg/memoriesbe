package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.events.EventSources;
import ro.ghil.memoriesbe.events.EventSources.Source;
import ro.ghil.memoriesbe.faces.FaceEngine;
import ro.ghil.memoriesbe.faces.FaceEngine.Face;
import ro.ghil.memoriesbe.faces.FaceIndexJob;
import ro.ghil.memoriesbe.faces.FaceIndexer;
import ro.ghil.memoriesbe.faces.FaceRepository;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.PhotoStorage.Entry;
import ro.ghil.memoriesbe.storage.ThumbService;
import ro.ghil.memoriesbe.storage.ThumbService.Served;

class FaceIndexerTests {

	private final EventSources sources = mock(EventSources.class);
	private final PhotoStorage storage = mock(PhotoStorage.class);
	private final ThumbService thumbs = mock(ThumbService.class);
	private final FaceEngine engine = mock(FaceEngine.class);
	private final FaceRepository faces = mock(FaceRepository.class);
	private final FaceIndexer indexer = new FaceIndexer(sources, storage, thumbs, engine, faces);

	private final UUID eventId = UUID.randomUUID();
	private final Event event = new Event(eventId, "owner", "Nunta", "nunta", LocalDate.of(2026, 6, 12), "camera", true, "tok", null, null, false, 0, true);
	private static final String PREFIX = "events/e/c/";

	private static Entry entry(String name, long modified) {
		return new Entry(PREFIX + name, name, Instant.ofEpochSecond(modified), "Cam");
	}

	private void photos(Entry... entries) {
		when(sources.of(event)).thenReturn(List.of(new Source(UUID.randomUUID(), "Cam", "camera", PREFIX)));
		when(storage.entries(PREFIX, "Cam")).thenReturn(List.of(entries));
		for (Entry e : entries) {
			when(thumbs.serve(e.key(), e.modified().getEpochSecond(), 1600)).thenReturn(new Served(new byte[] {1}, null));
		}
		when(faces.indexedVersions(eventId)).thenReturn(Map.of());
	}

	private static Face face() {
		return new Face(0, 0, 10, 10, new float[128]);
	}

	@Test
	void indexesOnlyNewPhotos() {
		photos(entry("a.jpg", 100), entry("b.jpg", 100), entry("c.jpg", 100));
		when(faces.indexedVersions(eventId)).thenReturn(Map.of(PREFIX + "a.jpg", 100L));
		when(engine.faces(any())).thenReturn(List.of(face()));
		assertEquals(2, indexer.indexSome(event, 10));
		verify(engine, times(2)).faces(any());
		verify(faces, never()).savePhoto(eq(eventId), eq(PREFIX + "a.jpg"), anyLong(), any());
	}

	@Test
	void aChangedPhotoIsIndexedAgain() {
		photos(entry("a.jpg", 200));
		when(faces.indexedVersions(eventId)).thenReturn(Map.of(PREFIX + "a.jpg", 100L));
		when(engine.faces(any())).thenReturn(List.of(face()));
		assertEquals(1, indexer.indexSome(event, 10));
		verify(faces).savePhoto(eq(eventId), eq(PREFIX + "a.jpg"), eq(200L), any());
	}

	@Test
	void aPhotoWithoutFacesIsDoneAndNotRetried() {
		photos(entry("a.jpg", 100));
		when(engine.faces(any())).thenReturn(List.of());
		indexer.indexSome(event, 10);
		verify(faces).savePhoto(eventId, PREFIX + "a.jpg", 100L, List.of());
	}

	@Test
	void aBrokenPhotoGivesUpOnTheThirdAttempt() {
		photos(entry("a.jpg", 100));
		when(thumbs.serve(PREFIX + "a.jpg", 100L, 1600)).thenReturn(new Served(null, "https://r2/original"));
		when(faces.attempts(eventId, PREFIX + "a.jpg")).thenReturn(0, 1, 2);
		indexer.indexSome(event, 10);
		indexer.indexSome(event, 10);
		verify(faces, times(2)).markFailedAttempt(eventId, PREFIX + "a.jpg", 100L);
		verify(faces, never()).savePhoto(any(), any(), anyLong(), any());
		indexer.indexSome(event, 10);
		verify(faces, times(2)).markFailedAttempt(eventId, PREFIX + "a.jpg", 100L);
		verify(faces).savePhoto(eventId, PREFIX + "a.jpg", 100L, List.of());
		verify(engine, never()).faces(any());
	}

	@Test
	void anEngineErrorCountsAsAFailedAttemptAndDoesNotStopTheRest() {
		photos(entry("a.jpg", 200), entry("b.jpg", 100));
		when(engine.faces(any())).thenThrow(new IllegalStateException("boom")).thenReturn(List.of(face()));
		assertEquals(1, indexer.indexSome(event, 10));
		verify(faces).markFailedAttempt(eventId, PREFIX + "a.jpg", 200L);
		verify(faces).savePhoto(eq(eventId), eq(PREFIX + "b.jpg"), eq(100L), any());
	}

	@Test
	void stopsAtTheLimitAndTakesTheNewestFirst() {
		Entry[] many = new Entry[25];
		for (int i = 0; i < many.length; i++) many[i] = entry("p" + i + ".jpg", 1000 + i);
		photos(many);
		when(engine.faces(any())).thenReturn(List.of());
		assertEquals(10, indexer.indexSome(event, 10));
		verify(faces).savePhoto(eq(eventId), eq(PREFIX + "p24.jpg"), eq(1024L), any());
		verify(faces, never()).savePhoto(eq(eventId), eq(PREFIX + "p0.jpg"), anyLong(), any());
	}

	@Test
	void progressCountsPhotosThatAreDoneAtTheirCurrentVersion() {
		photos(entry("a.jpg", 100), entry("b.jpg", 100), entry("c.jpg", 300));
		when(faces.indexedVersions(eventId)).thenReturn(Map.of(PREFIX + "a.jpg", 100L, PREFIX + "c.jpg", 200L));
		var p = indexer.progress(event);
		assertEquals(1, p.done());
		assertEquals(3, p.total());
	}

	// ---- the scheduled job ----

	private final EventRepository repo = mock(EventRepository.class);
	private final CreditService credits = mock(CreditService.class);
	private final FaceIndexer fakeIndexer = mock(FaceIndexer.class);

	private Event owned(String owner) {
		return new Event(UUID.randomUUID(), owner, "E", "e", LocalDate.of(2026, 6, 12), "camera", true, "t", null, null, false, 0, true);
	}

	@Test
	void theJobSkipsEventsWhoseOwnerNeverPaid() {
		Event paid = owned("paid"), free = owned("free");
		when(repo.faceSearchEvents()).thenReturn(List.of(free, paid));
		when(credits.unlocked("paid")).thenReturn(true);
		when(credits.unlocked("free")).thenReturn(false);
		when(engine.available()).thenReturn(true);
		when(fakeIndexer.indexSome(any(), anyInt())).thenReturn(1);
		new FaceIndexJob(repo, credits, fakeIndexer, engine, 10).tick();
		verify(fakeIndexer).indexSome(paid, 10);
		verify(fakeIndexer, never()).indexSome(eq(free), anyInt());
	}

	@Test
	void theJobDoesNothingWithoutModels() {
		when(engine.available()).thenReturn(false);
		new FaceIndexJob(repo, credits, fakeIndexer, engine, 10).tick();
		verify(repo, never()).faceSearchEvents();
	}

	@Test
	void theJobSharesOneBatchAcrossEventsAndSurvivesAFailure() {
		Event a = owned("a"), b = owned("b"), c = owned("c");
		when(repo.faceSearchEvents()).thenReturn(List.of(a, b, c));
		when(credits.unlocked(any())).thenReturn(true);
		when(engine.available()).thenReturn(true);
		doThrow(new IllegalStateException("storage down")).when(fakeIndexer).indexSome(eq(a), anyInt());
		when(fakeIndexer.indexSome(eq(b), anyInt())).thenReturn(4);
		when(fakeIndexer.indexSome(eq(c), anyInt())).thenReturn(1);
		new FaceIndexJob(repo, credits, fakeIndexer, engine, 10).tick();
		verify(fakeIndexer).indexSome(b, 10);
		verify(fakeIndexer).indexSome(c, 6);
	}
}
