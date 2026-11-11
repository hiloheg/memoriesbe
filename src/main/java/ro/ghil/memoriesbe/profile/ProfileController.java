package ro.ghil.memoriesbe.profile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.profile.ProfileRepository.Appearance;
import ro.ghil.memoriesbe.profile.ProfileRepository.Link;
import ro.ghil.memoriesbe.profile.ProfileRepository.Profile;
import ro.ghil.memoriesbe.profile.ProfileViews.ProfileDto;
import ro.ghil.memoriesbe.storage.PhotoStorage;

/** The signed-in photographer's own profile: page settings and picture. */
@RestController
@RequestMapping("/api/v1/profile")
class ProfileController {
    record LinkRequest(String label, String url) {}
    record UpdateRequest(String name, String slug, String tagline, String bio, String accent, String website,
                         String instagram, Boolean followGate, List<LinkRequest> links,
                         String bgKind, String bgValue, String bgPattern, String bgEffect, String bgFrame, String bgSocial) {}

    static final Set<String> ACCENTS = Set.of("#0e7c6b", "#2f5bd8", "#7d4fd6", "#c2410c", "#be185d", "#15803d");
    /** Background presets; the frontend (src/backgrounds.js) draws one look for each key. */
    static final Set<String> BG_COLORS = Set.of("snow", "mist", "sand", "blush", "mint", "sky", "lavender", "midnight", "forest", "charcoal");
    static final Set<String> BG_GRADIENTS = Set.of("dawn", "peach", "meadow", "ocean", "orchid", "twilight", "deepsea", "ember");
    static final Set<String> BG_PATTERNS = Set.of("none", "dots", "grid", "lines", "checks", "waves", "confetti");
    /** Frame themes that decorate the picture, header, tags and photos; the frontend (src/frames.js) draws each one. */
    static final Set<String> FRAMES = Set.of("none", "neon", "gold", "holo", "pixel", "paper");
    /** Follow button colors besides the page accent (empty): black, white and the accent colors. */
    static final Set<String> SOCIAL_COLORS = Set.of("", "#12161c", "#ffffff", "#0e7c6b", "#2f5bd8", "#7d4fd6", "#c2410c", "#be185d", "#15803d");
    static final Set<String> BG_EFFECTS = Set.of("none", "grain", "glow", "aurora", "vignette");
    private static final int MAX_LINKS = 5;
    private static final long MAX_PICTURE_BYTES = 2L * 1024 * 1024;

    private static final int MAX_LOGO_SIDE = 2000;

    private final ProfileRepository profiles;
    private final ProfileViews views;
    private final PhotoStorage storage;
    private final CreditService credits;

    ProfileController(ProfileRepository profiles, ProfileViews views, PhotoStorage storage, CreditService credits) {
        this.credits = credits;
        this.profiles = profiles;
        this.views = views;
        this.storage = storage;
    }

    @GetMapping
    ProfileDto get(@AuthenticationPrincipal Jwt jwt) {
        return views.dto(current(jwt), email(jwt));
    }

