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

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * An immutable snapshot of the user's translation preferences.
 */
public final class TranslationSettings {

	public final boolean autoTranslateEnabled;

	/**
	 * The normalised language code that text is translated into.
	 */
	@NonNull public final String targetLanguage;

	/**
	 * Normalised codes of languages which the user understands, and which are
	 * never automatically translated.
	 */
	@NonNull public final Set<String> secondaryLanguages;

	@NonNull private final EnumSet<TranslationContentType> mAutoContentTypes;

	public TranslationSettings(
			final boolean autoTranslateEnabled,
			@NonNull final String targetLanguage,
			@NonNull final Set<String> secondaryLanguages,
			@NonNull final Set<TranslationContentType> autoContentTypes) {

		this.autoTranslateEnabled = autoTranslateEnabled;
		this.targetLanguage = targetLanguage;
		this.secondaryLanguages = Collections.unmodifiableSet(new HashSet<>(secondaryLanguages));

		mAutoContentTypes = autoContentTypes.isEmpty()
				? EnumSet.noneOf(TranslationContentType.class)
				: EnumSet.copyOf(autoContentTypes);
	}

	public boolean shouldAutoTranslate(@NonNull final TranslationContentType type) {
		return autoTranslateEnabled && mAutoContentTypes.contains(type);
	}
}
