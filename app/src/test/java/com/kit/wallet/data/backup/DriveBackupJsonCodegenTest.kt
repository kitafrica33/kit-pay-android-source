package com.kit.wallet.data.backup

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Guards the release-startup adapters that must remain independent of Kotlin reflection. */
class DriveBackupJsonCodegenTest {
    private val moshi = Moshi.Builder().build()

    @Test fun `every backup dto has a generated adapter`() {
        assertGeneratedAdapter(DriveFileResponse::class.java)
        assertGeneratedAdapter(DriveFileListResponse::class.java)
        assertGeneratedAdapter(DriveFileMetadata::class.java)
        assertGeneratedAdapter(StoredBackupState::class.java)
    }

    @Test fun `a backup state written before run observability keeps its safe defaults`() {
        val oldState = """
            {
              "accountId":"user-1",
              "connected":true,
              "frequency":"DAILY",
              "requiresUnmeteredNetwork":true,
              "recoveryCodeConfirmed":true,
              "driveFileId":"drive-1",
              "lastBackupAtEpochMillis":1000,
              "lastBackupBytes":2000,
              "lastBackupMessageCount":3,
              "backupKey":null
            }
        """.trimIndent()

        val decoded = Moshi.Builder().build()
            .adapter(StoredBackupState::class.java)
            .fromJson(oldState)

        assertNotNull(decoded)
        assertEquals(null, decoded?.lastAttemptAtEpochMillis)
        assertEquals(MessageBackupRunStatus.NEVER.name, decoded?.lastRunStatus)
        assertEquals(0, decoded?.consecutiveFailures)
    }

    private fun <T> assertGeneratedAdapter(type: Class<T>) {
        val expectedName = "${type.name}JsonAdapter"
        assertEquals(expectedName, Class.forName(expectedName).name)
        // Resolution with no KotlinJsonAdapterFactory proves Moshi can actually find the generated
        // class instead of silently falling back to reflection in a debug build.
        assertNotNull(moshi.adapter(type))
    }
}
