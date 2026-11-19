/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.test.translation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.quantumbadger.redreader.translation.TranslationLanguages;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

public class TranslationLanguagesTest {

	@Test
	public void normalize() {
		assertEquals("en", TranslationLanguages.normalize("en"));
		assertEquals("en", TranslationLanguages.normalize(" EN "));
		assertEquals("pt", TranslationLanguages.normalize("pt-BR"));
		assertEquals("pt", TranslationLanguages.normalize("pt_BR"));
		assertEquals("pt", TranslationLanguages.normalize("pt-rBR"));
		assertEquals("zh", TranslationLanguages.normalize("zh-Hant"));
		assertEquals("zh", TranslationLanguages.normalize("zh-rTW"));
		assertEquals("nb", TranslationLanguages.normalize("nb-rNO"));
		assertEquals("nb", TranslationLanguages.normalize("no"));
		assertEquals("he", TranslationLanguages.normalize("iw"));
		assertEquals("id", TranslationLanguages.normalize("in"));
		assertEquals("yi", TranslationLanguages.normalize("ji"));
		assertEquals("fil", TranslationLanguages.normalize("fil"));
	}

	@Test
	public void normalizeInvalid() {
		assertNull(TranslationLanguages.normalize(null));
		assertNull(TranslationLanguages.normalize(""));
		assertNull(TranslationLanguages.normalize("   "));
		assertNull(TranslationLanguages.normalize("x"));
		assertNull(TranslationLanguages.normalize("english"));
		assertNull(TranslationLanguages.normalize("e1"));
		assertNull(TranslationLanguages.normalize("un"));
		assertNull(TranslationLanguages.normalize("und"));
		assertNull(TranslationLanguages.normalize("xx-Qaai"));
	}

	@Test
	public void normalizeIsLocaleIndependent() {
		final Locale previous = Locale.getDefault();

		try {
			// Turkish has a dotless lowercase i
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals("it", TranslationLanguages.normalize("IT"));
			assertEquals("id", TranslationLanguages.normalize("ID"));
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	public void resolveTargetLanguage() {

		final Locale system = Locale.GERMANY;

		// Explicit primary language wins
		assertEquals("fr", TranslationLanguages.resolveTargetLanguage("fr", "es", system));

		// Then the app language
		assertEquals("es", TranslationLanguages.resolveTargetLanguage("auto", "es", system));
		assertEquals("pt", TranslationLanguages.resolveTargetLanguage("auto", "pt-rBR", system));
		assertEquals("id", TranslationLanguages.resolveTargetLanguage("auto", "in", system));

		// Then the system language
		assertEquals("de", TranslationLanguages.resolveTargetLanguage("auto", "auto", system));
		assertEquals("de", TranslationLanguages.resolveTargetLanguage(null, null, system));

		// Falling back to English if all else fails
		assertEquals("en", TranslationLanguages.resolveTargetLanguage(
				"auto", "auto", Locale.ROOT));
	}

	@Test
	public void normalizeAll() {
		assertEquals(
				new HashSet<>(Arrays.asList("de", "nb")),
				TranslationLanguages.normalizeAll(Arrays.asList("de", "no", "nonsense", "DE")));

		assertTrue(TranslationLanguages.normalizeAll(null).isEmpty());
	}

	@Test
	public void supportedLanguagesAreNormalized() {
		for(final String code : TranslationLanguages.SUPPORTED_LANGUAGES) {
			assertEquals(code, TranslationLanguages.normalize(code));
		}

		assertEquals(
				TranslationLanguages.SUPPORTED_LANGUAGES.size(),
				new HashSet<>(TranslationLanguages.SUPPORTED_LANGUAGES).size());
	}

	@Test
	public void displayName() {
		assertEquals("German", TranslationLanguages.getDisplayName("de", Locale.ENGLISH));
		assertEquals("Deutsch", TranslationLanguages.getDisplayName("de", Locale.GERMAN));
		// French doesn't capitalise language names, but they're shown in a list
		assertEquals("Allemand", TranslationLanguages.getDisplayName("de", Locale.FRENCH));
	}
}
