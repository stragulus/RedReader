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
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.format.Formatter;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.os.ConfigurationCompat;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.common.General;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/**
 * The translation state of one piece of displayed text (a post title, a self post
 * body, or a comment body). Only accessed on the UI thread.
 */
@UiThread
public final class TranslatableText {

	public enum State {
		/**
		 * No translation has been attempted yet.
		 */
		IDLE,
		PENDING,
		TRANSLATED,

		/**
		 * Translation was attempted, but the text wasn't translated (because it was
		 * already in an understood language, the language couldn't be determined, the
		 * model was missing, or translation failed).
		 */
		NOT_TRANSLATED
	}

	public interface Listener {
		@UiThread
		void onTranslationStateChanged(@NonNull TranslatableText text);
	}

	@NonNull private final String mOriginal;
	private final boolean mIsHtml;
	@NonNull private final TranslationContentType mType;

	@NonNull private State mState = State.IDLE;
	@Nullable private TranslationResult mResult;
	private boolean mUserRequested;
	private boolean mShowingOriginal;

	/**
	 * The value of {@link TranslationManager#getModelsGeneration()} when the model
	 * was found to be missing.
	 */
	private int mModelsGeneration;

	@NonNull private final ArrayList<Listener> mListeners = new ArrayList<>(1);

	public TranslatableText(
			@NonNull final String original,
			final boolean isHtml,
			@NonNull final TranslationContentType type) {

		mOriginal = original;
		mIsHtml = isHtml;
		mType = type;
	}

	public void addListener(@NonNull final Listener listener) {
		if(!mListeners.contains(listener)) {
			mListeners.add(listener);
		}
	}

	public void removeListener(@NonNull final Listener listener) {
		mListeners.remove(listener);
	}

	@NonNull
	public State getState() {
		return mState;
	}

	@Nullable
	public TranslationResult getResult() {
		return mResult;
	}

	public boolean isTranslated() {
		return mState == State.TRANSLATED;
	}

	/**
	 * @return True if the translation (rather than the original) should be displayed.
	 */
	public boolean isShowingTranslation() {
		return mState == State.TRANSLATED && !mShowingOriginal;
	}

	@NonNull
	public String getOriginal() {
		return mOriginal;
	}

	/**
	 * @return The translated text, or null if not translated.
	 */
	@Nullable
	public String getTranslated() {
		return mResult == null ? null : mResult.translatedText;
	}

	/**
	 * @return The text which should currently be displayed.
	 */
	@NonNull
	public String getDisplayText() {

		final String translated = getTranslated();

		if(isShowingTranslation() && translated != null) {
			return translated;
		}

		return mOriginal;
	}

	/**
	 * Starts automatic translation if the user's preferences allow it and it hasn't
	 * been attempted yet. If the result is already cached, the state is updated
	 * synchronously, without notifying listeners, so callers should invoke this before
	 * rendering the text.
	 */
	public void requestAutoTranslation() {

		if(retryIfModelsChanged()) {
			return;
		}

		if(mState != State.IDLE || mOriginal.isEmpty()) {
			return;
		}

		final TranslationManager manager = TranslationManager.getInstance();

		if(!manager.isBackendAvailable()) {
			return;
		}

		final TranslationSettings settings = TranslationManager.getSettings();

		if(!settings.shouldAutoTranslate(mType)) {
			return;
		}

		start(manager, settings, false, null);
	}

	/**
	 * @return True if this text wasn't translated because the language model was
	 * missing.
	 */
	public boolean isModelMissing() {
		return mState == State.NOT_TRANSLATED
				&& mResult != null
				&& mResult.status == TranslationResult.Status.MODEL_MISSING
				&& mResult.sourceLanguage != null;
	}

	/**
	 * If this text wasn't translated because the language model was missing, and
	 * models have been installed since, tries again. Listeners are not notified if the
	 * result is available immediately.
	 *
	 * @return True if translation was attempted again.
	 */
	public boolean retryIfModelsChanged() {

		if(!isModelMissing()) {
			return false;
		}

		final TranslationManager manager = TranslationManager.getInstance();

		if(manager.getModelsGeneration() == mModelsGeneration
				|| !manager.isBackendAvailable()) {
			return false;
		}

		final TranslationSettings settings = TranslationManager.getSettings();

		mState = State.IDLE;
		mResult = null;

		if(mUserRequested || settings.shouldAutoTranslate(mType)) {
			start(manager, settings, mUserRequested, null);
		}

		return true;
	}

