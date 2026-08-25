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

    private fun <T> assertGeneratedAdapter(type: Class<T>) {
        val expectedName = "${type.name}JsonAdapter"
        assertEquals(expectedName, Class.forName(expectedName).name)
        // Resolution with no KotlinJsonAdapterFactory proves Moshi can actually find the generated
        // class instead of silently falling back to reflection in a debug build.
        assertNotNull(moshi.adapter(type))
    }
}
