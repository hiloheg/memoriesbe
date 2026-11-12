package ro.ghil.memoriesbe.faces;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQL access (JdbcClient) for the faces found in event photos and for which photos were already looked at. */
@Repository
public class FaceRepository {
    /** A photo is given up on after this many failed attempts. */
    public static final int MAX_ATTEMPTS = 3;

    /** One face of one photo. */
    public record StoredFace(String objectKey, float[] embedding) {}

    private final JdbcClient jdbc;

    FaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Photos that are finished (read, or given up on): object key to the version they were read at. */
    public Map<String, Long> indexedVersions(UUID eventId) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("select object_key, version from event_face_photos where event_id = ? and done")
                .param(eventId).query((rs, i) -> {
                    out.put(rs.getString("object_key"), rs.getLong("version"));
                    return 0;
                }).list();
        return out;
    }

    /** How many times reading this photo failed so far (0 when never). */
    public int attempts(UUID eventId, String objectKey) {
        return jdbc.sql("select attempts from event_face_photos where event_id = ? and object_key = ?")
                .params(eventId, objectKey).query(Integer.class).optional().orElse(0);
    }

    /** Counts one failed attempt on this photo (the count starts again when the photo changed). The photo stays pending. */
    public void markFailedAttempt(UUID eventId, String objectKey, long version) {
        jdbc.sql("""
                insert into event_face_photos (event_id, object_key, version, attempts, done) values (?, ?, ?, 1, false)
                on conflict (event_id, object_key) do update
                set attempts = case when event_face_photos.version = excluded.version then event_face_photos.attempts + 1 else 1 end,
                    version = excluded.version, done = false""")
                .params(eventId, objectKey, version).update();
    }

    /** Replaces the faces of this photo with these and marks it done. */
    @Transactional
    public void savePhoto(UUID eventId, String objectKey, long version, List<float[]> embeddings) {
        jdbc.sql("delete from event_faces where event_id = ? and object_key = ?").params(eventId, objectKey).update();
        for (float[] e : embeddings) {
            jdbc.sql("insert into event_faces (event_id, object_key, embedding) values (?, ?, ?)")
                    .params(eventId, objectKey, FaceMath.toBytes(e)).update();
        }
        jdbc.sql("""
                insert into event_face_photos (event_id, object_key, version, faces, attempts, done) values (?, ?, ?, ?, 0, true)
                on conflict (event_id, object_key) do update set version = excluded.version, faces = excluded.faces, attempts = 0, done = true""")
                .params(eventId, objectKey, version, embeddings.size()).update();
    }

    /** Every face stored for the event. */
    public List<StoredFace> faces(UUID eventId) {
        return jdbc.sql("select object_key, embedding from event_faces where event_id = ?")
                .param(eventId).query((rs, i) -> new StoredFace(rs.getString("object_key"), FaceMath.fromBytes(rs.getBytes("embedding")))).list();
    }

    /** Removes everything stored about the event's faces (the tables also cascade when the event row is deleted). */
    public void deleteAllFor(UUID eventId) {
        jdbc.sql("delete from event_faces where event_id = ?").param(eventId).update();
        jdbc.sql("delete from event_face_photos where event_id = ?").param(eventId).update();
    }
}
