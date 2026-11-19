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

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.ConnectivityManagerCompat
import org.json.JSONObject
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.common.AndroidCommon
import org.quantumbadger.redreader.common.CachedThreadPool
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.FailedRequestBody
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.translate.MozillaModelRegistry
import org.quantumbadger.redreader.translate.MozillaModelRegistry.ModelFile
import org.quantumbadger.redreader.translate.MozillaModelRegistry.ModelRecord
import org.quantumbadger.redreader.translate.TranslationModelFiles
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Downloads, installs, lists and deletes the Mozilla translation models used by
 * [BergamotTranslationBackend].
 *
 * - The model registry (the list of available models) is cached on disk, and refreshed at most
 *   once a day. When offline, the cached copy is used.
 * - Models are stored in `noBackupFilesDir/translation-models/<pair>/`, e.g. `.../es-en/`. They
 *   are downloaded (gzipped) into a temporary directory, decompressed while downloading,
 *   verified, and then moved into place, so a partially downloaded model is never used.
 * - Models are only ever downloaded in response to a user action. If the user only allows
 *   downloads over Wi-Fi, they are asked for confirmation on a metered connection.
 */
class TranslationModelManager private constructor(context: Context) : TranslationModelProvider {

	companion object {

		private const val TAG = "TranslationModels"

		private const val DIR_NAME = "translation-models"
		private const val REGISTRY_FILE = "registry.json"
		private const val MANIFEST_FILE = "manifest.json"
		private const val TEMP_PREFIX = ".tmp-"

		private const val REGISTRY_MAX_AGE_MS = 24L * 60 * 60 * 1000
		private const val REGISTRY_RETRY_AFTER_FAILURE_MS = 5L * 60 * 1000

		private const val PROGRESS_NOTIFY_INTERVAL_MS = 250L

		private val SAFE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")

		@Volatile
		private var instance: TranslationModelManager? = null

		@JvmStatic
		fun getInstance(context: Context): TranslationModelManager {
			instance?.let { return it }
			synchronized(this) {
				return instance ?: TranslationModelManager(context.applicationContext).also {
					instance = it
				}
			}
		}

		/**
		 * The registry only publishes the uncompressed size of the model file. The gzipped
		 * model is ~76% of that, and the vocabulary and lexical shortlist add ~3 MB.
		 */
		fun estimateDownloadBytes(record: ModelRecord): Long {
			val modelSize = record.model.uncompressedSize ?: return 28_000_000L
			return (modelSize * 0.76).toLong() + 3_000_000L
		}

		/**
		 * The display name of a language from the registry, e.g. "Spanish".
		 */
		@JvmStatic
		fun getLanguageDisplayName(
			context: Context,
			registryCode: String,
			locale: Locale
		): String = when (registryCode) {
			BergamotLanguages.SERBO_CROATIAN -> context.getString(R.string.translation_language_hbs)
			BergamotLanguages.CHINESE_TRADITIONAL -> Locale.forLanguageTag("zh-Hant")
				.getDisplayName(locale)
			"zh" -> Locale.forLanguageTag("zh-Hans").getDisplayName(locale)
			else -> TranslationLanguages.getDisplayName(registryCode, locale)
		}
	}

	class InstalledModel(
		val pairKey: String,
		val files: TranslationModelFiles,
		val sizeBytes: Long,
	)

	enum class RegistryState { NOT_LOADED, LOADING, LOADED, FAILED }

	private class Download(val pairKey: String, val estimatedBytes: Long) {
		@Volatile
		var downloadedBytes = 0L

		@Volatile
		var cancelled = false

		@Volatile
		var request: HTTPBackend.Request? = null

		@Volatile
		var lastNotified = 0L

		val progress: Float
			get() = (downloadedBytes.toFloat() / estimatedBytes).coerceIn(0f, 0.99f)
	}

