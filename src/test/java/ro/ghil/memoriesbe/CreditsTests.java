package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.credits.CreditRepository;
import ro.ghil.memoriesbe.credits.CreditRepository.Balance;
import ro.ghil.memoriesbe.credits.CreditService;
import ro.ghil.memoriesbe.credits.Plan;

class CreditsTests {

	private static final long GIB = 1024L * 1024 * 1024;
	private static final Instant LAUNCH = Instant.parse("2026-12-01T00:00:00Z");

	private final CreditRepository repo = mock(CreditRepository.class);

	private CreditService service(Instant now) {
		return new CreditService(repo, TransactionOperations.withoutTransaction(), Clock.fixed(now, ZoneOffset.UTC));
	}

	@Test
	void planLimitsMatchTheOwnersNumbers() {
		assertEquals(300, Plan.TRIAL.maxPhotos());
		assertEquals(4 * GIB, Plan.TRIAL.maxBytes());
		assertEquals(30, Plan.TRIAL.days());
		assertEquals(1, Plan.TRIAL.maxCameras());
		assertEquals(3000, Plan.FULL.maxPhotos());
		assertEquals(500 * GIB, Plan.FULL.maxBytes());
		assertEquals(90, Plan.FULL.days());
		assertEquals(Integer.MAX_VALUE, Plan.FULL.maxCameras());
		assertEquals("full", Plan.FULL.key());
		assertEquals(Plan.FULL, Plan.of("full"));
		assertEquals(Plan.TRIAL, Plan.of("trial"));
		assertThrows(IllegalArgumentException.class, () -> Plan.of("x"));
		assertEquals(3, new Balance(1, 2).total());
	}

	@Test
	void welcomeCreditIsInsertedOncePerAccount() {
		service(LAUNCH).grantWelcome("s1");
		verify(repo).insert("s1", 1, "trial", "welcome", "welcome:s1", null);
	}

	@Test
	void spendsAPurchasedCreditBeforeTheFreeOne() {
		when(repo.balance("s1")).thenReturn(new Balance(1, 2));
		UUID event = UUID.randomUUID();
		AtomicReference<Plan> seen = new AtomicReference<>();
		String result = service(LAUNCH).runSpending("s1", event, plan -> {
			seen.set(plan);
			return "ok";
		});
		assertEquals("ok", result);
		assertEquals(Plan.FULL, seen.get());
		verify(repo).insert("s1", -1, "full", "event", null, event);
	}

	@Test
	void spendsTheFreeCreditWhenNothingElseIsLeft() {
		when(repo.balance("s1")).thenReturn(new Balance(1, 0));
		UUID event = UUID.randomUUID();
		AtomicReference<Plan> seen = new AtomicReference<>();
		service(LAUNCH).runSpending("s1", event, plan -> {
			seen.set(plan);
			return null;
		});
		assertEquals(Plan.TRIAL, seen.get());
		verify(repo).insert("s1", -1, "trial", "event", null, event);
	}

	@Test
	void noCreditsIs402AndNothingIsInsertedOrBuilt() {
		when(repo.balance("s1")).thenReturn(new Balance(0, 0));
		AtomicReference<String> ran = new AtomicReference<>();
		var ex = assertThrows(ResponseStatusException.class,
				() -> service(LAUNCH).runSpending("s1", UUID.randomUUID(), plan -> ran.getAndSet("ran")));
		assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatusCode());
		assertNull(ran.get());
		verify(repo, never()).insert(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any());
	}

	@Test
	void lockIsTakenBeforeTheBalanceIsRead() {
		when(repo.balance("s1")).thenReturn(new Balance(0, 1));
		service(LAUNCH).runSpending("s1", UUID.randomUUID(), plan -> null);
		var order = inOrder(repo);
		order.verify(repo).lock("s1");
		order.verify(repo).balance("s1");
	}

	@Test
	void failureInsideTheWorkPropagates() {
		// With a real transaction manager the ledger row is rolled back too; that is the manager's job.
		when(repo.balance("s1")).thenReturn(new Balance(0, 1));
		assertThrows(IllegalStateException.class, () -> service(LAUNCH).runSpending("s1", UUID.randomUUID(), plan -> {
			throw new IllegalStateException("boom");
		}));
	}

	@Test
	void purchaseAddsCreditsAndADuplicateIsIgnored() {
		when(repo.insert("s1", 5, "full", "purchase", "ls-order:77", null)).thenReturn(true, false);
		assertTrue(service(LAUNCH).applyPurchase("s1", 5, "77"));
		assertFalse(service(LAUNCH).applyPurchase("s1", 5, "77"));
	}

	@Test
	void refundIsNegativeWithItsOwnRef() {
		service(LAUNCH).applyRefund("s1", 5, "77");
		verify(repo).insert("s1", -5, "full", "refund", "ls-refund:77", null);
	}

	@Test
	void unlockedByPurchaseOrByTheLaunchWindow() {
		when(repo.launchedAt()).thenReturn(LAUNCH);
		when(repo.hasUnrefundedPurchase("bought")).thenReturn(true);
		assertTrue(service(LAUNCH.plusSeconds(90L * 86400)).unlocked("bought"), "a purchase unlocks at any time");
		assertTrue(service(LAUNCH.plusSeconds(29L * 86400)).unlocked("free"), "inside the 30 days after launch");
		assertFalse(service(LAUNCH.plusSeconds(31L * 86400)).unlocked("free"), "after the 30 days");
	}
}
