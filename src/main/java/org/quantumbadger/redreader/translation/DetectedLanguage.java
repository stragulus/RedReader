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

import java.util.Locale;

/**
 * The result of language detection.
 */
public final class DetectedLanguage {

	/**
	 * The detected language as reported by the detector. This is normalised by
	 * {@link TranslationLanguages#normalize(String)} before use, so values like
	 * "pt-BR", "iw" or "zh-Hant" are acceptable.
	 */
	@NonNull public final String language;

	/**
	 * The detector's confidence, from 0.0 (no confidence) to 1.0 (certain).
	 */
	public final float confidence;

	/**
	 * Whether the detector considers this result reliable.
	 */
	public final boolean reliable;

	public DetectedLanguage(
			@NonNull final String language,
			final float confidence,
			final boolean reliable) {

		this.language = language;
		this.confidence = confidence;
		this.reliable = reliable;
	}

	@NonNull
	@Override
	public String toString() {
		return String.format(
				Locale.US,
				"DetectedLanguage(%s, %.2f, %s)",
				language,
				confidence,
				reliable ? "reliable" : "unreliable");
	}
}
