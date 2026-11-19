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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a piece of text should be translated. This class has no
 * Android dependencies, so it can be unit tested.
 */
public final class TranslationDecider {

	/**
	 * Text with fewer letters than this (after removing markup, code, quotes and
	 * URLs) is never automatically translated, as language detection is unreliable
	 * for very short text. Ideographic/syllabic characters count as several letters,
	 * see {@link #countLetters(String)}.
	 *
	 * This class is the single source of the detection policy: backends should pass
	 * this threshold (and {@link #MIN_CONFIDENCE_FOR_AUTO_TRANSLATION}) to their
	 * detector, if it has its own thresholds, so that they agree.
	 */
	public static final int MIN_LETTERS_FOR_AUTO_TRANSLATION = 15;

	/**
	 * Detection results with a lower confidence (the fraction of the text which is
	 * in the detected language) than this are never automatically translated.
	 */
	public static final float MIN_CONFIDENCE_FOR_AUTO_TRANSLATION = 0.6f;

	/**
	 * Each Han/Kana/Hangul character counts as this many letters, as each one
	 * carries roughly as much information as a short word.
	 */
	private static final int CJK_LETTER_WEIGHT = 3;

	private static final Pattern PATTERN_HTML_CODE = Pattern.compile(
			"<(pre|code)\\b[^>]*>.*?</\\1\\s*>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern PATTERN_HTML_QUOTE = Pattern.compile(
			"<blockquote\\b[^>]*>.*?</blockquote\\s*>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern PATTERN_HTML_TAG = Pattern.compile("<[^>]*>");

	private static final Pattern PATTERN_HTML_ENTITY = Pattern.compile(
			"&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");

	private static final Pattern PATTERN_MARKDOWN_CODE = Pattern.compile("`[^`]*`");

	private static final Pattern PATTERN_MARKDOWN_QUOTE = Pattern.compile(
			"^\\s*(&gt;|>).*$",
			Pattern.MULTILINE);

	private static final Pattern PATTERN_URL = Pattern.compile(
			"(?i)\\b(?:https?://|www\\.)\\S+");

	private static final Pattern PATTERN_REDDIT_REFERENCE = Pattern.compile(
			"(?i)(?<![\\w/])/?(?:r|u|user)/[\\w-]+");

	private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");

	public enum Outcome {
		/**
		 * The text should be translated.
		 */
		TRANSLATE,

		/**
		 * The text is already in the target language.
		 */
		ALREADY_TARGET_LANGUAGE,

		/**
		 * The text is in one of the user's secondary languages.
		 */
		SECONDARY_LANGUAGE,

		/**
		 * The language detector wasn't confident enough.
		 */
		NOT_RELIABLE,

		/**
		 * The text was too short to reliably determine its language.
		 */
		TOO_SHORT,

		/**
		 * The language of the text could not be determined.
		 */
		UNKNOWN_LANGUAGE
	}

	public static final class Decision {

		@NonNull public final Outcome outcome;

		/**
		 * The normalised source language, or null if unknown.
		 */
		@Nullable public final String sourceLanguage;

		public Decision(@NonNull final Outcome outcome, @Nullable final String sourceLanguage) {
			this.outcome = outcome;
			this.sourceLanguage = sourceLanguage;
		}

		public boolean shouldTranslate() {
			return outcome == Outcome.TRANSLATE;
		}

		@NonNull
		@Override
		public String toString() {
			return "Decision(" + outcome + ", " + sourceLanguage + ")";
		}
	}

	private TranslationDecider() {}

	/**
	 * Decides whether to translate some text.
	 *
	 * @param detected The result of language detection, or null if detection was not
	 *                 performed or failed.
	 * @param letterCount The result of {@link #countLetters(String)} on the text used
	 *                    for detection.
	 * @param settings The user's preferences.
	 * @param userRequested True if the user explicitly asked for this translation. In
	 *                      that case, the text is translated as long as its language is
	 *                      known and differs from the target language.
	 */
	@NonNull
	public static Decision decide(
			@Nullable final DetectedLanguage detected,
			final int letterCount,
			@NonNull final TranslationSettings settings,
			final boolean userRequested) {

		if(letterCount < (userRequested ? 1 : MIN_LETTERS_FOR_AUTO_TRANSLATION)) {
			return new Decision(Outcome.TOO_SHORT, null);
		}

		final String language = detected == null ? null : TranslationLanguages.normalize(
				detected.language);

		if(language == null) {
			return new Decision(Outcome.UNKNOWN_LANGUAGE, null);
		}

		if(language.equals(settings.targetLanguage)) {
			return new Decision(Outcome.ALREADY_TARGET_LANGUAGE, language);
		}

		if(userRequested) {
			return new Decision(Outcome.TRANSLATE, language);
		}

		if(settings.secondaryLanguages.contains(language)) {
			return new Decision(Outcome.SECONDARY_LANGUAGE, language);
		}

		if(!detected.reliable || detected.confidence < MIN_CONFIDENCE_FOR_AUTO_TRANSLATION) {
			return new Decision(Outcome.NOT_RELIABLE, language);
		}

		return new Decision(Outcome.TRANSLATE, language);
	}

	/**
	 * Extracts the text which should be used for language detection: markup, code,
	 * URLs, and subreddit/user references are removed. Quotes are also removed (as
	 * they're often quoting text in another language), unless the text consists only
	 * of quotes.
	 *
	 * @param text The text, either as HTML or as plain text.
	 * @param isHtml True if {@code text} is HTML.
	 */
	@NonNull
	public static String extractTextForDetection(
			@Nullable final String text,
			final boolean isHtml) {

		if(text == null || text.isEmpty()) {
			return "";
		}

		final String withoutQuotes = extractTextForDetection(text, isHtml, true);

		if(countLetters(withoutQuotes) > 0) {
			return withoutQuotes;
		}

		return extractTextForDetection(text, isHtml, false);
	}

	@NonNull
	private static String extractTextForDetection(
			@NonNull final String text,
			final boolean isHtml,
			final boolean removeQuotes) {

		String result = text;

		if(isHtml) {
			result = PATTERN_HTML_CODE.matcher(result).replaceAll(" ");

			if(removeQuotes) {
				result = PATTERN_HTML_QUOTE.matcher(result).replaceAll(" ");
			}

			result = PATTERN_HTML_TAG.matcher(result).replaceAll(" ");
			result = decodeHtmlEntities(result);

		} else {
			result = PATTERN_MARKDOWN_CODE.matcher(result).replaceAll(" ");

			if(removeQuotes) {
				result = PATTERN_MARKDOWN_QUOTE.matcher(result).replaceAll(" ");
			}
		}

		result = PATTERN_URL.matcher(result).replaceAll(" ");
		result = PATTERN_REDDIT_REFERENCE.matcher(result).replaceAll(" ");
		result = PATTERN_WHITESPACE.matcher(result).replaceAll(" ");

		return result.trim();
	}

	@NonNull
	private static String decodeHtmlEntities(@NonNull final String text) {

		if(text.indexOf('&') < 0) {
			return text;
		}

		final Matcher matcher = PATTERN_HTML_ENTITY.matcher(text);
		final StringBuilder result = new StringBuilder(text.length());

		int lastEnd = 0;

		while(matcher.find()) {

			result.append(text, lastEnd, matcher.start());
			lastEnd = matcher.end();

			final String entity = matcher.group(1);
			final String decoded = decodeHtmlEntity(entity);

			result.append(decoded != null ? decoded : matcher.group());
		}

		result.append(text, lastEnd, text.length());
		return result.toString();
	}

	@Nullable
	private static String decodeHtmlEntity(@NonNull final String entity) {

		if(entity.charAt(0) == '#') {

			final int codePoint;

			try {
				if(entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
					codePoint = Integer.parseInt(entity.substring(2), 16);
				} else {
					codePoint = Integer.parseInt(entity.substring(1));
				}
			} catch(final NumberFormatException e) {
				return null;
			}

			if(!Character.isValidCodePoint(codePoint)) {
				return null;
			}

			return new String(Character.toChars(codePoint));
		}

		switch(entity.toLowerCase(Locale.ROOT)) {
			case "amp":
				return "&";
			case "lt":
				return "<";
			case "gt":
				return ">";
			case "quot":
				return "\"";
			case "apos":
				return "'";
			case "nbsp":
				return " ";
			default:
				return null;
		}
	}

	/**
	 * Counts the letters in the given text. Ideographic and syllabic characters
	 * (Han, Hiragana, Katakana, Hangul) count as several letters each.
	 */
	public static int countLetters(@NonNull final String text) {

		int result = 0;
		int i = 0;

		while(i < text.length()) {

			final int codePoint = text.codePointAt(i);
			i += Character.charCount(codePoint);

			if(!Character.isLetter(codePoint)) {
				continue;
			}

			// Character.UnicodeScript would be simpler, but requires API 24
			final Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);

			if(block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
					|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
					|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
					|| block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
					|| block == Character.UnicodeBlock.HIRAGANA
					|| block == Character.UnicodeBlock.KATAKANA
					|| block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
					|| block == Character.UnicodeBlock.HANGUL_SYLLABLES
					|| block == Character.UnicodeBlock.HANGUL_JAMO
					|| block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {

				result += CJK_LETTER_WEIGHT;

			} else {
				result++;
			}
		}

		return result;
	}
}
