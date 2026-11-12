package ro.ghil.memoriesbe;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.events.EventSources;
import ro.ghil.memoriesbe.events.EventSources.Source;
import ro.ghil.memoriesbe.events.GuestRepository;
import ro.ghil.memoriesbe.events.GuestUploadService;
import ro.ghil.memoriesbe.faces.FaceEngine;
import ro.ghil.memoriesbe.faces.FaceEngine.Face;
import ro.ghil.memoriesbe.faces.FaceIndexer;
import ro.ghil.memoriesbe.faces.FaceRepository;
import ro.ghil.memoriesbe.faces.FaceRepository.StoredFace;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.sftpgo.SecretBox;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.PhotoStorage.Entry;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"GOOGLE_CLIENT_ID=test-client", "spring.flyway.enabled=false"})
class FaceSearchTests {

	@Autowired MockMvc mvc;
	@Autowired SecretBox box;
	@MockitoBean EventRepository repo;
	@MockitoBean ProfileRepository profiles;
	@MockitoBean SftpGoClient sftpgo;
	@MockitoBean PhotoStorage storage;
	@MockitoBean GuestRepository guestRepo;
	@MockitoBean GuestUploadService guestUploads;
	@MockitoBean CreditService credits;
	@MockitoBean FaceRepository faces;
	@MockitoBean FaceEngine engine;
	@MockitoBean FaceIndexer indexer;
	@MockitoBean EventSources sources;

	private static final String URL = "/api/v1/public/photographers/ana/events/nunta/face-search";
	private static final String PREFIX = "events/e/c/";
	private final UUID eventId = UUID.randomUUID();
	private final UUID cameraId = UUID.randomUUID();
	private static final float[] ME = {1, 0, 0, 0};
	private static final float[] SIMILAR = {0.9f, 0.1f, 0, 0};
	private static final float[] OTHER = {0, 1, 0, 0};

	private EventRepository.Event event(boolean faceSearch, String passwordEnc) {
		return new EventRepository.Event(eventId, "s1", "Nunta", "nunta", LocalDate.of(2026, 6, 12), "camera", true, "tok", null, passwordEnc, false, 0, faceSearch);
	}

	private static Face face(int size, float[] embedding) {
		return new Face(0, 0, size, size, embedding);
	}

