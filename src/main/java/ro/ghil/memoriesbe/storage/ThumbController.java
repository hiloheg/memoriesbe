package ro.ghil.memoriesbe.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Serves the small copies. The signed address is the permission: it is only given out by a gallery that passed its access check. */
@RestController
@RequestMapping("/api/v1/public")
class ThumbController {
    private final ThumbLinks links;
    private final ThumbService thumbs;

    ThumbController(ThumbLinks links, ThumbService thumbs) {
        this.links = links;
        this.thumbs = thumbs;
    }

    @GetMapping("/thumb")
    ResponseEntity<byte[]> thumb(@RequestParam("w") int width, @RequestParam("k") String encodedKey, @RequestParam("v") long version,
                                 @RequestParam("e") long expires, @RequestParam("s") String signature) {
        String key;
        try {
            key = new String(Base64.getUrlDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!key.startsWith("events/") || !links.valid(width, key, version, expires, signature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var served = thumbs.serve(key, version, width);
        if (served == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (served.redirect() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(served.redirect()))
                    .header(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate().getHeaderValue()).build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofHours(1)).cachePublic().getHeaderValue())
                .body(served.bytes());
    }
}
