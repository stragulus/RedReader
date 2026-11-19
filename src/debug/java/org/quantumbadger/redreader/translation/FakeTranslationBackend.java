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

package org.quantumbadger.redreader.translation;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake translation backend for exercising the translation UI without the native
 * translation library. Debug builds only: enabled using the developer preference in
 * the Translation settings, and loaded by reflection from TranslationManager.
 *
 * Languages are "detected" by counting common words, and "translation" wraps each
 * piece of text in [brackets], prefixed with the source language code. Text in
 * Dutch can't be translated, to simulate a missing model.
 */
@Keep
public final class FakeTranslationBackend implements TranslationBackend {

	private static final Pattern WORD = Pattern.compile("\\p{L}+");

	private static final Pattern HTML_TEXT = Pattern.compile(">([^<]*\\p{L}[^<]*)<");

	private static final Map<String, String[]> COMMON_WORDS = new HashMap<>();

	static {
		COMMON_WORDS.put("en", new String[] {
				"the", "and", "is", "of", "to", "in", "it", "that", "this", "you", "for",
				"with", "are", "was", "what", "have", "not", "be", "my", "on"});
		COMMON_WORDS.put("de", new String[] {
				"der", "die", "das", "und", "ist", "nicht", "ich", "ein", "eine", "zu",
				"mit", "auf", "den", "sich", "es", "von", "für", "auch", "wie", "aber"});
		COMMON_WORDS.put("fr", new String[] {
				"le", "la", "les", "et", "est", "un", "une", "des", "du", "pas", "je",
				"que", "qui", "en", "pour", "dans", "ce", "il", "sur", "avec"});
		COMMON_WORDS.put("es", new String[] {
				"el", "la", "los", "las", "y", "es", "un", "una", "que", "de", "no",
				"en", "por", "para", "con", "se", "lo", "mi", "pero", "como"});
		COMMON_WORDS.put("nl", new String[] {
				"de", "het", "een", "en", "is", "niet", "ik", "van", "dat", "op",
				"je", "met", "voor", "zijn", "maar", "ook", "wat", "naar", "er", "dit"});
	}

	@Nullable
	@Override
	public DetectedLanguage detectLanguage(@NonNull final String text) {

		simulateWork(2);

		final HashMap<String, Integer> scores = new HashMap<>();
		int totalWords = 0;

		final Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));

		while(matcher.find()) {

			totalWords++;
			final String word = matcher.group();

			for(final Map.Entry<String, String[]> entry : COMMON_WORDS.entrySet()) {
				for(final String common : entry.getValue()) {
					if(common.equals(word)) {
						final Integer score = scores.get(entry.getKey());
						scores.put(entry.getKey(), score == null ? 1 : score + 1);
					}
				}
			}
		}

		String best = null;
		int bestScore = 0;
		int totalScore = 0;

		for(final Map.Entry<String, Integer> entry : scores.entrySet()) {
			totalScore += entry.getValue();
			if(entry.getValue() > bestScore) {
				best = entry.getKey();
				bestScore = entry.getValue();
			}
		}

		if(best == null) {
			return null;
		}

		final float confidence = (float)bestScore / totalScore;
		final boolean reliable = totalWords >= 4 && bestScore >= 2 && confidence >= 0.5f;

		return new DetectedLanguage(best, confidence, reliable);
	}

	@Override
	public boolean isModelAvailable(@NonNull final String from, @NonNull final String to) {
		return !"nl".equals(from) && !"nl".equals(to);
	}

	@NonNull
	@Override
	public List<String> translate(
			@NonNull final String from,
			@NonNull final String to,
			@NonNull final List<String> texts,
			final boolean isHtml) {

		simulateWork(300);

		final ArrayList<String> result = new ArrayList<>(texts.size());

		for(final String text : texts) {

			if(isHtml) {
				final Matcher matcher = HTML_TEXT.matcher(text);
				final StringBuffer sb = new StringBuffer();

				while(matcher.find()) {
					matcher.appendReplacement(
							sb,
							Matcher.quoteReplacement(
									">[" + from + "→" + to + ": " + matcher.group(1) + "]<"));
				}

				matcher.appendTail(sb);
				result.add(sb.toString());

			} else {
				result.add("[" + from + "→" + to + ": " + text + "]");
			}
		}

		return result;
	}

	private static void simulateWork(final long millis) {
		try {
			Thread.sleep(millis);
		} catch(final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
