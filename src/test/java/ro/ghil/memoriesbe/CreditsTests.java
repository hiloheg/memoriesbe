package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import ro.ghil.memoriesbe.credits.CreditRepository.Balance;
import ro.ghil.memoriesbe.credits.Plan;

class CreditsTests {

	private static final long GIB = 1024L * 1024 * 1024;

	@Test
	void planLimitsMatchTheOwnersNumbers() {
		assertEquals(300, Plan.TRIAL.maxPhotos());
		assertEquals(4 * GIB, Plan.TRIAL.maxBytes());
		assertEquals(30, Plan.TRIAL.days());
		assertEquals(1, Plan.TRIAL.maxCameras());
		assertEquals(3000, Plan.FULL.maxPhotos());
		assertEquals(40 * GIB, Plan.FULL.maxBytes());
		assertEquals(90, Plan.FULL.days());
		assertEquals(Integer.MAX_VALUE, Plan.FULL.maxCameras());
		assertEquals("full", Plan.FULL.key());
		assertEquals(Plan.FULL, Plan.of("full"));
		assertEquals(Plan.TRIAL, Plan.of("trial"));
		assertThrows(IllegalArgumentException.class, () -> Plan.of("x"));
		assertEquals(3, new Balance(1, 2).total());
	}
}
