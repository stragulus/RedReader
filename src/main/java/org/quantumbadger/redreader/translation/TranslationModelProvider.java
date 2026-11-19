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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

/**
 * Provides the state of the language models needed for translation, and lets the
 * user download missing models. Models are never downloaded without user action.
 *
 * Register an implementation using
 * {@link TranslationManager#setModelProvider(TranslationModelProvider)}.
 */
public interface TranslationModelProvider {

	interface Listener {
		/**
		 * Called when the state of any model changes (including download progress).
		 */
		@UiThread
		void onModelStatusChanged();
	}

	final class ModelStatus {

		public enum State {
			/**
			 * The state is not known yet (e.g. the list of models is being loaded).
			 * Listeners are notified when it becomes known.
			 */
			UNKNOWN,

			/**
			 * No model exists for this language pair.
			 */
			NOT_AVAILABLE,

			/**
			 * The model(s) can be downloaded, see {@link #downloadBytes}.
			 */
			DOWNLOADABLE,

			/**
			 * The model(s) are being downloaded, see {@link #progress}.
			 */
			DOWNLOADING,

			INSTALLED
		}

		@NonNull public final State state;

		/**
		 * The approximate number of bytes which need to be downloaded, or -1 if unknown.
		 */
		public final long downloadBytes;

		/**
		 * Download progress, from 0 to 1.
		 */
		public final float progress;

		public ModelStatus(
				@NonNull final State state,
				final long downloadBytes,
				final float progress) {

			this.state = state;
			this.downloadBytes = downloadBytes;
			this.progress = progress;
		}
	}

	/**
	 * Returns the state of the model(s) needed to translate from {@code from} to
	 * {@code to}. This never blocks: if the state isn't known yet, it's loaded in the
	 * background, and listeners are notified when it's available.
	 */
	@UiThread
	@NonNull
	ModelStatus getStatus(@NonNull String from, @NonNull String to);

	/**
	 * Downloads the model(s) needed to translate from {@code from} to {@code to}, in
	 * response to a user action. May ask the user for confirmation first (e.g. on a
	 * metered connection), so {@code context} should be an Activity.
	 */
	@UiThread
	void requestDownload(@NonNull Context context, @NonNull String from, @NonNull String to);

	/**
	 * Listeners are held using weak references, so the caller must keep a reference
	 * to the listener for as long as it should be notified.
	 */
	@UiThread
	void addListener(@NonNull Listener listener);

	@UiThread
	void removeListener(@NonNull Listener listener);
}
