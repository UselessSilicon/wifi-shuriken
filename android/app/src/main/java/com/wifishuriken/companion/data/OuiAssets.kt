package com.wifishuriken.companion.data

import android.content.Context
import com.wifishuriken.companion.core.OuiTable

/** The IEEE vendor table bundled at build time, or null if it is missing. */
fun loadOuiTable(context: Context): OuiTable? = runCatching {
  context.assets.open("oui/oui.tsv").bufferedReader().useLines { lines -> OuiTable(lines) }
}.getOrNull()
