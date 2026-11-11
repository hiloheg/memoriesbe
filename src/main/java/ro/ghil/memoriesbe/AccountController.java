package ro.ghil.memoriesbe;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/** GDPR rights of a photographer: a copy of their data (access, portability) and erasure of the whole account. */
@RestController
class AccountController {
    record CameraExport(String name, String username, OffsetDateTime createdAt, int photos) {}
    record EventExport(String name, String slug, LocalDate eventDate, String icon, String visibility, OffsetDateTime createdAt, List<CameraExport> cameras) {}
    record LinkExport(String label, String url) {}
    record ProfileExport(String name, String slug, String tagline, String bio, String website, String accent, boolean followGate, boolean hasUploadedPicture, boolean hasUploadedLogo, List<LinkExport> links) {}
    record Export(String email, String googleAccountId, ProfileExport profile, List<EventExport> events, String note) {}

    private final EventRepository events;
    private final ProfileRepository profiles;
    private final SftpGoClient sftpgo;
    private final PhotoStorage storage;

    AccountController(EventRepository events, ProfileRepository profiles, SftpGoClient sftpgo, PhotoStorage storage) {
        this.events = events;
        this.profiles = profiles;
        this.sftpgo = sftpgo;
        this.storage = storage;
    }

    /** Everything we keep about the account, as JSON. Camera passwords and event passwords are not included. */
    @GetMapping("/api/v1/me/export")
    Export export(@AuthenticationPrincipal Jwt jwt) {
        String sub = jwt.getSubject();
        var profile = profiles.bySub(sub).orElse(null);
        ProfileExport pe = profile == null ? null : new ProfileExport(profile.name(), profile.slug(), profile.tagline(), profile.bio(),
                profile.website(), profile.accent(), profile.followGate(), profile.avatarKey() != null, profile.logoKey() != null,
                profiles.links(sub).stream().map(l -> new LinkExport(l.label(), l.url())).toList());
        List<EventExport> list = new ArrayList<>();
        for (var e : events.eventsOf(sub)) {
            List<CameraExport> cams = new ArrayList<>();
            for (var c : events.cameras(e.id())) {
                int photos = storage.entries("events/" + e.id() + "/" + c.id() + "/", c.name()).size();
                cams.add(new CameraExport(c.name(), c.sftpUsername(), c.createdAt(), photos));
            }
            String visibility = !e.listed() ? "private" : e.accessPasswordEnc() != null ? "protected" : "public";
            list.add(new EventExport(e.name(), e.slug(), e.eventDate(), e.icon(), visibility, e.createdAt(), cams));
        }
        return new Export(jwt.getClaimAsString("email"), sub, pe, list,
                "Photos themselves are not included here; open each event on your page to download them.");
    }

    /**
     * Deletes the account for good: camera logins, every photo, the events, the picture and logo, the follow buttons and the profile.
     * Safe to repeat if a step fails: what is already gone is skipped.
     */
    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        String sub = jwt.getSubject();
        try {
            for (var e : events.eventsOf(sub)) {
                for (var c : events.cameras(e.id())) {
                    try {
                        sftpgo.deleteUser(c.sftpUsername());
                    } catch (HttpClientErrorException.NotFound ignored) {
                        // already gone
                    }
                }
                storage.deleteAll("events/" + e.id() + "/");
                events.deleteEvent(e.id());
            }
            var profile = profiles.bySub(sub);
            if (profile.isPresent() && profile.get().avatarKey() != null) storage.delete(profile.get().avatarKey());
            if (profile.isPresent() && profile.get().logoKey() != null) storage.delete(profile.get().logoKey());
            profiles.deleteAll(sub);
        } catch (RestClientException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not delete everything. Nothing is lost: try again in a moment.");
        }
    }
}
