package org.nanokvm.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.nanokvm.mobile.runtime.PicoClawMessageContent
import org.nanokvm.mobile.runtime.PicoClawMessageRole
import org.nanokvm.mobile.runtime.PicoClawMessageUiState
import org.nanokvm.mobile.runtime.PicoClawRuntimeUiPhase

class PicoClawDialogTest {
    @Test
    fun `every runtime phase and message role has an explicit resource label`() {
        assertEquals(
            PicoClawRuntimeUiPhase.entries.size,
            PicoClawRuntimeUiPhase.entries
                .map(::picoClawRuntimePhaseLabelResource)
                .distinct()
                .size,
        )
        assertEquals(
            PicoClawMessageRole.entries.size,
            PicoClawMessageRole.entries
                .map(::picoClawMessageRoleLabelResource)
                .distinct()
                .size,
        )
    }

    @Test
    fun `app authored screen observation is semantic`() {
        val message = PicoClawMessageUiState(
            PicoClawMessageContent.ScreenObservationCaptured,
        )

        assertSame(PicoClawMessageContent.ScreenObservationCaptured, message.content)
        assertEquals(PicoClawMessageRole.Observation, message.role)
    }

    @Test
    fun `semantic tool event retains bounded action as data`() {
        val message = PicoClawMessageUiState(
            PicoClawMessageContent.ToolAction("click(10,20)"),
        )

        assertEquals(
            "click(10,20)",
            (message.content as PicoClawMessageContent.ToolAction).action,
        )
        assertEquals(PicoClawMessageRole.Tool, message.role)
    }

    @Test
    fun `legacy looking appliance content remains verbatim data`() {
        val values = listOf(
            PicoClawMessageContent.ApplianceText(
                PicoClawMessageRole.Observation,
                "Screen observation captured.",
            ),
            PicoClawMessageContent.ApplianceText(
                PicoClawMessageRole.Tool,
                "Action: literal appliance text",
            ),
        )
        val messages = values.map(::PicoClawMessageUiState)

        assertSame(values[0], messages[0].content)
        assertSame(values[1], messages[1].content)
        assertEquals("Screen observation captured.", values[0].value)
        assertEquals("Action: literal appliance text", values[1].value)
        assertEquals(PicoClawMessageRole.Observation, messages[0].role)
        assertEquals(PicoClawMessageRole.Tool, messages[1].role)
    }
}
