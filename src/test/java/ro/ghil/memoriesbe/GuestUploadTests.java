package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.events.GuestRepository;
import ro.ghil.memoriesbe.events.GuestRepository.Guest;
import ro.ghil.memoriesbe.events.GuestUploadService;
import ro.ghil.memoriesbe.events.ImageSniffer;
import ro.ghil.memoriesbe.events.UploadRateLimiter;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import org.springframework.security.oauth2.jwt.Jwt;

class GuestUploadTests {

	private static Jwt token(String email, boolean verified, String audience, String issuer) {
		return Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
				.issuer(issuer).audience(List.of(audience)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
				.claim("email", email).claim("email_verified", verified).build();
	}

	private static Jwt good(String email) {
		return token(email, true, "client-1", "https://accounts.google.com");
	}

	@Test
	void photographerTokensMustBeOnTheAllowList() {
		var validator = GoogleTokens.validators("client-1", List.of("a@b.c"));
		assertTrue(validator.validate(good("a@b.c")).hasErrors() == false);
		assertTrue(validator.validate(good("x@y.z")).hasErrors());
	}

	@Test
	void guestTokensNeedOnlyAVerifiedGoogleAccountForThisApp() {
		var guest = GoogleTokens.validators("client-1", List.of());
		assertFalse(guest.validate(good("x@y.z")).hasErrors());
		assertTrue(guest.validate(token("x@y.z", false, "client-1", "https://accounts.google.com")).hasErrors());
		assertTrue(guest.validate(token("x@y.z", true, "other-client", "https://accounts.google.com")).hasErrors());
		assertTrue(guest.validate(token("x@y.z", true, "client-1", "https://evil.example")).hasErrors());
	}

	@Test
	void onlyTheGuestAreaSkipsTheAllowList() {
		assertTrue(GoogleTokens.isGuestPath("/api/v1/guest/photographers/a/events/b/status"));
		assertFalse(GoogleTokens.isGuestPath("/api/v1/events"));
		assertFalse(GoogleTokens.isGuestPath("/api/v1/me"));
		assertFalse(GoogleTokens.isGuestPath("/api/v1/public/events/x"));
		assertFalse(GoogleTokens.isGuestPath("/api/v1/guestx/anything"));
	}

	// ---- image types ----

	private static byte[] bytes(int... b) {
		byte[] out = new byte[b.length + 32];
		for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
		return out;
	}

	private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0);
	private static final byte[] PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
	private static final byte[] WEBP = bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');

	@Test
	void snifferRecognisesRealImagesByTheirFirstBytes() {
		assertEquals("jpg", ImageSniffer.detect(JPEG).orElseThrow());
		assertEquals("png", ImageSniffer.detect(PNG).orElseThrow());
		assertEquals("webp", ImageSniffer.detect(WEBP).orElseThrow());
		assertTrue(ImageSniffer.detect("<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes()).isEmpty());
		assertTrue(ImageSniffer.detect("<html><script>alert(1)</script></html>".getBytes()).isEmpty());
		assertTrue(ImageSniffer.detect(new byte[0]).isEmpty());
		assertTrue(ImageSniffer.detect(new byte[] {(byte) 0xFF}).isEmpty());
	}

	// ---- rate limit ----

	@Test
	void rateLimiterAllowsTwentyPerMinutePerPerson() {
		var limiter = new UploadRateLimiter();
		for (int i = 0; i < 20; i++) assertTrue(limiter.tryAcquire("a"));
		assertFalse(limiter.tryAcquire("a"));
		assertTrue(limiter.tryAcquire("b"));
	}

	// ---- upload service ----

	private final GuestRepository guests = mock(GuestRepository.class);
	private final PhotoStorage storage = mock(PhotoStorage.class);
	private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
	private final ro.ghil.memoriesbe.events.EventLifetime lifetime = mock(ro.ghil.memoriesbe.events.EventLifetime.class);
	private final GuestUploadService service = new GuestUploadService(guests, storage, tx, new UploadRateLimiter(), lifetime);

	private static ro.ghil.memoriesbe.events.EventRepository.Limits limitsOf(UUID eventId, int maxPhotos, long maxBytes) {
		var closes = java.time.OffsetDateTime.now().plusDays(30);
		return new ro.ghil.memoriesbe.events.EventRepository.Limits(eventId, "full", maxPhotos, maxBytes, closes, closes.plusDays(14), null);
	}

	@Test
	void guestCannotUploadToAnExpiredEvent() {
		var e = event(true, 3);
		org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.GONE, "This gallery has expired")).when(lifetime).requireOpen(e.id());
		assertEquals(HttpStatus.GONE, statusOf(() -> service.upload(e, "g1", "Ana Pop", JPEG)));
		verify(storage, never()).put(anyString(), any(byte[].class), anyString());
	}

