package org.quantumbadger.redreader.translate

/**
 * @param languageCode ISO 639-1 code (e.g. "es"), with two exceptions: "zh-Hant" for Traditional
 * Chinese ("zh" is Simplified), and "un" if the language could not be determined. CLD2's legacy
 * codes are normalised ("iw" -> "he", "jw" -> "jv").
 * @param confidence Fraction (0..1) of the scored text that is in [languageCode].
 * @param isReliable True if CLD2 considers the result reliable AND the text passes our own
 * length/confidence thresholds (see [LanguageDetector]). Callers should not auto-translate text
 * whose detection is not reliable.
 */
data class DetectionResult(
	val languageCode: String,
	val confidence: Float,
	val isReliable: Boolean,
)

/**
 * Language detection using CLD2 (the detector used by Firefox Translations), compiled into the
 * native library: no model download is needed, and detection takes microseconds.
 *
 * CLD2 is weak on very short text, so on top of CLD2's own reliability flag a result is only
 * considered reliable if:
 *  - the text contains at least [minLetters] letters after removing URLs, Reddit user/subreddit
 *    references and markdown link targets (CJK/kana/hangul characters count as 3 letters, as
 *    they carry much more information per character), and
 *  - at least [minConfidence] of the scored text is in the top language.
 *
 * Thread-safe.
 */
object LanguageDetector {

	const val DEFAULT_MIN_LETTERS = 20
	const val DEFAULT_MIN_CONFIDENCE = 0.7f

	const val UNKNOWN = "un"

	private val urlRegex = Regex("""(?i)\b(?:https?://|www\.)\S+""")
	private val markdownLinkTargetRegex = Regex("""\]\([^)]*\)""")
	private val redditRefRegex = Regex("""(?i)(?<![\p{L}\p{N}])/?[ur]/[\p{L}\p{N}_-]+""")
	private val htmlTagRegex = Regex("""<[^>]*>""")

	/**
	 * Detects the language of plain text (markdown is fine).
	 */
	fun detect(text: String): DetectionResult = detect(text, isHtml = false)

	/**
	 * @param isHtml If true, [text] is treated as HTML: tags are skipped and entities decoded.
	 */
	fun detect(
		text: String,
		isHtml: Boolean,
		minLetters: Int = DEFAULT_MIN_LETTERS,
		minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
	): DetectionResult {

		NativeLibrary.ensureLoaded()

		val cleaned = clean(text)
		val letters = countLetters(if (isHtml) cleaned.replace(htmlTagRegex, " ") else cleaned)

		val out = IntArray(4)
		val rawCode = nativeDetect(cleaned.toByteArray(Charsets.UTF_8), !isHtml, out)

		val code = normaliseCode(rawCode)
		val confidence = out[0].coerceIn(0, 100) / 100f
		val cldReliable = out[1] != 0

		val reliable = code != UNKNOWN
				&& cldReliable
				&& letters >= minLetters
				&& confidence >= minConfidence

		return DetectionResult(code, confidence, reliable)
	}

	internal fun clean(text: String): String = text
		.replace(markdownLinkTargetRegex, "]")
		.replace(urlRegex, " ")
		.replace(redditRefRegex, " ")

	private fun countLetters(text: String): Int {
		var count = 0
		var i = 0
		while (i < text.length) {
			val cp = text.codePointAt(i)
			if (Character.isLetter(cp)) {
				count += if (isDenseScript(cp)) 3 else 1
			}
			i += Character.charCount(cp)
		}
		return count
	}

	// Character.UnicodeScript would be simpler, but requires API 24
	private fun isDenseScript(cp: Int): Boolean {
		return when (Character.UnicodeBlock.of(cp)) {
			Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
			Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
			Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
			Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
			Character.UnicodeBlock.HIRAGANA,
			Character.UnicodeBlock.KATAKANA,
			Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
			Character.UnicodeBlock.HANGUL_SYLLABLES,
			Character.UnicodeBlock.HANGUL_JAMO,
			Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> true
			else -> false
		}
	}

	private fun normaliseCode(cld2Code: String): String = when (cld2Code) {
		"iw" -> "he"
		"jw" -> "jv"
		"zh-Hant" -> "zh-Hant"
		"xx-Qaai", "" -> UNKNOWN
		else -> if (cld2Code.length > 3 && cld2Code[2] == '-') {
			// e.g. "sr-ME" (Montenegrin) -> "sr"
			cld2Code.substring(0, 2)
		} else {
			cld2Code
		}
	}

	@JvmStatic
	private external fun nativeDetect(utf8: ByteArray, isPlainText: Boolean, out: IntArray): String
}
