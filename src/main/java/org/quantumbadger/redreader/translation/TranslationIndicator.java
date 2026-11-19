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
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.common.General;

/**
 * Helpers for the small "Translated from X · Show original" label shown alongside
 * translated text.
 */
@UiThread
public final class TranslationIndicator {

	private TranslationIndicator() {}

	/**
	 * Creates an (initially hidden) indicator view, for layouts built in code.
	 */
	@NonNull
	public static TextView create(
			@NonNull final Context context,
			final int textColor,
			final float textSizeSp) {

		final TextView result = new TextView(context);
		result.setTextColor(textColor);
		result.setTextSize(textSizeSp);
		result.setVisibility(View.GONE);

		final int padding = General.dpToPixels(context, 3);
		result.setPadding(0, padding, 0, padding);

		return result;
	}

	/**
	 * Updates the indicator to reflect the state of the given text. Tapping the
	 * indicator switches between the original and the translation, or downloads the
	 * language model if it's missing.
	 *
	 * @param text The text, or null to hide the indicator.
	 */
	public static void bind(
			@NonNull final TextView indicator,
			@Nullable final TranslatableText text) {

		final ModelListener modelListener = getModelListener(indicator);
		modelListener.mText = text;

		final TranslationModelProvider provider
				= TranslationManager.getInstance().getModelProvider();

		if(provider != null) {
			if(text != null && text.isModelMissing()) {
				provider.addListener(modelListener);
			} else {
				provider.removeListener(modelListener);
			}
		}

		final CharSequence label = text == null
				? null
				: text.getIndicatorText(indicator.getContext());

		if(label == null) {
			indicator.setVisibility(View.GONE);
			indicator.setOnClickListener(null);
			indicator.setClickable(false);
			return;
		}

		indicator.setText(label);
		indicator.setVisibility(View.VISIBLE);

		if(text.isTranslated()) {
			indicator.setOnClickListener(v -> text.toggleShowingOriginal());

		} else if(text.isModelMissing()) {

			final TranslationModelProvider.ModelStatus status = text.getMissingModelStatus();

			if(status != null
					&& status.state == TranslationModelProvider.ModelStatus.State.DOWNLOADABLE) {

				indicator.setOnClickListener(
						v -> text.requestModelDownload(indicator.getContext()));
			} else {
				indicator.setOnClickListener(null);
				indicator.setClickable(false);
			}

		} else {
			indicator.setOnClickListener(null);
			indicator.setClickable(false);
		}
	}

	@NonNull
	private static ModelListener getModelListener(@NonNull final TextView indicator) {

		final Object tag = indicator.getTag(R.id.translation_indicator_model_listener);

		if(tag instanceof ModelListener) {
			return (ModelListener)tag;
		}

		final ModelListener result = new ModelListener(indicator);
		indicator.setTag(R.id.translation_indicator_model_listener, result);
		return result;
	}

	/**
	 * Keeps the indicator up to date while a missing model is downloaded, and starts
	 * translation when it's installed. Referenced only by the indicator view (model
	 * providers hold listeners weakly), so it lives as long as the view.
	 */
	private static final class ModelListener implements TranslationModelProvider.Listener {

		@NonNull private final TextView mIndicator;
		@Nullable TranslatableText mText;

		ModelListener(@NonNull final TextView indicator) {
			mIndicator = indicator;
		}

		@Override
		public void onModelStatusChanged() {

			final TranslatableText text = mText;

			if(text == null || !text.isModelMissing()) {
				return;
			}

			if(text.retryIfModelsChanged()) {
				// Causes the owning view to update itself, and rebind this indicator
				text.notifyStateChanged();
				return;
			}

			bind(mIndicator, text);
		}
	}
}
