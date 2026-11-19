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

import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.core.os.ConfigurationCompat;

import org.quantumbadger.redreader.BuildConfig;
import org.quantumbadger.redreader.common.AndroidCommon;
import org.quantumbadger.redreader.common.CachedThreadPool;
import org.quantumbadger.redreader.common.PrefsUtility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Coordinates language detection and translation.
 *
 * Translation is performed by a {@link TranslationBackend}, which must be registered
 * using {@link #setBackend(TranslationBackend)}. Until a backend is registered, no
 * text is ever translated, and the translation UI is hidden.
 */
public final class TranslationManager {

	private static final String TAG = "TranslationManager";

	private static final int THREADS = 2;
	private static final int DETECTION_CACHE_SIZE = 2000;
	private static final int TRANSLATION_CACHE_SIZE = 500;

	private static final String DEBUG_BACKEND_CLASS
			= "org.quantumbadger.redreader.translation.FakeTranslationBackend";

	private static final TranslationManager INSTANCE = new TranslationManager();

	private final CachedThreadPool mThreadPool = new CachedThreadPool(THREADS, "Translation");

	// All of the fields below are guarded by mLock
	private final Object mLock = new Object();

	@Nullable private TranslationBackend mBackend;
	@Nullable private TranslationBackend mRealBackend;
	@Nullable private TranslationModelProvider mModelProvider;
	private boolean mDebugBackendInstalled;
	private int mModelsGeneration;

	private final LruMap<TextKey, DetectionInfo> mDetectionCache
			= new LruMap<>(DETECTION_CACHE_SIZE);

	private final LruMap<TranslationKey, String> mTranslationCache
			= new LruMap<>(TRANSLATION_CACHE_SIZE);

	private final HashMap<RequestKey, ArrayList<Callback>> mInFlight = new HashMap<>();

	private final HashSet<LanguagePair> mMissingModels = new HashSet<>();

	@Nullable private ModelMissingListener mModelMissingListener;

	public interface Callback {
		@UiThread
		void onTranslationResult(@NonNull TranslationResult result);
	}

	public interface ModelMissingListener {
		/**
		 * Called when text should have been translated from {@code from} to {@code to},
		 * but the backend reported that the model for that pair is not available.
		 */
		@UiThread
		void onModelMissing(@NonNull LanguagePair pair);
	}

	public static final class LanguagePair {

		@NonNull public final String from;
		@NonNull public final String to;

		public LanguagePair(@NonNull final String from, @NonNull final String to) {
			this.from = from;
			this.to = to;
		}

		@Override
		public boolean equals(final Object o) {
			if(this == o) {
				return true;
			}
			if(!(o instanceof LanguagePair)) {
				return false;
			}
			final LanguagePair other = (LanguagePair)o;
			return from.equals(other.from) && to.equals(other.to);
		}

		@Override
		public int hashCode() {
			return from.hashCode() * 31 + to.hashCode();
		}

		@NonNull
		@Override
		public String toString() {
			return from + " -> " + to;
		}
	}

	private TranslationManager() {}

	@NonNull
	public static TranslationManager getInstance() {
		return INSTANCE;
	}

	/**
	 * Called on app startup.
	 */
	@UiThread
	public static void onAppCreate() {
		updateDebugBackend();
	}

	/**
	 * Registers the translation backend, or removes it if null. Clears all cached
	 * results. In debug builds, the fake backend (if enabled using the developer
	 * preference) takes precedence over this one.
	 */
	@AnyThread
	public static void setBackend(@Nullable final TranslationBackend backend) {
		synchronized(INSTANCE.mLock) {
			INSTANCE.mRealBackend = backend;

			if(!INSTANCE.mDebugBackendInstalled) {
				INSTANCE.setBackendInternal(backend, false);
			}
		}
	}

	/**
	 * Registers the provider used to download missing language models for the backend
	 * registered using {@link #setBackend(TranslationBackend)}.
	 */
	@AnyThread
	public static void setModelProvider(@Nullable final TranslationModelProvider provider) {
		synchronized(INSTANCE.mLock) {
			INSTANCE.mModelProvider = provider;
		}
	}

	/**
	 * @return The model provider for the current backend, or null if there is none (e.g.
	 * when the fake debug backend is in use).
	 */
	@Nullable
	@AnyThread
	public TranslationModelProvider getModelProvider() {
		synchronized(mLock) {
			if(mDebugBackendInstalled || mBackend == null) {
				return null;
			}
			return mModelProvider;
		}
	}

	private void setBackendInternal(
			@Nullable final TranslationBackend backend,
			final boolean isDebugBackend) {

		synchronized(mLock) {
			mBackend = backend;
			mDebugBackendInstalled = isDebugBackend && backend != null;
			mDetectionCache.clear();
			mTranslationCache.clear();
			mMissingModels.clear();
		}

		Log.i(TAG, "Backend set: " + (backend == null ? "none" : backend.getClass().getName()));
	}

	/**
	 * In debug builds, installs or removes the fake backend (from the debug source
	 * set) depending on the developer preference.
	 */
	@AnyThread
	public static void updateDebugBackend() {

		if(!BuildConfig.DEBUG) {
			return;
		}

		final boolean enabled = PrefsUtility.pref_translation_debug_fake_backend();

		synchronized(INSTANCE.mLock) {

			if(enabled == INSTANCE.mDebugBackendInstalled) {
				return;
			}

			if(!enabled) {
				INSTANCE.setBackendInternal(INSTANCE.mRealBackend, false);
				return;
			}

			try {
				final TranslationBackend backend = (TranslationBackend)Class
						.forName(DEBUG_BACKEND_CLASS)
						.getDeclaredConstructor()
						.newInstance();

				INSTANCE.setBackendInternal(backend, true);

			} catch(final Exception e) {
				Log.e(TAG, "Failed to install debug backend", e);
			}
		}
	}

	@AnyThread
	public boolean isBackendAvailable() {
		synchronized(mLock) {
			return mBackend != null;
		}
	}

	/**
	 * Called when language models have been installed or removed. Previously
	 * missing models will be checked again.
	 */
	@AnyThread
	public void onModelsChanged() {
		synchronized(mLock) {
			mMissingModels.clear();
			mModelsGeneration++;
		}
	}

	/**
	 * @return A number which changes whenever {@link #onModelsChanged()} is called.
	 */
	@AnyThread
	public int getModelsGeneration() {
		synchronized(mLock) {
			return mModelsGeneration;
		}
	}

	@AnyThread
	public void setModelMissingListener(@Nullable final ModelMissingListener listener) {
		synchronized(mLock) {
			mModelMissingListener = listener;
		}
	}

	/**
	 * @return The language pairs which were needed for translation since the last call
	 * to {@link #onModelsChanged()}, but which were not available.
	 */
	@NonNull
	@AnyThread
	public Set<LanguagePair> getMissingModelPairs() {
		synchronized(mLock) {
			return Collections.unmodifiableSet(new HashSet<>(mMissingModels));
		}
	}

	@NonNull
	@AnyThread
	public static TranslationSettings getSettings() {

		final Locale systemLocale = ConfigurationCompat.getLocales(
				Resources.getSystem().getConfiguration()).get(0);

		final String target = TranslationLanguages.resolveTargetLanguage(
				PrefsUtility.pref_translation_primary_language(),
				PrefsUtility.pref_appearance_langforce(),
				systemLocale != null ? systemLocale : Locale.getDefault());

		return new TranslationSettings(
				PrefsUtility.pref_translation_enabled(),
				target,
				TranslationLanguages.normalizeAll(
						PrefsUtility.pref_translation_secondary_languages()),
				PrefsUtility.pref_translation_content());
	}

	/**
	 * Returns the result immediately if it's already known, without performing any
	 * expensive work. Otherwise, returns null.
	 */
	@Nullable
	@AnyThread
	public TranslationResult getCachedResult(
			@NonNull final String text,
			final boolean isHtml,
			final boolean userRequested,
			@NonNull final TranslationSettings settings) {

		synchronized(mLock) {

			if(mBackend == null) {
				return null;
			}

			final DetectionInfo detection = mDetectionCache.get(new TextKey(text, isHtml));

			if(detection == null || (userRequested && !detection.detectionPerformed)) {
				return null;
			}

			final TranslationDecider.Decision decision = TranslationDecider.decide(
					detection.detected,
					detection.letterCount,
					settings,
					userRequested);

			if(!decision.shouldTranslate()) {
				return new TranslationResult(
						TranslationResult.Status.NOT_TRANSLATED,
						decision,
						decision.sourceLanguage,
						settings.targetLanguage,
						null);
			}

			final String translated = mTranslationCache.get(new TranslationKey(
					text,
					isHtml,
					Objects.requireNonNull(decision.sourceLanguage),
					settings.targetLanguage));

			if(translated == null) {
				return null;
			}

			return new TranslationResult(
					TranslationResult.Status.TRANSLATED,
					decision,
					decision.sourceLanguage,
					settings.targetLanguage,
					translated);
		}
	}

	/**
	 * Detects the language of the text and, if appropriate, translates it, in the
	 * background. The callback is always invoked later on the UI thread, never
	 * synchronously.
	 *
	 * @param userRequested True if the user explicitly asked for this text to be
	 *                      translated (in which case secondary languages and
	 *                      detection confidence are ignored).
	 */
	@UiThread
	public void translate(
			@NonNull final String text,
			final boolean isHtml,
			final boolean userRequested,
			@NonNull final TranslationSettings settings,
			@NonNull final Callback callback) {

		final TranslationBackend backend;

		synchronized(mLock) {
			backend = mBackend;
		}

		if(backend == null) {
			final TranslationResult result = new TranslationResult(
					TranslationResult.Status.NO_BACKEND,
					null,
					null,
					settings.targetLanguage,
					null);

			AndroidCommon.UI_THREAD_HANDLER.post(() -> callback.onTranslationResult(result));
			return;
		}

		final RequestKey requestKey = new RequestKey(
				text,
				isHtml,
				userRequested,
				settings.targetLanguage);

		synchronized(mLock) {

			final ArrayList<Callback> existing = mInFlight.get(requestKey);

			if(existing != null) {
				existing.add(callback);
				return;
			}

			final ArrayList<Callback> callbacks = new ArrayList<>(1);
			callbacks.add(callback);
			mInFlight.put(requestKey, callbacks);
		}

		mThreadPool.add(() -> {

			TranslationResult result;

			try {
				result = process(backend, text, isHtml, userRequested, settings);

			} catch(final Exception | LinkageError e) {
				Log.e(TAG, "Translation failed", e);

				result = new TranslationResult(
						TranslationResult.Status.FAILED,
						null,
						null,
						settings.targetLanguage,
						null);
			}

			final ArrayList<Callback> callbacks;

			synchronized(mLock) {
				callbacks = mInFlight.remove(requestKey);
			}

			if(callbacks == null) {
				return;
			}

			final TranslationResult finalResult = result;

			AndroidCommon.UI_THREAD_HANDLER.post(() -> {
				for(final Callback cb : callbacks) {
					cb.onTranslationResult(finalResult);
				}
			});
		});
	}

	@WorkerThread
	@NonNull
	private TranslationResult process(
			@NonNull final TranslationBackend backend,
			@NonNull final String text,
			final boolean isHtml,
			final boolean userRequested,
			@NonNull final TranslationSettings settings) throws Exception {

		final TextKey textKey = new TextKey(text, isHtml);

		DetectionInfo detection;

		synchronized(mLock) {
			detection = mDetectionCache.get(textKey);
		}

		if(detection == null || (userRequested && !detection.detectionPerformed)) {

			final String detectionText = TranslationDecider.extractTextForDetection(text, isHtml);
			final int letterCount = TranslationDecider.countLetters(detectionText);

			// Don't waste time detecting the language of text that's too short for
			// automatic translation, unless the user explicitly asked for it
			final boolean shouldDetect = letterCount > 0 && (userRequested
					|| letterCount >= TranslationDecider.MIN_LETTERS_FOR_AUTO_TRANSLATION);

			final DetectedLanguage detected = shouldDetect
					? backend.detectLanguage(detectionText)
					: null;

			detection = new DetectionInfo(detected, letterCount, shouldDetect);

			synchronized(mLock) {
				mDetectionCache.put(textKey, detection);
			}
		}

		final TranslationDecider.Decision decision = TranslationDecider.decide(
				detection.detected,
				detection.letterCount,
				settings,
				userRequested);

		if(BuildConfig.DEBUG) {
			Log.d(TAG, "Detected " + detection.detected + ", decision " + decision);
		}

		if(!decision.shouldTranslate()) {
			return new TranslationResult(
					TranslationResult.Status.NOT_TRANSLATED,
					decision,
					decision.sourceLanguage,
					settings.targetLanguage,
					null);
		}

		final String from = Objects.requireNonNull(decision.sourceLanguage);
		final String to = settings.targetLanguage;

		final TranslationKey translationKey = new TranslationKey(text, isHtml, from, to);

		synchronized(mLock) {
			final String cached = mTranslationCache.get(translationKey);

			if(cached != null) {
				return new TranslationResult(
						TranslationResult.Status.TRANSLATED,
						decision,
						from,
						to,
						cached);
			}
		}

		if(!backend.isModelAvailable(from, to)) {

			final LanguagePair pair = new LanguagePair(from, to);
			final ModelMissingListener listener;
			final boolean isNew;

			synchronized(mLock) {
				isNew = mMissingModels.add(pair);
				listener = mModelMissingListener;
			}

			if(isNew) {
				Log.i(TAG, "Model missing for " + pair);

				if(listener != null) {
					AndroidCommon.UI_THREAD_HANDLER.post(() -> listener.onModelMissing(pair));
				}
			}

			return new TranslationResult(
					TranslationResult.Status.MODEL_MISSING,
					decision,
					from,
					to,
					null);
		}

		final String translated = backend.translate(
				from,
				to,
				Collections.singletonList(text),
				isHtml).get(0);

		synchronized(mLock) {
			mTranslationCache.put(translationKey, translated);
		}

		return new TranslationResult(
				TranslationResult.Status.TRANSLATED,
				decision,
				from,
				to,
				translated);
	}

	private static final class LruMap<K, V> extends LinkedHashMap<K, V> {

		private static final long serialVersionUID = 1L;

		private final int mMaxEntries;

		LruMap(final int maxEntries) {
			super(16, 0.75f, true);
			mMaxEntries = maxEntries;
		}

		@Override
		protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
			return size() > mMaxEntries;
		}
	}

	private static final class DetectionInfo {

		@Nullable final DetectedLanguage detected;
		final int letterCount;
		final boolean detectionPerformed;

		DetectionInfo(
				@Nullable final DetectedLanguage detected,
				final int letterCount,
				final boolean detectionPerformed) {

			this.detected = detected;
			this.letterCount = letterCount;
			this.detectionPerformed = detectionPerformed;
		}
	}

	private static final class TextKey {

		@NonNull private final String mText;
		private final boolean mIsHtml;

		TextKey(@NonNull final String text, final boolean isHtml) {
			mText = text;
			mIsHtml = isHtml;
		}

		@Override
		public boolean equals(final Object o) {
			if(this == o) {
				return true;
			}
			if(!(o instanceof TextKey)) {
				return false;
			}
			final TextKey other = (TextKey)o;
			return mIsHtml == other.mIsHtml && mText.equals(other.mText);
		}

		@Override
		public int hashCode() {
			return mText.hashCode() * 2 + (mIsHtml ? 1 : 0);
		}
	}

	private static final class TranslationKey {

		@NonNull private final String mText;
		private final boolean mIsHtml;
		@NonNull private final String mFrom;
		@NonNull private final String mTo;

		TranslationKey(
				@NonNull final String text,
				final boolean isHtml,
				@NonNull final String from,
				@NonNull final String to) {

			mText = text;
			mIsHtml = isHtml;
			mFrom = from;
			mTo = to;
		}

		@Override
		public boolean equals(final Object o) {
			if(this == o) {
				return true;
			}
			if(!(o instanceof TranslationKey)) {
				return false;
			}
			final TranslationKey other = (TranslationKey)o;
			return mIsHtml == other.mIsHtml
					&& mText.equals(other.mText)
					&& mFrom.equals(other.mFrom)
					&& mTo.equals(other.mTo);
		}

		@Override
		public int hashCode() {
			return Objects.hash(mText, mIsHtml, mFrom, mTo);
		}
	}

	private static final class RequestKey {

		@NonNull private final String mText;
		private final boolean mIsHtml;
		private final boolean mUserRequested;
		@NonNull private final String mTo;

		RequestKey(
				@NonNull final String text,
				final boolean isHtml,
				final boolean userRequested,
				@NonNull final String to) {

			mText = text;
			mIsHtml = isHtml;
			mUserRequested = userRequested;
			mTo = to;
		}

		@Override
		public boolean equals(final Object o) {
			if(this == o) {
				return true;
			}
			if(!(o instanceof RequestKey)) {
				return false;
			}
			final RequestKey other = (RequestKey)o;
			return mIsHtml == other.mIsHtml
					&& mUserRequested == other.mUserRequested
					&& mText.equals(other.mText)
					&& mTo.equals(other.mTo);
		}

		@Override
		public int hashCode() {
			return Objects.hash(mText, mIsHtml, mUserRequested, mTo);
		}
	}
}
