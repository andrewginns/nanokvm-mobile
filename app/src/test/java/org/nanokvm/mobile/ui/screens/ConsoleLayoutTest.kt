package org.nanokvm.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleLayoutTest {
    @Test
    fun compactPortraitUsesBottomSheet() {
        assertEquals(
            ConsoleControlsPresentation.BottomSheet,
            consoleControlsPresentation(
                isExpandedWidth = false,
                isCompactWidth = true,
                heightDp = 720f,
            ),
        )
    }

    @Test
    fun compactLandscapeUsesSideOverlay() {
        assertEquals(
            ConsoleControlsPresentation.SideOverlay,
            consoleControlsPresentation(
                isExpandedWidth = false,
                isCompactWidth = true,
                heightDp = 360f,
            ),
        )
    }

    @Test
    fun compactHeightBreakpointStartsBottomSheetPresentation() {
        assertEquals(
            ConsoleControlsPresentation.SideOverlay,
            consoleControlsPresentation(
                isExpandedWidth = false,
                isCompactWidth = true,
                heightDp = 479.9f,
            ),
        )
        assertEquals(
            ConsoleControlsPresentation.BottomSheet,
            consoleControlsPresentation(
                isExpandedWidth = false,
                isCompactWidth = true,
                heightDp = 480f,
            ),
        )
    }

    @Test
    fun mediumWidthUsesSideOverlay() {
        assertEquals(
            ConsoleControlsPresentation.SideOverlay,
            consoleControlsPresentation(
                isExpandedWidth = false,
                isCompactWidth = false,
                heightDp = 900f,
            ),
        )
    }

    @Test
    fun compactHeightOverridesExpandedWidth() {
        assertEquals(
            ConsoleControlsPresentation.SideOverlay,
            consoleControlsPresentation(
                isExpandedWidth = true,
                isCompactWidth = false,
                heightDp = 479f,
            ),
        )
        assertEquals(
            ConsoleControlsPresentation.SupportingPane,
            consoleControlsPresentation(
                isExpandedWidth = true,
                isCompactWidth = false,
                heightDp = 480f,
            ),
        )
    }

    @Test
    fun widthAndHeightBoundaryMatrixIsDeterministic() {
        data class Case(
            val expanded: Boolean,
            val compact: Boolean,
            val height: Float,
            val expected: ConsoleControlsPresentation,
        )

        listOf(
            Case(false, true, 479f, ConsoleControlsPresentation.SideOverlay),
            Case(false, true, 480f, ConsoleControlsPresentation.BottomSheet),
            Case(false, false, 479f, ConsoleControlsPresentation.SideOverlay),
            Case(false, false, 480f, ConsoleControlsPresentation.SideOverlay),
            Case(true, false, 479f, ConsoleControlsPresentation.SideOverlay),
            Case(true, false, 480f, ConsoleControlsPresentation.SupportingPane),
        ).forEach { case ->
            assertEquals(
                case.expected,
                consoleControlsPresentation(case.expanded, case.compact, case.height),
            )
        }
    }
}
