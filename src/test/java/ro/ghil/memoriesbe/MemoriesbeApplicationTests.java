package ro.ghil.memoriesbe;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"GOOGLE_CLIENT_ID=test-client", "spring.flyway.enabled=false"})
class MemoriesbeApplicationTests {

	@Autowired MockMvc mvc;
	@MockitoBean EventRepository repo;
	@MockitoBean ProfileRepository profiles;
	@MockitoBean SftpGoClient sftpgo;
	@MockitoBean PhotoStorage storage;
	@Autowired ro.ghil.memoriesbe.sftpgo.SecretBox box;

	@Test
	void rejectsAnonymous() throws Exception {
		mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/events")).andExpect(status().isUnauthorized());
	}

	private static ProfileRepository.Profile profile(String slug) {
		return new ProfileRepository.Profile("s1", "Ana Pop", slug, null, null, null, null, null, null, "https://g/p.jpg", "tok", true, "default", "", "none", "none", null);
	}

	@Test
	void meIncludesReadablePageLinkAndPicture() throws Exception {
		when(profiles.ensure("s1", "Ana Pop", "https://g/p.jpg")).thenReturn(profile("ana-pop"));
		mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject("s1").claim("name", "Ana Pop").claim("email", "a@b.c").claim("picture", "https://g/p.jpg"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Ana Pop"))
				.andExpect(jsonPath("$.slug").value("ana-pop"))
				.andExpect(jsonPath("$.shareUrl").value(org.hamcrest.Matchers.endsWith("/ana-pop")))
				.andExpect(jsonPath("$.picture").value("https://g/p.jpg"))
				.andExpect(jsonPath("$.accent").value("#0e7c6b"));
	}

	@Test
	void profileRejectsReservedAndInvalidAddresses() throws Exception {
		when(profiles.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(profile("ana-pop"));
		for (String bad : new String[] {"events", "ab", "-ana", "Ana Pop!", "a--b"}) {
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/profile")
					.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))).contentType("application/json")
					.content("{\"name\":\"Ana\",\"slug\":\"" + bad + "\",\"accent\":\"#0e7c6b\"}"))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void profileTakenAddressIs409AndSaveWorks() throws Exception {
		when(profiles.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(profile("ana-pop"));
		org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("dup")).when(profiles)
				.update("s1", "Ana", "taken", null, null, "#0e7c6b", null, null, true, new ProfileRepository.Appearance("default", "", "none", "none"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/profile")
				.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))).contentType("application/json")
				.content("{\"name\":\"Ana\",\"slug\":\"taken\",\"accent\":\"#0e7c6b\"}"))
				.andExpect(status().isConflict());
		when(profiles.bySub("s1")).thenReturn(Optional.of(profile("ana-new")));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/profile")
				.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))).contentType("application/json")
				.content("{\"name\":\"Ana\",\"slug\":\"ana-new\",\"accent\":\"#0e7c6b\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.slug").value("ana-new"));
	}

	private org.springframework.test.web.servlet.ResultActions putProfile(String linksJson) throws Exception {
		when(profiles.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(profile("ana-pop"));
		when(profiles.bySub("s1")).thenReturn(Optional.of(profile("ana-pop")));
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/profile")
				.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))).contentType("application/json")
				.content("{\"name\":\"Ana\",\"slug\":\"ana-pop\",\"accent\":\"#0e7c6b\",\"followGate\":true,\"links\":" + linksJson + "}"));
	}

	@Test
	void followButtonsAreValidatedAndSaved() throws Exception {
		putProfile("[{\"label\":\"Instagram\",\"url\":\"https://instagram.com/ana\"},{\"label\":\"TikTok\",\"url\":\"https://tiktok.com/@ana\"}]")
				.andExpect(status().isOk());
		org.mockito.Mockito.verify(profiles).replaceLinks(org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.argThat(l -> l.size() == 2 && l.get(0).label().equals("Instagram")));
	}

	@Test
	void badFollowButtonsAreRejected() throws Exception {
		putProfile("[{\"label\":\"\",\"url\":\"https://x.com/a\"}]").andExpect(status().isBadRequest());
		putProfile("[{\"label\":\"Site\",\"url\":\"javascript:alert(1)\"}]").andExpect(status().isBadRequest());
		putProfile("[{\"label\":\"Site\",\"url\":\"ftp://x.com/a\"}]").andExpect(status().isBadRequest());
		String six = "[" + String.join(",", java.util.Collections.nCopies(6, "{\"label\":\"A\",\"url\":\"https://a.com/x\"}")) + "]";
		putProfile(six).andExpect(status().isBadRequest());
	}

	private EventRepository.Event event(UUID id, java.time.LocalDate date) {
		return new EventRepository.Event(id, "s1", "Nunta", "nunta", date, "camera", true, "tok", java.time.OffsetDateTime.now(), null);
	}

	private org.springframework.test.web.servlet.ResultActions post(String url, String json) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
				.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))).contentType("application/json").content(json));
	}

	@Test
	void eventNeedsADate() throws Exception {
		post("/api/v1/events", "{\"name\":\"Nunta\"}").andExpect(status().isBadRequest());
		post("/api/v1/events", "{\"name\":\"Nunta\",\"date\":\"1999-01-01\"}").andExpect(status().isBadRequest());
	}

	@Test
	void cameraLoginExpiresThreeDaysAfterTheEvent() throws Exception {
		UUID id = UUID.randomUUID();
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, today)));
		post("/api/v1/events/" + id + "/cameras", "{\"name\":\"Ceremony\"}").andExpect(status().isCreated());
		// end of the 3rd day after the event = start of day 4, UTC
		java.time.Instant expected = today.plusDays(4).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
		org.mockito.Mockito.verify(sftpgo).createUser(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.startsWith("events/" + id + "/"), org.mockito.ArgumentMatchers.eq(expected));
	}

	@Test
	void cannotAddCameraToAnEventWhoseAccessHasEnded() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(10))));
		post("/api/v1/events/" + id + "/cameras", "{\"name\":\"Late\"}").andExpect(status().isBadRequest());
		org.mockito.Mockito.verifyNoInteractions(sftpgo);
	}

	@Test
	void eventIconIsChosenAndValidated() throws Exception {
		post("/api/v1/events", "{\"name\":\"Nunta\",\"date\":\"2026-12-20\",\"icon\":\"skull\"}").andExpect(status().isBadRequest());
		when(repo.eventOf(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("s1")))
				.thenReturn(Optional.of(event(UUID.randomUUID(), java.time.LocalDate.of(2026, 12, 20))));
		post("/api/v1/events", "{\"name\":\"Nunta\",\"date\":\"2026-12-20\",\"icon\":\"wedding\"}").andExpect(status().isCreated());
		org.mockito.Mockito.verify(repo).insertEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.eq("Nunta"),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("wedding"), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void iconCanBeChangedOnlyOnOwnEvent() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		when(repo.eventOf(id, "intruder")).thenReturn(Optional.empty());
		var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/icon");
		mvc.perform(put.with(jwt().jwt(j -> j.subject("s1"))).contentType("application/json").content("{\"icon\":\"party\"}")).andExpect(status().isOk());
		org.mockito.Mockito.verify(repo).updateIcon(id, "party");
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/icon")
				.with(jwt().jwt(j -> j.subject("s1"))).contentType("application/json").content("{\"icon\":\"nope\"}")).andExpect(status().isBadRequest());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/icon")
				.with(jwt().jwt(j -> j.subject("intruder"))).contentType("application/json").content("{\"icon\":\"party\"}")).andExpect(status().isNotFound());
	}

	private org.springframework.test.web.servlet.ResultActions putLook(String lookJson) throws Exception {
		when(profiles.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(profile("ana-pop"));
		when(profiles.bySub("s1")).thenReturn(Optional.of(profile("ana-pop")));
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/profile")
				.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))).contentType("application/json")
				.content("{\"name\":\"Ana\",\"slug\":\"ana-pop\",\"accent\":\"#0e7c6b\"," + lookJson + "}"));
	}

	@Test
	void pageBackgroundIsValidatedAndSaved() throws Exception {
		putLook("\"bgKind\":\"gradient\",\"bgValue\":\"twilight\",\"bgPattern\":\"dots\",\"bgEffect\":\"aurora\"").andExpect(status().isOk());
		org.mockito.Mockito.verify(profiles).update(org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
				org.mockito.ArgumentMatchers.eq(new ProfileRepository.Appearance("gradient", "twilight", "dots", "aurora")));
	}

	@Test
	void unknownBackgroundChoicesAreRejected() throws Exception {
		putLook("\"bgKind\":\"color\",\"bgValue\":\"neon\"").andExpect(status().isBadRequest());
		putLook("\"bgKind\":\"gradient\",\"bgValue\":\"snow\"").andExpect(status().isBadRequest());
		putLook("\"bgKind\":\"image\",\"bgValue\":\"x\"").andExpect(status().isBadRequest());
		putLook("\"bgKind\":\"default\",\"bgPattern\":\"zigzag\"").andExpect(status().isBadRequest());
		putLook("\"bgKind\":\"default\",\"bgEffect\":\"rain\"").andExpect(status().isBadRequest());
	}

	private ProfileRepository.Profile profileWithMain(UUID main) {
		return new ProfileRepository.Profile("s1", "Ana Pop", "ana-pop", null, null, null, null, null, null, null, "tok", false, "default", "", "none", "none", main);
	}

	private org.springframework.test.web.servlet.ResultActions put(String url, String json) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
				.with(jwt().jwt(j -> j.subject("s1"))).contentType("application/json").content(json));
	}

	@Test
	void privateEventsCanBeHiddenButNotTheMainEvent() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		put("/api/v1/events/" + id + "/visibility", "{\"visibility\":\"private\"}").andExpect(status().isOk());
		org.mockito.Mockito.verify(repo).updateListed(id, false);
		put("/api/v1/events/" + id + "/visibility", "{\"visibility\":\"secret\"}").andExpect(status().isBadRequest());
		// once it is the main event it cannot be made private
		when(profiles.bySub("s1")).thenReturn(Optional.of(profileWithMain(id)));
		put("/api/v1/events/" + id + "/visibility", "{\"visibility\":\"private\"}").andExpect(status().isBadRequest());
	}

	@Test
	void settingTheMainEventMakesItPublicAndOnlyForOwnEvents() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		when(repo.eventOf(id, "intruder")).thenReturn(Optional.empty());
		put("/api/v1/events/" + id + "/main", "").andExpect(status().isOk());
		org.mockito.Mockito.verify(repo).updateListed(id, true);
		org.mockito.Mockito.verify(profiles).setMainEvent("s1", id);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/main")
				.with(jwt().jwt(j -> j.subject("intruder")))).andExpect(status().isNotFound());
	}

	@Test
	void removingTheMainEventClearsIt() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		when(profiles.bySub("s1")).thenReturn(Optional.of(profileWithMain(id)));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/events/" + id + "/main")
				.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isOk()).andExpect(jsonPath("$.main").value(false));
		org.mockito.Mockito.verify(profiles).setMainEvent("s1", null);
	}

	@Test
	void profilePageShowsOnlyPublicEventsOrJustTheMainOne() throws Exception {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
		var day = java.time.LocalDate.of(2026, 12, 20);
		var pub = new EventRepository.Event(a, "s1", "Public", "public", day, "camera", true, "t1", java.time.OffsetDateTime.now(), null);
		var hidden = new EventRepository.Event(b, "s1", "Hidden", "hidden", day, "camera", false, "t2", java.time.OffsetDateTime.now(), null);
		var main = new EventRepository.Event(c, "s1", "Main", "main", day, "camera", true, "t3", java.time.OffsetDateTime.now(), null);
		when(repo.eventsOf("s1")).thenReturn(List.of(pub, hidden, main));
		// no main event: only the public ones
		when(profiles.bySlug("ana-pop")).thenReturn(Optional.of(profileWithMain(null)));
		mvc.perform(get("/api/v1/public/photographers/ana-pop")).andExpect(status().isOk())
				.andExpect(jsonPath("$.featured").value(false)).andExpect(jsonPath("$.events.length()").value(2))
				.andExpect(jsonPath("$.events[?(@.slug=='hidden')]").isEmpty());
		// with a main event: only that one
		when(profiles.bySlug("ana-pop")).thenReturn(Optional.of(profileWithMain(c)));
		when(repo.eventById(c)).thenReturn(Optional.of(main));
		mvc.perform(get("/api/v1/public/photographers/ana-pop")).andExpect(status().isOk())
				.andExpect(jsonPath("$.featured").value(true)).andExpect(jsonPath("$.events.length()").value(1))
				.andExpect(jsonPath("$.events[0].slug").value("main"));
	}

	@Test
	void photographerPageIsPublicAndUnknownAddressIs404() throws Exception {
		when(profiles.bySlug("nope")).thenReturn(Optional.empty());
		mvc.perform(get("/api/v1/public/photographers/nope")).andExpect(status().isNotFound());
		when(repo.eventBySlugs("nope", "x")).thenReturn(Optional.empty());
		mvc.perform(get("/api/v1/public/photographers/nope/events/x")).andExpect(status().isNotFound());
	}

	@Test
	void listsOnlyOwnEvents() throws Exception {
		when(repo.eventsOf("sub-1")).thenReturn(List.of());
		mvc.perform(get("/api/v1/events").with(jwt().jwt(j -> j.subject("sub-1"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void otherUsersEventIs404() throws Exception {
		when(repo.eventOf(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("intruder")))
				.thenReturn(Optional.empty());
		mvc.perform(get("/api/v1/events/" + java.util.UUID.randomUUID()).with(jwt().jwt(j -> j.subject("intruder"))))
				.andExpect(status().isNotFound());
	}

	@Test
	void publicGalleryNeedsNoLoginButUnknownTokenIs404() throws Exception {
		when(repo.eventByToken("nope")).thenReturn(Optional.empty());
		mvc.perform(get("/api/v1/public/events/nope")).andExpect(status().isNotFound());
	}

	@Test
	void photoStreamIsNewestFirstAcrossCamerasAndPaged() throws Exception {
		var eventId = UUID.randomUUID();
		var cam1 = new EventRepository.Camera(UUID.randomUUID(), eventId, "Ceremony", "cam_a", null, null);
		var cam2 = new EventRepository.Camera(UUID.randomUUID(), eventId, "Party", "cam_b", null, null);
		var event = new EventRepository.Event(eventId, "owner", "Wedding", "wedding", java.time.LocalDate.of(2026, 6, 12), "heart", true, "tok", null, null);
		when(repo.eventBySlugs("ana-pop", "wedding")).thenReturn(Optional.of(event));
		when(repo.cameras(eventId)).thenReturn(List.of(cam1, cam2));
		var t = java.time.Instant.parse("2026-06-12T10:00:00Z");
		when(storage.entries(org.mockito.ArgumentMatchers.contains(cam1.id().toString()), org.mockito.ArgumentMatchers.eq("Ceremony")))
				.thenReturn(List.of(new PhotoStorage.Entry("k/a1.jpg", "a1.jpg", t, "Ceremony"), new PhotoStorage.Entry("k/a2.jpg", "a2.jpg", t.plusSeconds(60), "Ceremony")));
		when(storage.entries(org.mockito.ArgumentMatchers.contains(cam2.id().toString()), org.mockito.ArgumentMatchers.eq("Party")))
				.thenReturn(List.of(new PhotoStorage.Entry("k/b1.jpg", "b1.jpg", t.plusSeconds(30), "Party")));
		when(storage.signedUrl(org.mockito.ArgumentMatchers.anyString())).thenAnswer(i -> "https://r2/" + i.getArgument(0));

		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos?limit=2")).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Wedding")).andExpect(jsonPath("$.eventDate").value("2026-06-12"))
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.photos[0].name").value("a2.jpg")).andExpect(jsonPath("$.photos[1].name").value("b1.jpg"))
				.andExpect(jsonPath("$.photos[1].camera").value("Party")).andExpect(jsonPath("$.next").value(2));
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos?offset=2&limit=2")).andExpect(status().isOk())
				.andExpect(jsonPath("$.photos[0].name").value("a1.jpg")).andExpect(jsonPath("$.next").doesNotExist());
	}

	@Test
	void cameraPasswordCanBeShownAgainOrResetOnlyByTheOwner() throws Exception {
		UUID id = UUID.randomUUID();
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, today)));
		UUID camId = UUID.randomUUID();
		when(repo.camera(camId, id)).thenReturn(Optional.of(new EventRepository.Camera(camId, id, "Ceremony", "cam_x", null, box.encrypt("pw-123"))));
		UUID oldId = UUID.randomUUID();
		when(repo.camera(oldId, id)).thenReturn(Optional.of(new EventRepository.Camera(oldId, id, "Old", "cam_old", null, null)));

		mvc.perform(get("/api/v1/events/" + id + "/cameras/" + camId + "/credentials").with(jwt().jwt(j -> j.subject("s1"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.username").value("cam_x")).andExpect(jsonPath("$.password").value("pw-123"));
		mvc.perform(get("/api/v1/events/" + id + "/cameras/" + oldId + "/credentials").with(jwt().jwt(j -> j.subject("s1"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.password").doesNotExist());
		post("/api/v1/events/" + id + "/cameras/" + oldId + "/credentials/reset", "{}").andExpect(status().isOk())
				.andExpect(jsonPath("$.password").isNotEmpty());
		org.mockito.Mockito.verify(repo).setCameraPassword(org.mockito.ArgumentMatchers.eq(oldId), org.mockito.ArgumentMatchers.anyString());
		mvc.perform(get("/api/v1/events/" + id + "/cameras/" + camId + "/credentials").with(jwt().jwt(j -> j.subject("other"))))
				.andExpect(status().isNotFound());
	}

	@Test
	void eventCanBeRenamedAndKeepsItsAddress() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/details")
				.with(jwt().jwt(j -> j.subject("s1"))).contentType("application/json");
		mvc.perform(put.content("{\"name\":\"  \"}")).andExpect(status().isBadRequest());
		mvc.perform(put.content("{\"icon\":\"skull\"}")).andExpect(status().isBadRequest());
		mvc.perform(put.content("{\"name\":\"Nunta Ana\",\"icon\":\"wedding\"}")).andExpect(status().isOk());
		org.mockito.Mockito.verify(repo).updateDetails(id, "Nunta Ana", "wedding");
	}

	@Test
	void protectedEventNeedsItsPassword() throws Exception {
		UUID id = UUID.randomUUID();
		var event = new EventRepository.Event(id, "s1", "Nunta", "nunta", java.time.LocalDate.of(2026, 6, 12), "camera", true, "tok", null, box.encrypt("secret1"));
		when(repo.eventBySlugs("ana-pop", "nunta")).thenReturn(Optional.of(event));
		when(repo.eventByToken("tok")).thenReturn(Optional.of(event));
		when(repo.cameras(id)).thenReturn(List.of());
		var photos = "/api/v1/public/photographers/ana-pop/events/nunta/photos";
		mvc.perform(get(photos)).andExpect(status().isUnauthorized());
		mvc.perform(get(photos).header("X-Event-Password", "nope")).andExpect(status().isForbidden());
		mvc.perform(get(photos).header("X-Event-Password", "secret1")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/public/events/tok")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/nunta")).andExpect(status().isUnauthorized());
	}

	@Test
	void protectedVisibilityNeedsAPasswordAndShowsItToTheOwner() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/visibility")
				.with(jwt().jwt(j -> j.subject("s1"))).contentType("application/json");
		mvc.perform(put.content("{\"visibility\":\"protected\"}")).andExpect(status().isBadRequest());
		mvc.perform(put.content("{\"visibility\":\"protected\",\"password\":\"abc\"}")).andExpect(status().isBadRequest());
		mvc.perform(put.content("{\"visibility\":\"protected\",\"password\":\"abcd\"}")).andExpect(status().isOk());
		org.mockito.Mockito.verify(repo).setAccessPassword(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
		mvc.perform(put.content("{\"visibility\":\"public\"}")).andExpect(status().isOk());
		org.mockito.Mockito.verify(repo).setAccessPassword(id, null);
	}

	@Test
	void browserMayAskForTheEventPasswordHeaderAcrossOrigins() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/public/photographers/a/events/b")
				.header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "x-event-password"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
	}

	@Test
	void mainEventBecomesPublicAndStaysPublic() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		when(profiles.bySub("s1")).thenReturn(Optional.empty());
		org.mockito.Mockito.reset(profiles);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/main")
				.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isOk());
		org.mockito.Mockito.verify(repo).updateListed(id, true);
		org.mockito.Mockito.verify(repo).setAccessPassword(id, null);
	}

	@Test
	void accountCanBeExportedAndErasedWithEverythingItOwns() throws Exception {
		UUID id = UUID.randomUUID();
		UUID camId = UUID.randomUUID();
		var event = new EventRepository.Event(id, "s1", "Nunta", "nunta", java.time.LocalDate.of(2026, 6, 12), "camera", true, "tok", null, null);
		when(repo.eventsOf("s1")).thenReturn(List.of(event));
		when(repo.cameras(id)).thenReturn(List.of(new EventRepository.Camera(camId, id, "Ceremony", "cam_x", null, null)));
		when(storage.entries(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

		mvc.perform(get("/api/v1/me/export").with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.email").value("a@b.c"))
				.andExpect(jsonPath("$.events[0].name").value("Nunta")).andExpect(jsonPath("$.events[0].cameras[0].username").value("cam_x"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
				.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNoContent());
		org.mockito.Mockito.verify(sftpgo).deleteUser("cam_x");
		org.mockito.Mockito.verify(storage).deleteAll("events/" + id + "/");
		org.mockito.Mockito.verify(repo).deleteEvent(id);
		org.mockito.Mockito.verify(profiles).deleteAll("s1");
		mvc.perform(get("/api/v1/me/export")).andExpect(status().isUnauthorized());
	}
}
