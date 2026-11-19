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

public final class TranslationResult {

	public enum Status {
		/**
		 * The text was translated, see {@link #translatedText}.
		 */
		TRANSLATED,

		/**
		 * The text was not translated. See {@link #decision} for the reason.
		 */
		NOT_TRANSLATED,

		/**
		 * The text should be translated, but the language model for this language pair
		 * isn't available. See {@link TranslationManager#getMissingModelPairs()}.
		 */
		MODEL_MISSING,

		/**
		 * No translation backend is available.
		 */
		NO_BACKEND,

		/**
		 * The backend threw an exception.
		 */
		FAILED
	}

	@NonNull public final Status status;

	@Nullable public final TranslationDecider.Decision decision;

	/**
	 * The normalised source language, if known.
	 */
	@Nullable public final String sourceLanguage;

	@NonNull public final String targetLanguage;

	@Nullable public final String translatedText;

	public TranslationResult(
			@NonNull final Status status,
			@Nullable final TranslationDecider.Decision decision,
			@Nullable final String sourceLanguage,
			@NonNull final String targetLanguage,
			@Nullable final String translatedText) {

		this.status = status;
		this.decision = decision;
		this.sourceLanguage = sourceLanguage;
		this.targetLanguage = targetLanguage;
		this.translatedText = translatedText;
	}

	@NonNull
	@Override
	public String toString() {
		return "TranslationResult(" + status + ", " + decision + ", "
				+ sourceLanguage + " -> " + targetLanguage + ")";
	}
}
