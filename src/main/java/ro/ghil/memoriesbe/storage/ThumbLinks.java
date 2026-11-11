package ro.ghil.memoriesbe.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signed links to the small copies of photos. A link is only handed out after the gallery's own access check, and it
 * stops working after one to two hours, like the presigned links to the originals. The expiry is rounded to the hour,
 * so the same photo gets the same link for a whole hour and the browser can keep it in its cache.
 */
@Component
public class ThumbLinks {
    /** The sizes we make: the grid tile and the full-screen viewer. */
    public static final List<Integer> WIDTHS = List.of(480, 1600);
    private static final long HOUR = 3600;

    private final byte[] secret;
    private final Clock clock;

    /** Without THUMB_SECRET a random key is made at startup: links on pages that are already open stop working after a restart (those photos then load as originals) and are re-issued on the next load. */
    @Autowired
    public ThumbLinks(@Value("${app.thumbs.secret:}") String configured) {
        this(configured, Clock.systemUTC());
    }

    public ThumbLinks(String configured, Clock clock) {
        this.clock = clock;
        if (configured == null || configured.isBlank()) {
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
            LoggerFactory.getLogger(ThumbLinks.class).warn("THUMB_SECRET is not set: photo links use a random key that changes on every restart. Set it to 32 or more random characters.");
        } else {
            if (configured.length() < 32) throw new IllegalStateException("THUMB_SECRET must be at least 32 characters");
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Relative address (the frontend adds the API address) of the small copy of one photo. */
    public String url(String key, long version, int width) {
        long exp = (clock.millis() / 1000 / HOUR + 2) * HOUR;
        return "/api/v1/public/thumb?w=" + width
                + "&k=" + Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8))
                + "&v=" + version + "&e=" + exp + "&s=" + sign(width, key, version, exp);
    }

    public String sign(int width, String key, long version, long exp) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal((width + "|" + key + "|" + version + "|" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public boolean valid(int width, String key, long version, long exp, String signature) {
        long now = clock.millis() / 1000;
        if (!WIDTHS.contains(width) || exp <= now || exp > now + 3 * HOUR || signature == null) return false;
        return MessageDigest.isEqual(sign(width, key, version, exp).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