	/**
	 * Downloads the language model needed to translate this text, if it's missing. The
	 * text is translated once the download completes.
	 */
	public void requestModelDownload(@NonNull final Context context) {

		final TranslationModelProvider provider
				= TranslationManager.getInstance().getModelProvider();

		if(!isModelMissing() || provider == null || mResult == null) {
			return;
		}

		provider.requestDownload(
				context,
				Objects.requireNonNull(mResult.sourceLanguage),
				mResult.targetLanguage);
	}

	/**
	 * @return The state of the missing model needed to translate this text, or null if
	 * no model is missing.
	 */
	@Nullable
	public TranslationModelProvider.ModelStatus getMissingModelStatus() {

		final TranslationModelProvider provider
				= TranslationManager.getInstance().getModelProvider();

		if(!isModelMissing() || provider == null || mResult == null) {
			return null;
		}

		return provider.getStatus(
				Objects.requireNonNull(mResult.sourceLanguage),
				mResult.targetLanguage);
	}

	/**
	 * Notifies listeners that the state of this text changed (for example, because
	 * {@link #retryIfModelsChanged()} started a new attempt).
	 */
	public void notifyStateChanged() {
		notifyListeners();
	}

	/**
	 * Handles the user explicitly asking for this text to be translated (or for the
	 * translation to be shown, if it's currently showing the original).
	 */
	public void requestUserTranslation(@NonNull final Context context) {
		requestUserTranslation(context, true);
	}

	/**
	 * Handles the user explicitly asking for this text to be translated (or for the
	 * translation to be shown, if it's currently showing the original).
	 *
	 * @param showFailureMessage If true, a toast is shown if the text isn't translated.
	 */
	public void requestUserTranslation(
			@NonNull final Context context,
			final boolean showFailureMessage) {

		if(mState == State.TRANSLATED) {
			if(mShowingOriginal) {
				mShowingOriginal = false;
				notifyListeners();
			}
			return;
		}

		if(mState == State.PENDING || mOriginal.isEmpty()) {
			return;
		}

		final TranslationManager manager = TranslationManager.getInstance();

		if(!manager.isBackendAvailable()) {
			General.quickToast(context, R.string.translation_unavailable);
			return;
		}

		start(
				manager,
				TranslationManager.getSettings(),
				true,
				showFailureMessage ? context.getApplicationContext() : null);
		notifyListeners();
	}

	/**
	 * Switches between showing the original and the translation.
	 */
	public void setShowingOriginal(final boolean showingOriginal) {

		if(mState != State.TRANSLATED || mShowingOriginal == showingOriginal) {
			return;
		}

		mShowingOriginal = showingOriginal;
		notifyListeners();
	}

	public void toggleShowingOriginal() {
		setShowingOriginal(!mShowingOriginal);
	}

	private void start(
			@NonNull final TranslationManager manager,
			@NonNull final TranslationSettings settings,
			final boolean userRequested,
			@Nullable final Context appContext) {

		mUserRequested = userRequested;

		// Read this before starting, so that if a model is installed while the request
		// is in progress, the text is retried later rather than staying untranslated
		final int modelsGeneration = manager.getModelsGeneration();

		final TranslationResult cached
				= manager.getCachedResult(mOriginal, mIsHtml, userRequested, settings);

		if(cached != null) {
			applyResult(cached, modelsGeneration, appContext);
			return;
		}

		mState = State.PENDING;

		manager.translate(mOriginal, mIsHtml, userRequested, settings, result -> {
			applyResult(result, modelsGeneration, appContext);
			notifyListeners();
		});
	}

