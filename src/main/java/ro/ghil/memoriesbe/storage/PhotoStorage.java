package ro.ghil.memoriesbe.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import ro.ghil.memoriesbe.config.Props;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Lists photos under an R2 prefix and hands out short-lived presigned GET URLs (the bucket stays private). */
@Component
public class PhotoStorage {
    /** key and modified (epoch seconds) let the caller build links to the small copies; both are empty for hand-made test photos. */
    public record Photo(String name, String url, String key, long modified) {
        public Photo(String name, String url) {
            this(name, url, null, 0L);
        }
    }

    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Duration URL_TTL = Duration.ofHours(1);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public PhotoStorage(S3Client s3, S3Presigner presigner, Props.R2 r2) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = r2.bucket();
    }

    /** A stored photo before it gets a download URL. */
    public record Entry(String key, String name, Instant modified, String folder) {}

    /** Every image under the prefix with no URL signed yet, so callers can sort and page cheaply. At most 1000 per prefix. */
    public List<Entry> entries(String prefix, String folder) {
        var res = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(1000).build());
        return res.contents().stream()
                .filter(o -> isImage(o.key()))
                .map(o -> new Entry(o.key(), o.key().substring(prefix.length()), o.lastModified() == null ? Instant.EPOCH : o.lastModified(), folder))
                .toList();
    }

    /** Newest first, at most 1000 photos per prefix. */
    public List<Photo> list(String prefix) {
        var res = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(1000).build());
        return res.contents().stream()
                .filter(o -> isImage(o.key()))
                .sorted(Comparator.comparing((S3Object o) -> o.lastModified() == null ? Instant.EPOCH : o.lastModified()).reversed())
                .map(o -> new Photo(o.key().substring(prefix.length()), signedUrl(o.key()), o.key(), o.lastModified() == null ? 0L : o.lastModified().getEpochSecond()))
                .toList();
    }

    /** Deletes every object under the prefix (all pages, 1000 per request), and the small copies of those photos. Safe to call again if it fails halfway. */
    public int deleteAll(String prefix) {
        if (prefix == null || prefix.length() < 8 || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("Refusing to delete with an unsafe prefix: " + prefix);
        }
        int deleted = purge(prefix);
        if (prefix.startsWith("events/")) {
            for (int w : ThumbLinks.WIDTHS) purge("thumbs/" + w + "/" + prefix);
        }
        return deleted;
    }

    private int purge(String prefix) {
        int deleted = 0;
        for (var page : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())) {
            var ids = page.contents().stream().map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
            if (ids.isEmpty()) continue;
            var res = s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                    .delete(Delete.builder().objects(ids).quiet(true).build()).build());
            if (res.hasErrors() && !res.errors().isEmpty()) {
                throw new IllegalStateException("R2 refused to delete " + res.errors().size() + " objects under " + prefix);
            }
            deleted += ids.size();
        }
        return deleted;
    }

    /** The object's bytes, or null when it does not exist. */
    public byte[] tryGet(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (NoSuchKeyException ex) {
            return null;
        }
    }

    /** Stores one small object (used for profile pictures). */
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(), RequestBody.fromBytes(bytes));
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        if (key.startsWith("events/")) {
            for (int w : ThumbLinks.WIDTHS) purge("thumbs/" + w + "/" + key + "@");
        }
    }

    private boolean isImage(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 && IMAGES.contains(key.substring(dot + 1).toLowerCase());
    }

    private record Signed(String url, Instant at) {}

    private static final Duration REUSE = Duration.ofMinutes(30);
    private static final int MAX_REMEMBERED = 20000;
    private final ConcurrentHashMap<String, Signed> signed = new ConcurrentHashMap<>();

    /**
     * A presigned link to the object, kept for half an hour so the same photo has the same address on every request
     * (a new address each time would defeat the browser's cache). The link itself is valid for an hour.
     */
    public String signedUrl(String key) {
        Instant now = Instant.now();
        var known = signed.get(key);
        if (known != null && Duration.between(known.at(), now).compareTo(REUSE) < 0) return known.url();
        if (signed.size() > MAX_REMEMBERED) signed.clear();
        String url = presigner.presignGetObject(r -> r.signatureDuration(URL_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).responseCacheControl("private, max-age=1800").build())).url().toString();
        signed.put(key, new Signed(url, now));
        return url;
    }
}
