package ro.ghil.memoriesbe.credits;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.config.Props;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives Lemon Squeezy payment events and turns them into credits. The request is only believed when its signature matches;
 * after that every event is stored for support and answered with 200 (Lemon Squeezy retries anything else), even when we ignore it.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class LemonSqueezyWebhookController {
    private static final Logger log = LoggerFactory.getLogger(LemonSqueezyWebhookController.class);

    private final Props.LemonSqueezy props;
    private final CreditService credits;
    private final CreditRepository ledger;
    private final ProfileRepository profiles;
    private final JsonMapper json;

    LemonSqueezyWebhookController(Props.LemonSqueezy props, CreditService credits, CreditRepository ledger, ProfileRepository profiles, JsonMapper json) {
        this.props = props;
        this.credits = credits;
        this.ledger = ledger;
        this.profiles = profiles;
        this.json = json;
    }

    @PostMapping("/lemonsqueezy")
    ResponseEntity<Void> receive(@RequestBody byte[] body,
                                 @RequestHeader(value = "X-Signature", required = false) String signature,
                                 @RequestHeader(value = "X-Event-Name", required = false) String eventHeader) {
        if (!LemonSqueezySignature.valid(body, signature, props.webhookSecret())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String event = eventHeader != null && !eventHeader.isBlank() ? eventHeader : text(root.path("meta").path("event_name"));
        String orderId = text(root.path("data").path("id"));
        String outcome = handle(event, root, orderId);
        ledger.insertPaymentEvent(orderId, event, outcome, new String(body, StandardCharsets.UTF_8));
        log.info("Lemon Squeezy {} order {}: {}", event, orderId, outcome);
        return ResponseEntity.ok().build();
    }

    private String handle(String event, JsonNode root, String orderId) {
        boolean purchase = "order_created".equals(event);
        boolean refund = "order_refunded".equals(event);
        if (!purchase && !refund) return "ignored";
        JsonNode attrs = root.path("data").path("attributes");
        if (!props.storeId().equals(text(attrs.path("store_id")))) return "wrong_store";
        JsonNode item = attrs.path("first_order_item");
        if (item.path("test_mode").asBoolean(false) && !props.allowTest()) return "test_ignored";
        if (purchase && !"paid".equals(text(attrs.path("status")))) return "unpaid";
        int count = props.creditsFor(text(item.path("variant_id")));
        if (count == 0) return "unknown_variant";
        String sub = text(root.path("meta").path("custom_data").path("owner_sub"));
        if (sub.isEmpty() && refund) {
            String found = ledger.ownerOfRef("ls-order:" + orderId); // a refund event may not carry our custom data
            sub = found == null ? "" : found;
        }
        if (sub.isEmpty() || profiles.bySub(sub).isEmpty()) return "unknown_account";
        if (purchase) return credits.applyPurchase(sub, count, orderId) ? "credited" : "duplicate";
        return credits.applyRefund(sub, count, orderId) ? "refunded" : "duplicate";
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "" : n.asString("");
    }
}