	private void applyResult(
			@NonNull final TranslationResult result,
			final int modelsGeneration,
			@Nullable final Context appContext) {

		mResult = result;

		if(result.status == TranslationResult.Status.TRANSLATED
				&& result.translatedText != null) {

			mState = State.TRANSLATED;
			mShowingOriginal = false;

		} else {
			mState = State.NOT_TRANSLATED;

			if(result.status == TranslationResult.Status.MODEL_MISSING) {
				mModelsGeneration = modelsGeneration;
			}

			if(appContext != null) {
				General.quickToast(appContext, describeFailure(appContext, result));
			}
		}
	}

	@NonNull
	private static String describeFailure(
			@NonNull final Context context,
			@NonNull final TranslationResult result) {

		final Locale locale = getDisplayLocale(context);

		switch(result.status) {

			case NO_BACKEND:
				return context.getString(R.string.translation_unavailable);

			case MODEL_MISSING:
				return context.getString(
						R.string.translation_model_missing,
						languageName(result.sourceLanguage, locale),
						languageName(result.targetLanguage, locale));

			case FAILED:
				return context.getString(R.string.translation_failed);

			case NOT_TRANSLATED:
			case TRANSLATED:
			default:
				if(result.decision != null && result.decision.outcome
						== TranslationDecider.Outcome.ALREADY_TARGET_LANGUAGE) {

					return context.getString(
							R.string.translation_already_in_language,
							languageName(result.targetLanguage, locale));
				}

				return context.getString(R.string.translation_language_unknown);
		}
	}

	/**
	 * @return The text of the indicator which should be shown alongside this text,
	 * or null if no indicator should be shown.
	 */
	@Nullable
	public CharSequence getIndicatorText(@NonNull final Context context) {

		if(mState == State.PENDING && mUserRequested) {
			return context.getString(R.string.translation_in_progress);
		}

		if(isModelMissing()) {
			return getModelMissingIndicatorText(context);
		}

		if(mState != State.TRANSLATED || mResult == null) {
			return null;
		}

		final String language = languageName(mResult.sourceLanguage, getDisplayLocale(context));

		final String description;
		final String action;

		if(mShowingOriginal) {
			description = context.getString(R.string.translation_indicator_original, language);
			action = context.getString(R.string.action_translation_show_translation);
		} else {
			description = context.getString(R.string.translation_indicator_translated, language);
			action = context.getString(R.string.action_translation_show_original);
		}

		return withAction(description, action);
	}

	@Nullable
	private CharSequence getModelMissingIndicatorText(@NonNull final Context context) {

		final TranslationModelProvider.ModelStatus status = getMissingModelStatus();

		if(status == null || mResult == null) {
			return null;
		}

		final String language = languageName(mResult.sourceLanguage, getDisplayLocale(context));

		switch(status.state) {

			case DOWNLOADABLE: {
				final String action = status.downloadBytes > 0
						? context.getString(
								R.string.translation_action_download_model_size,
								Formatter.formatShortFileSize(context, status.downloadBytes))
						: context.getString(R.string.translation_action_download_model);

				return withAction(language, action);
			}

			case DOWNLOADING:
				return language + " \u00B7 " + context.getString(
						R.string.translation_model_downloading,
						Math.round(status.progress * 100));

			case UNKNOWN:
			case NOT_AVAILABLE:
			case INSTALLED:
			default:
				return null;
		}
	}

	@NonNull
	private static CharSequence withAction(
			@NonNull final String description,
			@NonNull final String action) {

		final SpannableStringBuilder result = new SpannableStringBuilder(description);
		result.append(" \u00B7 ");

		final int actionStart = result.length();
		result.append(action);
		result.setSpan(
				new StyleSpan(Typeface.BOLD),
				actionStart,
				result.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

		return result;
	}

	private void notifyListeners() {
		for(final Listener listener : new ArrayList<>(mListeners)) {
			listener.onTranslationStateChanged(this);
		}
	}

	@NonNull
	private static Locale getDisplayLocale(@NonNull final Context context) {
		final Locale locale = ConfigurationCompat.getLocales(
				context.getResources().getConfiguration()).get(0);
		return locale != null ? locale : Locale.getDefault();
	}

	@NonNull
	private static String languageName(
			@Nullable final String code,
			@NonNull final Locale displayLocale) {

		if(code == null) {
			return "?";
		}

		return TranslationLanguages.getDisplayName(code, displayLocale);
	}
}
