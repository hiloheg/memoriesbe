package ro.ghil.memoriesbe.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Registers the typed settings and the S3 clients pointed at Cloudflare R2. */
@Configuration
@EnableConfigurationProperties({Props.SftpGo.class, Props.R2.class, Props.Ftp.class})
class AppConfig {

    private static StaticCredentialsProvider creds(Props.R2 r2) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(r2.accessKey(), r2.secret()));
    }

    @Bean
    S3Client s3Client(Props.R2 r2) {
        return S3Client.builder()
                .endpointOverride(URI.create(r2.endpoint()))
                .region(Region.of(r2.region()))
                .credentialsProvider(creds(r2))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean
    S3Presigner s3Presigner(Props.R2 r2) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(r2.endpoint()))
                .region(Region.of(r2.region()))
                .credentialsProvider(creds(r2))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
