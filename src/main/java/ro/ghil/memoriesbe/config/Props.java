package ro.ghil.memoriesbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed views of the app.* settings (all values come from environment variables, see application.yml). */
public final class Props {
    private Props() {}

    @ConfigurationProperties("app.sftpgo")
    public record SftpGo(String url, String adminUser, String adminPassword) {}

    @ConfigurationProperties("app.r2")
    public record R2(String endpoint, String bucket, String region, String accessKey, String secret) {}

    /** Lemon Squeezy: webhook signing secret, the store, and the variant (product option) ids of the 1, 5 and 10 credit packs. */
    @ConfigurationProperties("app.lemonsqueezy")
    public record LemonSqueezy(String webhookSecret, String storeId, String storeSlug, String variant1, String variant5, String variant10,
                               String buyId1, String buyId5, String buyId10, boolean allowTest) {
        /** Credits sold by the variant, or 0 when it is not one of ours. */
        public int creditsFor(String variantId) {
            if (variantId == null || variantId.isBlank()) return 0;
            if (variantId.equals(variant1)) return 1;
            if (variantId.equals(variant5)) return 5;
            if (variantId.equals(variant10)) return 10;
            return 0;
        }

        /**
         * The id used in the buy link of a pack. Lemon Squeezy buy links take the long id from the variant's Share link, which is not the
         * numeric variant id the webhook reports. When no buy id is set the variant id is tried, which only works if Lemon Squeezy accepts it.
         */
        public String buyIdFor(int pack) {
            String id = switch (pack) {
                case 1 -> buyId1;
                case 5 -> buyId5;
                case 10 -> buyId10;
                default -> null;
            };
            return id == null || id.isBlank() ? variantFor(pack) : id;
        }

        /** The variant id for a pack of 1, 5 or 10 credits, or null. */
        public String variantFor(int pack) {
            String v = switch (pack) {
                case 1 -> variant1;
                case 5 -> variant5;
                case 10 -> variant10;
                default -> null;
            };
            return v == null || v.isBlank() ? null : v;
        }
    }

    @ConfigurationProperties("app.ftp")
    public record Ftp(String publicHost, int publicPort, int sftpPort) {}
}