	/**
	 * A row on the "Manage language models" screen: one translation direction.
	 */
	class ModelEntry(
		val pairKey: String,
		val sourceLanguage: String,
		val targetLanguage: String,
		/** Null if installed, but no longer in the registry. */
		val record: ModelRecord?,
		val installed: InstalledModel?,
		/** Download progress (0..1), or null if not downloading. */
		val downloadProgress: Float?,
	) {
		val downloadBytes: Long
			get() = record?.let { estimateDownloadBytes(it) } ?: -1
	}

	private val appContext = context.applicationContext
	// Lazy, as getNoBackupFilesDir() creates the directory, and this object is created on
	// the UI thread at startup (even if translation is never used)
	private val rootDir by lazy { File(ContextCompat.getNoBackupFilesDir(appContext), DIR_NAME) }

	private val ioThreads = CachedThreadPool(1, "TranslationModelIO")
	private val downloadThreads = CachedThreadPool(2, "TranslationModelDownload")

	private val lock = Any()

	// Guarded by lock
	private var installed: Map<String, InstalledModel>? = null
	private var installedLoading = false
	private var registry: Map<String, ModelRecord>? = null
	private var registryFetchedAt = 0L
	private var registryState = RegistryState.NOT_LOADED
	private var registryLastAttempt = 0L
	private var registryError: String? = null
	private val downloads = LinkedHashMap<String, Download>()

	// Only accessed on the UI thread
	private val listeners = ArrayList<WeakReference<TranslationModelProvider.Listener>>()

	/**
	 * Called (on a background thread) before installed models are deleted or replaced, so that
	 * loaded engines using them can be closed.
	 */
	@Volatile
	var onModelsRemoved: ((Set<String>) -> Unit)? = null

	// ---------------------------------------------------------------------------------------
	// Installed models
	// ---------------------------------------------------------------------------------------

	/**
	 * Returns the installed models, keyed by pair key (e.g. "es-en"). Blocks while scanning the
	 * models directory, the first time this is called.
	 */
	@WorkerThread
	fun getInstalledModels(): Map<String, InstalledModel> {

		synchronized(lock) {
			installed?.let { return it }
		}

		val scanned = scanInstalled()

		synchronized(lock) {
			// Another thread may have scanned (or installed a model) in the meantime
			return installed ?: scanned.also { installed = it }
		}
	}

	@WorkerThread
	private fun scanInstalled(): Map<String, InstalledModel> {

		val result = HashMap<String, InstalledModel>()
		val dirs = rootDir.listFiles() ?: return result

		val downloading = synchronized(lock) { downloads.isNotEmpty() }

		for (dir in dirs) {
			if (!dir.isDirectory) {
				continue
			}

			if (dir.name.startsWith(TEMP_PREFIX)) {
				// Left behind by an interrupted download
				if (!downloading) {
					dir.deleteRecursively()
				}
				continue
			}

			val model = readManifest(dir)

			if (model == null) {
				Log.w(TAG, "Deleting invalid model directory ${dir.name}")
				dir.deleteRecursively()
				continue
			}

			result[model.pairKey] = model
		}

		return result
	}

	@WorkerThread
	private fun readManifest(dir: File): InstalledModel? {
		return try {
			val json = JSONObject(File(dir, MANIFEST_FILE).readText())

			fun file(key: String): File? {
				if (json.isNull(key)) {
					return null
				}
				val name = json.getString(key)
				require(SAFE_NAME.matches(name)) { "Bad file name" }
				return File(dir, name).takeIf { it.isFile }
					?: throw IOException("Missing file $name")
			}

			val files = TranslationModelFiles(
				model = file("model")!!,
				srcVocab = file("srcVocab")!!,
				trgVocab = file("trgVocab")!!,
				lexicalShortlist = file("lexicalShortlist"),
			)

			val size = (dir.listFiles() ?: emptyArray()).sumOf { it.length() }

			InstalledModel(json.getString("pair"), files, size)

		} catch (e: Exception) {
			Log.e(TAG, "Failed to read manifest in ${dir.name}", e)
			null
		}
	}

