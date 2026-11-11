package ro.ghil.memoriesbe;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.authentication.AuthenticationManager;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Stateless API security: every request needs a valid Google ID token for this app's client ID and an allowed email. */
@Configuration
class SecurityConfig {

    private static final String CERTS = "https://www.googleapis.com/oauth2/v3/certs";

    /** Photographer routes need an allow-listed account; the guest area (/api/v1/guest/**) accepts any verified Google account. */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
            @Value("${app.google.client-id}") String clientId,
            JwtDecoder photographerDecoder) throws Exception {
        JwtDecoder guestDecoder = decoder(GoogleTokens.validators(clientId, List.of()));
        AuthenticationManager photographers = new JwtAuthenticationProvider(photographerDecoder)::authenticate;
        AuthenticationManager guests = new JwtAuthenticationProvider(guestDecoder)::authenticate;
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/public/**").permitAll()
                .requestMatchers("/api/v1/webhooks/**").permitAll() // Lemon Squeezy calls this; the signature is the permission
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.authenticationManagerResolver(
                    (HttpServletRequest req) -> GoogleTokens.isGuestPath(req.getRequestURI()) ? guests : photographers));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.google.client-id}") String clientId,
            @Value("${app.google.allowed-emails:}") String allowedEmails) {
        List<String> allowed = Arrays.stream(allowedEmails.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase).toList();
        return decoder(GoogleTokens.validators(clientId, allowed));
    }

    private static JwtDecoder decoder(OAuth2TokenValidator<Jwt> validator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(CERTS).build();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Event-Password"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
