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

package org.quantumbadger.redreader.compose.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.activities.BaseActivity
import org.quantumbadger.redreader.translation.BergamotLanguages
import org.quantumbadger.redreader.translation.TranslationLanguages
import org.quantumbadger.redreader.translation.TranslationManager
import org.quantumbadger.redreader.translation.TranslationModelManager
import org.quantumbadger.redreader.translation.TranslationModelProvider
import java.text.Collator
import java.util.Locale

private class ModelRow(
	val entry: TranslationModelManager.ModelEntry,
	val title: String,
	val needed: Boolean,
	/** True if the model is useful for translating into the user's primary language. */
	val useful: Boolean,
	/** True if this is the English -> primary language model (used for pivoting). */
	val isPivotTarget: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationModelsScreen(
	activity: BaseActivity,
	onBackPressed: () -> Unit,
) {
	val manager = remember { TranslationModelManager.getInstance(activity) }

	// Incremented whenever the state of the models changes, to trigger recomposition
	var version by remember { mutableIntStateOf(0) }

	// The manager only holds listeners weakly, so keep a reference here
	val listener = remember { TranslationModelProvider.Listener { version++ } }

	DisposableEffect(manager) {
		manager.addListener(listener)
		onDispose { manager.removeListener(listener) }
	}

	val entries = remember(version) { manager.getEntries() }
	val registryState = remember(version) { manager.getRegistryState() }
	val registryError = remember(version) { manager.getRegistryError() }

	val locale = remember {
		ConfigurationCompat.getLocales(activity.resources.configuration).get(0)
			?: Locale.getDefault()
	}

	// Settings can't change while this screen is shown
	val primaryLanguage = remember { TranslationManager.getSettings().targetLanguage }
	val primaryLanguageName = remember {
		TranslationLanguages.getDisplayName(primaryLanguage, locale)
	}

	val rows = remember(entries) {
		entries?.let { buildRows(activity, it, locale, primaryLanguage) }
	}

	var showAll by rememberSaveable { mutableStateOf(false) }

	var pendingDelete by remember { mutableStateOf<ModelRow?>(null) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.translation_models_title)) },
				navigationIcon = {
					IconButton(onClick = onBackPressed) {
						Icon(
							painter = painterResource(R.drawable.ic_action_back_dark),
							contentDescription = stringResource(R.string.action_back),
						)
					}
				}
			)
		}
	) { padding ->

		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = padding,
		) {
			item {
				Text(
					modifier = Modifier.padding(16.dp),
					text = stringResource(R.string.translation_models_description),
					style = MaterialTheme.typography.bodyMedium,
				)
			}

			if (registryState == TranslationModelManager.RegistryState.FAILED) {
				item {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							modifier = Modifier.weight(1f),
							text = stringResource(
								R.string.translation_models_load_failed,
								registryError ?: "?"
							),
							color = MaterialTheme.colorScheme.error,
							style = MaterialTheme.typography.bodyMedium,
						)
						TextButton(onClick = { manager.ensureRegistryLoaded(true) }) {
							Text(stringResource(R.string.translation_models_retry))
						}
					}
				}
			}

			item {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.toggleable(
							value = showAll,
							role = Role.Switch,
							onValueChange = { showAll = it },
						)
						.padding(horizontal = 16.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						modifier = Modifier.weight(1f),
						text = stringResource(R.string.translation_models_show_all),
						style = MaterialTheme.typography.bodyLarge,
					)
					Switch(checked = showAll, onCheckedChange = null)
				}
			}

			if (rows == null) {
				item {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(16.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(16.dp),
					) {
						CircularProgressIndicator()
						Text(stringResource(R.string.translation_models_loading))
					}
				}
				return@LazyColumn
			}

			val note = when {
				showAll || primaryLanguage == BergamotLanguages.ENGLISH -> null
				rows.any { it.isPivotTarget } -> R.string.translation_models_pivot_note
				registryState == TranslationModelManager.RegistryState.LOADED ->
					R.string.translation_models_no_target
				else -> null
			}

			if (note != null) {
				item {
					Text(
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
						text = stringResource(note, primaryLanguageName),
						style = MaterialTheme.typography.bodyMedium,
					)
				}
			}

			val shownRows = if (showAll) {
				rows
			} else {
				rows.filter {
					it.useful
							|| it.needed
							|| it.entry.installed != null
							|| it.entry.downloadProgress != null
				}
			}

			val (installed, available) = shownRows.partition {
				it.entry.installed != null || it.entry.downloadProgress != null
			}

			if (installed.isNotEmpty()) {
				item { SectionHeader(stringResource(R.string.translation_models_installed)) }
				items(installed, key = { "i-" + it.entry.pairKey }) { row ->
					ModelRowView(
						row = row,
						onDownload = {},
						onDelete = { pendingDelete = row },
						onCancel = { manager.cancelDownload(row.entry.pairKey) },
					)
				}
			}

			if (available.isNotEmpty()) {
				item { SectionHeader(stringResource(R.string.translation_models_available)) }
				items(available, key = { "a-" + it.entry.pairKey }) { row ->
					ModelRowView(
						row = row,
						onDownload = {
							manager.downloadWithConfirmation(activity, listOf(row.entry.pairKey))
						},
						onDelete = {},
						onCancel = {},
					)
				}
			}

			item { Spacer(Modifier.height(16.dp)) }
		}
	}

	pendingDelete?.let { row ->
		AlertDialog(
			onDismissRequest = { pendingDelete = null },
			text = { Text(stringResource(R.string.translation_models_delete_confirm, row.title)) },
			confirmButton = {
				TextButton(onClick = {
					manager.delete(row.entry.pairKey)
					pendingDelete = null
				}) {
					Text(stringResource(R.string.translation_models_action_delete))
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingDelete = null }) {
					Text(stringResource(R.string.dialog_cancel))
				}
			}
		)
	}
}

