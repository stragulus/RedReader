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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Language codes and helpers. This class has no Android dependencies, so it
 * can be unit tested.
 */
public final class TranslationLanguages {

	/**
	 * Preference value meaning "use the app's language, or the system language".
	 */
	@NonNull public static final String PRIMARY_LANGUAGE_AUTO = "auto";

	/**
	 * Languages supported by the Mozilla Bergamot/Firefox Translations models (to or
	 * from English), as ISO 639-1 codes.
	 */
	@NonNull public static final List<String> SUPPORTED_LANGUAGES = Collections.unmodifiableList(
			Arrays.asList(
					"af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el",
					"en", "es", "et", "eu", "fa", "fi", "fr", "gl", "gu", "he", "hi", "hr",
					"hu", "id", "is", "it", "ja", "kn", "ko", "lt", "lv", "ml", "mr", "ms",
					"mt", "nb", "nl", "nn", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sr",
					"sv", "ta", "te", "th", "tl", "tr", "ug", "uk", "ur", "vi", "zh"));

	@NonNull private static final Set<String> NO_LANGUAGE_CODES = new HashSet<>(Arrays.asList(
			"un", "und", "unk", "unknown", "xx", "xxx", "zxx", "mul"));

	private TranslationLanguages() {}

	/**
	 * Normalises a language code or tag into a lowercase ISO 639-1 code (where one
	 * exists). Accepts BCP 47 tags ("pt-BR"), Java locale strings ("pt_BR"), Android
	 * resource qualifiers ("pt-rBR"), and legacy codes ("iw", "in", "ji", "no").
	 *
	 * @return The normalised code, or null if the input doesn't describe a language.
	 */
	@Nullable
	public static String normalize(@Nullable final String code) {

		if(code == null) {
			return null;
		}

		final String trimmed = code.trim().toLowerCase(Locale.ROOT).replace('_', '-');

		final int separator = trimmed.indexOf('-');
		final String primary = separator < 0 ? trimmed : trimmed.substring(0, separator);

		if(primary.length() < 2 || primary.length() > 3) {
			return null;
		}

		for(int i = 0; i < primary.length(); i++) {
			final char c = primary.charAt(i);
			if(c < 'a' || c > 'z') {
				return null;
			}
		}

		if(NO_LANGUAGE_CODES.contains(primary)) {
			return null;
		}

		switch(primary) {
			case "iw":
				return "he";
			case "in":
				return "id";
			case "ji":
				return "yi";
			case "no":
			case "nob":
				return "nb";
			case "nno":
				return "nn";
			case "zho":
			case "chi":
				return "zh";
			default:
				return primary;
		}
	}

	/**
	 * Determines the language that text should be translated into.
	 *
	 * @param primaryLanguagePref The translation primary language preference
	 *                            ("auto", or a language code).
	 * @param appLanguagePref The app language preference ("auto", or a value
	 *                        such as "de" or "pt-rBR").
	 * @param systemLocale The system locale, used if both preferences are "auto".
	 */
	@NonNull
	public static String resolveTargetLanguage(
			@Nullable final String primaryLanguagePref,
			@Nullable final String appLanguagePref,
			@NonNull final Locale systemLocale) {

		if(primaryLanguagePref != null
				&& !PRIMARY_LANGUAGE_AUTO.equals(primaryLanguagePref)) {

			final String result = normalize(primaryLanguagePref);

			if(result != null) {
				return result;
			}
		}

		if(appLanguagePref != null && !PRIMARY_LANGUAGE_AUTO.equals(appLanguagePref)) {

			final String result = normalize(appLanguagePref);

			if(result != null) {
				return result;
			}
		}

		final String result = normalize(systemLocale.getLanguage());
		return result != null ? result : "en";
	}

	/**
	 * Normalises each of the given codes, discarding any which are invalid.
	 */
	@NonNull
	public static Set<String> normalizeAll(@Nullable final Iterable<String> codes) {

		final HashSet<String> result = new HashSet<>();

		if(codes == null) {
			return result;
		}

		for(final String code : codes) {
			final String normalized = normalize(code);
			if(normalized != null) {
				result.add(normalized);
			}
		}

		return result;
	}

	/**
	 * @return The name of the language, in the language of {@code displayLocale}.
	 */
	@NonNull
	public static String getDisplayName(
			@NonNull final String code,
			@NonNull final Locale displayLocale) {

		final String name = Locale.forLanguageTag(code).getDisplayLanguage(displayLocale);

		if(name == null || name.isEmpty() || name.equalsIgnoreCase(code)) {
			return code;
		}

		// Some languages (e.g. French) don't capitalise language names
		return name.substring(0, 1).toUpperCase(displayLocale) + name.substring(1);
	}
}
