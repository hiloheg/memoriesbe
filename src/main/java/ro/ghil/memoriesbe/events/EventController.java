package ro.ghil.memoriesbe.events;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.config.Props;
import ro.ghil.memoriesbe.events.EventRepository.Camera;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.credits.Plan;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.events.EventRepository.Limits;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.profile.Slugs;
import ro.ghil.memoriesbe.sftpgo.CredentialGenerator;
import ro.ghil.memoriesbe.sftpgo.SecretBox;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/** Photographer API: manage own events and register cameras (each camera is an SFTPGo user on R2). */
@RestController
@RequestMapping("/api/v1/events")
class EventController {
    record CreateRequest(String name) {}
    /** `confirmed` must be true: the person has seen that the event uses one credit and when it closes. */
    record CreateEventRequest(String name, LocalDate date, String icon, String visibility, String password, Boolean confirmed) {}
    record VisibilityRequest(String visibility, String password) {}
    record IconRequest(String icon) {}
    record GuestUploadsRequest(boolean enabled, int limit) {}
    record DetailsRequest(String name, String icon) {}
    record EventDto(UUID id, String name, String slug, String shareUrl, LocalDate eventDate, String icon, String visibility, String accessPassword, boolean main,
                    OffsetDateTime createdAt, int cameraCount, Instant cameraAccessEndsAt, boolean cameraAccessExpired,
                    boolean guestUploadsEnabled, int guestUploadLimit,
                    String plan, int maxPhotos, int maxCameras, OffsetDateTime expiresAt, boolean expired, OffsetDateTime purgedAt) {}
    record CameraDto(UUID id, String name, String username, OffsetDateTime createdAt) {}
    record EventDetail(EventDto event, List<CameraDto> cameras) {}
    record CameraCredentials(UUID id, String name, String username, String password,
                             String ftpHost, int ftpPort, int sftpPort, Instant expiresAt) {}

    /** Camera logins stop working this many days after the event date (end of that day, UTC). */
    static final int CAMERA_ACCESS_DAYS = 3;
    /** Icons a photographer can pick for an event (the frontend draws one for each key). */
    static final Set<String> ICONS = Set.of("camera", "wedding", "heart", "baptism", "party", "birthday",
            "concert", "conference", "graduation", "sports", "corporate", "travel");
    /** Most photos one guest may add to one event. */
    static final int MAX_GUEST_PHOTOS = 50;
    /** Days the images stay after an event closes, so the photographer can still download them. */
    static final int PURGE_GRACE_DAYS = 14;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CreditService credits;
    private final EventLifetime lifetime;
    private final EventRepository repo;
    private final SftpGoClient sftpgo;
    private final PhotoStorage storage;
    private final ProfileRepository profiles;
    private final CredentialGenerator credentials;
    private final SecretBox secrets;
    private final EventAccess access;
    private final Props.Ftp ftp;
    private final String publicBaseUrl;

    EventController(CreditService credits, EventLifetime lifetime, EventRepository repo, SftpGoClient sftpgo, PhotoStorage storage, ProfileRepository profiles, CredentialGenerator credentials, SecretBox secrets, EventAccess access, Props.Ftp ftp,
                    @Value("${app.public-base-url}") String publicBaseUrl) {
        this.credits = credits;
        this.lifetime = lifetime;
        this.repo = repo;
        this.sftpgo = sftpgo;
        this.storage = storage;
        this.profiles = profiles;
        this.credentials = credentials;
        this.secrets = secrets;
        this.access = access;
        this.ftp = ftp;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EventDto create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateEventRequest req) {
        String name = requireName(req.name());
        LocalDate date = req.date();
        if (date == null || date.getYear() < 2000 || date.getYear() > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the date of the event");
        }
        UUID id = UUID.randomUUID();
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String icon = req.icon() == null || req.icon().isBlank() ? "camera" : requireIcon(req.icon());
        String visibility = req.visibility() == null || req.visibility().isBlank() ? "public" : req.visibility();
        boolean listed = parseVisibility(visibility);
        String passwordEnc = passwordFor(visibility, req.password());
        if (!Boolean.TRUE.equals(req.confirmed())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirm that creating this event uses 1 credit");
        }
        String owner = jwt.getSubject();
        // The credit is spent and the event stored in one transaction: if anything fails the credit comes back.
        return credits.runSpending(owner, id, plan -> {
            OffsetDateTime expires = OffsetDateTime.now().plusDays(plan.days());
            OffsetDateTime purgeAfter = expires.plusDays(PURGE_GRACE_DAYS);
            repo.insertEvent(id, owner, name, uniqueSlug(owner, name), date, icon, listed, token);
            if (passwordEnc != null) repo.setAccessPassword(id, passwordEnc);
            repo.applyPlan(id, plan, expires, purgeAfter);
            var limits = new Limits(id, plan.key(), plan.maxPhotos(), plan.maxBytes(), expires, purgeAfter, null);
            return dto(repo.eventOf(id, owner).orElseThrow(), 0, mainEventId(owner), limits);
        });
    }