    @PutMapping
    ProfileDto update(@AuthenticationPrincipal Jwt jwt, @RequestBody UpdateRequest req) {
        Profile current = current(jwt);
        String name = text(req.name(), 1, 60, "Name must be 1 to 60 characters");
        String slug = req.slug() == null ? "" : req.slug().trim().toLowerCase();
        if (!Slugs.valid(slug)) {
            throw bad(Slugs.reserved(slug)
                    ? "That address is reserved. Choose another."
                    : "Address must be 3 to 30 characters: letters, numbers and single hyphens, not starting or ending with a hyphen.");
        }
        String accent = req.accent() == null || req.accent().isBlank() ? ProfileViews.DEFAULT_ACCENT : req.accent().toLowerCase();
        if (!ACCENTS.contains(accent)) throw bad("Choose one of the available colors");
        String tagline = optional(req.tagline(), 80, "Tagline can be up to 80 characters");
        String bio = optional(req.bio(), 400, "Bio can be up to 400 characters");
        String website = optional(req.website(), 200, "Website can be up to 200 characters");
        if (website != null && !website.matches("^https?://[^\\s]+$")) throw bad("Website must start with http:// or https://");
        String instagram = optional(req.instagram(), 31, "Instagram handle is too long");
        if (instagram != null) {
            instagram = instagram.replaceFirst("^@", "");
            if (!instagram.matches("^[A-Za-z0-9._]{1,30}$")) throw bad("Instagram handle can only use letters, numbers, dots and underscores");
        }
        List<Link> links = cleanLinks(req.links());
        Appearance look = cleanLook(req);
        // Until the first purchase the page keeps its default look; saving the look it already has is fine, changing it is not.
        if (!credits.unlocked(current.ownerSub()) && !(accent.equals(ProfileViews.accent(current)) && look.equals(lookOf(current)))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "locked: Buy any pack to change the look of your page");
        }
        boolean followGate = req.followGate() == null || req.followGate();
        try {
            profiles.update(current.ownerSub(), name, slug, tagline, bio, accent, website, instagram, followGate, look);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That address is already taken. Choose another.");
        }
        profiles.replaceLinks(current.ownerSub(), links);
        return views.dto(profiles.bySub(current.ownerSub()).orElseThrow(), email(jwt));
    }

    /** Accepts a JPEG, PNG or WebP up to 2 MB (the browser already crops and shrinks it). */
    @PostMapping(path = "/avatar", consumes = "multipart/form-data")
    ProfileDto uploadAvatar(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) throws IOException {
        Profile current = current(jwt);
        if (file.isEmpty() || file.getSize() > MAX_PICTURE_BYTES) throw bad("Picture must be smaller than 2 MB");
        byte[] bytes = file.getBytes();
        String ext = extensionOf(bytes);
        if (ext == null) throw bad("Picture must be a JPEG, PNG or WebP image");
        String key = "profiles/" + current.publicToken() + "/" + UUID.randomUUID() + "." + ext;
        storage.put(key, bytes, contentType(ext));
        profiles.setAvatarKey(current.ownerSub(), key);
        if (current.avatarKey() != null) storage.delete(current.avatarKey());
        return views.dto(profiles.bySub(current.ownerSub()).orElseThrow(), email(jwt));
    }

    /** Goes back to the Google photo. */
    @DeleteMapping("/avatar")
    ProfileDto removeAvatar(@AuthenticationPrincipal Jwt jwt) {
        Profile current = current(jwt);
        profiles.setAvatarKey(current.ownerSub(), null);
        if (current.avatarKey() != null) storage.delete(current.avatarKey());
        return views.dto(profiles.bySub(current.ownerSub()).orElseThrow(), email(jwt));
    }

    /** Accepts a JPEG, PNG or WebP logo up to 2 MB and 2000 px on each side. */
    @PostMapping(path = "/logo", consumes = "multipart/form-data")
    ProfileDto uploadLogo(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) throws IOException {
        Profile current = current(jwt);
        if (!credits.unlocked(current.ownerSub())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "locked: Buy any pack to add your logo");
        if (file.isEmpty() || file.getSize() > MAX_PICTURE_BYTES) throw bad("Logo must be smaller than 2 MB");
        byte[] bytes = file.getBytes();
        String ext = extensionOf(bytes);
        if (ext == null) throw bad("Logo must be a JPEG, PNG or WebP image");
        int[] size = ImageDimensions.of(bytes);
        if (size != null && (size[0] <= 0 || size[1] <= 0 || size[0] > MAX_LOGO_SIDE || size[1] > MAX_LOGO_SIDE)) throw bad("Logo must be between 1 and 2000 pixels wide and tall");
        String key = "profiles/" + current.publicToken() + "/logo/" + UUID.randomUUID() + "." + ext;
        storage.put(key, bytes, contentType(ext));
        profiles.setLogoKey(current.ownerSub(), key);
        if (current.logoKey() != null) storage.delete(current.logoKey());
        return views.dto(profiles.bySub(current.ownerSub()).orElseThrow(), email(jwt));
    }

    @DeleteMapping("/logo")
    ProfileDto removeLogo(@AuthenticationPrincipal Jwt jwt) {
        Profile current = current(jwt);
        profiles.setLogoKey(current.ownerSub(), null);
        if (current.logoKey() != null) storage.delete(current.logoKey());
        return views.dto(profiles.bySub(current.ownerSub()).orElseThrow(), email(jwt));
    }

    private Profile current(Jwt jwt) {
        String email = email(jwt);
        String name = jwt.getClaimAsString("name");
        return profiles.ensure(jwt.getSubject(), name == null || name.isBlank() ? email : name, jwt.getClaimAsString("picture"));
    }

    private static Appearance lookOf(Profile p) {
        return new Appearance(p.bgKind(), p.bgValue(), p.bgPattern(), p.bgEffect(), p.frame(), p.socialColor());
    }

    private static String email(Jwt jwt) {
        return String.valueOf(jwt.getClaimAsString("email"));
    }

    /** Background choices; anything not sent falls back to the plain default look. */
    private static Appearance cleanLook(UpdateRequest r) {
        String kind = r.bgKind() == null || r.bgKind().isBlank() ? "default" : r.bgKind();
        String value = r.bgValue() == null ? "" : r.bgValue();
        String pattern = r.bgPattern() == null || r.bgPattern().isBlank() ? "none" : r.bgPattern();
        String effect = r.bgEffect() == null || r.bgEffect().isBlank() ? "none" : r.bgEffect();
        String social = r.bgSocial() == null ? "" : r.bgSocial();
        String frame = r.bgFrame() == null || r.bgFrame().isBlank() ? "none" : r.bgFrame();
        switch (kind) {
            case "default" -> value = "";
            case "color" -> { if (!BG_COLORS.contains(value)) throw bad("Choose one of the available background colors"); }
            case "gradient" -> { if (!BG_GRADIENTS.contains(value)) throw bad("Choose one of the available gradients"); }
            default -> throw bad("Choose a background: default, color or gradient");
        }
        if (!BG_PATTERNS.contains(pattern)) throw bad("Choose one of the available patterns");
        if (!BG_EFFECTS.contains(effect)) throw bad("Choose one of the available effects");
        if (!FRAMES.contains(frame)) throw bad("Choose one of the available frames");
        if (!SOCIAL_COLORS.contains(social)) throw bad("Choose one of the available button colors");
        return new Appearance(kind, value, pattern, effect, frame, social);
    }

    /** Follow buttons: up to 5, each with a short label and an http(s) link. */
    private static List<Link> cleanLinks(List<LinkRequest> requested) {
        if (requested == null) return List.of();
        if (requested.size() > MAX_LINKS) throw bad("You can add up to " + MAX_LINKS + " follow buttons");
        return requested.stream().map(r -> {
            String label = r.label() == null ? "" : r.label().trim();
            String url = r.url() == null ? "" : r.url().trim();
            if (label.isEmpty() || label.length() > 30) throw bad("Each follow button needs a name of up to 30 characters");
            if (url.length() > 300 || !url.matches("^https?://[^\\s]+$")) throw bad("Each follow button needs a link starting with http:// or https://");
            return new Link(null, label, url);
        }).toList();
    }

    private static String extensionOf(byte[] b) {
        if (b.length > 12 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        return null;
    }

    private static String contentType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private static String text(String v, int min, int max, String message) {
        String t = v == null ? "" : v.trim();
        if (t.length() < min || t.length() > max) throw bad(message);
        return t;
    }

    private static String optional(String v, int max, String message) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        if (t.length() > max) throw bad(message);
        return t;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