	@Test
	void guestUploadRefusedWhenTheEventIsFull() {
		var e = event(true, 3);
		guestFor(e);
		when(lifetime.limits(e.id())).thenReturn(java.util.Optional.of(limitsOf(e.id(), 3000, 42949672960L)));
		when(storage.usage("events/" + e.id() + "/")).thenReturn(new PhotoStorage.Usage(3000, 1));
		var ex = assertThrows(ResponseStatusException.class, () -> service.upload(e, "g1", "Ana Pop", JPEG));
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
		assertTrue(ex.getReason().contains("photo limit"), ex.getReason());
		verify(storage, never()).put(anyString(), any(byte[].class), anyString());
	}

	@Test
	void guestUploadRefusedWhenTheEventIsOverItsStorageCap() {
		var e = event(true, 3);
		guestFor(e);
		when(lifetime.limits(e.id())).thenReturn(java.util.Optional.of(limitsOf(e.id(), 3000, 42949672960L)));
		when(storage.usage("events/" + e.id() + "/")).thenReturn(new PhotoStorage.Usage(10, 42949672960L));
		assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.upload(e, "g1", "Ana Pop", JPEG)));
	}

	@Test
	void guestUploadAllowedWhileThereIsRoom() {
		var e = event(true, 3);
		var g = guestFor(e);
		when(guests.usedBy(g.id())).thenReturn(0);
		when(lifetime.limits(e.id())).thenReturn(java.util.Optional.of(limitsOf(e.id(), 3000, 42949672960L)));
		when(storage.usage("events/" + e.id() + "/")).thenReturn(new PhotoStorage.Usage(2999, 1000));
		assertEquals(2, service.upload(e, "g1", "Ana Pop", JPEG));
	}

	private static Event event(boolean enabled, int limit) {
		return new Event(UUID.randomUUID(), "owner", "Nunta", "nunta", java.time.LocalDate.of(2026, 6, 12), "camera", true, "tok", null, null, enabled, limit, false);
	}

	private Guest guestFor(Event e) {
		var g = new Guest(UUID.randomUUID(), e.id(), "g1", "Ana Pop", null, null);
		when(guests.ensureGuest(e.id(), "g1", "Ana Pop")).thenReturn(g);
		when(guests.lockGuest(g.id())).thenReturn(g);
		return g;
	}

	private static HttpStatus statusOf(Runnable r) {
		return (HttpStatus) assertThrows(ResponseStatusException.class, r::run).getStatusCode();
	}

	@Test
	void uploadStoresUnderTheGuestPrefixAndReturnsRemaining() {
		var e = event(true, 3);
		var g = guestFor(e);
		when(guests.usedBy(g.id())).thenReturn(1);
		int remaining = service.upload(e, "g1", "Ana Pop", JPEG);
		assertEquals(1, remaining);
		verify(storage).put(org.mockito.ArgumentMatchers.startsWith("events/" + e.id() + "/guests/" + g.id() + "/"), eq(JPEG), eq("image/jpeg"));
		verify(guests).insertUpload(any(), eq(e.id()), eq(g.id()), org.mockito.ArgumentMatchers.endsWith(".jpg"));
	}

	@Test
	void rejectsWhenSwitchedOffLimitZeroOrReached() {
		var off = event(false, 5);
		assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.upload(off, "g1", "Ana Pop", JPEG)));
		var zero = event(true, 0);
		assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.upload(zero, "g1", "Ana Pop", JPEG)));
		var full = event(true, 2);
		var g = guestFor(full);
		when(guests.usedBy(g.id())).thenReturn(2);
		assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.upload(full, "g1", "Ana Pop", JPEG)));
		verify(storage, never()).put(anyString(), any(), anyString());
	}

	@Test
	void stopsAtTheHardCapForTheWholeEvent() {
		var e = event(true, 50);
		var g = guestFor(e);
		when(guests.usedBy(g.id())).thenReturn(0);
		when(guests.totalFor(e.id())).thenReturn(2000);
		assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.upload(e, "g1", "Ana Pop", JPEG)));
	}

	@Test
	void locksTheGuestBeforeCountingAndInserting() {
		var e = event(true, 3);
		var g = guestFor(e);
		service.upload(e, "g1", "Ana Pop", JPEG);
		var order = inOrder(guests);
		order.verify(guests).lockGuest(g.id());
		order.verify(guests).usedBy(g.id());
		order.verify(guests).insertUpload(any(), eq(e.id()), eq(g.id()), anyString());
	}

	@Test
	void failedStorageWriteRollsTheUploadBack() {
		var e = event(true, 3);
		guestFor(e);
		org.mockito.Mockito.doThrow(new IllegalStateException("R2 down")).when(storage).put(anyString(), any(), anyString());
		assertThrows(IllegalStateException.class, () -> service.upload(e, "g1", "Ana Pop", JPEG));
		verify(tx).rollback(any());
		verify(tx, never()).commit(any());
	}

	@Test
	void htmlNamedAsAPhotoIsRejected() {
		var e = event(true, 3);
		var html = "<html><script>alert(1)</script></html>".getBytes();
		assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.upload(e, "g1", "Ana Pop", html)));
		verify(storage, never()).put(anyString(), any(), anyString());
	}

	@Test
	void filesOverFifteenMegabytesAreRefused() {
		var e = event(true, 3);
		var big = new byte[15 * 1024 * 1024 + 1];
		big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
		assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, statusOf(() -> service.upload(e, "g1", "Ana Pop", big)));
	}

	@Test
	void tooManyUploadsInAMinuteIs429() {
		var e = event(true, 50);
		guestFor(e);
		for (int i = 0; i < 20; i++) service.upload(e, "g1", "Ana Pop", JPEG);
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf(() -> service.upload(e, "g1", "Ana Pop", JPEG)));
	}

	@Test
	void statusCountsWhatTheGuestAlreadyAdded() {
		var e = event(true, 4);
		var g = new Guest(UUID.randomUUID(), e.id(), "g1", "Ana Pop", null, null);
		when(guests.guestBySub(e.id(), "g1")).thenReturn(java.util.Optional.of(g));
		when(guests.usedBy(g.id())).thenReturn(3);
		assertEquals(new GuestUploadService.Status(true, 4, 3, false), service.status(e, "g1"));
		when(guests.guestBySub(e.id(), "new")).thenReturn(java.util.Optional.empty());
		assertEquals(new GuestUploadService.Status(true, 4, 0, false), service.status(e, "new"));
	}

	@Test
	void aBlockedGuestCannotAddPhotos() {
		var e = event(true, 5);
		var blocked = new Guest(UUID.randomUUID(), e.id(), "g1", "Ana Pop", null, java.time.OffsetDateTime.now());
		when(guests.ensureGuest(e.id(), "g1", "Ana Pop")).thenReturn(blocked);
		when(guests.lockGuest(blocked.id())).thenReturn(blocked);
		assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.upload(e, "g1", "Ana Pop", JPEG)));
		verify(guests, never()).insertUpload(any(), any(), any(), anyString());
		verify(storage, never()).put(anyString(), any(), anyString());
	}

	@Test
	void statusTellsABlockedGuestTheyAreBlocked() {
		var e = event(true, 5);
		var blocked = new Guest(UUID.randomUUID(), e.id(), "g1", "Ana Pop", null, java.time.OffsetDateTime.now());
		when(guests.guestBySub(e.id(), "g1")).thenReturn(java.util.Optional.of(blocked));
		assertEquals(new GuestUploadService.Status(true, 5, 0, true), service.status(e, "g1"));
	}
}
