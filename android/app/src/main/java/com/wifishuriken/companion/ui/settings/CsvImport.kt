package com.wifishuriken.companion.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import com.wifishuriken.companion.data.loadOuiTable
import com.wifishuriken.companion.live.ArchiveStore
import com.wifishuriken.companion.live.ImportOutcome
import com.wifishuriken.companion.live.ManualImporter
import com.wifishuriken.companion.ui.components.formatCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Imports the picked files one by one and returns a one-line summary for the Settings screen. */
suspend fun importCsvFiles(context: Context, uris: List<Uri>, onProgress: (Int, Int) -> Unit): String = withContext(Dispatchers.IO) {
  val db = CompanionDb.get(context)
  val importer = ManualImporter(ArchiveStore(File(context.filesDir, "archive")), Ingestor(db, loadOuiTable(context)), db)
  val outcomes = uris.mapIndexed { i, uri ->
    withContext(Dispatchers.Main) { onProgress(i + 1, uris.size) }
    val name = displayName(context, uri)
    runCatching {
      val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot open")
      importer.import(name, bytes)
    }.getOrElse { ImportOutcome.NotShurikenCsv(name) }
  }
  summarize(outcomes)
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
    if (c.moveToFirst()) c.getString(0) else null
  }
}.getOrNull() ?: uri.lastPathSegment ?: "file"

fun summarize(outcomes: List<ImportOutcome>): String {
  val imported = outcomes.filterIsInstance<ImportOutcome.Imported>()
  val parts = listOfNotNull(
    "Imported ${imported.size} (${formatCount(imported.sumOf { it.rows })} rows)",
    outcomes.count { it is ImportOutcome.AlreadyImported }.takeIf { it > 0 }?.let { "$it already imported" },
    outcomes.count { it is ImportOutcome.Conflict }.takeIf { it > 0 }?.let { "$it skipped (same name, different content)" },
    outcomes.count { it is ImportOutcome.NotShurikenCsv }.takeIf { it > 0 }?.let { "$it not Shuriken CSV" },
    outcomes.count { it is ImportOutcome.NoRows }.takeIf { it > 0 }?.let { "$it without rows" },
    outcomes.count { it is ImportOutcome.TooLarge }.takeIf { it > 0 }?.let { "$it too large" },
  )
  return parts.joinToString(", ") + "."
}

/** File types offered by the picker; providers label CSV files inconsistently. */
val CSV_TYPES = arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")
