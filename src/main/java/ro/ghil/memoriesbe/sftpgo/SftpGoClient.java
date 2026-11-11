package ro.ghil.memoriesbe.sftpgo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.ghil.memoriesbe.config.Props;

/** Talks to the SFTPGo REST API: admin token, create and delete S3-backed (R2) camera users. */
@Component
public class SftpGoClient {
    private final RestClient http;
    private final Props.SftpGo sftpgo;
    private final Props.R2 r2;
    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    @Autowired
    public SftpGoClient(Props.SftpGo sftpgo, Props.R2 r2) {
        this(RestClient.builder(), sftpgo, r2);
    }

    public SftpGoClient(RestClient.Builder builder, Props.SftpGo sftpgo, Props.R2 r2) {
        this.http = builder.baseUrl(sftpgo.url()).build();
        this.sftpgo = sftpgo;
        this.r2 = r2;
    }

    /** Creates a user that can only list/upload under {@code keyPrefix} of the R2 bucket and cannot log in after {@code expiresAt}. */
    public void createUser(String username, String password, String keyPrefix, Instant expiresAt) {
        Map<String, Object> body = Map.of(
                "username", username,
                "password", password,
                "status", 1,
                "expiration_date", expiresAt.toEpochMilli(),
                "home_dir", "/var/lib/sftpgo/users/" + username,
                "permissions", Map.of("/", List.of("list", "upload", "create_dirs")),
                "filesystem", Map.of(
                        "provider", 1,
                        "s3config", Map.of(
                                "bucket", r2.bucket(),
                                "region", r2.region(),
                                "endpoint", r2.endpoint(),
                                "access_key", r2.accessKey(),
                                "access_secret", Map.of("status", "Plain", "payload", r2.secret()),
                                "key_prefix", keyPrefix,
                                "force_path_style", true)));
        http.post().uri("/api/v2/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .body(body).retrieve().toBodilessEntity();
    }

    public void deleteUser(String username) {
        http.delete().uri("/api/v2/users/{u}", username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve().toBodilessEntity();
    }

    private synchronized String adminToken() {
        if (token != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) return token;
        Map<?, ?> res = http.get().uri("/api/v2/token")
                .headers(h -> h.setBasicAuth(sftpgo.adminUser(), sftpgo.adminPassword()))
                .retrieve().body(Map.class);
        token = (String) res.get("access_token");
        tokenExpiry = Instant.parse((String) res.get("expires_at"));
        return token;
    }
}
