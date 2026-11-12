package ro.ghil.memoriesbe;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.events.GuestRepository;
import ro.ghil.memoriesbe.events.GuestUploadService;
import ro.ghil.memoriesbe.faces.FaceRepository;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"GOOGLE_CLIENT_ID=test-client", "spring.flyway.enabled=false"})
class FaceSwitchTests {

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

	private EventRepository.Event event(boolean faceSearch) {
		return new EventRepository.Event(id, "s1", "Nunta", "nunta", LocalDate.of(2026, 6, 12), "camera", true, "tok", null, null, false, 0, faceSearch);
	}

	@BeforeEach
	void setUp() {
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(false)), Optional.of(event(true)));
		when(repo.eventOf(id, "intruder")).thenReturn(Optional.empty());
		when(credits.unlocked("s1")).thenReturn(true);
	}

	private ResultActions send(String sub, boolean enabled) throws Exception {
		return mvc.perform(put("/api/v1/events/" + id + "/face-search").with(jwt().jwt(j -> j.subject(sub)))
				.contentType("application/json").content("{\"enabled\":" + enabled + "}"));
	}

	@Test
	void ownerWhoPaidCanSwitchItOn() throws Exception {
		send("s1", true).andExpect(status().isOk()).andExpect(jsonPath("$.faceSearchEnabled", is(true)));
		verify(repo).setFaceSearch(id, true);
	}

	@Test
	void ownerWhoNeverPaidCannotSwitchItOnButCanSwitchItOff() throws Exception {
		when(credits.unlocked("s1")).thenReturn(false);
		send("s1", true).andExpect(status().isForbidden());
		verify(repo, never()).setFaceSearch(any(), anyBoolean());
		send("s1", false).andExpect(status().isOk());
		verify(repo).setFaceSearch(id, false);
	}

	@Test
	void anotherOwnersEventIsNotFound() throws Exception {
		send("intruder", true).andExpect(status().isNotFound());
		verify(repo, never()).setFaceSearch(any(), anyBoolean());
	}

	@Test
	void theGalleryOffersFaceSearchOnlyWhenOnAndTheOwnerPaid() throws Exception {
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.of(event(true)));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/public/photographers/ana/events/nunta"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.faceSearch", is(true)));
		when(credits.unlocked("s1")).thenReturn(false);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/public/photographers/ana/events/nunta"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.faceSearch", is(false)));
		when(credits.unlocked("s1")).thenReturn(true);
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.of(event(false)));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/public/photographers/ana/events/nunta"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.faceSearch", is(false)));
	}
}
