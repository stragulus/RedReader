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

package org.quantumbadger.redreader.translation

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import org.quantumbadger.redreader.common.CachedThreadPool
import org.quantumbadger.redreader.translate.LanguageDetector
import org.quantumbadger.redreader.translate.TranslationEngine
import java.io.IOException

/**
 * A [TranslationBackend] using the native Bergamot translation engine and the CLD2 language
 * detector (from the redreader-translate module), with models from Mozilla, managed by
 * [TranslationModelManager].
 *
 * The native library is loaded lazily, the first time it's needed (always on a background
 * thread), so creating this object is cheap.
 *
 * Loaded engines (~31 MB of memory per model) are cached: at most [MAX_ENGINES] are kept, and
 * they're all released when the app goes into the background or memory is low.
 */
class BergamotTranslationBackend(
	context: Context,
	private val models: TranslationModelManager,
) : TranslationBackend, ComponentCallbacks2 {

	companion object {
		private const val TAG = "BergamotBackend"
		private const val MAX_ENGINES = 2
	}

	private class EngineHolder(val key: String, val pairKeys: Set<String>) {

		// Guarded by loadLock
		var engine: TranslationEngine? = null
		val loadLock = Any()

		// Guarded by the backend's cacheLock
		var refCount = 0
		var evicted = false

		fun close() {
			synchronized(loadLock) {
				engine?.close()
				engine = null
			}
		}
	}

	private val cacheLock = Any()

	// Guarded by cacheLock. In access order, so the eldest entry is the least recently used.
	private val engines = LinkedHashMap<String, EngineHolder>(4, 0.75f, true)

	private val releaseThread = CachedThreadPool(1, "TranslationEngineRelease")

	@Volatile
	private var nativeLibraryMissing = false

	init {
		context.applicationContext.registerComponentCallbacks(this)
		models.onModelsRemoved = { pairKeys ->
			closeAll(evict { holder -> holder.pairKeys.any { it in pairKeys } })
		}
	}

	override fun detectLanguage(text: String): DetectedLanguage? {

		// TranslationDecider makes the final decision, but pass it the same thresholds so that
		// CLD2's "reliable" flag is consistent with the decider's policy
		if (nativeLibraryMissing) {
			return null
		}

		val result = try {
			LanguageDetector.detect(
				text,
				isHtml = false,
				minLetters = TranslationDecider.MIN_LETTERS_FOR_AUTO_TRANSLATION,
				minConfidence = TranslationDecider.MIN_CONFIDENCE_FOR_AUTO_TRANSLATION,
			)
		} catch (e: UnsatisfiedLinkError) {
			// The native library isn't built for this device's ABI
			Log.e(TAG, "Native translation library not available", e)
			nativeLibraryMissing = true
			return null
		}

		if (result.languageCode == LanguageDetector.UNKNOWN) {
			return null
		}

		return DetectedLanguage(result.languageCode, result.confidence, result.isReliable)
	}

	override fun isModelAvailable(from: String, to: String): Boolean {
		return models.resolveInstalled(from, to) != null
	}

	override fun translate(
		from: String,
		to: String,
		texts: List<String>,
		isHtml: Boolean
	): List<String> {

		if (texts.isEmpty()) {
			return emptyList()
		}

		// TranslationLanguages treats Traditional and Simplified Chinese as one language, but
		// Mozilla has separate models for them
		val traditionalChinese = from == "zh" && LanguageDetector.detect(
			texts.joinToString("\n"),
			isHtml,
			minLetters = 0,
			minConfidence = 0f
		).languageCode == "zh-Hant"

		val plan = models.resolveInstalled(from, to, traditionalChinese)
			?: throw IOException("No model installed for $from -> $to")

		val holder = acquire(plan.map { it.pairKey })

		try {
			val engine = synchronized(holder.loadLock) {
				holder.engine ?: run {
					val start = System.currentTimeMillis()
					TranslationEngine(plan.map { it.files }).also {
						holder.engine = it
						Log.i(
							TAG,
							"Loaded engine ${holder.key} in ${System.currentTimeMillis() - start} ms"
						)
					}
				}
			}

			return engine.translate(texts, isHtml)

		} finally {
			release(holder)
		}
	}

	private fun acquire(pairKeys: List<String>): EngineHolder {

		val key = pairKeys.joinToString(">")
		val toClose: List<EngineHolder>
		val holder: EngineHolder

		synchronized(cacheLock) {
			holder = engines.getOrPut(key) { EngineHolder(key, pairKeys.toSet()) }
			holder.refCount++

			val excess = engines.size - MAX_ENGINES
			toClose = if (excess > 0) {
				val eldest = engines.values.filter { it !== holder }.take(excess).toSet()
				evictLocked { it in eldest }
			} else {
				emptyList()
			}
		}

		closeAll(toClose)
		return holder
	}

	private fun release(holder: EngineHolder) {

		val close = synchronized(cacheLock) {
			holder.refCount--
			holder.evicted && holder.refCount == 0
		}

		if (close) {
			holder.close()
		}
	}

	/**
	 * Removes matching engines from the cache. Returns those which can be closed now: the
	 * others are closed when they're released.
	 */
	private fun evict(predicate: (EngineHolder) -> Boolean): List<EngineHolder> =
		synchronized(cacheLock) { evictLocked(predicate) }

	private fun evictLocked(predicate: (EngineHolder) -> Boolean): List<EngineHolder> {

		val result = ArrayList<EngineHolder>()
		val iterator = engines.values.iterator()

		while (iterator.hasNext()) {
			val holder = iterator.next()
			if (predicate(holder)) {
				iterator.remove()
				holder.evicted = true
				if (holder.refCount == 0) {
					result.add(holder)
				}
				Log.i(TAG, "Evicting engine ${holder.key}")
			}
		}

		return result
	}

	private fun closeAll(holders: List<EngineHolder>) {
		holders.forEach { it.close() }
	}

	/**
	 * Releases all loaded engines (in the background).
	 */
	fun releaseAll() {
		val holders = evict { true }
		if (holders.isNotEmpty()) {
			releaseThread.add { closeAll(holders) }
		}
	}

	override fun onTrimMemory(level: Int) {
		if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
			releaseAll()
		}
	}

	@Deprecated("Deprecated in Java")
	override fun onLowMemory() {
		releaseAll()
	}

	override fun onConfigurationChanged(newConfig: Configuration) {}
}
