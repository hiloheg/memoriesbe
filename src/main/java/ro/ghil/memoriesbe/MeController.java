package ro.ghil.memoriesbe;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.profile.ProfileViews;
import ro.ghil.memoriesbe.profile.ProfileViews.ProfileDto;

/** Returns the signed-in photographer with their page settings, picture and public page link. */
@RestController
class MeController {
    private final ProfileRepository profiles;
    private final ProfileViews views;

    MeController(ProfileRepository profiles, ProfileViews views) {
        this.profiles = profiles;
        this.views = views;
    }

    @GetMapping("/api/v1/me")
    ProfileDto me(@AuthenticationPrincipal Jwt jwt) {
        String email = String.valueOf(jwt.getClaimAsString("email"));
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = email;
        var profile = profiles.ensure(jwt.getSubject(), name, jwt.getClaimAsString("picture"));
        return views.dto(profile, email);
    }
}
