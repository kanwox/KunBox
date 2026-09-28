package com.kunk.singbox.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfilesDragPolicyTest {

    @Test
    fun dragAutoScrollFollowsViewportEdges() {
        val top = calculateProfileDragAutoScroll(
            pointerY = 10f,
            viewportTop = 0f,
            viewportBottom = 500f,
            edgeThreshold = 100f,
            maxScrollPerFrame = 40f
        )
        val middle = calculateProfileDragAutoScroll(
            pointerY = 250f,
            viewportTop = 0f,
            viewportBottom = 500f,
            edgeThreshold = 100f,
            maxScrollPerFrame = 40f
        )
        val bottom = calculateProfileDragAutoScroll(
            pointerY = 490f,
            viewportTop = 0f,
            viewportBottom = 500f,
            edgeThreshold = 100f,
            maxScrollPerFrame = 40f
        )

        assertTrue(top < 0f)
        assertEquals(0f, middle, 0f)
        assertTrue(bottom > 0f)
        assertTrue(kotlin.math.abs(top) <= 40f)
        assertTrue(kotlin.math.abs(bottom) <= 40f)
    }

    @Test
    fun dragUsesReorderableLibrary() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("rememberReorderableLazyListState"))
        assertTrue(source.contains("ReorderableItem"))
        assertTrue(source.contains("longPressDraggableHandle"))
    }
}
