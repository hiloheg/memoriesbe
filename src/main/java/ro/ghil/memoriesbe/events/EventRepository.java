package ro.ghil.memoriesbe.events;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access (JdbcClient) for events and their cameras. */
@Repository
public class EventRepository {
    public record Event(UUID id, String ownerSub, String name, String slug, LocalDate eventDate, String icon, boolean listed, String publicToken, OffsetDateTime createdAt, String accessPasswordEnc) {}
    public record Camera(UUID id, UUID eventId, String name, String sftpUsername, OffsetDateTime createdAt, String passwordEnc) {}

    private final JdbcClient jdbc;

    EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertEvent(UUID id, String ownerSub, String name, String slug, LocalDate eventDate, String icon, boolean listed, String publicToken) {
        jdbc.sql("insert into events (id, owner_sub, name, slug, event_date, icon, listed, public_token) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, ownerSub, name, slug, eventDate, icon, listed, publicToken).update();
    }

    public boolean eventSlugTaken(String ownerSub, String slug) {
        return jdbc.sql("select count(*) from events where owner_sub = ? and slug = ?")
                .params(ownerSub, slug).query(Integer.class).single() > 0;
    }

    /** Finds an event from the readable address: photographer slug plus event slug. */
    public Optional<Event> eventBySlugs(String profileSlug, String eventSlug) {
        return jdbc.sql("""
                select e.* from events e join profiles p on p.owner_sub = e.owner_sub
                where p.slug = ? and e.slug = ?""")
                .params(profileSlug, eventSlug).query(Event.class).optional();
    }

    public List<Event> eventsOf(String ownerSub) {
        return jdbc.sql("select * from events where owner_sub = ? order by event_date desc, created_at desc")
                .param(ownerSub).query(Event.class).list();
    }

    /** Number of cameras per event for all events of one owner. */
    public Map<UUID, Integer> cameraCounts(String ownerSub) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql("select c.event_id, count(*) as n from cameras c join events e on e.id = c.event_id where e.owner_sub = ? group by c.event_id")
                .param(ownerSub)
                .query((rs, i) -> {
                    counts.put(rs.getObject("event_id", UUID.class), rs.getInt("n"));
                    return 0;
                }).list();
        return counts;
    }

    public Optional<Event> eventOf(UUID id, String ownerSub) {
        return jdbc.sql("select * from events where id = ? and owner_sub = ?")
                .params(id, ownerSub).query(Event.class).optional();
    }

    public Optional<Event> eventByToken(String token) {
        return jdbc.sql("select * from events where public_token = ?").param(token).query(Event.class).optional();
    }

    public Optional<Event> eventById(UUID id) {
        return jdbc.sql("select * from events where id = ?").param(id).query(Event.class).optional();
    }

    public void setAccessPassword(UUID id, String passwordEnc) {
        jdbc.sql("update events set access_password_enc = ? where id = ?").params(passwordEnc, id).update();
    }

    public void updateListed(UUID id, boolean listed) {
        jdbc.sql("update events set listed = ? where id = ?").params(listed, id).update();
    }

    public void updateDetails(UUID id, String name, String icon) {
        jdbc.sql("update events set name = ?, icon = ? where id = ?").params(name, icon, id).update();
    }

    public void updateIcon(UUID id, String icon) {
        jdbc.sql("update events set icon = ? where id = ?").params(icon, id).update();
    }

    public void deleteEvent(UUID id) {
        jdbc.sql("delete from events where id = ?").param(id).update();
    }

    public void insertCamera(UUID id, UUID eventId, String name, String username, String passwordEnc) {
        jdbc.sql("insert into cameras (id, event_id, name, sftp_username, password_enc) values (?, ?, ?, ?, ?)")
                .params(id, eventId, name, username, passwordEnc).update();
    }

    public void setCameraPassword(UUID id, String passwordEnc) {
        jdbc.sql("update cameras set password_enc = ? where id = ?").params(passwordEnc, id).update();
    }

    public List<Camera> cameras(UUID eventId) {
        return jdbc.sql("select * from cameras where event_id = ? order by created_at")
                .param(eventId).query(Camera.class).list();
    }

    public Optional<Camera> camera(UUID id, UUID eventId) {
        return jdbc.sql("select * from cameras where id = ? and event_id = ?")
                .params(id, eventId).query(Camera.class).optional();
    }

    public void deleteCamera(UUID id) {
        jdbc.sql("delete from cameras where id = ?").param(id).update();
    }
}
