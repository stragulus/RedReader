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

/**
 * Maps between the app's language codes (lowercase ISO 639-1, see [TranslationLanguages]) and
 * the language codes used by Mozilla's translation model registry, and works out which models
 * are needed for a translation.
 *
 * All of Mozilla's models translate to or from English, so translating between two other
 * languages pivots through English using two models.
 *
 * No Android dependencies, so this can be unit tested.
 */
object BergamotLanguages {

	const val ENGLISH = "en"

	/** Registry code for Traditional Chinese. */
	const val CHINESE_TRADITIONAL = "zh_hant"

	/** Registry code for Serbo-Croatian (Bosnian, Croatian, Montenegrin, Serbian). */
	const val SERBO_CROATIAN = "hbs"

	fun pairKey(from: String, to: String) = "$from-$to"

	/**
	 * Registry codes which can be used to translate text in [appCode] into English, in order of
	 * preference.
	 *
	 * @param traditionalChinese True if the text is known to be written in Traditional Chinese.
	 */
	fun sourceCandidates(appCode: String, traditionalChinese: Boolean = false): List<String> =
		when (appCode) {
			// Mozilla publishes a single Serbo-Croatian -> English model
			"bs", "hr", "sr" -> listOf(appCode, SERBO_CROATIAN)
			// The Nynorsk models are unreleased: Bokmål is close enough to be useful
			"nn" -> listOf("nn", "nb", "no")
			"nb" -> listOf("nb", "no")
			"zh" -> if (traditionalChinese) {
				listOf(CHINESE_TRADITIONAL, "zh")
			} else {
				listOf("zh", CHINESE_TRADITIONAL)
			}
			"fil" -> listOf("tl")
			else -> listOf(appCode)
		}

	/**
	 * Registry codes which can be used to translate English text into [appCode], in order of
	 * preference.
	 */
	fun targetCandidates(appCode: String): List<String> = when (appCode) {
		"nn" -> listOf("nn", "nb", "no")
		"nb" -> listOf("nb", "no")
		"fil" -> listOf("tl")
		else -> listOf(appCode)
	}

	/**
	 * Works out which models (registry pair keys, e.g. "es-en") are needed to translate from
	 * [from] to [to] (app codes).
	 *
	 * @param isUsable Whether a model (pair key) can be used: for example, whether it is
	 * installed, or whether it exists in the registry.
	 * @return One pair key for a direct translation, two to pivot through English, or null if
	 * no suitable models are usable.
	 */
	fun resolve(
		from: String,
		to: String,
		traditionalChinese: Boolean = false,
		isUsable: (String) -> Boolean,
	): List<String>? {

		if (from == to) {
			return null
		}

		val toEnglish = if (from == ENGLISH) {
			null
		} else {
			sourceCandidates(from, traditionalChinese)
				.map { pairKey(it, ENGLISH) }
				.firstOrNull(isUsable)
				?: return null
		}

		val fromEnglish = if (to == ENGLISH) {
			null
		} else {
			targetCandidates(to)
				.map { pairKey(ENGLISH, it) }
				.firstOrNull(isUsable)
				?: return null
		}

		return listOfNotNull(toEnglish, fromEnglish)
	}
}
