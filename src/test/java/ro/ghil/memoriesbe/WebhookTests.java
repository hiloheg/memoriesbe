package ro.ghil.memoriesbe;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ro.ghil.memoriesbe.credits.CreditRepository;
import ro.ghil.memoriesbe.credits.CreditRepository.Balance;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.events.EventRepository;
import ro.ghil.memoriesbe.profile.ProfileRepository;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;
import ro.ghil.memoriesbe.storage.PhotoStorage;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"GOOGLE_CLIENT_ID=test-client", "spring.flyway.enabled=false",
		"LEMONSQUEEZY_WEBHOOK_SECRET=test-secret", "LEMONSQUEEZY_STORE_ID=42", "LEMONSQUEEZY_STORE_SLUG=memories",
		"LEMONSQUEEZY_VARIANT_1=11", "LEMONSQUEEZY_VARIANT_5=55", "LEMONSQUEEZY_VARIANT_10=1010", "LEMONSQUEEZY_ALLOW_TEST=false"})
class WebhookTests {

	@Autowired MockMvc mvc;
	@MockitoBean EventRepository repo;
	@MockitoBean ProfileRepository profiles;
	@MockitoBean SftpGoClient sftpgo;
	@MockitoBean PhotoStorage storage;
	@MockitoBean ro.ghil.memoriesbe.events.GuestRepository guestRepo;
	@MockitoBean ro.ghil.memoriesbe.events.GuestUploadService guestUploads;
	@MockitoBean CreditService credits;
	@MockitoBean CreditRepository creditRepo;

	private static String sign(String body) throws Exception {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		var hex = new StringBuilder();
		for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
		return hex.toString();
	}

	private static String order(String event, String id, String status, int storeId, int variant, boolean test, String ownerSub) {
		String custom = ownerSub == null ? "" : ",\"custom_data\":{\"owner_sub\":\"" + ownerSub + "\"}";
		return "{\"meta\":{\"event_name\":\"" + event + "\"" + custom + "},\"data\":{\"type\":\"orders\",\"id\":\"" + id + "\",\"attributes\":{"
				+ "\"store_id\":" + storeId + ",\"status\":\"" + status + "\",\"user_email\":\"a@b.c\","
				+ "\"first_order_item\":{\"variant_id\":" + variant + ",\"test_mode\":" + test + "}}}}";
	}

	private ResultActions send(String event, String body, String signature) throws Exception {
		return mvc.perform(post("/api/v1/webhooks/lemonsqueezy").contentType("application/json")
				.header("X-Event-Name", event).header("X-Signature", signature).content(body));
	}

	private ResultActions signed(String event, String body) throws Exception {
		return send(event, body, sign(body));
	}

	private void accountExists() {
		when(profiles.bySub("s1")).thenReturn(Optional.of(new ProfileRepository.Profile("s1", "Ana", "ana", null, null, null, null, null, null, null, null, "tok", true,
				"default", "", "none", "none", "none", "", null)));
	}

	@Test
	void badSignatureIs401AndStoresNothing() throws Exception {
		String body = order("order_created", "9001", "paid", 42, 55, false, "s1");
		send("order_created", body, "0".repeat(64)).andExpect(status().isUnauthorized());
		verify(creditRepo, never()).insertPaymentEvent(any(), any(), any(), any());
		verify(credits, never()).applyPurchase(any(), anyInt(), any());
	}

	@Test
	void missingSignatureIs401() throws Exception {
		mvc.perform(post("/api/v1/webhooks/lemonsqueezy").contentType("application/json").header("X-Event-Name", "order_created")
				.content(order("order_created", "9001", "paid", 42, 55, false, "s1"))).andExpect(status().isUnauthorized());
	}

	@Test
	void wrongLengthSignatureIs401NotAnException() throws Exception {
		send("order_created", order("order_created", "9001", "paid", 42, 55, false, "s1"), "abc").andExpect(status().isUnauthorized());
	}

	@Test
	void paidOrderAddsCredits() throws Exception {
		accountExists();
		signed("order_created", order("order_created", "9001", "paid", 42, 55, false, "s1")).andExpect(status().isOk());
		verify(credits).applyPurchase("s1", 5, "9001");
	}

	@Test
	void eachPackMapsToItsCredits() throws Exception {
		accountExists();
		signed("order_created", order("order_created", "1", "paid", 42, 11, false, "s1")).andExpect(status().isOk());
		signed("order_created", order("order_created", "2", "paid", 42, 1010, false, "s1")).andExpect(status().isOk());
		verify(credits).applyPurchase("s1", 1, "1");
		verify(credits).applyPurchase("s1", 10, "2");
	}

	@Test
	void duplicateDeliveryIsHarmless() throws Exception {
		accountExists();
		when(credits.applyPurchase("s1", 5, "9001")).thenReturn(true, false);
		String body = order("order_created", "9001", "paid", 42, 55, false, "s1");
		signed("order_created", body).andExpect(status().isOk());
		signed("order_created", body).andExpect(status().isOk());
		verify(creditRepo).insertPaymentEvent(eq("9001"), eq("order_created"), eq("credited"), anyString());
		verify(creditRepo).insertPaymentEvent(eq("9001"), eq("order_created"), eq("duplicate"), anyString());
	}

	@Test
	void unknownVariantIsStoredNotCredited() throws Exception {
		accountExists();
		signed("order_created", order("order_created", "9001", "paid", 42, 999, false, "s1")).andExpect(status().isOk());
		verify(creditRepo).insertPaymentEvent(eq("9001"), eq("order_created"), eq("unknown_variant"), anyString());
		verify(credits, never()).applyPurchase(any(), anyInt(), any());
	}

