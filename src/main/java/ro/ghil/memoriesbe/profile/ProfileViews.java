package ro.ghil.memoriesbe.profile;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.ghil.memoriesbe.profile.ProfileRepository.Link;
import ro.ghil.memoriesbe.profile.ProfileRepository.Profile;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/** Turns a stored profile into what the frontend needs: picture URL, page link, follow buttons and the accent default. */
@Component
public class ProfileViews {
    public static final String DEFAULT_ACCENT = "#0e7c6b";

    public record ProfileDto(String email, String name, String slug, String tagline, String bio, String accent,
                             String website, String instagram, String picture, boolean customPicture, String shareUrl,
                             boolean followGate, List<Link> links, Background background, String logo) {}

    /** What the page background looks like: kind, preset key, pattern key and effect key. */
    public record Background(String kind, String value, String pattern, String effect, String frame, String social) {}

    private final PhotoStorage storage;
    private final ProfileRepository profiles;
    private final String publicBaseUrl;

    ProfileViews(PhotoStorage storage, ProfileRepository profiles, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.storage = storage;
        this.profiles = profiles;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public ProfileDto dto(Profile p, String email) {
        return new ProfileDto(email, p.name(), p.slug(), nz(p.tagline()), nz(p.bio()), accent(p),
                nz(p.website()), nz(p.instagram()), pictureUrl(p), p.avatarKey() != null, pageUrl(p.slug()),
                p.followGate(), profiles.links(p.ownerSub()), background(p), logoUrl(p));
    }

    public String pageUrl(String slug) {
        return publicBaseUrl + "/" + slug;
    }

    /** The uploaded picture if there is one (short-lived signed link), otherwise the Google photo. */
    public String pictureUrl(Profile p) {
        if (p.avatarKey() != null) return storage.signedUrl(p.avatarKey());
        return p.googlePicture() == null ? "" : p.googlePicture();
    }

    /** Short-lived signed link to the photographer's logo, or empty when there is none. */
    public String logoUrl(Profile p) {
        return p.logoKey() == null ? "" : storage.signedUrl(p.logoKey());
    }

    public static Background background(Profile p) {
        return new Background(p.bgKind(), p.bgValue(), p.bgPattern(), p.bgEffect(), p.frame(), p.socialColor());
    }

    public static String accent(Profile p) {
        return p.accent() == null || p.accent().isBlank() ? DEFAULT_ACCENT : p.accent();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
