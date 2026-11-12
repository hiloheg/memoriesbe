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
class CameraPhotosTests {

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

	@Test
	void theOwnerSeesEveryCameraPhotoNewestFirst() throws Exception {
		mvc.perform(get("/api/v1/events/" + id + "/camera-photos").with(jwt().jwt(j -> j.subject("s1"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].name", is("sub/b.jpg")))
				.andExpect(jsonPath("$[0].camera", is("Canon")))
				.andExpect(jsonPath("$[0].cameraId", is(cam1.toString())))
				.andExpect(jsonPath("$[1].name", is("c.jpg")))
				.andExpect(jsonPath("$[2].name", is("a.jpg")));
	}

	@Test
	void anotherOwnerCannotSeeThem() throws Exception {
		mvc.perform(get("/api/v1/events/" + id + "/camera-photos").with(jwt().jwt(j -> j.subject("intruder"))))
				.andExpect(status().isNotFound());
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