	@Test
	void unknownAccountIsStoredNotCredited() throws Exception {
		when(profiles.bySub("ghost")).thenReturn(Optional.empty());
		signed("order_created", order("order_created", "9001", "paid", 42, 55, false, "ghost")).andExpect(status().isOk());
		verify(creditRepo).insertPaymentEvent(eq("9001"), eq("order_created"), eq("unknown_account"), anyString());
		verify(credits, never()).applyPurchase(any(), anyInt(), any());
	}

	@Test
	void anOrderWithoutAnOwnerIsStoredNotCredited() throws Exception {
		signed("order_created", order("order_created", "9001", "paid", 42, 55, false, null)).andExpect(status().isOk());
		verify(creditRepo).insertPaymentEvent(eq("9001"), eq("order_created"), eq("unknown_account"), anyString());
		verify(credits, never()).applyPurchase(any(), anyInt(), any());
	}

	@Test
	void unpaidOrWrongStoreIsIgnored() throws Exception {
		accountExists();
		signed("order_created", order("order_created", "1", "pending", 42, 55, false, "s1")).andExpect(status().isOk());
		signed("order_created", order("order_created", "2", "paid", 43, 55, false, "s1")).andExpect(status().isOk());
		verify(credits, never()).applyPurchase(any(), anyInt(), any());
		verify(creditRepo).insertPaymentEvent(eq("1"), eq("order_created"), eq("unpaid"), anyString());
		verify(creditRepo).insertPaymentEvent(eq("2"), eq("order_created"), eq("wrong_store"), anyString());
	}

	@Test
	void testModeOrderIsIgnoredInProduction() throws Exception {
		accountExists();
		signed("order_created", order("order_created", "9001", "paid", 42, 55, true, "s1")).andExpect(status().isOk());
		verify(credits, never()).applyPurchase(any(), anyInt(), any());
		verify(creditRepo).insertPaymentEvent(eq("9001"), eq("order_created"), eq("test_ignored"), anyString());
	}

	@Test
	void refundTakesCreditsBack() throws Exception {
		accountExists();
		signed("order_refunded", order("order_refunded", "9001", "refunded", 42, 55, false, "s1")).andExpect(status().isOk());
		verify(credits).applyRefund("s1", 5, "9001");
	}

	@Test
	void refundFindsTheOwnerFromTheOriginalOrder() throws Exception {
		accountExists();
		when(creditRepo.ownerOfRef("ls-order:9001")).thenReturn("s1");
		signed("order_refunded", order("order_refunded", "9001", "refunded", 42, 55, false, null)).andExpect(status().isOk());
		verify(credits).applyRefund("s1", 5, "9001");
	}

	@Test
	void refundStillTakesCreditsBackWhenTheProfileWasDeleted() throws Exception {
		// The purchase row is kept after the account is deleted, so its refund must be kept too.
		when(profiles.bySub("s1")).thenReturn(Optional.empty());
		when(creditRepo.ownerOfRef("ls-order:9001")).thenReturn("s1");
		signed("order_refunded", order("order_refunded", "9001", "refunded", 42, 55, false, null)).andExpect(status().isOk());
		verify(credits).applyRefund("s1", 5, "9001");
	}

	@Test
	void otherEventsAreAcceptedAndIgnored() throws Exception {
		signed("subscription_created", "{\"meta\":{\"event_name\":\"subscription_created\"},\"data\":{\"id\":\"5\"}}").andExpect(status().isOk());
		verify(creditRepo).insertPaymentEvent(eq("5"), eq("subscription_created"), eq("ignored"), anyString());
	}

	@Test
	void checkoutLinkHasVariantEmailAndOwner() throws Exception {
		var result = mvc.perform(get("/api/v1/credits/checkout").param("pack", "5").with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))))
				.andExpect(status().isOk()).andReturn();
		String url = URLDecoder.decode(jsonUrl(result.getResponse().getContentAsString()), StandardCharsets.UTF_8);
		org.junit.jupiter.api.Assertions.assertTrue(url.startsWith("https://memories.lemonsqueezy.com/checkout/buy/55?"), url);
		org.junit.jupiter.api.Assertions.assertTrue(url.contains("checkout[email]=a@b.c"), url);
		org.junit.jupiter.api.Assertions.assertTrue(url.contains("checkout[custom][owner_sub]=s1"), url);
		mvc.perform(get("/api/v1/credits/checkout").param("pack", "7").with(jwt().jwt(j -> j.subject("s1").claim("email", "a@b.c"))))
				.andExpect(status().isBadRequest());
	}

	private static String jsonUrl(String json) {
		var m = java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
		org.junit.jupiter.api.Assertions.assertTrue(m.find(), json);
		return m.group(1).replace("\\u0026", "&");
	}

	@Test
	void creditsEndpointReturnsBalanceAndUnlocked() throws Exception {
		when(credits.balance("s1")).thenReturn(new Balance(1, 2));
		when(credits.unlocked("s1")).thenReturn(true);
		when(credits.history("s1")).thenReturn(List.of());
		mvc.perform(get("/api/v1/credits").with(jwt().jwt(j -> j.subject("s1"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance.trial").value(1))
				.andExpect(jsonPath("$.balance.full").value(2))
				.andExpect(jsonPath("$.balance.total").value(3))
				.andExpect(jsonPath("$.unlocked").value(true));
	}

	@Test
	void creditsNeedSignIn() throws Exception {
		mvc.perform(get("/api/v1/credits")).andExpect(status().isUnauthorized());
	}
}