    @GetMapping
    List<EventDto> list(@AuthenticationPrincipal Jwt jwt) {
        var counts = repo.cameraCounts(jwt.getSubject());
        var limits = repo.limitsOfOwner(jwt.getSubject());
        UUID mainId = mainEventId(jwt.getSubject());
        return repo.eventsOf(jwt.getSubject()).stream().map(e -> dto(e, counts.getOrDefault(e.id(), 0), mainId, limits.get(e.id()))).toList();
    }

    @GetMapping("/{id}")
    EventDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Event e = owned(jwt, id);
        var cams = repo.cameras(id).stream()
                .map(c -> new CameraDto(c.id(), c.name(), c.sftpUsername(), c.createdAt())).toList();
        return new EventDetail(dto(e, cams.size(), mainEventId(jwt.getSubject())), cams);
    }

    /** Renames the event and/or changes its icon. The address (slug) stays the same, so shared links and QR codes keep working. */
    @PutMapping("/{id}/details")
    EventDto changeDetails(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody DetailsRequest req) {
        Event current = owned(jwt, id);
        String name = req.name() == null ? current.name() : requireName(req.name());
        String icon = req.icon() == null ? current.icon() : requireIcon(req.icon());
        repo.updateDetails(id, name, icon);
        return dto(owned(jwt, id), repo.cameras(id).size(), mainEventId(jwt.getSubject()));
    }

    @PutMapping("/{id}/icon")
    EventDto changeIcon(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody IconRequest req) {
        owned(jwt, id);
        repo.updateIcon(id, requireIcon(req.icon()));
        return dto(owned(jwt, id), repo.cameras(id).size(), mainEventId(jwt.getSubject()));
    }

    /** Lets signed-in guests add photos to this event: switch on/off and how many each guest may add (0 to 50). */
    @PutMapping("/{id}/guest-uploads")
    EventDto changeGuestUploads(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody GuestUploadsRequest req) {
        owned(jwt, id);
        if (req.limit() < 0 || req.limit() > MAX_GUEST_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a limit between 0 and " + MAX_GUEST_PHOTOS + " photos per guest");
        }
        repo.setGuestUploads(id, req.enabled(), req.limit());
        return dto(owned(jwt, id), repo.cameras(id).size(), mainEventId(jwt.getSubject()));
    }

    /** Public events are listed on the photographer page; private ones are only reachable by their direct link. */
    @PutMapping("/{id}/visibility")
    EventDto changeVisibility(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody VisibilityRequest req) {
        owned(jwt, id);
        boolean listed = parseVisibility(req.visibility());
        String passwordEnc = passwordFor(req.visibility(), req.password());
        if (!"public".equals(req.visibility()) && id.equals(mainEventId(jwt.getSubject()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This is your main event, so it is always public. Remove it as the main event first.");
        }
        repo.updateListed(id, listed);
        repo.setAccessPassword(id, passwordEnc);
        return dto(owned(jwt, id), repo.cameras(id).size(), mainEventId(jwt.getSubject()));
    }

    /** The page then shows only this event. A main event is always public. */
    @PutMapping("/{id}/main")
    EventDto makeMain(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        owned(jwt, id);
        repo.updateListed(id, true);
        repo.setAccessPassword(id, null); // a main event is public: no password
        profiles.setMainEvent(jwt.getSubject(), id);
        return dto(owned(jwt, id), repo.cameras(id).size(), id);
    }

    @DeleteMapping("/{id}/main")
    EventDto removeMain(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        owned(jwt, id);
        UUID mainId = mainEventId(jwt.getSubject());
        if (id.equals(mainId)) {
            profiles.setMainEvent(jwt.getSubject(), null);
            mainId = null;
        }
        return dto(owned(jwt, id), repo.cameras(id).size(), mainId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        owned(jwt, id);
        // Stop uploads first, then wipe the photos, then forget the event. A failure leaves the event so it can be retried.
        for (Camera c : repo.cameras(id)) removeSftpUser(c.sftpUsername());
        deletePhotos("events/" + id + "/");
        repo.deleteEvent(id);
    }

    /** Returns the FTP credentials of the new camera; the password is only ever shown in this response. */
    @PostMapping("/{id}/cameras")
    @ResponseStatus(HttpStatus.CREATED)
    CameraCredentials addCamera(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody CreateRequest req) {
        Event event = owned(jwt, id);
        lifetime.requireOpen(id);
        lifetime.requireCameraRoom(id, repo.cameras(id).size());
        String name = requireName(req.name());
        Instant expiresAt = accessEnds(event.eventDate());
        if (!Instant.now().isBefore(expiresAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Camera logins for this event have expired. Create a new event for a new date.");
        }
        UUID cameraId = UUID.randomUUID();
        String username = credentials.username();
        String password = credentials.password();
        repo.insertCamera(cameraId, id, name, username, secrets.encrypt(password));
        try {
            var plan = planOf(id);
            sftpgo.createUser(username, password, keyPrefix(id, cameraId), expiresAt, plan.maxPhotos(), plan.maxBytes());
        } catch (RestClientException ex) {
            repo.deleteCamera(cameraId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not create the camera user in SFTPGo");
        }
        return new CameraCredentials(cameraId, name, username, password, ftp.publicHost(), ftp.publicPort(), ftp.sftpPort(), expiresAt);
    }

    /** The camera's login again. `password` is null for cameras made before passwords were kept: reset it instead. */
    @GetMapping("/{id}/cameras/{cameraId}/credentials")
    CameraCredentials credentials(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID cameraId) {
        Event event = owned(jwt, id);
        Camera c = repo.camera(cameraId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String password = c.passwordEnc() == null ? null : secrets.decrypt(c.passwordEnc());
        return new CameraCredentials(c.id(), c.name(), c.sftpUsername(), password, ftp.publicHost(), ftp.publicPort(), ftp.sftpPort(), accessEnds(event.eventDate()));
    }

    /** Gives the camera a new password (the old one stops working) and returns the new login. */
    @PostMapping("/{id}/cameras/{cameraId}/credentials/reset")
    CameraCredentials resetCredentials(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID cameraId) {
        Event event = owned(jwt, id);
        lifetime.requireOpen(id);
        Camera c = repo.camera(cameraId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Instant expiresAt = accessEnds(event.eventDate());
        if (!Instant.now().isBefore(expiresAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Camera logins for this event have expired.");
        }
        String password = credentials.password();
        removeSftpUser(c.sftpUsername());
        try {
            var plan = planOf(id);
            sftpgo.createUser(c.sftpUsername(), password, keyPrefix(id, cameraId), expiresAt, plan.maxPhotos(), plan.maxBytes());
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reset the login in SFTPGo");
        }
        repo.setCameraPassword(cameraId, secrets.encrypt(password));
        return new CameraCredentials(c.id(), c.name(), c.sftpUsername(), password, ftp.publicHost(), ftp.publicPort(), ftp.sftpPort(), expiresAt);
    }

    @DeleteMapping("/{id}/cameras/{cameraId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCamera(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID cameraId) {
        owned(jwt, id);
        Camera c = repo.camera(cameraId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        removeSftpUser(c.sftpUsername());
        deletePhotos(keyPrefix(id, cameraId));
        repo.deleteCamera(cameraId);
    }

    /** Moment camera logins stop working: the end of the third day after the event date, UTC. */
    static Instant accessEnds(LocalDate eventDate) {
        return eventDate.plusDays(CAMERA_ACCESS_DAYS + 1L).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** R2 key prefix a camera uploads into (also used by the public gallery to list its photos). */
    static String keyPrefix(UUID eventId, UUID cameraId) {
        return "events/" + eventId + "/" + cameraId + "/";
    }

    /** R2 key prefix the photos of one guest are written to. */
    static String guestPrefix(UUID eventId, UUID guestId) {
        return "events/" + eventId + "/guests/" + guestId + "/";
    }

    private void deletePhotos(String prefix) {
        try {
            storage.deleteAll(prefix);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not delete the photos from storage. Try again.");
        }
    }

    private void removeSftpUser(String username) {
        try {
            sftpgo.deleteUser(username);
        } catch (HttpClientErrorException.NotFound ignored) {
            // already gone
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not remove the camera user in SFTPGo");
        }
    }

    /** The plan the event was created with; an event without one (made before credits) counts as a full event. */
    private Plan planOf(UUID eventId) {
        return lifetime.limits(eventId).map(l -> Plan.of(l.plan())).orElse(Plan.FULL);
    }

    private Event owned(Jwt jwt, UUID id) {
        return repo.eventOf(id, jwt.getSubject()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String uniqueSlug(String ownerSub, String name) {
        String base = Slugs.slugify(name);
        String candidate = base;
        for (int n = 2; repo.eventSlugTaken(ownerSub, candidate); n++) {
            String suffix = "-" + n;
            candidate = base.substring(0, Math.min(base.length(), 30 - suffix.length())) + suffix;
        }
        return candidate;
    }

    /** Readable link (photographer/event) when the owner has an address, otherwise the older token link. */
    private String shareUrl(Event e) {
        return profiles.bySub(e.ownerSub()).map(p -> p.slug())
                .map(slug -> publicBaseUrl + "/" + slug + "/" + e.slug())
                .orElse(publicBaseUrl + "/e/" + e.publicToken());
    }

    private UUID mainEventId(String ownerSub) {
        return profiles.bySub(ownerSub).map(p -> p.mainEventId()).orElse(null);
    }

    private static boolean parseVisibility(String visibility) {
        if ("public".equals(visibility) || "protected".equals(visibility)) return true; // both are listed on the page
        if ("private".equals(visibility)) return false;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose public, private or protected");
    }

    /** Encrypted password for a protected event (4 to 60 characters); null for the other kinds. */
    private String passwordFor(String visibility, String password) {
        if (!"protected".equals(visibility)) return null;
        if (password == null || password.length() < 4 || password.length() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a password of 4 to 60 characters");
        }
        return access.encrypt(password);
    }

    private EventDto dto(Event e, int cameraCount, UUID mainId) {
        return dto(e, cameraCount, mainId, repo.limitsOf(e.id()).orElse(null));
    }

    /** `limits` is null for an event without a plan (it is then shown as open, with no limits). */
    private EventDto dto(Event e, int cameraCount, UUID mainId, Limits limits) {
        Instant ends = accessEnds(e.eventDate());
        Plan plan = limits == null ? null : Plan.of(limits.plan());
        boolean expired = limits != null && (limits.purgedAt() != null || limits.expiresAt().isBefore(OffsetDateTime.now()));
        return new EventDto(e.id(), e.name(), e.slug(), shareUrl(e), e.eventDate(), e.icon(),
                !e.listed() ? "private" : EventAccess.isProtected(e) ? "protected" : "public", access.passwordOf(e), e.id().equals(mainId), e.createdAt(), cameraCount,
                ends, !Instant.now().isBefore(ends), e.guestUploadsEnabled(), e.guestUploadLimit(),
                limits == null ? null : limits.plan(), limits == null ? 0 : limits.maxPhotos(), plan == null ? Integer.MAX_VALUE : plan.maxCameras(),
                limits == null ? null : limits.expiresAt(), expired, limits == null ? null : limits.purgedAt());
    }

    private static String requireIcon(String icon) {
        if (icon == null || !ICONS.contains(icon)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose one of the available icons");
        }
        return icon;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required (max 100 characters)");
        }
        return name.trim();
    }
}