private fun buildRows(
	activity: BaseActivity,
	entries: List<TranslationModelManager.ModelEntry>,
	locale: Locale,
	primaryLanguage: String,
): List<ModelRow> {

	val registryKeys = entries.map { it.pairKey }.toSet()

	// The models that translate into the primary language: X -> English, plus (unless the
	// primary language is English) English -> primary, to pivot through English
	val primaryAsSource = BergamotLanguages.sourceCandidates(primaryLanguage)
	val pivotTargets = if (primaryLanguage == BergamotLanguages.ENGLISH) {
		emptyList()
	} else {
		BergamotLanguages.targetCandidates(primaryLanguage)
	}

	// Mozilla may publish more than one candidate (e.g. "nb" and "no"): only the preferred
	// one is used
	val pivotTargetKey = pivotTargets
		.map { BergamotLanguages.pairKey(BergamotLanguages.ENGLISH, it) }
		.firstOrNull { it in registryKeys }

	fun isUseful(entry: TranslationModelManager.ModelEntry): Boolean = when {
		entry.pairKey == pivotTargetKey -> true
		// Without a model into the primary language, X -> English models are of no use
		pivotTargets.isNotEmpty() && pivotTargetKey == null -> false
		entry.targetLanguage == BergamotLanguages.ENGLISH ->
			entry.sourceLanguage !in primaryAsSource
		else -> false
	}

	// Models needed for text the user has seen, which couldn't be translated
	val needed = HashSet<String>()
	for (pair in TranslationManager.getInstance().missingModelPairs) {
		BergamotLanguages.resolve(pair.from, pair.to) { it in registryKeys }
			?.let { needed.addAll(it) }
	}

	fun name(code: String) = TranslationModelManager.getLanguageDisplayName(activity, code, locale)

	val collator = Collator.getInstance(locale)

	return entries
		.map { entry ->
			ModelRow(
				entry = entry,
				title = activity.getString(
					R.string.translation_models_direction,
					name(entry.sourceLanguage),
					name(entry.targetLanguage)
				),
				needed = entry.pairKey in needed && entry.installed == null,
				useful = isUseful(entry),
				isPivotTarget = entry.pairKey == pivotTargetKey,
			)
		}
		.sortedWith { a, b ->
			// Needed but missing models first, then the English -> primary model
			fun rank(row: ModelRow) = when {
				row.needed -> 0
				row.isPivotTarget -> 1
				else -> 2
			}

			val rankResult = rank(a) - rank(b)

			if (rankResult != 0) {
				return@sortedWith rankResult
			}

			// Group both directions for each language together, sorted by the name of the
			// language which isn't English
			fun otherLanguage(row: ModelRow) =
				if (row.entry.sourceLanguage == BergamotLanguages.ENGLISH) {
					row.entry.targetLanguage
				} else {
					row.entry.sourceLanguage
				}

			val result = collator.compare(name(otherLanguage(a)), name(otherLanguage(b)))

			if (result != 0) {
				return@sortedWith result
			}

			// X -> English first
			fun directionRank(row: ModelRow) =
				if (row.entry.targetLanguage == BergamotLanguages.ENGLISH) 0 else 1

			val direction = directionRank(a) - directionRank(b)

			if (direction != 0) direction else a.entry.pairKey.compareTo(b.entry.pairKey)
		}
}

@Composable
private fun SectionHeader(text: String) {
	Text(
		modifier = Modifier
			.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
			.semantics { heading() },
		text = text,
		color = MaterialTheme.colorScheme.primary,
		style = MaterialTheme.typography.titleSmall,
	)
}

@Composable
private fun ModelRowView(
	row: ModelRow,
	onDownload: () -> Unit,
	onDelete: () -> Unit,
	onCancel: () -> Unit,
) {
	val context = androidx.compose.ui.platform.LocalContext.current
	val entry = row.entry
	val progress = entry.downloadProgress

	Column(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.weight(1f)) {

				Text(text = row.title, style = MaterialTheme.typography.bodyLarge)

				val subtitle = when {
					progress != null -> stringResource(
						R.string.translation_models_downloading,
						Math.round(progress * 100)
					)

					entry.installed != null -> stringResource(
						R.string.translation_models_installed_size,
						Formatter.formatShortFileSize(context, entry.installed.sizeBytes)
					)

					entry.downloadBytes > 0 -> stringResource(
						R.string.translation_models_download_size,
						Formatter.formatShortFileSize(context, entry.downloadBytes)
					)

					else -> null
				}

				if (subtitle != null) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				if (row.needed) {
					Text(
						text = stringResource(R.string.translation_models_needed),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}

				if (progress != null) {
					Box(modifier = Modifier.padding(top = 6.dp, end = 8.dp)) {
						LinearProgressIndicator(
							progress = { progress },
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			}

			when {
				progress != null -> TextButton(onClick = onCancel) {
					Text(stringResource(R.string.translation_models_action_cancel))
				}

				entry.installed != null -> TextButton(onClick = onDelete) {
					Text(stringResource(R.string.translation_models_action_delete))
				}

				entry.record != null -> TextButton(onClick = onDownload) {
					Text(stringResource(R.string.translation_models_action_download))
				}
			}
		}

		HorizontalDivider(modifier = Modifier.padding(PaddingValues(start = 16.dp)))
	}
}
