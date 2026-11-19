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
import androidx.annotation.WorkerThread;

import java.util.List;

/**
 * An on-device language detection and translation engine.
 *
 * All methods are called on background threads (never the UI thread), possibly
 * concurrently from more than one thread, so implementations must be thread-safe.
 *
 * Language codes are lowercase ISO 639-1 codes (e.g. "de", "nb", "zh").
 *
 * Register an implementation using {@link TranslationManager#setBackend(TranslationBackend)}.
 */
public interface TranslationBackend {

	/**
	 * Detects the language of the given plain text. The text has already had
	 * HTML markup, code blocks, URLs and quotes removed.
	 *
	 * @return The detected language, or null if the language could not be determined.
	 */
	@WorkerThread
	@Nullable
	DetectedLanguage detectLanguage(@NonNull String text);

	/**
	 * @return True if text in language {@code from} can currently be translated into
	 * language {@code to} without downloading anything (pivoting through another
	 * language is the backend's responsibility).
	 */
	@WorkerThread
	boolean isModelAvailable(@NonNull String from, @NonNull String to);

	/**
	 * Translates each of the given texts. This blocks until translation is complete.
	 *
	 * @param isHtml If true, the texts are HTML fragments, and the markup must be
	 *               preserved in the output.
	 * @return The translated texts, in the same order as the input, and with the same size.
	 * @throws Exception If the translation failed.
	 */
	@WorkerThread
	@NonNull
	List<String> translate(
			@NonNull String from,
			@NonNull String to,
			@NonNull List<String> texts,
			boolean isHtml) throws Exception;
}
