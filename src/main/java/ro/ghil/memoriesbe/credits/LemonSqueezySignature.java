package ro.ghil.memoriesbe.credits;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Checks the X-Signature of a Lemon Squeezy webhook: the HMAC-SHA256 hex digest of the raw request body, made with the signing secret. */
public final class LemonSqueezySignature {
    private LemonSqueezySignature() {}

    public static boolean valid(byte[] body, String header, String secret) {
        if (body == null || header == null || header.isBlank() || secret == null || secret.isBlank()) return false;
        byte[] expected = hex(body, secret).getBytes(StandardCharsets.UTF_8);
        byte[] given = header.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual compares in constant time; it already returns false for different lengths.
        return MessageDigest.isEqual(expected, given);
    }

    static String hex(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var out = new StringBuilder();
            for (byte b : mac.doFinal(body)) out.append(String.format("%02x", b));
            return out.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
