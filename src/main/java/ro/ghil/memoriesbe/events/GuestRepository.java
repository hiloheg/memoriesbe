package ro.ghil.memoriesbe.events;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access (JdbcClient) for the guests of an event and the photos they uploaded. */
@Repository
public class GuestRepository {
    public record Guest(UUID id, UUID eventId, String sub, String name, OffsetDateTime createdAt, OffsetDateTime blockedAt) {}
    /** A guest as the event creator sees them: how many photos they still have in the event, and whether they are blocked. */
    public record GuestSummary(UUID id, String name, boolean blocked, int photos, OffsetDateTime createdAt) {}
    public record Upload(UUID id, UUID eventId, UUID guestId, String objectKey, OffsetDateTime createdAt, OffsetDateTime deletedAt) {}

    private final JdbcClient jdbc;

    GuestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Forgets every guest of the event and their uploads (names are personal data); the photos themselves are deleted from storage separately. */
    public void deleteAllFor(UUID eventId) {
        jdbc.sql("delete from guest_uploads where event_id = ?").param(eventId).update();
        jdbc.sql("delete from event_guests where event_id = ?").param(eventId).update();
    }

    /** The guest row for this Google account on this event; created on first use, the name is kept up to date. */
    public Guest ensureGuest(UUID eventId, String sub, String name) {
        return jdbc.sql("""
                insert into event_guests (id, event_id, sub, name) values (?, ?, ?, ?)
                on conflict (event_id, sub) do update set name = excluded.name
                returning *""")
                .params(UUID.randomUUID(), eventId, sub, name).query(Guest.class).single();
    }

    /** Locks the guest row until the transaction ends, so two uploads by one guest are checked one after the other. */
    public Guest lockGuest(UUID guestId) {
        return jdbc.sql("select * from event_guests where id = ? for update").param(guestId).query(Guest.class).single();
    }

    public Optional<Guest> guestBySub(UUID eventId, String sub) {
        return jdbc.sql("select * from event_guests where event_id = ? and sub = ?").params(eventId, sub).query(Guest.class).optional();
    }

    /** Every guest of the event, blocked ones and those whose photos were all deleted included. */
    public List<GuestSummary> guestsOf(UUID eventId) {
        return jdbc.sql("""
                select g.id, g.name, (g.blocked_at is not null) as blocked,
                       (count(u.id) filter (where u.deleted_at is null))::int as photos, g.created_at
                from event_guests g left join guest_uploads u on u.guest_id = g.id
                where g.event_id = ?
                group by g.id order by g.name, g.created_at""")
                .param(eventId).query(GuestSummary.class).list();
    }

    public void setBlocked(UUID guestId, boolean blocked) {
        jdbc.sql("update event_guests set blocked_at = case when ? then coalesce(blocked_at, now()) else null end where id = ?")
                .params(blocked, guestId).update();
    }

    public Optional<Guest> guest(UUID id) {
        return jdbc.sql("select * from event_guests where id = ?").param(id).query(Guest.class).optional();
    }

    /** Photos this guest ever added to the event, deleted ones included. */
    public int usedBy(UUID guestId) {
        return jdbc.sql("select count(*) from guest_uploads where guest_id = ?").param(guestId).query(Integer.class).single();
    }

    public int totalFor(UUID eventId) {
        return jdbc.sql("select count(*) from guest_uploads where event_id = ?").param(eventId).query(Integer.class).single();
    }

    public void insertUpload(UUID id, UUID eventId, UUID guestId, String objectKey) {
        jdbc.sql("insert into guest_uploads (id, event_id, guest_id, object_key) values (?, ?, ?, ?)")
                .params(id, eventId, guestId, objectKey).update();
    }

    /** Guests who still have at least one photo in the event. */
    public List<Guest> guestsWithPhotos(UUID eventId) {
        return jdbc.sql("""
                select g.* from event_guests g
                where g.event_id = ? and exists (select 1 from guest_uploads u where u.guest_id = g.id and u.deleted_at is null)
                order by g.name, g.created_at""")
                .param(eventId).query(Guest.class).list();
    }

    /** Photos still in the event, newest first. */
    public List<Upload> uploads(UUID eventId) {
        return jdbc.sql("select * from guest_uploads where event_id = ? and deleted_at is null order by created_at desc")
                .param(eventId).query(Upload.class).list();
    }

    public Optional<Upload> upload(UUID id, UUID eventId) {
        return jdbc.sql("select * from guest_uploads where id = ? and event_id = ? and deleted_at is null")
                .params(id, eventId).query(Upload.class).optional();
    }

    public void markDeleted(UUID id) {
        jdbc.sql("update guest_uploads set deleted_at = now() where id = ?").param(id).update();
    }
}
