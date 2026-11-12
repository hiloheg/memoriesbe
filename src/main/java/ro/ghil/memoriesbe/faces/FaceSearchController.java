package ro.ghil.memoriesbe.faces;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventAccess;
import ro.ghil.memoriesbe.events.EventLifetime;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.faces.FaceSearchService.FaceSearchResult;

/** "Find my photos": a guest sends a selfie and gets back the photos of this event they appear in. No sign-in; the event's password still applies. */
@RestController
@RequestMapping("/api/v1/public")
class FaceSearchController {
    private static final long MAX_SELFIE_BYTES = 8L * 1024 * 1024;
    private static final String PASSWORD_HEADER = "X-Event-Password";

    private final EventRepository events;
    private final EventLifetime lifetime;
    private final EventAccess access;
    private final CreditService credits;
    private final FaceEngine engine;
    private final FaceSearchRateLimiter limiter;
    private final FaceSearchService search;

    FaceSearchController(EventRepository events, EventLifetime lifetime, EventAccess access, CreditService credits, FaceEngine engine,
                         FaceSearchRateLimiter limiter, FaceSearchService search) {
        this.events = events;
        this.lifetime = lifetime;
        this.access = access;
        this.credits = credits;
        this.engine = engine;
        this.limiter = limiter;
        this.search = search;
    }

    @PostMapping("/photographers/{slug}/events/{eventSlug}/face-search")
    FaceSearchResult find(@PathVariable String slug, @PathVariable String eventSlug,
                          @RequestParam("selfie") MultipartFile selfie,
                          @RequestParam(value = "consent", defaultValue = "false") boolean consent,
                          @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
                          HttpServletRequest request) throws IOException {
        Event event = events.eventBySlugs(slug.toLowerCase(), eventSlug.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        lifetime.requireOpen(event.id());
        access.require(event, password);
        if (!event.faceSearchEnabled() || !credits.unlocked(event.ownerSub())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!engine.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Face search is not available right now.");
        if (!consent) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please agree to the face scan first.");
        if (!limiter.tryAcquire(clientOf(request))) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many tries. Wait a minute.");
        if (selfie.getSize() > MAX_SELFIE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "That picture is too large.");
        return search.search(event, selfie.getBytes());
    }

    /** Behind the Cloudflare Tunnel the visitor's address is in this header; otherwise the connection's address is the best we have. */
    private static String clientOf(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        return cf != null && !cf.isBlank() ? cf.trim() : request.getRemoteAddr();
    }
}
