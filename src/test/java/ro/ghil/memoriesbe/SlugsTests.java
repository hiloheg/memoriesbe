package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ro.ghil.memoriesbe.profile.Slugs;

class SlugsTests {
	@Test
	void slugifiesNamesIncludingDiacritics() {
		assertEquals("ana-pop", Slugs.slugify("Ana Pop"));
		assertEquals("stefan-ionescu", Slugs.slugify("Stefan Ionescu"));
		assertEquals("stefan-ionescu", Slugs.slugify("Ștefan  Ionescu!"));
		assertEquals("ab-photo", Slugs.slugify("ab"));
		assertTrue(Slugs.slugify("x".repeat(80)).length() <= 30);
	}

	@Test
	void validatesAddresses() {
		assertTrue(Slugs.valid("ana-pop"));
		assertTrue(Slugs.valid("studio99"));
		assertFalse(Slugs.valid("events"));
		assertFalse(Slugs.valid("ab"));
		assertFalse(Slugs.valid("-ana"));
		assertFalse(Slugs.valid("ana-"));
		assertFalse(Slugs.valid("a--b"));
		assertFalse(Slugs.valid("Ana"));
		assertFalse(Slugs.valid(null));
	}
}
