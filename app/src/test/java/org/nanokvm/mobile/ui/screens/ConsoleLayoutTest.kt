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
    fun expandedWidthUsesSupportingPaneAtAnyHeight() {
        assertEquals(
            ConsoleControlsPresentation.SupportingPane,
            consoleControlsPresentation(
                isExpandedWidth = true,
                isCompactWidth = false,
                heightDp = 300f,
            ),
        )
    }
}