	/**
	 * Starts loading the installed models in the background, if needed.
	 */
	@AnyThread
	private fun ensureInstalledLoaded() {
		synchronized(lock) {
			if (installed != null || installedLoading) {
				return
			}
			installedLoading = true
		}

		ioThreads.add {
			try {
				getInstalledModels()
			} finally {
				synchronized(lock) {
					installedLoading = false
				}
				notifyListeners()
			}
		}
	}

	/**
	 * Deletes an installed model, in the background.
	 */
	@UiThread
	fun delete(pairKey: String) {
		ioThreads.add {
			try {
				getInstalledModels()
				onModelsRemoved?.invoke(setOf(pairKey))

				val dir = File(rootDir, pairKey)

				// Move the directory out of the way first, so a half-deleted model is never
				// seen as installed
				val trash = File(rootDir, TEMP_PREFIX + "delete-" + pairKey)
				trash.deleteRecursively()

				if (dir.renameTo(trash)) {
					trash.deleteRecursively()
				} else {
					dir.deleteRecursively()
				}

				synchronized(lock) {
					installed = (installed ?: emptyMap()) - pairKey
				}

				Log.i(TAG, "Deleted model $pairKey")

			} catch (e: Exception) {
				Log.e(TAG, "Failed to delete model $pairKey", e)
			}

			onModelsChanged()
		}
	}

	// ---------------------------------------------------------------------------------------
	// Registry
	// ---------------------------------------------------------------------------------------

	@AnyThread
	fun getRegistryState(): RegistryState = synchronized(lock) { registryState }

	@AnyThread
	fun getRegistryError(): String? = synchronized(lock) { registryError }

	/**
	 * Loads the registry in the background: from the disk cache if it's recent enough, and
	 * otherwise from the network (falling back to the disk cache if offline).
	 *
	 * @param force If true, retries after a failure even if the last attempt was recent.
	 */
	@AnyThread
	fun ensureRegistryLoaded(force: Boolean) {

		val now = System.currentTimeMillis()

		synchronized(lock) {
			when (registryState) {
				RegistryState.LOADING -> return
				RegistryState.LOADED -> if (now - registryFetchedAt < REGISTRY_MAX_AGE_MS
					|| now - registryLastAttempt < REGISTRY_RETRY_AFTER_FAILURE_MS) {
					return
				}
				RegistryState.FAILED -> if (!force
					&& now - registryLastAttempt < REGISTRY_RETRY_AFTER_FAILURE_MS) {
					return
				}
				RegistryState.NOT_LOADED -> {}
			}

			if (registryState != RegistryState.LOADED) {
				registryState = RegistryState.LOADING
			}
			registryLastAttempt = now
		}

		notifyListeners()
		ioThreads.add { loadRegistry() }
	}

	@WorkerThread
	private fun loadRegistry() {

		val cacheFile = File(rootDir, REGISTRY_FILE)

		var parsed: Map<String, ModelRecord>? = null
		var fetchedAt = 0L

		synchronized(lock) {
			if (registry != null) {
				parsed = registry
				fetchedAt = registryFetchedAt
			}
		}

		if (parsed == null && cacheFile.isFile) {
			try {
				parsed = MozillaModelRegistry.parse(cacheFile.readText())
				fetchedAt = cacheFile.lastModified()
			} catch (e: Exception) {
				Log.e(TAG, "Failed to parse cached registry", e)
			}
		}

		var error: String? = null

		if (parsed == null || System.currentTimeMillis() - fetchedAt >= REGISTRY_MAX_AGE_MS) {
			try {
				val json = fetchString(MozillaModelRegistry.REGISTRY_URL)
				val fresh = MozillaModelRegistry.parse(json)

				rootDir.mkdirs()
				val temp = File(rootDir, "$REGISTRY_FILE.tmp")
				temp.writeText(json)
				if (!temp.renameTo(cacheFile)) {
					throw IOException("Failed to save registry")
				}

				parsed = fresh
				fetchedAt = System.currentTimeMillis()
				Log.i(TAG, "Fetched registry: ${fresh.size} models")

			} catch (e: Exception) {
				Log.w(TAG, "Failed to fetch registry", e)
				error = e.message ?: e.javaClass.simpleName
			}
		}

		synchronized(lock) {
			if (parsed != null) {
				registry = parsed
				registryFetchedAt = fetchedAt
				registryState = RegistryState.LOADED
				registryError = null
			} else {
				registryState = RegistryState.FAILED
				registryError = error
			}
		}

		notifyListeners()
	}

