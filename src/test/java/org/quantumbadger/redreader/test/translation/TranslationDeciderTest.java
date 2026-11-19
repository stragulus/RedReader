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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.quantumbadger.redreader.translation.DetectedLanguage;
import org.quantumbadger.redreader.translation.TranslationContentType;
import org.quantumbadger.redreader.translation.TranslationDecider;
import org.quantumbadger.redreader.translation.TranslationDecider.Decision;
import org.quantumbadger.redreader.translation.TranslationDecider.Outcome;
import org.quantumbadger.redreader.translation.TranslationSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;

public class TranslationDeciderTest {

	private static final int LONG_ENOUGH = TranslationDecider.MIN_LETTERS_FOR_AUTO_TRANSLATION;

	private static TranslationSettings settings(final String target, final String... secondary) {
		return new TranslationSettings(
				true,
				target,
				new HashSet<>(Arrays.asList(secondary)),
				EnumSet.allOf(TranslationContentType.class));
	}

	private static DetectedLanguage reliable(final String language) {
		return new DetectedLanguage(language, 0.95f, true);
	}

	@Test
	public void translatesForeignLanguage() {
		final Decision decision = TranslationDecider.decide(
				reliable("es"), LONG_ENOUGH, settings("en"), false);

		assertEquals(Outcome.TRANSLATE, decision.outcome);
		assertEquals("es", decision.sourceLanguage);
		assertTrue(decision.shouldTranslate());
	}

	@Test
	public void neverTranslatesPrimaryLanguage() {
		assertEquals(
				Outcome.ALREADY_TARGET_LANGUAGE,
				TranslationDecider.decide(reliable("en"), 500, settings("en"), false).outcome);

		assertEquals(
				Outcome.ALREADY_TARGET_LANGUAGE,
				TranslationDecider.decide(reliable("en"), 500, settings("en"), true).outcome);
	}

	@Test
	public void primaryLanguageIsNormalized() {
		assertEquals(
				Outcome.ALREADY_TARGET_LANGUAGE,
				TranslationDecider.decide(reliable("pt-BR"), 500, settings("pt"), false).outcome);

		assertEquals(
				Outcome.ALREADY_TARGET_LANGUAGE,
				TranslationDecider.decide(reliable("iw"), 500, settings("he"), false).outcome);
	}

	@Test
	public void neverAutoTranslatesSecondaryLanguages() {
		assertEquals(
				Outcome.SECONDARY_LANGUAGE,
				TranslationDecider.decide(
						reliable("de"), 500, settings("en", "de", "fr"), false).outcome);

		assertEquals(
				Outcome.SECONDARY_LANGUAGE,
				TranslationDecider.decide(
						reliable("no"), 500, settings("en", "nb"), false).outcome);
	}

	@Test
	public void userCanTranslateSecondaryLanguages() {
		final Decision decision = TranslationDecider.decide(
				reliable("de"), 500, settings("en", "de"), true);

		assertEquals(Outcome.TRANSLATE, decision.outcome);
		assertEquals("de", decision.sourceLanguage);
	}

	@Test
	public void neverAutoTranslatesUnreliableDetection() {
		assertEquals(
				Outcome.NOT_RELIABLE,
				TranslationDecider.decide(
						new DetectedLanguage("es", 0.99f, false),
						500,
						settings("en"),
						false).outcome);

		assertEquals(
				Outcome.NOT_RELIABLE,
				TranslationDecider.decide(
						new DetectedLanguage("es", 0.2f, true),
						500,
						settings("en"),
						false).outcome);
	}

	@Test
	public void userCanTranslateUnreliableDetection() {
		assertEquals(
				Outcome.TRANSLATE,
				TranslationDecider.decide(
						new DetectedLanguage("es", 0.2f, false),
						3,
						settings("en"),
						true).outcome);
	}

	@Test
	public void neverAutoTranslatesShortText() {
		assertEquals(
				Outcome.TOO_SHORT,
				TranslationDecider.decide(
						reliable("es"), LONG_ENOUGH - 1, settings("en"), false).outcome);

		assertEquals(
				Outcome.TOO_SHORT,
				TranslationDecider.decide(reliable("es"), 0, settings("en"), true).outcome);
	}

