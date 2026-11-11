package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import ro.ghil.memoriesbe.storage.ImageResizer;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.ThumbLinks;
import ro.ghil.memoriesbe.storage.ThumbService;

class ThumbTests {

	private static final String KEY = "events/e1/c1/IMG_0001.JPG";
	private static final String SECRET = "a-test-secret-that-is-long-enough-1234";

	private static Clock at(long epochSecond) {
		return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
	}

	private static Map<String, String> query(String url) {
		return Arrays.stream(URI.create(url).getRawQuery().split("&")).map(p -> p.split("=", 2))
				.collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
	}

	private static byte[] jpeg(int w, int h) throws IOException {
		var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		var out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

	private static BufferedImage read(byte[] bytes) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(bytes));
	}

	// ---- signed links

	@Test
	void linkVerifiesAndIsStableWithinTheSameHour() {
		var early = new ThumbLinks(SECRET, at(7200 + 10));
		var late = new ThumbLinks(SECRET, at(7200 + 3590));
		String a = early.url(KEY, 123, 480);
		assertEquals(a, late.url(KEY, 123, 480), "same hour, same link, so the browser can cache it");
		var q = query(a);
		assertTrue(early.valid(480, new String(java.util.Base64.getUrlDecoder().decode(q.get("k")), StandardCharsets.UTF_8),
				Long.parseLong(q.get("v")), Long.parseLong(q.get("e")), q.get("s")));
		assertNotEquals(a, new ThumbLinks(SECRET, at(7200 * 5)).url(KEY, 123, 480), "a later hour gets a new link");
	}

	@Test
	void tamperedOrExpiredOrForeignLinksAreRejected() {
		var links = new ThumbLinks(SECRET, at(10_000));
		var q = query(links.url(KEY, 5, 480));
		long exp = Long.parseLong(q.get("e"));
		String sig = q.get("s");
		assertFalse(links.valid(480, "events/e1/c1/OTHER.JPG", 5, exp, sig), "another photo");
		assertFalse(links.valid(1600, KEY, 5, exp, sig), "another size");
		assertFalse(links.valid(480, KEY, 6, exp, sig), "another version");
		assertFalse(links.valid(480, KEY, 5, exp + 3600, sig), "a longer lifetime");
		assertFalse(links.valid(480, KEY, 5, exp, sig.substring(1) + "x"), "a bad signature");
		assertFalse(new ThumbLinks(SECRET, at(exp + 1)).valid(480, KEY, 5, exp, sig), "expired");
		assertFalse(new ThumbLinks(SECRET.replace('a', 'b'), at(10_000)).valid(480, KEY, 5, exp, sig), "another secret");
		assertFalse(links.valid(333, KEY, 5, exp, links.sign(333, KEY, 5, exp)), "a size we do not make");
	}

	// ---- resizing

	@Test
	void bigPhotosAreScaledToTheWidthAndSmallOnesAreNotEnlarged() throws IOException {
		var big = read(ImageResizer.resize(jpeg(4000, 3000), 480));
		assertEquals(480, big.getWidth());
		assertEquals(360, big.getHeight());
		var small = read(ImageResizer.resize(jpeg(300, 200), 480));
		assertEquals(300, small.getWidth());
		assertEquals(200, small.getHeight());
	}

	@Test
	void unreadableBytesAreAnError() {
		assertThrows(IOException.class, () -> ImageResizer.resize(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, 480));
	}

	// ---- the service that makes them on first request

	private static final String THUMB = "thumbs/480/" + KEY + "@5.jpg";

	@Test
	void anExistingThumbIsServedWithoutTouchingTheOriginal() {
		var storage = mock(PhotoStorage.class);
		when(storage.tryGet(THUMB)).thenReturn(new byte[] {9, 9});
		var served = new ThumbService(storage).serve(KEY, 5, 480);
		assertArrayEquals(new byte[] {9, 9}, served.bytes());
		verify(storage, never()).tryGet(KEY);
	}

	@Test
	void aMissingThumbIsMadeFromTheOriginalAndStored() throws IOException {
		var storage = mock(PhotoStorage.class);
		when(storage.tryGet(THUMB)).thenReturn(null);
		when(storage.tryGet(KEY)).thenReturn(jpeg(2000, 1000));
		var served = new ThumbService(storage).serve(KEY, 5, 480);
		assertEquals(480, read(served.bytes()).getWidth());
		verify(storage).put(eq(THUMB), any(byte[].class), eq("image/jpeg"));
	}

	@Test
	void aMissingOriginalIsNothing() {
		var storage = mock(PhotoStorage.class);
		assertNull(new ThumbService(storage).serve(KEY, 5, 480));
	}

	@Test
	void aFormatWeCannotResizeFallsBackToTheOriginalLink() {
		var storage = mock(PhotoStorage.class);
		when(storage.tryGet(KEY)).thenReturn(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
		when(storage.signedUrl(KEY)).thenReturn("https://r2/original");
		var served = new ThumbService(storage).serve(KEY, 5, 480);
		assertNull(served.bytes());
		assertEquals("https://r2/original", served.redirect());
		verify(storage, never()).put(anyString(), any(byte[].class), anyString());
	}

	// ---- protection against heavy or broken photos

	/** A tiny PNG whose header claims a huge picture, like a decompression bomb. */
	private static byte[] pngClaiming(int w, int h) throws IOException {
		var out = new ByteArrayOutputStream();
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
		var ihdr = new ByteArrayOutputStream();
		var d = new java.io.DataOutputStream(ihdr);
		d.writeInt(w);
		d.writeInt(h);
		d.write(new byte[] {8, 2, 0, 0, 0});
		byte[] data = ihdr.toByteArray();
		var chunk = new java.io.DataOutputStream(out);
		chunk.writeInt(data.length);
		byte[] typeAndData = new byte[4 + data.length];
		System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, typeAndData, 0, 4);
		System.arraycopy(data, 0, typeAndData, 4, data.length);
		chunk.write(typeAndData);
		var crc = new java.util.zip.CRC32();
		crc.update(typeAndData);
		chunk.writeInt((int) crc.getValue());
		return out.toByteArray();
	}

	@Test
	void anImageClaimingTooManyPixelsIsRefusedBeforeItIsDecoded() throws IOException {
		var ex = assertThrows(IOException.class, () -> ImageResizer.resize(pngClaiming(30000, 30000), 480));
		assertTrue(ex.getMessage().contains("too large"), ex.getMessage());
	}

	@Test
	void aPhotoThatFailedIsNotDownloadedAgainOnTheNextRequest() {
		var storage = mock(PhotoStorage.class);
		when(storage.tryGet(KEY)).thenReturn(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
		when(storage.signedUrl(KEY)).thenReturn("https://r2/original");
		var service = new ThumbService(storage);
		assertEquals("https://r2/original", service.serve(KEY, 5, 480).redirect());
		assertEquals("https://r2/original", service.serve(KEY, 5, 480).redirect());
		verify(storage, org.mockito.Mockito.times(1)).tryGet(KEY);
	}

	@Test
	void webpAndGifGoStraightToTheOriginalWithoutDownloadingIt() {
		var storage = mock(PhotoStorage.class);
		when(storage.signedUrl("events/e1/g1/a.webp")).thenReturn("https://r2/webp");
		var served = new ThumbService(storage).serve("events/e1/g1/a.webp", 1, 480);
		assertEquals("https://r2/webp", served.redirect());
		verify(storage, never()).tryGet(anyString());
	}

	@Test
	void whenAllTurnsAreTakenARequestGetsTheOriginalInsteadOfWaiting() {
		var storage = mock(PhotoStorage.class);
		when(storage.signedUrl(KEY)).thenReturn("https://r2/original");
		var busy = new ThumbService(storage, 0, 20);
		assertEquals("https://r2/original", busy.serve(KEY, 5, 480).redirect());
		verify(storage, never()).tryGet(KEY);
	}

	@Test
	void aConfiguredSecretMustBeLongEnough() {
		assertThrows(IllegalStateException.class, () -> new ThumbLinks("too-short", at(1)));
		new ThumbLinks("x".repeat(32), at(1));
	}
}
