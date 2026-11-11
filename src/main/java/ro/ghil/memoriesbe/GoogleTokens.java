package ro.ghil.memoriesbe;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** The checks a Google ID token must pass. Photographers also have to be on the allow-list; guests do not. */
final class GoogleTokens {
    /** Everything under this path is for guests: any verified Google account may call it. */
    static final String GUEST_PREFIX = "/api/v1/guest/";

    private GoogleTokens() {}

    static boolean isGuestPath(String path) {
        return path != null && path.startsWith(GUEST_PREFIX);
    }

    /** `allowedEmails` empty means any verified Google account (the guest setting). */
    static OAuth2TokenValidator<Jwt> validators(String clientId, List<String> allowedEmails) {
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        OAuth2TokenValidator<Jwt> email = jwt -> {
            String e = jwt.getClaimAsString("email");
            boolean ok = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) && e != null
                    && (allowedEmails.isEmpty() || allowedEmails.contains(e.toLowerCase()));
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Email not allowed", null));
        };
        // Google ID tokens use either issuer form.
        OAuth2TokenValidator<Jwt> issuer = jwt -> {
            String i = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
            return i.equals("https://accounts.google.com") || i.equals("accounts.google.com")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong issuer", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), issuer, audience, email);
    }
}
