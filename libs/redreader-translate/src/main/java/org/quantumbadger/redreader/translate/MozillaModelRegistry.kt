package org.quantumbadger.redreader.translate

import org.json.JSONObject

/**
 * Parser for Mozilla's translation model registry (the same models Firefox downloads), see
 * [REGISTRY_URL]. No networking is done here: fetch the JSON (~200 KB) and pass it to [parse].
 *
 * Models are published per direction, always to or from English ("es-en", "en-de", ...), so
 * translating between two non-English languages requires pivoting through English with two
 * models (see [TranslationEngine]).
 *
 * Files are gzipped at [ModelFile.url]; [ModelFile.fileName] is the name without the `.gz`.
 * Only the model file has a published uncompressed size and SHA-256.
 */
object MozillaModelRegistry {

	const val REGISTRY_URL =
		"https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"

	data class ModelFile(
		/** Absolute download URL (gzipped). */
		val url: String,
		/** File name after decompression, e.g. `model.esen.intgemm.alphas.bin`. */
		val fileName: String,
		val uncompressedSize: Long?,
		/** Lowercase hex SHA-256 of the uncompressed file, if published. */
		val uncompressedSha256: String?,
	)

	data class ModelRecord(
		/** Mozilla language code, e.g. "es", "zh_hant", "hbs". */
		val sourceLanguage: String,
		val targetLanguage: String,
		/** "tiny", "base" or "base-memory" */
		val architecture: String,
		/** e.g. "Release", "Release Android", "Release Desktop", "Nightly", or null */
		val releaseStatus: String?,
		val model: ModelFile,
		val srcVocab: ModelFile,
		/** Same as [srcVocab] for models with a shared vocabulary. */
		val trgVocab: ModelFile,
		val lexicalShortlist: ModelFile?,
	) {
		val pairKey: String get() = "$sourceLanguage-$targetLanguage"

		/** Distinct files to download (vocab is listed once if shared). */
		val files: List<ModelFile>
			get() = listOfNotNull(model, srcVocab, trgVocab, lexicalShortlist).distinct()
	}

	private val releasePriority = mapOf(
		"Release Android" to 4,
		"Release" to 3,
		"Release Desktop" to 2,
	)

	private val architecturePriority = mapOf(
		"base-memory" to 3,
		"base" to 2,
		"tiny" to 1,
	)

	/**
	 * Parses the registry and returns the best released model per direction, keyed by
	 * [ModelRecord.pairKey]. Unreleased (Nightly/null status) models are skipped.
	 *
	 * Preference: "Release Android" > "Release" > "Release Desktop", then
	 * base-memory > base > tiny (the same order the F-Droid Offline Translator app uses).
	 */
	fun parse(json: String): Map<String, ModelRecord> {

		val root = JSONObject(json)
		val baseUrl = root.getString("baseUrl").trimEnd('/')
		val models = root.getJSONObject("models")

		val result = HashMap<String, ModelRecord>()

		for (pair in models.keys()) {
			val variants = models.getJSONArray(pair)
			var best: ModelRecord? = null
			var bestRank = -1

			for (i in 0 until variants.length()) {
				val record = parseRecord(baseUrl, variants.getJSONObject(i)) ?: continue
				val release = releasePriority[record.releaseStatus] ?: continue
				val rank = release * 10 + (architecturePriority[record.architecture] ?: 0)
				if (rank > bestRank) {
					best = record
					bestRank = rank
				}
			}

			if (best != null) result[pair] = best
		}

		return result
	}

	private fun parseRecord(baseUrl: String, obj: JSONObject): ModelRecord? {

		val files = obj.optJSONObject("files") ?: return null

		fun file(key: String): ModelFile? {
			val f = files.optJSONObject(key) ?: return null
			val path = f.getString("path")
			val name = path.substringAfterLast('/').removeSuffix(".gz")
			return ModelFile(
				url = "$baseUrl/$path",
				fileName = name,
				uncompressedSize = if (f.has("uncompressedSize")) f.getLong("uncompressedSize") else null,
				uncompressedSha256 = f.optString("uncompressedHash").ifEmpty { null },
			)
		}

		val model = file("model") ?: return null
		val sharedVocab = file("vocab")
		val srcVocab = file("srcVocab") ?: sharedVocab ?: return null
		val trgVocab = file("trgVocab") ?: sharedVocab ?: return null

		return ModelRecord(
			sourceLanguage = obj.getString("sourceLanguage"),
			targetLanguage = obj.getString("targetLanguage"),
			architecture = obj.optString("architecture"),
			releaseStatus = if (obj.isNull("releaseStatus")) null else obj.optString("releaseStatus"),
			model = model,
			srcVocab = srcVocab,
			trgVocab = trgVocab,
			lexicalShortlist = file("lexicalShortlist"),
		)
	}
}
