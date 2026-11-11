package ro.ghil.memoriesbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed views of the app.* settings (all values come from environment variables, see application.yml). */
public final class Props {
    private Props() {}

    @ConfigurationProperties("app.sftpgo")
    public record SftpGo(String url, String adminUser, String adminPassword) {}

    @ConfigurationProperties("app.r2")
    public record R2(String endpoint, String bucket, String region, String accessKey, String secret) {}

    @ConfigurationProperties("app.ftp")
    public record Ftp(String publicHost, int publicPort, int sftpPort) {}
}