	@WorkerThread
	private fun fetchString(url: String): String {
		var result: String? = null
		execute(url, null) { input, _ -> result = input.readBytes().toString(Charsets.UTF_8) }
		return result ?: throw IOException("Empty response")
	}

	/**
	 * Performs a GET request using the app's HTTP backend (which sets the user agent, and
	 * uses Tor if enabled), passing the response body to [handleBody].
	 */
	@WorkerThread
	private fun execute(
		url: String,
		download: Download?,
		handleBody: (InputStream, Long?) -> Unit
	) {
		val request = HTTPBackend.backend.prepareRequest(
			appContext,
			HTTPBackend.RequestDetails(UriString(url), null)
		)

		download?.request = request

		if (download?.cancelled == true) {
			throw DownloadCancelledException()
		}

		var failure: Exception? = null
		var succeeded = false

		request.executeInThisThread(object : HTTPBackend.Listener {
			override fun onError(
				failureType: CacheRequest.RequestFailureType,
				exception: Throwable?,
				httpStatus: Int?,
				body: FailedRequestBody?
			) {
				if (failure == null) {
					failure = when {
						exception is DownloadCancelledException -> exception
						exception is Exception -> IOException(exception.message, exception)
						httpStatus != null -> IOException("HTTP error $httpStatus")
						else -> IOException("Request failed ($failureType)")
					}
				}
			}

			override fun onSuccess(mimetype: String?, bodyBytes: Long?, body: InputStream?) {
				if (body == null) {
					throw IOException("Empty response")
				}
				handleBody(body, bodyBytes)
				succeeded = true
			}
		})

		download?.request = null

		if (download?.cancelled == true) {
			throw DownloadCancelledException()
		}

		failure?.let { throw it }

		if (!succeeded) {
			throw IOException("Request failed")
		}
	}

	// ---------------------------------------------------------------------------------------
	// Downloads
	// ---------------------------------------------------------------------------------------

	private class DownloadCancelledException : IOException("Cancelled")

	/**
	 * Downloads the given models (pair keys), in response to a user action. On a metered
	 * connection, if the user only allows downloads over Wi-Fi, asks for confirmation first.
	 */
	@UiThread
	fun downloadWithConfirmation(context: Context, pairKeys: Collection<String>) {

		if (pairKeys.isEmpty()) {
			return
		}

		if (!General.isNetworkConnected(context)) {
			General.quickToast(context, R.string.translation_model_no_connection)
			return
		}

		val records = synchronized(lock) { pairKeys.mapNotNull { registry?.get(it) } }

		if (records.size != pairKeys.size) {
			General.quickToast(context, R.string.translation_model_download_failed_generic)
			return
		}

		val connectivityManager = ContextCompat.getSystemService(
			context,
			ConnectivityManager::class.java
		)

		val metered = connectivityManager == null
				|| ConnectivityManagerCompat.isActiveNetworkMetered(connectivityManager)

		if (!metered || !PrefsUtility.pref_translation_wifi_only()) {
			records.forEach { startDownload(it) }
			return
		}

		if (context !is Activity) {
			General.quickToast(context, R.string.translation_model_wifi_only)
			return
		}

		val bytes = records.sumOf { estimateDownloadBytes(it) }

		AlertDialog.Builder(context)
			.setTitle(R.string.translation_model_metered_title)
			.setMessage(context.getString(
				R.string.translation_model_metered_message,
				formatSize(context, bytes)
			))
			.setPositiveButton(R.string.translation_model_download_anyway) { _, _ ->
				records.forEach { startDownload(it) }
			}
			.setNegativeButton(R.string.dialog_cancel, null)
			.show()
	}

