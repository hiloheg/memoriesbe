package ro.ghil.memoriesbe.credits;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.config.Props;
import ro.ghil.memoriesbe.credits.CreditRepository.LedgerRow;

/** The signed-in photographer's credits and the link to buy a pack. */
@RestController
@RequestMapping("/api/v1/credits")
class CreditsController {
    record BalanceDto(int trial, int full, int total) {}
    record CreditsDto(BalanceDto balance, boolean unlocked, List<LedgerRow> history) {}
    record CheckoutDto(String url) {}

    private final CreditService credits;
    private final Props.LemonSqueezy props;

    CreditsController(CreditService credits, Props.LemonSqueezy props) {
        this.credits = credits;
        this.props = props;
    }

    @GetMapping
    CreditsDto get(@AuthenticationPrincipal Jwt jwt) {
        String sub = jwt.getSubject();
        var b = credits.balance(sub);
        return new CreditsDto(new BalanceDto(b.trial(), b.full(), b.total()), credits.unlocked(sub), credits.history(sub));
    }

    /** Lemon Squeezy checkout for a pack of 1, 5 or 10 credits, with the buyer's email filled in and our account id attached to the order. */
    @GetMapping("/checkout")
    CheckoutDto checkout(@AuthenticationPrincipal Jwt jwt, @RequestParam("pack") int pack) {
        if (pack != 1 && pack != 5 && pack != 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a pack of 1, 5 or 10 credits");
        }
        String variant = props.variantFor(pack);
        if (variant == null || props.storeSlug() == null || props.storeSlug().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payments are not set up yet");
        }
        String email = jwt.getClaimAsString("email");
        String url = "https://" + props.storeSlug() + ".lemonsqueezy.com/checkout/buy/" + variant
                + "?checkout[custom][owner_sub]=" + enc(jwt.getSubject())
                + (email == null || email.isBlank() ? "" : "&checkout[email]=" + enc(email));
        return new CheckoutDto(url);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
