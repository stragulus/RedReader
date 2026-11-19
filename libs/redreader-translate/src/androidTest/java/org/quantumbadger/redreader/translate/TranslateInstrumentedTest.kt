package org.quantumbadger.redreader.translate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Smoke test for the native library. Downloads es->en and en->de models (~2 x 35 MB) from
 * Mozilla's storage on first run (cached in the test app's cache dir afterwards).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TranslateInstrumentedTest {

	companion object {
		private const val TAG = "TranslateTest"

		private lateinit var registry: Map<String, MozillaModelRegistry.ModelRecord>
		private lateinit var modelDir: File

		@BeforeClass
		@JvmStatic
		fun setUp() {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			modelDir = File(context.cacheDir, "translate-test-models").apply { mkdirs() }
			registry = MozillaModelRegistry.parse(
				String(download(MozillaModelRegistry.REGISTRY_URL), Charsets.UTF_8)
			)
		}

		private fun download(url: String): ByteArray {
			val conn = URL(url).openConnection() as HttpURLConnection
			conn.connectTimeout = 30_000
			conn.readTimeout = 60_000
			try {
				if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
				return conn.inputStream.use { it.readBytes() }
			} finally {
				conn.disconnect()
			}
		}

		private fun fetch(record: MozillaModelRegistry.ModelRecord): TranslationModelFiles {
			val dir = File(modelDir, record.pairKey).apply { mkdirs() }
			for (f in record.files) {
				val out = File(dir, f.fileName)
				if (out.isFile && (f.uncompressedSize == null || out.length() == f.uncompressedSize)) {
					continue
				}
				val start = System.nanoTime()
				val bytes = GZIPInputStream(download(f.url).inputStream()).use { it.readBytes() }
				if (f.uncompressedSha256 != null) {
					val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
						.joinToString("") { "%02x".format(it) }
					assertEquals("SHA-256 of ${f.fileName}", f.uncompressedSha256, sha)
				}
				out.writeBytes(bytes)
				Log.i(TAG, "Downloaded ${f.fileName} (${bytes.size} bytes) in ${(System.nanoTime() - start) / 1_000_000} ms")
			}
			return TranslationModelFiles(
				model = File(dir, record.model.fileName),
				srcVocab = File(dir, record.srcVocab.fileName),
				trgVocab = File(dir, record.trgVocab.fileName),
				lexicalShortlist = record.lexicalShortlist?.let { File(dir, it.fileName) },
			)
		}

		private inline fun <T> timed(label: String, block: () -> T): T {
			val start = System.nanoTime()
			val result = block()
			Log.i(TAG, "$label took ${(System.nanoTime() - start) / 1_000_000} ms")
			return result
		}
	}

	@Test
	fun a_registryHasCommonPairs() {
		for (pair in listOf("es-en", "en-es", "de-en", "en-de", "fr-en", "ja-en", "zh-en")) {
			assertTrue("Missing $pair", registry.containsKey(pair))
		}
		Log.i(TAG, "Registry has ${registry.size} released pairs: ${registry.keys.sorted()}")
	}

	@Test
	fun b_detectLanguages() {
		val samples = mapOf(
			"es" to "Hola a todos, ¿alguien sabe cuándo abre la tienda nueva del centro?",
			"de" to "Ich habe gestern das neue Update installiert und jetzt stürzt alles ab.",
			"fr" to "Je ne comprends pas pourquoi tout le monde parle de ce film.",
			"en" to "I can't believe they actually shipped this without testing it first.",
			"nl" to "Ik denk dat de meeste mensen dit gewoon niet weten, eerlijk gezegd.",
			"ja" to "昨日の試合は本当に面白かったです。また見に行きたいです。",
			"ru" to "Кто-нибудь знает, где можно купить такую же куртку?",
			"zh" to "我觉得这个问题没有那么简单，我们需要更多的信息。",
		)
		for ((expected, text) in samples) {
			val result = timed("detect($expected)") { LanguageDetector.detect(text) }
			Log.i(TAG, "detect: '$text' -> $result")
			assertEquals(text, expected, result.languageCode)
			assertTrue("Should be reliable: $text -> $result", result.isReliable)
		}

		// Too short to be reliable
		val short = LanguageDetector.detect("lol ok")
		Log.i(TAG, "detect short -> $short")
		assertFalse(short.isReliable)

		// URLs and subreddit names must not drive the detection
		val withNoise = LanguageDetector.detect(
			"Mira esto https://www.example.com/some/english/path r/AskReddit u/SomeUser es increíble, de verdad"
		)
		Log.i(TAG, "detect with noise -> $withNoise")
		assertEquals("es", withNoise.languageCode)

		// HTML
		val html = LanguageDetector.detect(
			"<div class=\"md\"><p>Das ist wirklich eine <strong>sehr gute</strong> Idee, danke dafür!</p></div>",
			isHtml = true
		)
		Log.i(TAG, "detect html -> $html")
		assertEquals("de", html.languageCode)
	}

	@Test
	fun c_translateSpanishToEnglish() {
		val record = registry.getValue("es-en")
		Log.i(TAG, "Using $record")
		val files = timed("download es-en") { fetch(record) }

		timed("load es-en") { TranslationEngine(listOf(files)) }.use { engine ->

			val inputs = listOf(
				"Hola, ¿cómo estás? Me gusta mucho este subreddit.",
				"El perro come una manzana. 😀 ¡Qué divertido!",
				"",
			)
			val out = timed("translate es-en (cold)") { engine.translate(inputs, html = false) }
			out.forEachIndexed { i, s -> Log.i(TAG, "es-en: '${inputs[i]}' -> '$s'") }
			assertEquals(3, out.size)
			// Exact wording differs slightly between ABIs (ruy vs intgemm int8 GEMM numerics)
			assertTrue(out[0], out[0].contains("how", ignoreCase = true))
			assertTrue(out[0], out[0].contains("subreddit"))
			assertTrue(out[1], out[1].contains("dog", ignoreCase = true))
			assertTrue("Emoji must survive: ${out[1]}", out[1].contains("😀"))
			assertEquals("", out[2])

			val longText = List(10) {
				"Ayer fui al mercado con mi hermana y compramos muchas frutas y verduras frescas."
			}.joinToString(" ")
			val longOut = timed("translate es-en (10 sentences)") {
				engine.translate(listOf(longText), html = false)
			}
			Log.i(TAG, "es-en long: '${longOut[0].take(200)}...'")

			val htmlIn = "<div class=\"md\"><p>Esto es <strong>muy</strong> importante. " +
					"Lee <a href=\"https://example.com\">este artículo</a> por favor.</p></div>"
			val htmlOut = timed("translate es-en html") { engine.translate(listOf(htmlIn), html = true) }[0]
			Log.i(TAG, "es-en html: '$htmlOut'")
			assertTrue(htmlOut, htmlOut.startsWith("<div class=\"md\"><p>"))
			assertTrue(htmlOut, htmlOut.contains("<strong>"))
			assertTrue(htmlOut, htmlOut.contains("<a href=\"https://example.com\">"))
			assertTrue(htmlOut, htmlOut.contains("important", ignoreCase = true))
		}
	}

	@Test
	fun d_pivotSpanishToGermanAndConcurrency() {
		val esEn = fetch(registry.getValue("es-en"))
		val enDe = timed("download en-de") { fetch(registry.getValue("en-de")) }

		val engine = timed("load es-en-de") { TranslationEngine(listOf(esEn, enDe)) }
		val out = timed("translate es-en-de") {
			engine.translate(listOf("El perro come una manzana."), html = false)
		}
		Log.i(TAG, "es-en-de: '${out[0]}'")
		assertTrue(out[0], out[0].contains("Hund"))

		// Concurrent callers are serialised, not crashing
		val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
		val threads = List(4) { n ->
			Thread {
				try {
					repeat(3) {
						val r = engine.translate(listOf("Hola mundo número $n."), html = false)
						assertTrue(r[0], r[0].isNotEmpty())
					}
				} catch (t: Throwable) {
					errors.add(t)
				}
			}
		}
		threads.forEach { it.start() }
		threads.forEach { it.join() }
		errors.peek()?.let { throw it }

		engine.close()
		engine.close() // idempotent
		assertTrue(engine.isClosed)
		try {
			engine.translate(listOf("Hola"), html = false)
			throw AssertionError("Expected IllegalStateException")
		} catch (e: IllegalStateException) {
			// expected
		}
	}

	@Test
	fun e_badModelThrows() {
		val bogus = File(modelDir, "bogus.bin").apply { writeText("not a model") }
		val vocab = File(File(modelDir, "es-en"), registry.getValue("es-en").srcVocab.fileName)
		try {
			TranslationEngine(listOf(TranslationModelFiles(bogus, vocab, vocab, null))).close()
			throw AssertionError("Expected IOException")
		} catch (e: IOException) {
			Log.i(TAG, "Bad model correctly rejected: ${e.message}")
		}
	}

	/** Not an assertion test: logs detector behaviour on short, informal text for tuning. */
	@Test
	fun f_logShortTextDetection() {
		val samples = listOf(
			"Gracias amigo!",
			"Qué bueno, me encanta",
			"jajaja no puede ser",
			"Danke schön!",
			"Das ist doch Quatsch",
			"mdr c'est trop drôle",
			"lol same",
			"This is fine.",
			"Ja, klopt helemaal",
			"ok boomer",
			"Obrigado pela ajuda, funcionou!",
			"Grazie mille ragazzi, siete fantastici",
			"ありがとう",
			"감사합니다 정말 도움이 됐어요",
			"Спасибо, очень помогло",
			"Haha ja precies, dat dacht ik ook al toen ik het las",
		)
		for (text in samples) {
			Log.i(TAG, "short: '$text' -> ${LanguageDetector.detect(text)}")
		}
	}
}
