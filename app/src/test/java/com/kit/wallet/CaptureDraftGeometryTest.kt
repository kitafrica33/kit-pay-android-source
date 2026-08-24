package com.kit.wallet

import com.kit.wallet.feature.chat.camera.CropHandle
import com.kit.wallet.feature.chat.camera.EditorPoint
import com.kit.wallet.feature.chat.camera.EditorRect
import com.kit.wallet.feature.chat.camera.clampCaptionToWire
import com.kit.wallet.feature.chat.camera.mapThroughCrop
import com.kit.wallet.feature.chat.camera.resizeCropRect
import com.kit.wallet.feature.chat.camera.rotateQuarterClockwise
import com.kit.wallet.feature.chat.camera.widthFractionAfterCrop
import com.kit.wallet.feature.chat.camera.widthFractionAfterQuarterRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDraftGeometryTest {

    @Test
    fun `quarter clockwise rotation maps the corners around the square`() {
        assertPointEquals(EditorPoint(1f, 0f), rotateQuarterClockwise(EditorPoint(0f, 0f)))
        assertPointEquals(EditorPoint(1f, 1f), rotateQuarterClockwise(EditorPoint(1f, 0f)))
        assertPointEquals(EditorPoint(0f, 1f), rotateQuarterClockwise(EditorPoint(1f, 1f)))
        assertPointEquals(EditorPoint(0f, 0f), rotateQuarterClockwise(EditorPoint(0f, 1f)))
    }

    @Test
    fun `quarter clockwise rotation keeps the center fixed`() {
        assertPointEquals(EditorPoint(0.5f, 0.5f), rotateQuarterClockwise(EditorPoint(0.5f, 0.5f)))
    }

    @Test
    fun `width fraction rescaled by the aspect ratio keeps its pixel size across rotation`() {
        val width = 4_000f
        val height = 3_000f
        val fraction = 0.02f

        val rotated = widthFractionAfterQuarterRotation(fraction, aspectRatio = width / height)

        // 80 px of a 4000 px wide image must still be 80 px once the width becomes 3000 px.
        assertEquals(fraction * width, rotated * height, 1e-4f)
    }

    @Test
    fun `crop corners map to the unit corners and its center to the unit center`() {
        val crop = EditorRect(left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.7f)

        assertPointEquals(EditorPoint(0f, 0f), mapThroughCrop(EditorPoint(0.2f, 0.3f), crop))
        assertPointEquals(EditorPoint(1f, 1f), mapThroughCrop(EditorPoint(0.8f, 0.7f), crop))
        assertPointEquals(EditorPoint(0.5f, 0.5f), mapThroughCrop(EditorPoint(0.5f, 0.5f), crop))
    }

    @Test
    fun `degenerate zero-size crop returns the point unchanged`() {
        val point = EditorPoint(0.4f, 0.6f)
        val zeroWidth = EditorRect(left = 0.5f, top = 0.1f, right = 0.5f, bottom = 0.9f)
        val zeroHeight = EditorRect(left = 0.1f, top = 0.5f, right = 0.9f, bottom = 0.5f)

        assertPointEquals(point, mapThroughCrop(point, zeroWidth))
        assertPointEquals(point, mapThroughCrop(point, zeroHeight))
    }

    @Test
    fun `width fraction grows by the inverse of the crop width`() {
        val halfWide = EditorRect(left = 0.25f, top = 0f, right = 0.75f, bottom = 1f)
        assertEquals(0.2f, widthFractionAfterCrop(0.1f, halfWide), EPSILON)
    }

    @Test
    fun `degenerate crop width leaves the fraction unchanged`() {
        val degenerate = EditorRect(left = 0.5f, top = 0f, right = 0.5f, bottom = 1f)
        assertEquals(0.1f, widthFractionAfterCrop(0.1f, degenerate), EPSILON)
    }

    @Test
    fun `handles clamp to the image bounds`() {
        val rect = EditorRect(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)
        val minSize = 0.1f

        val topLeft = resizeCropRect(rect, CropHandle.TOP_LEFT, dx = -1f, dy = -1f, minSize = minSize)
        assertEquals(0f, topLeft.left, EPSILON)
        assertEquals(0f, topLeft.top, EPSILON)
        assertEquals(0.8f, topLeft.right, EPSILON)
        assertEquals(0.8f, topLeft.bottom, EPSILON)

        val topRight = resizeCropRect(rect, CropHandle.TOP_RIGHT, dx = 1f, dy = -1f, minSize = minSize)
        assertEquals(1f, topRight.right, EPSILON)
        assertEquals(0f, topRight.top, EPSILON)
        assertEquals(0.2f, topRight.left, EPSILON)
        assertEquals(0.8f, topRight.bottom, EPSILON)

        val bottomLeft = resizeCropRect(rect, CropHandle.BOTTOM_LEFT, dx = -1f, dy = 1f, minSize = minSize)
        assertEquals(0f, bottomLeft.left, EPSILON)
        assertEquals(1f, bottomLeft.bottom, EPSILON)
        assertEquals(0.8f, bottomLeft.right, EPSILON)
        assertEquals(0.2f, bottomLeft.top, EPSILON)

        val bottomRight = resizeCropRect(rect, CropHandle.BOTTOM_RIGHT, dx = 1f, dy = 1f, minSize = minSize)
        assertEquals(1f, bottomRight.right, EPSILON)
        assertEquals(1f, bottomRight.bottom, EPSILON)
        assertEquals(0.2f, bottomRight.left, EPSILON)
        assertEquals(0.2f, bottomRight.top, EPSILON)
    }

    @Test
    fun `dragging TOP_LEFT past BOTTOM_RIGHT pins the crop at minSize`() {
        val pinned = resizeCropRect(EditorRect.FULL, CropHandle.TOP_LEFT, dx = 2f, dy = 2f, minSize = 0.1f)

        assertEquals(0.9f, pinned.left, EPSILON)
        assertEquals(0.9f, pinned.top, EPSILON)
        assertEquals(1f, pinned.right, EPSILON)
        assertEquals(1f, pinned.bottom, EPSILON)
        assertEquals(0.1f, pinned.width, EPSILON)
        assertEquals(0.1f, pinned.height, EPSILON)
    }

    @Test
    fun `no handle can shrink the crop below minSize`() {
        val minSize = 0.2f
        val rect = EditorRect(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f)

        for (handle in CropHandle.values()) {
            // Drag every handle hard toward its opposite corner.
            val (dx, dy) = when (handle) {
                CropHandle.TOP_LEFT -> 5f to 5f
                CropHandle.TOP_RIGHT -> -5f to 5f
                CropHandle.BOTTOM_LEFT -> 5f to -5f
                CropHandle.BOTTOM_RIGHT -> -5f to -5f
            }
            val resized = resizeCropRect(rect, handle, dx, dy, minSize)

            assertEquals("$handle width", minSize, resized.width, EPSILON)
            assertEquals("$handle height", minSize, resized.height, EPSILON)
        }
    }

    @Test
    fun `caption whitespace is trimmed`() {
        assertEquals("hello", clampCaptionToWire("  hello  "))
    }

    @Test
    fun `blank caption becomes null`() {
        assertNull(clampCaptionToWire(""))
        assertNull(clampCaptionToWire("   \n\t "))
    }

    @Test
    fun `ascii caption within bounds passes through unchanged`() {
        val caption = "Sent from the kit camera"
        assertEquals(caption, clampCaptionToWire(caption))
    }

    @Test
    fun `multi-byte caption clamps to the byte bound without splitting a surrogate pair`() {
        val emoji = "😀" // one emoji = a surrogate pair = 4 UTF-8 bytes
        val clamped = clampCaptionToWire(emoji.repeat(600))

        assertNotNull(clamped)
        assertTrue(clamped!!.isNotEmpty())
        assertTrue(clamped.toByteArray(Charsets.UTF_8).size <= 2_048)
        assertFalse(clamped.last().isHighSurrogate())
    }

    @Test
    fun `caption exactly at the byte bound survives intact`() {
        val caption = "a".repeat(2_048)
        assertEquals(caption, clampCaptionToWire(caption))
    }

    private fun assertPointEquals(expected: EditorPoint, actual: EditorPoint) {
        assertEquals(expected.x, actual.x, EPSILON)
        assertEquals(expected.y, actual.y, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
