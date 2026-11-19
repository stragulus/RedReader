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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end test of on-device translation through [TranslationManager], using the real
 * Bergamot backend registered by the application, and a real model downloaded from Mozilla.
 *
 * Needs network access (downloads the ~26 MB Spanish -> English model, if it isn't installed).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BergamotEndToEndTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context = instrumentation.targetContext
	private val models = TranslationModelManager.getInstance(context)

	private val settings = TranslationSettings(
		true,
		"en",
		emptySet(),
		EnumSet.allOf(TranslationContentType::class.java)
	)

	private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
		val end = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < end) {
			if (condition()) {
				return true
			}
			Thread.sleep(200)
		}
		return condition()
	}

	private fun translate(text: String, isHtml: Boolean): TranslationResult {
		val latch = CountDownLatch(1)
		val result = AtomicReference<TranslationResult>()

		instrumentation.runOnMainSync {
			TranslationManager.getInstance().translate(text, isHtml, false, settings) {
				result.set(it)
				latch.countDown()
			}
		}

		assertTrue("Timed out", latch.await(120, TimeUnit.SECONDS))
		return result.get()
	}

	private fun ensureInstalled(pairKey: String) {

		if (models.getInstalledModels().containsKey(pairKey)) {
			return
		}

		models.ensureRegistryLoaded(true)

		assertTrue(
			"Registry didn't load: ${models.getRegistryError()}",
			waitFor(60_000) {
				models.getRegistryState() == TranslationModelManager.RegistryState.LOADED
			}
		)

		instrumentation.runOnMainSync { assertTrue(models.download(pairKey)) }

		assertTrue(
			"Download of $pairKey didn't complete",
			waitFor(600_000) { models.getInstalledModels().containsKey(pairKey) }
		)
	}

	@Test
	fun translatesSpanishCommentHtml() {

		assumeTrue(
			"Fake debug backend is enabled",
			TranslationManager.getInstance().modelProvider != null
		)

		ensureInstalled("es-en")

		val html = "<div class=\"md\"><p>Hola a todos. Ayer fui al mercado con mi hermana y " +
				"compramos muchas <strong>frutas frescas</strong> para la cena.</p></div>"

		val result = translate(html, true)

		assertEquals(result.toString(), TranslationResult.Status.TRANSLATED, result.status)
		assertEquals("es", result.sourceLanguage)

		assertNotNull(result.translatedText)
		val translated = result.translatedText!!
		Log.i("BergamotEndToEndTest", "Translated: $translated")

		assertTrue(translated, translated.startsWith("<div class=\"md\"><p>"))
		assertTrue(translated, translated.contains("<strong>"))

		val lower = translated.lowercase(Locale.ROOT)
		assertTrue(translated, lower.contains("market"))
		assertTrue(translated, lower.contains("sister"))
	}

	@Test
	fun reportsMissingModel() {

		assumeTrue(
			"Fake debug backend is enabled",
			TranslationManager.getInstance().modelProvider != null
		)
		assumeTrue(
			"Icelandic model is installed",
			!models.getInstalledModels().containsKey("is-en")
		)

		val result = translate(
			"Ég fór í búðina í gær með systur minni og við keyptum mikið af ávöxtum.",
			false
		)

		assertEquals(result.toString(), TranslationResult.Status.MODEL_MISSING, result.status)
		assertEquals("is", result.sourceLanguage)
	}
}
