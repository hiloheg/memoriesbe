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
	@MockitoBean ro.ghil.memoriesbe.events.GuestRepository guestRepo;
	@MockitoBean ro.ghil.memoriesbe.events.GuestUploadService guestUploads;
	@MockitoBean ro.ghil.memoriesbe.credits.CreditService credits;
	@Autowired ro.ghil.memoriesbe.sftpgo.SecretBox box;
	@Autowired ro.ghil.memoriesbe.storage.ThumbLinks thumbLinks;

	/** Spending a credit runs the event creation with the given plan (a full event unless a test says otherwise). */
	@SuppressWarnings("unchecked")
	private void spendsAs(ro.ghil.memoriesbe.credits.Plan plan) {
		org.mockito.Mockito.doAnswer(inv -> ((java.util.function.Function<ro.ghil.memoriesbe.credits.Plan, Object>) inv.getArgument(2)).apply(plan))
				.when(credits).runSpending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@org.junit.jupiter.api.BeforeEach
	void creditsDefaults() {
		spendsAs(ro.ghil.memoriesbe.credits.Plan.FULL);
		when(credits.unlocked(org.mockito.ArgumentMatchers.any())).thenReturn(true);
	}

	@Test
	void rejectsAnonymous() throws Exception {
		mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/events")).andExpect(status().isUnauthorized());
	}

	private static ProfileRepository.Profile profile(String slug) {
		return new ProfileRepository.Profile("s1", "Ana Pop", slug, null, null, null, null, null, null, null, "https://g/p.jpg", "tok", true, "default", "", "none", "none", "none", "", null);
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
				.update("s1", "Ana", "taken", null, null, "#0e7c6b", null, null, true, new ProfileRepository.Appearance("default", "", "none", "none", "none", ""));
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
		return new EventRepository.Event(id, "s1", "Nunta", "nunta", date, "camera", true, "tok", java.time.OffsetDateTime.now(), null, false, 0);
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
		post("/api/v1/events", "{\"name\":\"Nunta\",\"date\":\"2026-12-20\",\"icon\":\"wedding\",\"confirmed\":true}").andExpect(status().isCreated());
		org.mockito.Mockito.verify(repo).insertEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.eq("Nunta"),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("wedding"), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.anyString());
	}

	private EventRepository.Limits limits(UUID id, String plan, int days) {
		var closes = java.time.OffsetDateTime.now().plusDays(days);
		return new EventRepository.Limits(id, plan, plan.equals("trial") ? 300 : 3000, 42949672960L, closes, closes.plusDays(14), null);
	}

	private EventRepository.Camera camera(UUID eventId) {
		return new EventRepository.Camera(UUID.randomUUID(), eventId, "Cam", "cam_x", java.time.OffsetDateTime.now(), null);
	}

	@Test
	void expiredGalleryIs410() throws Exception {
		UUID id = UUID.randomUUID();
		var e = event(id, java.time.LocalDate.of(2026, 1, 1));
		when(repo.eventByToken("tok")).thenReturn(Optional.of(e));
		when(repo.eventBySlugs("ana", "nunta")).thenReturn(Optional.of(e));
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits(id, "full", -1)));
		mvc.perform(get("/api/v1/public/events/tok")).andExpect(status().isGone());
		mvc.perform(get("/api/v1/public/photographers/ana/events/nunta/photos")).andExpect(status().isGone());
	}

	@Test
	void cannotAddACameraToAnExpiredEvent() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.now(java.time.ZoneOffset.UTC))));
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits(id, "full", -1)));
		post("/api/v1/events/" + id + "/cameras", "{\"name\":\"Late\"}").andExpect(status().isGone());
		org.mockito.Mockito.verifyNoInteractions(sftpgo);
	}

	@Test
	void trialEventAllowsOnlyOneCamera() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.now(java.time.ZoneOffset.UTC))));
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits(id, "trial", 10)));
		when(repo.cameras(id)).thenReturn(List.of(camera(id)));
		post("/api/v1/events/" + id + "/cameras", "{\"name\":\"Second\"}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Trial events support 1 camera. Buy a pack to create full events with more cameras."));
		org.mockito.Mockito.verifyNoInteractions(sftpgo);
		when(repo.cameras(id)).thenReturn(List.of());
		post("/api/v1/events/" + id + "/cameras", "{\"name\":\"First\"}").andExpect(status().isCreated());
	}

	@Test
	void fullEventAllowsManyCameras() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.now(java.time.ZoneOffset.UTC))));
		when(repo.limitsOf(id)).thenReturn(Optional.of(limits(id, "full", 10)));
		when(repo.cameras(id)).thenReturn(List.of(camera(id), camera(id), camera(id), camera(id), camera(id)));
		post("/api/v1/events/" + id + "/cameras", "{\"name\":\"Sixth\"}").andExpect(status().isCreated());
	}

	private static final String NEW_EVENT ="{\"name\":\"Nunta\",\"date\":\"2026-12-20\",\"icon\":\"wedding\"";

	@Test
	void creatingAnEventNeedsConfirmation() throws Exception {
		post("/api/v1/events", NEW_EVENT + "}").andExpect(status().isBadRequest());
		post("/api/v1/events", NEW_EVENT + ",\"confirmed\":false}").andExpect(status().isBadRequest());
		org.mockito.Mockito.verify(credits, org.mockito.Mockito.never()).runSpending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).insertEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
	}

	private void createdEventHas(ro.ghil.memoriesbe.credits.Plan plan, int days) throws Exception {
		spendsAs(plan);
		when(repo.eventOf(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("s1")))
				.thenReturn(Optional.of(event(UUID.randomUUID(), java.time.LocalDate.of(2026, 12, 20))));
		var before = java.time.OffsetDateTime.now();
		post("/api/v1/events", NEW_EVENT + ",\"confirmed\":true}").andExpect(status().isCreated());
		var expires = org.mockito.ArgumentCaptor.forClass(java.time.OffsetDateTime.class);
		var purge = org.mockito.ArgumentCaptor.forClass(java.time.OffsetDateTime.class);
		org.mockito.Mockito.verify(repo).applyPlan(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(plan), expires.capture(), purge.capture());
		org.junit.jupiter.api.Assertions.assertTrue(!expires.getValue().isBefore(before.plusDays(days)) && expires.getValue().isBefore(before.plusDays(days).plusMinutes(1)),
				"online for " + days + " days from now, was " + expires.getValue());
		org.junit.jupiter.api.Assertions.assertEquals(expires.getValue().plusDays(14), purge.getValue(), "images go 14 days after it closes");
	}

	@Test
	void creatingAnEventSpendsOneCreditAndStoresTheFullPlan() throws Exception {
		createdEventHas(ro.ghil.memoriesbe.credits.Plan.FULL, 90);
		org.mockito.Mockito.verify(credits).runSpending(org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void trialEventGetsTrialLimits() throws Exception {
		createdEventHas(ro.ghil.memoriesbe.credits.Plan.TRIAL, 30);
	}

	@Test
	void noCreditsGives402AndCreatesNothing() throws Exception {
		org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "You have no credits left."))
				.when(credits).runSpending(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		post("/api/v1/events", NEW_EVENT + ",\"confirmed\":true}").andExpect(status().isPaymentRequired());
		org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).insertEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void eventListShowsPlanAndExpiry() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventsOf("s1")).thenReturn(List.of(event(id, java.time.LocalDate.of(2026, 1, 1))));
		var past = java.time.OffsetDateTime.now().minusDays(1);
		when(repo.limitsOfOwner("s1")).thenReturn(java.util.Map.of(id, new EventRepository.Limits(id, "full", 3000, 42949672960L, past, past.plusDays(14), null)));
		mvc.perform(get("/api/v1/events").with(jwt().jwt(j -> j.subject("s1"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].plan").value("full"))
				.andExpect(jsonPath("$[0].maxPhotos").value(3000))
				.andExpect(jsonPath("$[0].maxCameras").value(Integer.MAX_VALUE))
				.andExpect(jsonPath("$[0].expired").value(true));
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
				org.mockito.ArgumentMatchers.eq(new ProfileRepository.Appearance("gradient", "twilight", "dots", "aurora", "none", "")));
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
		return new ProfileRepository.Profile("s1", "Ana Pop", "ana-pop", null, null, null, null, null, null, null, null, "tok", false, "default", "", "none", "none", "none", "", main);
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
		var pub = new EventRepository.Event(a, "s1", "Public", "public", day, "camera", true, "t1", java.time.OffsetDateTime.now(), null, false, 0);
		var hidden = new EventRepository.Event(b, "s1", "Hidden", "hidden", day, "camera", false, "t2", java.time.OffsetDateTime.now(), null, false, 0);
		var main = new EventRepository.Event(c, "s1", "Main", "main", day, "camera", true, "t3", java.time.OffsetDateTime.now(), null, false, 0);
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
		var event = new EventRepository.Event(eventId, "owner", "Wedding", "wedding", java.time.LocalDate.of(2026, 6, 12), "heart", true, "tok", null, null, false, 0);
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
				.andExpect(jsonPath("$.photos[1].camera").value("Party")).andExpect(jsonPath("$.next").value(2))
				.andExpect(jsonPath("$.photos[0].thumb").value(org.hamcrest.Matchers.startsWith("/api/v1/public/thumb?w=480&k=")))
				.andExpect(jsonPath("$.photos[0].view").value(org.hamcrest.Matchers.startsWith("/api/v1/public/thumb?w=1600&k=")))
				.andExpect(jsonPath("$.photos[0].url").value("https://r2/k/a2.jpg"));
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos?offset=2&limit=2")).andExpect(status().isOk())
				.andExpect(jsonPath("$.photos[0].name").value("a1.jpg")).andExpect(jsonPath("$.next").doesNotExist());
	}

	private String thumbPath(String key, long version, int width) {
		return thumbLinks.url(key, version, width);
	}

	@Test
	void aSignedThumbLinkServesTheSmallPhotoAndCachesIt() throws Exception {
		String key = "events/e1/c1/IMG_1.JPG";
		when(storage.tryGet(ro.ghil.memoriesbe.storage.ThumbService.thumbKey(key, 7, 480))).thenReturn(new byte[] {1, 2, 3});
		mvc.perform(get(thumbPath(key, 7, 480))).andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/jpeg"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=3600")))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(new byte[] {1, 2, 3}));
	}

	@Test
	void aThumbLinkWithABadSignatureOrForAnotherFolderIsForbidden() throws Exception {
		String good = thumbPath("events/e1/c1/IMG_1.JPG", 7, 480);
		mvc.perform(get(good.replaceAll("&s=.*$", "&s=AAAA"))).andExpect(status().isForbidden());
		mvc.perform(get(good.replace("&v=7", "&v=8"))).andExpect(status().isForbidden());
		// a perfectly signed link to something that is not an event photo is still refused
		mvc.perform(get(thumbPath("profiles/tok/logo/x.png", 1, 480))).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/public/thumb?w=480&k=%%%&v=1&e=1&s=x")).andExpect(status().is4xxClientError());
	}

	@Test
	void aThumbLinkForAPhotoThatIsGoneIs404() throws Exception {
		mvc.perform(get(thumbPath("events/e1/c1/GONE.JPG", 1, 480))).andExpect(status().isNotFound());
	}

	private EventRepository.Event logoEvent() {
		var eventId = UUID.randomUUID();
		var event = new EventRepository.Event(eventId, "owner", "Wedding", "wedding", java.time.LocalDate.of(2026, 6, 12), "heart", true, "tok", null, null, false, 0);
		when(repo.eventBySlugs("ana-pop", "wedding")).thenReturn(Optional.of(event));
		when(repo.eventByToken("tok")).thenReturn(Optional.of(event));
		when(repo.cameras(eventId)).thenReturn(List.of());
		return event;
	}

	@Test
	void photoStreamIncludesTheOwnersLogoUrl() throws Exception {
		logoEvent();
		when(profiles.bySub("owner")).thenReturn(Optional.of(profileWithLogo("profiles/tok/logo/a.png")));
		when(storage.signedUrl("profiles/tok/logo/a.png")).thenReturn("https://signed/logo");
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos")).andExpect(status().isOk())
				.andExpect(jsonPath("$.logoUrl").value("https://signed/logo"));
	}

	@Test
	void photoStreamLogoUrlIsEmptyWithoutLogo() throws Exception {
		logoEvent();
		when(profiles.bySub("owner")).thenReturn(Optional.of(profileWithLogo(null)));
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos")).andExpect(status().isOk())
				.andExpect(jsonPath("$.logoUrl").value(""));
		when(profiles.bySub("owner")).thenReturn(Optional.empty());
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos")).andExpect(status().isOk())
				.andExpect(jsonPath("$.logoUrl").value(""));
	}

	@Test
	void tokenGalleryIncludesLogoUrl() throws Exception {
		logoEvent();
		when(profiles.bySub("owner")).thenReturn(Optional.of(profileWithLogo("profiles/tok/logo/a.png")));
		when(storage.signedUrl("profiles/tok/logo/a.png")).thenReturn("https://signed/logo");
		mvc.perform(get("/api/v1/public/events/tok")).andExpect(status().isOk())
				.andExpect(jsonPath("$.logoUrl").value("https://signed/logo"));
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
		var event = new EventRepository.Event(id, "s1", "Nunta", "nunta", java.time.LocalDate.of(2026, 6, 12), "camera", true, "tok", null, box.encrypt("secret1"), false, 0);
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
		var event = new EventRepository.Event(id, "s1", "Nunta", "nunta", java.time.LocalDate.of(2026, 6, 12), "camera", true, "tok", null, null, false, 0);
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

	@Test
	void guestUploadSettingsAreValidatedAndOwnerOnly() throws Exception {
		UUID id = UUID.randomUUID();
		var before = event(id, java.time.LocalDate.of(2026, 12, 20));
		var after = new EventRepository.Event(id, "s1", "Nunta", "nunta", java.time.LocalDate.of(2026, 12, 20), "camera", true, "tok", null, null, true, 50);
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(before), Optional.of(before), Optional.of(after));
		when(repo.eventOf(id, "intruder")).thenReturn(Optional.empty());
		put("/api/v1/events/" + id + "/guest-uploads", "{\"enabled\":true,\"limit\":51}").andExpect(status().isBadRequest());
		put("/api/v1/events/" + id + "/guest-uploads", "{\"enabled\":true,\"limit\":-1}").andExpect(status().isBadRequest());
		org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).setGuestUploads(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt());
		put("/api/v1/events/" + id + "/guest-uploads", "{\"enabled\":true,\"limit\":50}").andExpect(status().isOk())
				.andExpect(jsonPath("$.guestUploadLimit").value(50)).andExpect(jsonPath("$.guestUploadsEnabled").value(true));
		org.mockito.Mockito.verify(repo).setGuestUploads(id, true, 50);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/guest-uploads")
				.with(jwt().jwt(j -> j.subject("intruder"))).contentType("application/json").content("{\"enabled\":true,\"limit\":5}"))
				.andExpect(status().isNotFound());
	}

	private static final String GUEST = "/api/v1/guest/photographers/ana-pop/events/nunta";

	@Test
	void guestAreaNeedsASignInAndProtectedEventsNeedTheirPassword() throws Exception {
		var event = new EventRepository.Event(UUID.randomUUID(), "s1", "Nunta", "nunta", java.time.LocalDate.of(2026, 6, 12), "camera", true, "tok", null, box.encrypt("secret1"), true, 5);
		when(repo.eventBySlugs("ana-pop", "nunta")).thenReturn(Optional.of(event));
		mvc.perform(get(GUEST + "/status")).andExpect(status().isUnauthorized());
		var guest = jwt().jwt(j -> j.subject("g1").claim("name", "Ana"));
		mvc.perform(get(GUEST + "/status").with(guest)).andExpect(status().isUnauthorized());
		mvc.perform(get(GUEST + "/status").with(guest).header("X-Event-Password", "nope")).andExpect(status().isForbidden());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(GUEST + "/photos")
				.file(new org.springframework.mock.web.MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1})).with(guest))
				.andExpect(status().isUnauthorized());
		org.mockito.Mockito.verifyNoInteractions(guestUploads);
		when(guestUploads.status(event, "g1")).thenReturn(new ro.ghil.memoriesbe.events.GuestUploadService.Status(true, 5, 2, false));
		mvc.perform(get(GUEST + "/status").with(guest).header("X-Event-Password", "secret1")).andExpect(status().isOk())
				.andExpect(jsonPath("$.limit").value(5)).andExpect(jsonPath("$.used").value(2));
	}

	@Test
	void guestUploadUsesTheGoogleNameAndNeverTheEmail() throws Exception {
		var event = new EventRepository.Event(UUID.randomUUID(), "s1", "Nunta", "nunta", java.time.LocalDate.of(2026, 6, 12), "camera", true, "tok", null, null, true, 5);
		when(repo.eventBySlugs("ana-pop", "nunta")).thenReturn(Optional.of(event));
		when(guestUploads.upload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(3);
		var file = new org.springframework.mock.web.MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1, 2});
		var up = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(GUEST + "/photos").file(file);
		mvc.perform(up.with(jwt().jwt(j -> j.subject("g1").claim("name", "Ana Pop")))).andExpect(status().isCreated()).andExpect(jsonPath("$.remaining").value(3));
		org.mockito.Mockito.verify(guestUploads).upload(event, "g1", "Ana Pop", new byte[] {1, 2});
		mvc.perform(up.with(jwt().jwt(j -> j.subject("g2").claim("email", "mihai@x.ro")))).andExpect(status().isCreated());
		org.mockito.Mockito.verify(guestUploads).upload(event, "g2", "Guest", new byte[] {1, 2}); // never part of the email
		mvc.perform(up.with(jwt().jwt(j -> j.subject("g3")))).andExpect(status().isCreated());
		org.mockito.Mockito.verify(guestUploads).upload(event, "g3", "Guest", new byte[] {1, 2});
	}

	private record Setup(UUID eventId, UUID camId, UUID guestId) {}

	private Setup eventWithCameraAndGuest(int guestLimit) {
		var eventId = UUID.randomUUID();
		var cam = new EventRepository.Camera(UUID.randomUUID(), eventId, "Ceremony", "cam_a", null, null);
		var guest = new ro.ghil.memoriesbe.events.GuestRepository.Guest(UUID.randomUUID(), eventId, "g1", "Ana Pop", null, null);
		var event = new EventRepository.Event(eventId, "owner", "Wedding", "wedding", java.time.LocalDate.of(2026, 6, 12), "heart", true, "tok", null, null, guestLimit > 0, guestLimit);
		when(repo.eventBySlugs("ana-pop", "wedding")).thenReturn(Optional.of(event));
		when(repo.cameras(eventId)).thenReturn(List.of(cam));
		when(guestRepo.guestsWithPhotos(eventId)).thenReturn(List.of(guest));
		var t = java.time.Instant.parse("2026-06-12T10:00:00Z");
		when(storage.entries(org.mockito.ArgumentMatchers.contains(cam.id().toString()), org.mockito.ArgumentMatchers.eq("Ceremony")))
				.thenReturn(List.of(new PhotoStorage.Entry("k/a1.jpg", "a1.jpg", t, "Ceremony")));
		when(storage.entries(org.mockito.ArgumentMatchers.contains("/guests/" + guest.id()), org.mockito.ArgumentMatchers.eq("Ana Pop")))
				.thenReturn(List.of(new PhotoStorage.Entry("k/g1.jpg", "g1.jpg", t.plusSeconds(60), "Ana Pop"), new PhotoStorage.Entry("k/g2.jpg", "g2.jpg", t.plusSeconds(90), "Ana Pop")));
		when(storage.signedUrl(org.mockito.ArgumentMatchers.anyString())).thenAnswer(i -> "https://r2/" + i.getArgument(0));
		return new Setup(eventId, cam.id(), guest.id());
	}

	@Test
	void photoStreamIncludesGuestPhotosAndTags() throws Exception {
		var s = eventWithCameraAndGuest(5);
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos")).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.guestLimit").value(5))
				.andExpect(jsonPath("$.photos[0].name").value("g2.jpg")).andExpect(jsonPath("$.photos[0].kind").value("guest"))
				.andExpect(jsonPath("$.photos[0].byId").value(s.guestId().toString()))
				.andExpect(jsonPath("$.photos[2].kind").value("camera"))
				.andExpect(jsonPath("$.tags.length()").value(2))
				.andExpect(jsonPath("$.tags[0].label").value("Ceremony")).andExpect(jsonPath("$.tags[0].kind").value("camera")).andExpect(jsonPath("$.tags[0].count").value(1))
				.andExpect(jsonPath("$.tags[1].label").value("Ana Pop")).andExpect(jsonPath("$.tags[1].kind").value("guest")).andExpect(jsonPath("$.tags[1].count").value(2));
	}

	@Test
	void photoStreamFiltersByOneOrMoreTagsButKeepsAllTags() throws Exception {
		var s = eventWithCameraAndGuest(5);
		var url = "/api/v1/public/photographers/ana-pop/events/wedding/photos";
		mvc.perform(get(url).param("by", s.camId().toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.photos[0].name").value("a1.jpg")).andExpect(jsonPath("$.tags.length()").value(2));
		mvc.perform(get(url).param("by", s.guestId().toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.photos[0].kind").value("guest"));
		mvc.perform(get(url).param("by", s.camId().toString(), s.guestId().toString())).andExpect(jsonPath("$.total").value(3));
		mvc.perform(get(url).param("by", UUID.randomUUID().toString())).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.photos.length()").value(0));
	}

	@Test
	void galleryListsGuestFoldersAndTheGuestLimit() throws Exception {
		var s = eventWithCameraAndGuest(7);
		when(storage.list(org.mockito.ArgumentMatchers.contains("/guests/" + s.guestId()))).thenReturn(List.of(new PhotoStorage.Photo("g1.jpg", "https://r2/g1")));
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding")).andExpect(status().isOk())
				.andExpect(jsonPath("$.guestLimit").value(7)).andExpect(jsonPath("$.folders.length()").value(2))
				.andExpect(jsonPath("$.folders[0].kind").value("camera")).andExpect(jsonPath("$.folders[1].kind").value("guest"))
				.andExpect(jsonPath("$.folders[1].name").value("Ana Pop")).andExpect(jsonPath("$.folders[1].photos[0].name").value("g1.jpg"));
	}

	@Test
	void guestLimitIsZeroWhenUploadsAreOff() throws Exception {
		eventWithCameraAndGuest(0);
		mvc.perform(get("/api/v1/public/photographers/ana-pop/events/wedding/photos")).andExpect(jsonPath("$.guestLimit").value(0));
	}

	@Test
	void creatorListsAndDeletesGuestPhotosAndOthersCannot() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		when(repo.eventOf(id, "intruder")).thenReturn(Optional.empty());
		var guest = new ro.ghil.memoriesbe.events.GuestRepository.Guest(UUID.randomUUID(), id, "g1", "Ana Pop", null, null);
		var upload = new ro.ghil.memoriesbe.events.GuestRepository.Upload(UUID.randomUUID(), id, guest.id(), "events/x/guests/y/p.jpg", null, null);
		when(guestRepo.uploads(id)).thenReturn(List.of(upload));
		when(guestRepo.guest(guest.id())).thenReturn(Optional.of(guest));
		when(guestRepo.upload(upload.id(), id)).thenReturn(Optional.of(upload), Optional.empty());
		when(storage.signedUrl("events/x/guests/y/p.jpg")).thenReturn("https://r2/p");
		mvc.perform(get("/api/v1/events/" + id + "/guest-photos").with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].guestName").value("Ana Pop")).andExpect(jsonPath("$[0].url").value("https://r2/p"));
		mvc.perform(get("/api/v1/events/" + id + "/guest-photos").with(jwt().jwt(j -> j.subject("intruder")))).andExpect(status().isNotFound());
		var del = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/events/" + id + "/guest-photos/" + upload.id());
		mvc.perform(del.with(jwt().jwt(j -> j.subject("intruder")))).andExpect(status().isNotFound());
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete(org.mockito.ArgumentMatchers.anyString());
		mvc.perform(del.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNoContent());
		var order = org.mockito.Mockito.inOrder(storage, guestRepo);
		order.verify(storage).delete("events/x/guests/y/p.jpg");
		order.verify(guestRepo).markDeleted(upload.id());
		mvc.perform(del.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNotFound());
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(1)).delete(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void creatorSeesGuestsAndBlocksOrUnblocksThemOnOwnEventOnly() throws Exception {
		UUID id = UUID.randomUUID();
		when(repo.eventOf(id, "s1")).thenReturn(Optional.of(event(id, java.time.LocalDate.of(2026, 12, 20))));
		when(repo.eventOf(id, "intruder")).thenReturn(Optional.empty());
		var guest = new ro.ghil.memoriesbe.events.GuestRepository.Guest(UUID.randomUUID(), id, "g1", "Ana Pop", null, null);
		var elsewhere = new ro.ghil.memoriesbe.events.GuestRepository.Guest(UUID.randomUUID(), UUID.randomUUID(), "g2", "Mihai", null, null);
		when(guestRepo.guest(guest.id())).thenReturn(Optional.of(guest));
		when(guestRepo.guest(elsewhere.id())).thenReturn(Optional.of(elsewhere));
		when(guestRepo.guestsOf(id)).thenReturn(List.of(new ro.ghil.memoriesbe.events.GuestRepository.GuestSummary(guest.id(), "Ana Pop", true, 3, null)));
		mvc.perform(get("/api/v1/events/" + id + "/guests").with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Ana Pop")).andExpect(jsonPath("$[0].blocked").value(true)).andExpect(jsonPath("$[0].photos").value(3));
		mvc.perform(get("/api/v1/events/" + id + "/guests").with(jwt().jwt(j -> j.subject("intruder")))).andExpect(status().isNotFound());
		var url = "/api/v1/events/" + id + "/guests/" + guest.id() + "/blocked";
		var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).contentType("application/json");
		mvc.perform(put.content("{\"blocked\":true}").with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNoContent());
		org.mockito.Mockito.verify(guestRepo).setBlocked(guest.id(), true);
		mvc.perform(put.content("{\"blocked\":false}").with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNoContent());
		org.mockito.Mockito.verify(guestRepo).setBlocked(guest.id(), false);
		mvc.perform(put.content("{\"blocked\":true}").with(jwt().jwt(j -> j.subject("intruder")))).andExpect(status().isNotFound());
		// a guest of another event cannot be blocked through this one
		var other = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/events/" + id + "/guests/" + elsewhere.id() + "/blocked").contentType("application/json");
		mvc.perform(other.content("{\"blocked\":true}").with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNotFound());
		org.mockito.Mockito.verify(guestRepo, org.mockito.Mockito.never()).setBlocked(elsewhere.id(), true);
	}

	@Test
	void frameThemeIsValidatedAndSaved() throws Exception {
		putLook("\"bgFrame\":\"neon\"").andExpect(status().isOk());
		org.mockito.Mockito.verify(profiles).update(org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
				org.mockito.ArgumentMatchers.eq(new ProfileRepository.Appearance("default", "", "none", "none", "neon", "")));
		putLook("\"bgFrame\":\"barbed-wire\"").andExpect(status().isBadRequest());
	}


	@Test
	void socialButtonColorIsValidatedAndSaved() throws Exception {
		putLook("\"bgSocial\":\"#12161c\"").andExpect(status().isOk());
		org.mockito.Mockito.verify(profiles).update(org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
				org.mockito.ArgumentMatchers.eq(new ProfileRepository.Appearance("default", "", "none", "none", "none", "#12161c")));
		putLook("\"bgSocial\":\"#ffffff\"").andExpect(status().isOk());
		putLook("\"bgSocial\":\"#2f5bd8\"").andExpect(status().isOk());
		putLook("\"bgSocial\":\"red\"").andExpect(status().isBadRequest());
		putLook("\"bgSocial\":\"#123456\"").andExpect(status().isBadRequest());
	}

	@Test
	void deletingTheAccountAlsoDeletesTheLogoObject() throws Exception {
		when(repo.eventsOf("s1")).thenReturn(List.of());
		when(profiles.bySub("s1")).thenReturn(Optional.of(profileWithLogo("profiles/tok/logo/old.png")));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
				.with(jwt().jwt(j -> j.subject("s1")))).andExpect(status().isNoContent());
		org.mockito.Mockito.verify(storage).delete("profiles/tok/logo/old.png");
		org.mockito.Mockito.verify(profiles).deleteAll("s1");
	}

	private static ProfileRepository.Profile profileWithLogo(String logoKey) {
		return new ProfileRepository.Profile("s1", "Ana Pop", "ana-pop", null, null, null, null, null, null, logoKey, "https://g/p.jpg", "tok", true, "default", "", "none", "none", "none", "", null);
	}

	private static byte[] png(int width, int height, int totalSize) {
		byte[] b = new byte[totalSize];
		byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
		System.arraycopy(head, 0, b, 0, head.length);
		java.nio.ByteBuffer.wrap(b).putInt(16, width).putInt(20, height);
		return b;
	}

	private org.springframework.test.web.servlet.ResultActions uploadLogo(byte[] bytes) throws Exception {
		when(profiles.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(profileWithLogo("profiles/tok/logo/old.png"));
		when(profiles.bySub("s1")).thenReturn(Optional.of(profileWithLogo("profiles/tok/logo/new.png")));
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/profile/logo")
				.file(new org.springframework.mock.web.MockMultipartFile("file", "logo.png", "image/png", bytes))
				.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))));
	}

	@Test
	void logoUploadStoresItAndReplacesTheOldOne() throws Exception {
		uploadLogo(png(100, 40, 200)).andExpect(status().isOk());
		org.mockito.Mockito.verify(profiles).setLogoKey(org.mockito.ArgumentMatchers.eq("s1"),
				org.mockito.ArgumentMatchers.argThat(k -> k.startsWith("profiles/tok/logo/") && k.endsWith(".png")));
		org.mockito.Mockito.verify(storage).delete("profiles/tok/logo/old.png");
	}

	@Test
	void logoRejectsWrongTypeAndTooBig() throws Exception {
		uploadLogo(new byte[] {1, 2, 3}).andExpect(status().isBadRequest());
		uploadLogo(png(100, 40, 2 * 1024 * 1024 + 1)).andExpect(status().isBadRequest());
		org.mockito.Mockito.verify(profiles, org.mockito.Mockito.never()).setLogoKey(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void logoRejectsOversizedDimensions() throws Exception {
		uploadLogo(png(2001, 100, 200)).andExpect(status().isBadRequest());
		org.mockito.Mockito.verify(profiles, org.mockito.Mockito.never()).setLogoKey(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void logoRejectsNegativeAndZeroDimensionsButAcceptsTheLimit() throws Exception {
		uploadLogo(png(0xFFFFFFFF, 100, 200)).andExpect(status().isBadRequest());
		uploadLogo(png(100, 0x80000000, 200)).andExpect(status().isBadRequest());
		uploadLogo(png(0, 0, 200)).andExpect(status().isBadRequest());
		org.mockito.Mockito.verify(profiles, org.mockito.Mockito.never()).setLogoKey(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		uploadLogo(png(2000, 2000, 200)).andExpect(status().isOk());
		org.mockito.Mockito.verify(profiles).setLogoKey(org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void logoCanBeRemoved() throws Exception {
		when(profiles.ensure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(profileWithLogo("profiles/tok/logo/old.png"));
		when(profiles.bySub("s1")).thenReturn(Optional.of(profileWithLogo(null)));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/profile/logo")
				.with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c")))).andExpect(status().isOk());
		org.mockito.Mockito.verify(profiles).setLogoKey("s1", null);
		org.mockito.Mockito.verify(storage).delete("profiles/tok/logo/old.png");
	}

	@Test
	void profileDtoCarriesLogoUrlOrEmpty() throws Exception {
		when(storage.signedUrl("profiles/tok/logo/a.png")).thenReturn("https://signed/logo");
		when(profiles.ensure("s1", "Ana Pop", "https://g/p.jpg")).thenReturn(profileWithLogo("profiles/tok/logo/a.png"));
		var me = get("/api/v1/profile").with(jwt().jwt(j -> j.subject("s1").claim("name", "Ana Pop").claim("email", "a@b.c").claim("picture", "https://g/p.jpg")));
		mvc.perform(me).andExpect(status().isOk()).andExpect(jsonPath("$.logo").value("https://signed/logo"));
		when(profiles.ensure("s1", "Ana Pop", "https://g/p.jpg")).thenReturn(profileWithLogo(null));
		mvc.perform(me).andExpect(status().isOk()).andExpect(jsonPath("$.logo").value(""));
	}
}
