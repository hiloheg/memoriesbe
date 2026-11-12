package ro.ghil.memoriesbe;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.events.GuestRepository;
import ro.ghil.memoriesbe.events.GuestUploadService;
import ro.ghil.memoriesbe.faces.FaceRepository;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.PhotoStorage.Entry;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"GOOGLE_CLIENT_ID=test-client", "spring.flyway.enabled=false"})
class EventPhotosTests {

	@Autowired MockMvc mvc;
	@MockitoBean EventRepository repo;
	@MockitoBean ProfileRepository profiles;
	@MockitoBean SftpGoClient sftpgo;
	@MockitoBean PhotoStorage storage;
	@MockitoBean GuestRepository guestRepo;
	@MockitoBean GuestUploadService guestUploads;
	@MockitoBean CreditService credits;
	@MockitoBean FaceRepository faces;

	private final UUID id = UUID.randomUUID();
	private final UUID cam1 = UUID.randomUUID();
	private final UUID cam2 = UUID.randomUUID();
	private String prefix1() { return "events/" + id + "/" + cam1 + "/"; }
	private String prefix2() { return "events/" + id + "/" + cam2 + "/"; }

	private EventRepository.Event event() {
		return new EventRepository.Event(id, "s1", "Nunta", "nunta", LocalDate.of(2026, 6, 12), "camera", true, "tok", null, null, false, 0, false);
	}

	private EventRepository.Camera camera(UUID cid, String name) {
		return new EventRepository.Camera(cid, id, name, "u-" + name, OffsetDateTime.now(), null);
	}

	@BeforeEach
	void setUp() {
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event()));
		when(repo.eventOf(id, "intruder")).thenReturn(Optional.empty());
		when(repo.cameras(id)).thenReturn(List.of(camera(cam1, "Canon"), camera(cam2, "Sony")));
		when(storage.entries(prefix1(), "Canon")).thenReturn(List.of(
				new Entry(prefix1() + "a.jpg", "a.jpg", Instant.ofEpochSecond(100), "Canon"),
				new Entry(prefix1() + "sub/b.jpg", "sub/b.jpg", Instant.ofEpochSecond(300), "Canon")));
		when(storage.entries(prefix2(), "Sony")).thenReturn(List.of(new Entry(prefix2() + "c.jpg", "c.jpg", Instant.ofEpochSecond(200), "Sony")));
		when(storage.signedUrl(anyString())).thenAnswer(i -> "https://r2/" + i.getArgument(0));
	}

	private final UUID guestId = UUID.randomUUID();
	private final UUID uploadId = UUID.randomUUID();
	private String guestPrefix() { return "events/" + id + "/guests/" + guestId + "/"; }

	private void withAGuest() {
		var guest = new GuestRepository.Guest(guestId, id, "g-sub", "Ana", OffsetDateTime.now(), null);
		when(guestRepo.guestsWithPhotos(id)).thenReturn(List.of(guest));
		when(guestRepo.guestsOf(id)).thenReturn(List.of(new GuestRepository.GuestSummary(guestId, "Ana", true, 1, OffsetDateTime.now())));
		when(guestRepo.uploads(id)).thenReturn(List.of(new GuestRepository.Upload(uploadId, id, guestId, guestPrefix() + "g.jpg", OffsetDateTime.now(), null)));
		when(storage.entries(guestPrefix(), "Ana")).thenReturn(List.of(new Entry(guestPrefix() + "g.jpg", "g.jpg", Instant.ofEpochSecond(250), "Ana")));
	}

	private org.springframework.test.web.servlet.ResultActions list(String sub, String query) throws Exception {
		return mvc.perform(get("/api/v1/events/" + id + "/photos" + query).with(jwt().jwt(j -> j.subject(sub))));
	}

	@Test
	void theOwnerSeesCameraAndGuestPhotosTogetherNewestFirstWithTheirSource() throws Exception {
		withAGuest();
		list("s1", "").andExpect(status().isOk())
				.andExpect(jsonPath("$.total", is(4)))
				.andExpect(jsonPath("$.next").doesNotExist())
				.andExpect(jsonPath("$.items", hasSize(4)))
				.andExpect(jsonPath("$.items[0].name", is("sub/b.jpg")))
				.andExpect(jsonPath("$.items[0].source", is("Canon")))
				.andExpect(jsonPath("$.items[0].kind", is("camera")))
				.andExpect(jsonPath("$.items[0].sourceId", is(cam1.toString())))
				.andExpect(jsonPath("$.items[1].name", is("g.jpg")))
				.andExpect(jsonPath("$.items[1].kind", is("guest")))
				.andExpect(jsonPath("$.items[1].uploadId", is(uploadId.toString())))
				.andExpect(jsonPath("$.items[2].name", is("c.jpg")))
				.andExpect(jsonPath("$.items[3].name", is("a.jpg")))
				.andExpect(jsonPath("$.sources", hasSize(3)))
				.andExpect(jsonPath("$.sources[0].label", is("Canon")))
				.andExpect(jsonPath("$.sources[0].count", is(2)))
				.andExpect(jsonPath("$.sources[2].label", is("Ana")))
				.andExpect(jsonPath("$.sources[2].blocked", is(true)));
	}

	@Test
	void photosComeInPagesAndNextPointsAtTheRest() throws Exception {
		list("s1", "?limit=2").andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.next", is(2))).andExpect(jsonPath("$.total", is(3)));
		list("s1", "?limit=2&offset=2").andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].name", is("a.jpg"))).andExpect(jsonPath("$.next").doesNotExist());
	}

	@Test
	void oneSourceCanBeShownOnItsOwn() throws Exception {
		list("s1", "?source=" + cam2).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].name", is("c.jpg")))
				.andExpect(jsonPath("$.total", is(1))).andExpect(jsonPath("$.sources", hasSize(2)));
	}

	@Test
	void anotherOwnerCannotSeeThem() throws Exception {
		list("intruder", "").andExpect(status().isNotFound());
	}

	@Test
	void theOwnerDeletesOnePhotoOfOneCamera() throws Exception {
		mvc.perform(delete("/api/v1/events/" + id + "/camera-photos").param("camera", cam1.toString()).param("name", "sub/b.jpg")
				.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNoContent());
		verify(storage).delete(prefix1() + "sub/b.jpg");
	}

	@Test
	void aNameThatIsNotAPhotoOfThatCameraIsNotFoundAndNothingIsDeleted() throws Exception {
		for (String name : List.of("../x.jpg", "c.jpg", "missing.jpg")) {
			mvc.perform(delete("/api/v1/events/" + id + "/camera-photos").param("camera", cam1.toString()).param("name", name)
					.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNotFound());
		}
		verify(storage, never()).delete(anyString());
	}

	@Test
	void aCameraOfAnotherEventIsNotFound() throws Exception {
		mvc.perform(delete("/api/v1/events/" + id + "/camera-photos").param("camera", UUID.randomUUID().toString()).param("name", "a.jpg")
				.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNotFound());
		verify(storage, never()).delete(anyString());
	}

	@Test
	void anotherOwnerCannotDelete() throws Exception {
		mvc.perform(delete("/api/v1/events/" + id + "/camera-photos").param("camera", cam1.toString()).param("name", "a.jpg")
				.with(jwt().jwt(j -> j.subject("intruder")))).andExpect(status().isNotFound());
		verify(storage, never()).delete(anyString());
	}
}