	/**
	 * Downloads a model without asking for confirmation. The caller is responsible for
	 * respecting the user's preferences, see [downloadWithConfirmation].
	 *
	 * @return False if the model isn't in the registry (which may not have been loaded yet).
	 */
	@UiThread
	fun download(pairKey: String): Boolean {
		val record = synchronized(lock) { registry?.get(pairKey) } ?: return false
		startDownload(record)
		return true
	}

	@UiThread
	private fun startDownload(record: ModelRecord) {

		val pairKey = record.pairKey

		if (!SAFE_NAME.matches(pairKey)) {
			Log.e(TAG, "Refusing to download model with unsafe name")
			return
		}

		val download = Download(pairKey, estimateDownloadBytes(record))

		synchronized(lock) {
			if (downloads.containsKey(pairKey)) {
				return
			}
			downloads[pairKey] = download
		}

		notifyListeners()

		downloadThreads.add { runDownload(record, download) }
	}

	@UiThread
	fun cancelDownload(pairKey: String) {
		val download = synchronized(lock) { downloads[pairKey] } ?: return
		download.cancelled = true
		download.request?.cancel()
	}

	@WorkerThread
	private fun runDownload(record: ModelRecord, download: Download) {

		val pairKey = record.pairKey

		// Make sure that the initial scan (which deletes old temporary directories) has
		// happened before creating our temporary directory
		getInstalledModels()

		val tempDir = File(rootDir, TEMP_PREFIX + pairKey + "-" + SystemClock.elapsedRealtime())

		var error: Exception? = null

		try {
			if (!tempDir.mkdirs()) {
				throw IOException("Failed to create directory")
			}

			for (file in record.files) {
				downloadFile(file, tempDir, download)
			}

			val manifest = JSONObject()
			manifest.put("pair", pairKey)
			manifest.put("model", record.model.fileName)
			manifest.put("srcVocab", record.srcVocab.fileName)
			manifest.put("trgVocab", record.trgVocab.fileName)
			manifest.put("lexicalShortlist", record.lexicalShortlist?.fileName ?: JSONObject.NULL)
			manifest.put("modelSha256", record.model.uncompressedSha256 ?: JSONObject.NULL)
			manifest.put("architecture", record.architecture)
			File(tempDir, MANIFEST_FILE).writeText(manifest.toString())

			val installedModel = readManifest(tempDir)
				?: throw IOException("Failed to read manifest")

			val dest = File(rootDir, pairKey)

			if (dest.exists()) {
				onModelsRemoved?.invoke(setOf(pairKey))
				synchronized(lock) {
					installed = installed?.let { it - pairKey }
				}
				dest.deleteRecursively()
			}

			if (!tempDir.renameTo(dest)) {
				throw IOException("Failed to install model")
			}

			val finalModel = readManifest(dest) ?: throw IOException("Failed to read manifest")

			synchronized(lock) {
				installed = (installed ?: emptyMap()) + (pairKey to finalModel)
			}

			Log.i(TAG, "Installed model $pairKey (${installedModel.sizeBytes} bytes)")

		} catch (e: Exception) {
			error = e
			tempDir.deleteRecursively()

			if (e is DownloadCancelledException || download.cancelled) {
				Log.i(TAG, "Download of $pairKey cancelled")
			} else {
				Log.e(TAG, "Download of $pairKey failed", e)
			}

		} finally {
			synchronized(lock) {
				downloads.remove(pairKey)
			}
		}

		if (error != null && !download.cancelled && error !is DownloadCancelledException) {
			val message = error.message ?: error.javaClass.simpleName
			AndroidCommon.UI_THREAD_HANDLER.post {
				General.quickToast(
					appContext,
					appContext.getString(R.string.translation_model_download_failed, message)
				)
			}
		}

		onModelsChanged()
	}