	@Test
	public void unknownLanguage() {
		assertEquals(
				Outcome.UNKNOWN_LANGUAGE,
				TranslationDecider.decide(null, 500, settings("en"), false).outcome);

		assertEquals(
				Outcome.UNKNOWN_LANGUAGE,
				TranslationDecider.decide(null, 500, settings("en"), true).outcome);

		assertEquals(
				Outcome.UNKNOWN_LANGUAGE,
				TranslationDecider.decide(reliable("un"), 500, settings("en"), true).outcome);
	}

	@Test
	public void contentTypes() {
		final TranslationSettings commentsOnly = new TranslationSettings(
				true,
				"en",
				Collections.emptySet(),
				EnumSet.of(TranslationContentType.COMMENTS));

		assertTrue(commentsOnly.shouldAutoTranslate(TranslationContentType.COMMENTS));
		assertFalse(commentsOnly.shouldAutoTranslate(TranslationContentType.TITLES));

		final TranslationSettings disabled = new TranslationSettings(
				false,
				"en",
				Collections.emptySet(),
				EnumSet.allOf(TranslationContentType.class));

		assertFalse(disabled.shouldAutoTranslate(TranslationContentType.COMMENTS));

		final TranslationSettings nothing = new TranslationSettings(
				true,
				"en",
				Collections.emptySet(),
				EnumSet.noneOf(TranslationContentType.class));

		assertFalse(nothing.shouldAutoTranslate(TranslationContentType.TITLES));
	}

	@Test
	public void extractRemovesMarkupCodeAndUrls() {
		final String html = "<div class=\"md\"><p>Hola &amp; adi&#243;s, mira "
				+ "<a href=\"https://example.com/x\">https://example.com/x</a> en r/spain</p>"
				+ "<pre><code>int x = 1;</code></pre>"
				+ "<p>Visita www.example.org y <code>foo()</code></p></div>";

		assertEquals(
				"Hola & adiós, mira en Visita y",
				TranslationDecider.extractTextForDetection(html, true));
	}

	@Test
	public void extractRemovesQuotesUnlessOnlyQuotes() {
		final String html = "<div class=\"md\"><blockquote><p>This is English</p></blockquote>"
				+ "<p>Das ist Deutsch</p></div>";

		assertEquals("Das ist Deutsch", TranslationDecider.extractTextForDetection(html, true));

		final String onlyQuote = "<blockquote><p>This is English</p></blockquote>";

		assertEquals(
				"This is English",
				TranslationDecider.extractTextForDetection(onlyQuote, true));
	}

	@Test
	public void extractPlainText() {
		assertEquals(
				"Was ist das für ein Vogel?",
				TranslationDecider.extractTextForDetection(
						"Was ist das für ein Vogel? https://i.redd.it/abc.jpg", false));

		assertEquals(
				"Run on my PC, see",
				TranslationDecider.extractTextForDetection(
						"Run `ls -la` on my PC, see /u/someone", false));

		assertEquals("", TranslationDecider.extractTextForDetection(null, true));
		assertEquals("", TranslationDecider.extractTextForDetection("", false));
	}

	@Test
	public void countLetters() {
		assertEquals(0, TranslationDecider.countLetters(""));
		assertEquals(0, TranslationDecider.countLetters("123 !? 🙂"));
		assertEquals(5, TranslationDecider.countLetters("héllo"));
		assertEquals(6, TranslationDecider.countLetters("привет"));
		// Each Han/Kana/Hangul character counts as several letters
		assertTrue(TranslationDecider.countLetters("日本語のテキスト") >= LONG_ENOUGH);
		assertTrue(TranslationDecider.countLetters("한국어 텍스트") >= LONG_ENOUGH);
	}

	@Test
	public void decisionWithoutDetection() {
		final Decision decision = TranslationDecider.decide(null, 0, settings("en"), false);
		assertNull(decision.sourceLanguage);
		assertFalse(decision.shouldTranslate());
	}
}
