package ro.ghil.memoriesbe.events;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.storage.PhotoStorage;
import ro.ghil.memoriesbe.storage.ThumbLinks;

/** The event creator's view of the photos the cameras uploaded, and a way to remove the ones they do not want. */
@RestController
@RequestMapping("/api/v1/events/{id}")
class CameraPhotosController {
    /** `name` is the file's path inside the camera's folder; `thumb` is a small copy for the grid, relative to the API address. */
    record CameraPhoto(String cameraId, String camera, String name, String url, String thumb, Instant modified) {}

    private final EventRepository events;
    private final PhotoStorage storage;
    private final ThumbLinks thumbs;

    CameraPhotosController(EventRepository events, PhotoStorage storage, ThumbLinks thumbs) {
        this.events = events;
        this.storage = storage;
        this.thumbs = thumbs;
    }

    @GetMapping("/camera-photos")
    List<CameraPhoto> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        owned(jwt, id);
        return events.cameras(id).stream()
                .flatMap(c -> storage.entries(EventController.keyPrefix(id, c.id()), c.name()).stream()
                        .map(e -> new CameraPhoto(c.id().toString(), c.name(), e.name(), storage.signedUrl(e.key()),
                                thumbs.url(e.key(), e.modified().getEpochSecond(), 480), e.modified())))
                .sorted(Comparator.comparing(CameraPhoto::modified).reversed().thenComparing(CameraPhoto::name))
                .toList();
    }

    /** Deletes one photo (and its small copies). The name must be a photo that is really in that camera's folder; nothing else is ever deleted. */
    @DeleteMapping("/camera-photos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam UUID camera, @RequestParam String name) {
        owned(jwt, id);
        var cam = events.cameras(id).stream().filter(c -> c.id().equals(camera)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var entry = storage.entries(EventController.keyPrefix(id, cam.id()), cam.name()).stream()
                .filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            storage.delete(entry.key());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not delete the photo from storage. Try again.");
        }
    }

    private void owned(Jwt jwt, UUID id) {
        events.eventOf(id, jwt.getSubject()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