	@WorkerThread
	private fun downloadFile(file: ModelFile, dir: File, download: Download) {

		if (!SAFE_NAME.matches(file.fileName)) {
			throw IOException("Unsafe file name")
		}

		val dest = File(dir, file.fileName)

		execute(file.url, download) { body, _ ->

			val counting = object : FilterInputStream(body) {

				override fun read(): Int {
					val result = super.read()
					if (result >= 0) {
						onRead(1)
					}
					return result
				}

				override fun read(b: ByteArray, off: Int, len: Int): Int {
					val result = super.read(b, off, len)
					if (result > 0) {
						onRead(result)
					}
					return result
				}

				private fun onRead(count: Int) {
					if (download.cancelled) {
						throw DownloadCancelledException()
					}
					download.downloadedBytes += count
					val now = SystemClock.elapsedRealtime()
					if (now - download.lastNotified >= PROGRESS_NOTIFY_INTERVAL_MS) {
						download.lastNotified = now
						notifyListeners()
					}
				}
			}

			val digest = MessageDigest.getInstance("SHA-256")
			var size = 0L

			GZIPInputStream(BufferedInputStream(counting, 64 * 1024), 64 * 1024).use { input ->
				FileOutputStream(dest).use { output ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						val read = input.read(buffer)
						if (read < 0) {
							break
						}
						digest.update(buffer, 0, read)
						output.write(buffer, 0, read)
						size += read
					}
					output.fd.sync()
				}
			}

			file.uncompressedSize?.let {
				if (it != size) {
					throw IOException("Size mismatch for ${file.fileName}")
				}
			}

			file.uncompressedSha256?.let { expected ->
				val actual = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
				if (!expected.equals(actual, ignoreCase = true)) {
					throw IOException("Checksum mismatch for ${file.fileName}")
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------------------------

	@AnyThread
	private fun onModelsChanged() {
		TranslationManager.getInstance().onModelsChanged()
		notifyListeners()
	}

	/**
	 * Works out which installed models to use to translate from [from] to [to] (app language
	 * codes), or returns null if they're not all installed. Blocks while loading the list of
	 * installed models, the first time.
	 */
	@WorkerThread
	fun resolveInstalled(
		from: String,
		to: String,
		traditionalChinese: Boolean = false
	): List<InstalledModel>? {
		val models = getInstalledModels()
		return BergamotLanguages.resolve(from, to, traditionalChinese) { it in models }
			?.map { models.getValue(it) }
	}

	/**
	 * @return All models which are installed, downloading, or available to download, or null if
	 * the registry or the list of installed models is still loading.
	 */
	@UiThread
	fun getEntries(): List<ModelEntry>? {

		ensureInstalledLoaded()
		ensureRegistryLoaded(false)

		synchronized(lock) {
			val installed = this.installed ?: return null
			val registry = this.registry ?: if (registryState == RegistryState.FAILED) {
				emptyMap()
			} else {
				return null
			}

			val keys = LinkedHashSet<String>()
			keys.addAll(installed.keys)

			for ((key, record) in registry) {
				// Mozilla publishes identical "no" (Norwegian) and "nb" (Bokmål) models
				val isNorwegianDuplicate = (record.sourceLanguage == "no"
						&& registry.containsKey("nb-${record.targetLanguage}"))
						|| (record.targetLanguage == "no"
						&& registry.containsKey("${record.sourceLanguage}-nb"))

				if (!isNorwegianDuplicate) {
					keys.add(key)
				}
			}

			return keys.mapNotNull { key ->
				val record = registry[key]
				val parts = key.split('-')
				if (parts.size != 2) {
					return@mapNotNull null
				}
				ModelEntry(
					pairKey = key,
					sourceLanguage = record?.sourceLanguage ?: parts[0],
					targetLanguage = record?.targetLanguage ?: parts[1],
					record = record,
					installed = installed[key],
					downloadProgress = downloads[key]?.progress,
				)
			}
		}
	}

	@UiThread
	override fun getStatus(
		from: String,
		to: String
	): TranslationModelProvider.ModelStatus {

		ensureInstalledLoaded()

		synchronized(lock) {

			val installed = this.installed
				?: return status(TranslationModelProvider.ModelStatus.State.UNKNOWN)

			if (BergamotLanguages.resolve(from, to) { it in installed } != null) {
				return status(TranslationModelProvider.ModelStatus.State.INSTALLED)
			}

			val registry = this.registry

			if (registry == null) {
				val state = registryState
				// Avoid holding the lock while starting the load
				AndroidCommon.UI_THREAD_HANDLER.post { ensureRegistryLoaded(false) }
				return status(
					if (state == RegistryState.FAILED) {
						TranslationModelProvider.ModelStatus.State.NOT_AVAILABLE
					} else {
						TranslationModelProvider.ModelStatus.State.UNKNOWN
					}
				)
			}

			val plan = BergamotLanguages.resolve(from, to) { it in installed || it in registry }
				?: return status(TranslationModelProvider.ModelStatus.State.NOT_AVAILABLE)

			val missing = plan.filter { it !in installed }

			val activeDownloads = missing.mapNotNull { downloads[it] }

			val totalBytes = missing.sumOf { key ->
				registry[key]?.let { estimateDownloadBytes(it) } ?: 0L
			}

			if (activeDownloads.isNotEmpty()) {
				val downloaded = activeDownloads.sumOf { it.downloadedBytes }
				return TranslationModelProvider.ModelStatus(
					TranslationModelProvider.ModelStatus.State.DOWNLOADING,
					totalBytes,
					(downloaded.toFloat() / totalBytes.coerceAtLeast(1)).coerceIn(0f, 0.99f)
				)
			}

			return TranslationModelProvider.ModelStatus(
				TranslationModelProvider.ModelStatus.State.DOWNLOADABLE,
				totalBytes,
				0f
			)
		}
	}

	private fun status(state: TranslationModelProvider.ModelStatus.State) =
		TranslationModelProvider.ModelStatus(state, -1, 0f)

	@UiThread
	override fun requestDownload(context: Context, from: String, to: String) {

		val pairKeys = synchronized(lock) {
			val installed = this.installed ?: emptyMap()
			val registry = this.registry ?: emptyMap()

			BergamotLanguages.resolve(from, to) { it in installed || it in registry }
				?.filter { it !in installed && !downloads.containsKey(it) }
		}

		if (pairKeys == null) {
			General.quickToast(context, R.string.translation_model_download_failed_generic)
			return
		}

		downloadWithConfirmation(context, pairKeys)
	}

	@UiThread
	override fun addListener(listener: TranslationModelProvider.Listener) {
		listeners.removeAll { it.get() == null || it.get() === listener }
		listeners.add(WeakReference(listener))
	}

	@UiThread
	override fun removeListener(listener: TranslationModelProvider.Listener) {
		listeners.removeAll { it.get() == null || it.get() === listener }
	}

	@AnyThread
	private fun notifyListeners() {
		AndroidCommon.runOnUiThread {
			for (ref in ArrayList(listeners)) {
				ref.get()?.onModelStatusChanged()
			}
			listeners.removeAll { it.get() == null }
		}
	}

	fun formatSize(context: Context, bytes: Long): String =
		android.text.format.Formatter.formatShortFileSize(context, bytes)
}
