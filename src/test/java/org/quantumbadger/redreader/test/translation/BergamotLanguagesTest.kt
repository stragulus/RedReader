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

package org.quantumbadger.redreader.test.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.quantumbadger.redreader.translation.BergamotLanguages

class BergamotLanguagesTest {

	// A subset of the released models in Mozilla's registry
	private val registry = setOf(
		"es-en", "en-es", "de-en", "en-de", "hbs-en", "en-hr", "en-sr", "en-bs",
		"nb-en", "no-en", "en-nb", "en-no", "zh-en", "zh_hant-en", "en-zh", "en-zh_hant",
	)

	private fun resolve(from: String, to: String, traditional: Boolean = false) =
		BergamotLanguages.resolve(from, to, traditional) { it in registry }

	@Test
	fun direct() {
		assertEquals(listOf("es-en"), resolve("es", "en"))
		assertEquals(listOf("en-de"), resolve("en", "de"))
	}

	@Test
	fun pivot() {
		assertEquals(listOf("es-en", "en-de"), resolve("es", "de"))
	}

	@Test
	fun unsupported() {
		assertNull(resolve("cy", "en"))
		assertNull(resolve("en", "cy"))
		assertNull(resolve("es", "cy"))
		assertNull(resolve("en", "en"))
	}

	@Test
	fun serboCroatian() {
		assertEquals(listOf("hbs-en"), resolve("hr", "en"))
		assertEquals(listOf("hbs-en"), resolve("sr", "en"))
		assertEquals(listOf("hbs-en"), resolve("bs", "en"))
		assertEquals(listOf("en-hr"), resolve("en", "hr"))
		assertEquals(listOf("hbs-en", "en-sr"), resolve("hr", "sr"))
	}

	@Test
	fun norwegian() {
		assertEquals(listOf("nb-en"), resolve("nb", "en"))
		assertEquals(listOf("nb-en"), resolve("nn", "en"))
		assertEquals(listOf("en-nb"), resolve("en", "nn"))
		assertEquals(listOf("no-en"), BergamotLanguages.resolve("nb", "en") { it == "no-en" })
	}

	@Test
	fun chinese() {
		assertEquals(listOf("zh-en"), resolve("zh", "en"))
		assertEquals(listOf("zh_hant-en"), resolve("zh", "en", traditional = true))
		assertEquals(listOf("en-zh"), resolve("en", "zh"))

		// Falls back to the other script's model if that's the only one installed
		assertEquals(
			listOf("zh-en"),
			BergamotLanguages.resolve("zh", "en", true) { it == "zh-en" }
		)
		assertEquals(
			listOf("zh_hant-en"),
			BergamotLanguages.resolve("zh", "en") { it == "zh_hant-en" }
		)
	}
}
