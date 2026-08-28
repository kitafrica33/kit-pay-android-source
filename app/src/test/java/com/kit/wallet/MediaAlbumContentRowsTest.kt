package com.kit.wallet

import com.kit.wallet.feature.chat.mediaAlbumContentRows
import com.kit.wallet.ui.model.MessageMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAlbumContentRowsTest {
    @Test
    fun `consecutive photos and videos pair while a trailing visual stands alone`() {
        val items = listOf(
            item("a", "image/jpeg"),
            item("b", "video/mp4"),
            item("c", "image/png"),
        )
        assertEquals(
            listOf(listOf(items[0], items[1]), listOf(items[2])),
            mediaAlbumContentRows(items),
        )
    }

    @Test
    fun `a document or voice note takes its own row without reordering neighbours`() {
        val items = listOf(
            item("a", "image/jpeg"),
            item("b", "application/pdf"),
            item("c", "image/webp"),
            item("d", "audio/mp4"),
            item("e", "video/mp4"),
        )
        // Display order is the descriptor's order: grouping may only ever split runs, so the
        // flattened rows always read back as exactly the album.
        val rows = mediaAlbumContentRows(items)
        assertEquals(items.map { listOf(it) }, rows)
        assertEquals(items, rows.flatten())
    }

    @Test
    fun `four visuals make two even rows`() {
        val items = (0..3).map { item("$it", if (it % 2 == 0) "image/jpeg" else "video/mp4") }
        assertEquals(listOf(items.take(2), items.drop(2)), mediaAlbumContentRows(items))
    }

    private fun item(id: String, mediaType: String) = MessageMediaItem(
        attachmentId = id,
        mediaType = mediaType,
        plaintextBytes = 4,
    )
}
