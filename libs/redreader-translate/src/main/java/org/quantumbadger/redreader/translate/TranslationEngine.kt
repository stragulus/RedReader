package org.quantumbadger.redreader.translate

import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * The (uncompressed) files making up one Bergamot model for a single direction, e.g. es->en.
 *
 * Mozilla distributes the files gzipped; they must be decompressed before use.
 *
 * @param model e.g. `model.esen.intgemm.alphas.bin`
 * @param srcVocab e.g. `vocab.esen.spm` (or `srcvocab.xxyy.spm` for models with split vocabularies)
 * @param trgVocab same as [srcVocab] for models with a shared vocabulary, else `trgvocab.xxyy.spm`
 * @param lexicalShortlist e.g. `lex.50.50.esen.s2t.bin`. Optional, but strongly recommended: it
 * makes translation several times faster.
 */
data class TranslationModelFiles(
	val model: File,
	val srcVocab: File,
	val trgVocab: File = srcVocab,
	val lexicalShortlist: File?,
)

/**
 * A loaded Bergamot translation engine for one language direction.
 *
 * Pass one model for a direct translation (e.g. es->en), or two to pivot through an intermediate
 * language (e.g. es->en followed by en->de). The target language of the first model must be the
 * source language of the second.
 *
 * Loading a model takes some time (it reads ~20-40 MB per model) and the engine holds that memory
 * until [close] is called, so callers should cache engines rather than create one per request.
 *
 * Thread-safe: translations are serialised (concurrent callers block until the engine is free).
 * Translation runs on the calling thread, so never call [translate] from the UI thread.
 *
 * @throws IOException if a model can't be loaded.
 * @throws UnsatisfiedLinkError if the native library is not available for this ABI.
 */
class TranslationEngine @Throws(IOException::class) constructor(
	models: List<TranslationModelFiles>,
	options: Options = Options(),
) : Closeable {

	/**
	 * @param workspaceMb Initial size in MB of Marian's per-model work area. It grows if needed.
	 * @param maxLengthBreak Sentences longer than this (in tokens) are split.
	 * @param miniBatchWords Max words per batch.
	 */
	data class Options(
		val workspaceMb: Int = 32,
		val maxLengthBreak: Int = 128,
		val miniBatchWords: Int = 1024,
	)

	private val lock = Any()

	// Guarded by lock
	private var handle: Long

	init {
		require(models.size in 1..2) { "Expected one or two models, got ${models.size}" }

		for (m in models) {
			for (f in listOfNotNull(m.model, m.srcVocab, m.trgVocab, m.lexicalShortlist)) {
				if (!f.isFile) throw IOException("Model file not found: $f")
			}
		}

		NativeLibrary.ensureLoaded()

		handle = TranslationNative.nativeCreate(
			models.map { buildConfig(it, options).toByteArray(Charsets.UTF_8) }.toTypedArray()
		)
	}

	/**
	 * Translates each of [texts] independently. The output list has the same size and order.
	 *
	 * Each text may contain multiple sentences/paragraphs.
	 *
	 * @param html If true, the texts are HTML fragments: tags are preserved (and moved along with
	 * the words they wrap), only the text content is translated. Input must be reasonably
	 * well-formed HTML; on parse failure a [TranslationException] is thrown.
	 *
	 * @throws TranslationException on a native error.
	 * @throws IllegalStateException if the engine has been closed.
	 */
	@Throws(TranslationException::class)
	fun translate(texts: List<String>, html: Boolean): List<String> {

		if (texts.isEmpty()) return emptyList()

		val input = Array(texts.size) { texts[it].toByteArray(Charsets.UTF_8) }

		val output = synchronized(lock) {
			check(handle != 0L) { "TranslationEngine is closed" }
			TranslationNative.nativeTranslate(handle, input, html)
		}

		return output.map { String(it, Charsets.UTF_8) }
	}

	/**
	 * Releases the native model memory. Blocks until any in-progress translation finishes.
	 * Idempotent.
	 */
	override fun close() {
		synchronized(lock) {
			if (handle != 0L) {
				TranslationNative.nativeDestroy(handle)
				handle = 0L
			}
		}
	}

	val isClosed: Boolean
		get() = synchronized(lock) { handle == 0L }

	companion object {

		// Mirrors the configuration Firefox uses for these models
		// (toolkit/components/translations/content/translations-engine.worker.js)
		internal fun buildConfig(files: TranslationModelFiles, options: Options): String {

			val gemmPrecision = if (files.model.name.endsWith("intgemm8.bin")) {
				"int8shift"
			} else {
				"int8shiftAlphaAll"
			}

			val sb = StringBuilder()
			sb.append("models:\n")
			sb.append("  - ").append(yamlQuote(files.model.absolutePath)).append('\n')
			sb.append("vocabs:\n")
			sb.append("  - ").append(yamlQuote(files.srcVocab.absolutePath)).append('\n')
			sb.append("  - ").append(yamlQuote(files.trgVocab.absolutePath)).append('\n')
			if (files.lexicalShortlist != null) {
				sb.append("shortlist:\n")
				sb.append("  - ").append(yamlQuote(files.lexicalShortlist.absolutePath)).append('\n')
				sb.append("  - false\n")
			}
			sb.append("beam-size: 1\n")
			sb.append("normalize: 1.0\n")
			sb.append("word-penalty: 0\n")
			sb.append("max-length-break: ").append(options.maxLengthBreak).append('\n')
			sb.append("mini-batch-words: ").append(options.miniBatchWords).append('\n')
			sb.append("workspace: ").append(options.workspaceMb).append('\n')
			sb.append("max-length-factor: 2.0\n")
			sb.append("skip-cost: true\n")
			sb.append("cpu-threads: 0\n")
			sb.append("quiet: true\n")
			sb.append("quiet-translation: true\n")
			sb.append("gemm-precision: ").append(gemmPrecision).append('\n')
			sb.append("alignment: soft\n")
			sb.append("ssplit-mode: paragraph\n")
			return sb.toString()
		}

		private fun yamlQuote(s: String) = "'" + s.replace("'", "''") + "'"
	}
}

internal object TranslationNative {

	@JvmStatic
	external fun nativeCreate(configs: Array<ByteArray>): Long

	@JvmStatic
	external fun nativeDestroy(handle: Long)

	@JvmStatic
	external fun nativeTranslate(handle: Long, inputs: Array<ByteArray>, html: Boolean): Array<ByteArray>
}
