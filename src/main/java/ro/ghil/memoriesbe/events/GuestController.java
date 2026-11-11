package ro.ghil.memoriesbe.events;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository.Event;

/** Guests (any signed-in Google account) adding photos to an event. Tokens here never work on photographer routes. */
@RestController
@RequestMapping("/api/v1/guest/photographers/{slug}/events/{eventSlug}")
class GuestController {
    record Uploaded(int remaining) {}

    private final EventRepository events;
    private final EventAccess access;
    private final GuestUploadService uploads;

    GuestController(EventRepository events, EventAccess access, GuestUploadService uploads) {
        this.events = events;
        this.access = access;
        this.uploads = uploads;
    }

    @GetMapping("/status")
    GuestUploadService.Status status(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug, @PathVariable String eventSlug,
                                     @RequestHeader(value = PublicEventController.PASSWORD_HEADER, required = false) String password) {
        return uploads.status(event(slug, eventSlug, password), jwt.getSubject());
    }

    @PostMapping("/photos")
    @ResponseStatus(HttpStatus.CREATED)
    Uploaded upload(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug, @PathVariable String eventSlug,
                    @RequestHeader(value = PublicEventController.PASSWORD_HEADER, required = false) String password,
                    @RequestParam("file") MultipartFile file) throws IOException {
        Event event = event(slug, eventSlug, password);
        return new Uploaded(uploads.upload(event, jwt.getSubject(), displayName(jwt), file.getBytes()));
    }

    private Event event(String slug, String eventSlug, String password) {
        Event event = events.eventBySlugs(slug.toLowerCase(), eventSlug.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.require(event, password);
        return event;
    }

    /** The guest's Google name, shown publicly as their tag. Without one it is "Guest": never part of the email address. */
    static String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        return name != null && !name.isBlank() ? name.trim() : "Guest";
    }
}
