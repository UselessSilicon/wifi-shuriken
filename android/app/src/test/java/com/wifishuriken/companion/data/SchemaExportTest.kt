package com.wifishuriken.companion.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SchemaExportTest {
  @Test
  fun currentSchemaIsExportedForFutureMigrations() {
    val f = File("schemas/com.wifishuriken.companion.data.CompanionDb/1.json")
    assertTrue("missing ${f.absolutePath}", f.isFile)
    assertTrue(f.readText().contains("\"version\": 1"))
  }
}
