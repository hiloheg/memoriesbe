package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import ro.ghil.memoriesbe.config.Props;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class PhotoStorageTests {

	private final S3Client s3 = mock(S3Client.class);
	private final PhotoStorage storage = new PhotoStorage(s3, mock(S3Presigner.class), new Props.R2("https://r2", "memories", "auto", "AK", "SK"));

	private static S3Object obj(String key, long size) {
		return S3Object.builder().key(key).size(size).lastModified(Instant.parse("2026-12-01T10:00:00Z")).build();
	}

	/** Two pages of results: the first is cut off at the page size, so the second must be asked for. */
	private void twoPages() {
		when(s3.listObjectsV2(argThat((ListObjectsV2Request r) -> r != null && r.continuationToken() == null)))
				.thenReturn(ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("t1")
						.contents(obj("events/e/c/a.jpg", 10), obj("events/e/c/b.JPG", 20)).build());
		when(s3.listObjectsV2(argThat((ListObjectsV2Request r) -> r != null && "t1".equals(r.continuationToken()))))
				.thenReturn(ListObjectsV2Response.builder().isTruncated(false)
						.contents(obj("events/e/c/c.png", 30), obj("events/e/c/notes.txt", 999)).build());
	}

	@Test
	void usageCountsEveryPageAndOnlyImages() {
		twoPages();
		var usage = storage.usage("events/e/");
		assertEquals(3, usage.photos(), "the .txt file is not a photo");
		assertEquals(60, usage.bytes());
	}

	@Test
	void entriesReadPastTheFirstThousandPhotos() {
		twoPages();
		assertEquals(3, storage.entries("events/e/c/", "Cam").size());
	}
}