	private static byte[] jpeg() throws Exception {
		var out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "jpg", out);
		return out.toByteArray();
	}

	@BeforeEach
	void setUp() {
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.of(event(true, null)));
		when(credits.unlocked("s1")).thenReturn(true);
		when(engine.available()).thenReturn(true);
		when(engine.faces(any())).thenReturn(List.of(face(100, ME)));
		when(sources.of(any())).thenReturn(List.of(new Source(cameraId, "Cam", "camera", PREFIX)));
		when(storage.entries(PREFIX, "Cam")).thenReturn(List.of(
				new Entry(PREFIX + "p1.jpg", "p1.jpg", Instant.ofEpochSecond(1), "Cam"),
				new Entry(PREFIX + "p2.jpg", "p2.jpg", Instant.ofEpochSecond(1), "Cam"),
				new Entry(PREFIX + "p3.jpg", "p3.jpg", Instant.ofEpochSecond(1), "Cam")));
		when(faces.faces(eventId)).thenReturn(List.of(
				new StoredFace(PREFIX + "p1.jpg", new float[] {0.8f, 0.2f, 0, 0}),
				new StoredFace(PREFIX + "p2.jpg", OTHER),
				new StoredFace(PREFIX + "p3.jpg", OTHER),
				new StoredFace(PREFIX + "p3.jpg", SIMILAR),
				new StoredFace(PREFIX + "deleted.jpg", ME)));
		when(indexer.indexSome(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0);
		when(indexer.progress(any())).thenReturn(new FaceIndexer.Progress(2, 3));
	}

	private ResultActions search(String ip, boolean consent, byte[] bytes, String password) throws Exception {
		MockMultipartHttpServletRequestBuilder req = multipart(URL);
		req.file(new MockMultipartFile("selfie", "selfie.jpg", "image/jpeg", bytes));
		if (consent) req.param("consent", "true");
		req.header("CF-Connecting-IP", ip);
		if (password != null) req.header("X-Event-Password", password);
		return mvc.perform(req);
	}

	@Test
	void returnsThePhotosWithAMatchingFaceBestFirstAndIgnoresDeletedOnes() throws Exception {
		search("ip-match", true, jpeg(), null).andExpect(status().isOk())
				.andExpect(jsonPath("$.matches", hasSize(2)))
				.andExpect(jsonPath("$.matches[0].name", is("p3.jpg")))
				.andExpect(jsonPath("$.matches[1].name", is("p1.jpg")))
				.andExpect(jsonPath("$.matches[0].folderId", is(cameraId.toString())))
				.andExpect(jsonPath("$.indexed", is(2)))
				.andExpect(jsonPath("$.total", is(3)));
	}

	@Test
	void needsConsentAndNeverScansWithoutIt() throws Exception {
		search("ip-consent", false, jpeg(), null).andExpect(status().isBadRequest());
		verify(engine, never()).faces(any());
	}

	@Test
	void aPictureWithoutAFaceIsRejected() throws Exception {
		when(engine.faces(any())).thenReturn(List.of());
		search("ip-noface", true, jpeg(), null).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void twoSimilarSizedFacesAreRejectedButOneClearlyLargestIsUsed() throws Exception {
		when(engine.faces(any())).thenReturn(List.of(face(100, ME), face(80, OTHER)));
		search("ip-two", true, jpeg(), null).andExpect(status().isUnprocessableEntity());
		when(engine.faces(any())).thenReturn(List.of(face(30, OTHER), face(100, ME)));
		search("ip-two", true, jpeg(), null).andExpect(status().isOk()).andExpect(jsonPath("$.matches", hasSize(2)));
	}

	@Test
	void somethingThatIsNotAPictureIsRejected() throws Exception {
		search("ip-text", true, "hello".getBytes(), null).andExpect(status().isUnsupportedMediaType());
		verify(engine, never()).faces(any());
	}

	@Test
	void aProtectedEventNeedsItsPassword() throws Exception {
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.of(event(true, box.encrypt("secret1"))));
		search("ip-pass", true, jpeg(), null).andExpect(status().isUnauthorized());
		search("ip-pass", true, jpeg(), "wrong").andExpect(status().isForbidden());
		search("ip-pass", true, jpeg(), "secret1").andExpect(status().isOk());
	}

	@Test
	void notFoundWhenTheSwitchIsOffOrTheOwnerNeverPaidOrTheEventIsUnknown() throws Exception {
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.of(event(false, null)));
		search("ip-off", true, jpeg(), null).andExpect(status().isNotFound());
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.of(event(true, null)));
		when(credits.unlocked("s1")).thenReturn(false);
		search("ip-off", true, jpeg(), null).andExpect(status().isNotFound());
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.empty());
		search("ip-off", true, jpeg(), null).andExpect(status().isNotFound());
		verify(engine, never()).faces(any());
	}

	@Test
	void unavailableWhenTheModelsAreNotInstalled() throws Exception {
		when(engine.available()).thenReturn(false);
		search("ip-nomodels", true, jpeg(), null).andExpect(status().isServiceUnavailable());
	}

	@Test
	void theSixthTryInAMinuteFromOneClientIsRefused() throws Exception {
		for (int i = 0; i < 5; i++) search("ip-rate", true, jpeg(), null).andExpect(status().isOk());
		search("ip-rate", true, jpeg(), null).andExpect(status().isTooManyRequests());
		search("ip-other", true, jpeg(), null).andExpect(status().isOk());
	}

	@Test
	void theSelfieAndItsFaceAreNeverStored() throws Exception {
		search("ip-keep", true, jpeg(), null).andExpect(status().isOk());
		verify(storage, never()).put(any(), any(), any());
		verify(faces, never()).savePhoto(any(), any(), anyLong(), any());
		verify(faces, never()).markFailedAttempt(any(), any(), anyLong());
	}
}
