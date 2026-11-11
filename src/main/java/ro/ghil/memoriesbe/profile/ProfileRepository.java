package ro.ghil.memoriesbe.profile;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Photographer profile: the public page settings, the readable address (slug), the picture and the follow buttons. */
@Repository
public class ProfileRepository {
    public record Profile(String ownerSub, String name, String slug, String tagline, String bio, String accent,
                          String website, String instagram, String avatarKey, String googlePicture,
                          String publicToken, boolean followGate,
                          String bgKind, String bgValue, String bgPattern, String bgEffect, UUID mainEventId) {}

    /** Page background chosen by the photographer: kind (default, color, gradient), preset, pattern and effect keys. */
    public record Appearance(String kind, String value, String pattern, String effect) {}

    /** One follow button on the public page. */
    public record Link(UUID id, String label, String url) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String COLUMNS = "owner_sub, name, slug, tagline, bio, accent, website, instagram, avatar_key, google_picture, public_token, follow_gate, bg_kind, bg_value, bg_pattern, bg_effect, main_event_id";
    private final JdbcClient jdbc;

    ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the profile on first sign-in (name and address from the Google account) and refreshes the Google picture. */
    public Profile ensure(String ownerSub, String googleName, String googlePicture) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Profile p = jdbc.sql("""
                insert into profiles (owner_sub, name, public_token, google_picture) values (?, ?, ?, ?)
                on conflict (owner_sub) do update set google_picture = excluded.google_picture
                returning """ + " " + COLUMNS)
                .params(ownerSub, googleName, token, googlePicture).query(Profile.class).single();
        if (p.slug() != null) return p;

        String base = Slugs.slugify(p.name());
        String candidate = base;
        for (int n = 2; Slugs.reserved(candidate) || slugTaken(candidate); n++) {
            String suffix = "-" + n;
            candidate = base.substring(0, Math.min(base.length(), 30 - suffix.length())) + suffix;
        }
        jdbc.sql("update profiles set slug = ? where owner_sub = ? and slug is null").params(candidate, ownerSub).update();
        return bySub(ownerSub).orElseThrow();
    }

    public Optional<Profile> bySub(String ownerSub) {
        return jdbc.sql("select " + COLUMNS + " from profiles where owner_sub = ?")
                .param(ownerSub).query(Profile.class).optional();
    }

    public Optional<Profile> bySlug(String slug) {
        return jdbc.sql("select " + COLUMNS + " from profiles where slug = ?")
                .param(slug).query(Profile.class).optional();
    }

    public boolean slugTaken(String slug) {
        return jdbc.sql("select count(*) from profiles where slug = ?").param(slug).query(Integer.class).single() > 0;
    }

    /** Throws DuplicateKeyException when the slug belongs to someone else. */
    public void update(String ownerSub, String name, String slug, String tagline, String bio, String accent,
                       String website, String instagram, boolean followGate, Appearance look) {
        jdbc.sql("""
                update profiles set name = ?, slug = ?, tagline = ?, bio = ?, accent = ?, website = ?, instagram = ?, follow_gate = ?,
                    bg_kind = ?, bg_value = ?, bg_pattern = ?, bg_effect = ?
                where owner_sub = ?""")
                .params(name, slug, tagline, bio, accent, website, instagram, followGate,
                        look.kind(), look.value(), look.pattern(), look.effect(), ownerSub).update();
    }

    /** The event the page shows on its own; null shows the public events instead. */
    public void setMainEvent(String ownerSub, UUID eventId) {
        jdbc.sql("update profiles set main_event_id = ? where owner_sub = ?").params(eventId, ownerSub).update();
    }

    public void setAvatarKey(String ownerSub, String key) {
        jdbc.sql("update profiles set avatar_key = ? where owner_sub = ?").params(key, ownerSub).update();
    }

    /** Removes the profile and its follow buttons (account deletion). */
    @Transactional
    public void deleteAll(String ownerSub) {
        jdbc.sql("delete from social_links where owner_sub = ?").param(ownerSub).update();
        jdbc.sql("delete from profiles where owner_sub = ?").param(ownerSub).update();
    }

    public List<Link> links(String ownerSub) {
        return jdbc.sql("select id, label, url from social_links where owner_sub = ? order by position")
                .param(ownerSub).query(Link.class).list();
    }

    /** Replaces all follow buttons of one photographer, keeping the given order. */
    @Transactional
    public void replaceLinks(String ownerSub, List<Link> links) {
        jdbc.sql("delete from social_links where owner_sub = ?").param(ownerSub).update();
        int position = 0;
        for (Link l : links) {
            jdbc.sql("insert into social_links (id, owner_sub, label, url, position) values (?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), ownerSub, l.label(), l.url(), position++).update();
        }
    }
}
