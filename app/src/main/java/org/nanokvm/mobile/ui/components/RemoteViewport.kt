package org.nanokvm.mobile.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import org.nanokvm.mobile.R
import org.nanokvm.mobile.data.DEFAULT_SCROLL_SENSITIVITY
import org.nanokvm.mobile.data.normalizeScrollSensitivity
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.ui.input.ExternalAbsolutePosition
import org.nanokvm.mobile.ui.input.ExternalPointerInputRouter
import org.nanokvm.mobile.ui.input.ExternalPointerRoutingMode
import org.nanokvm.mobile.ui.input.PointerCaptureController
import org.nanokvm.mobile.ui.input.PointerCaptureHost
import org.nanokvm.mobile.ui.input.PointerCaptureReleaseReason
import org.nanokvm.mobile.ui.input.dispatchTo
import org.nanokvm.mobile.ui.input.routeExternalMouseInput
import org.nanokvm.mobile.ui.theme.LocalConsoleColorScheme
import org.nanokvm.mobile.ui.viewport.FloatPoint
import org.nanokvm.mobile.ui.viewport.FloatRect
import org.nanokvm.mobile.ui.viewport.FloatSize
import org.nanokvm.mobile.ui.viewport.RemotePoint
import org.nanokvm.mobile.ui.viewport.ViewportTransform
import org.nanokvm.mobile.ui.viewport.ViewportScaleMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class PointerMode { Direct, Trackpad, Captured }

enum class ViewportAction { FocusTop, Center, FocusBottom, ZoomOut, ZoomIn, Fit, ActualSize }

data class ViewportCommand(
    val sequence: Int,
    val action: ViewportAction,
)

@Composable
fun RemoteViewport(
    input: RemoteInputSink,
    videoSurface: VideoSurfaceSink,
    remoteWidth: Int,
    remoteHeight: Int,
    videoSurfaceGeneration: Long,
    pointerMode: PointerMode,
    fitRequest: Int,
    modifier: Modifier = Modifier,
    inputGeneration: Long = 0,
    viewNavigationVisible: Boolean = false,
    keyboardVisible: Boolean = false,
    viewportCommand: ViewportCommand? = null,
    scrollSensitivity: Float = DEFAULT_SCROLL_SENSITIVITY,
    pointerCaptureController: PointerCaptureController? = null,
    onZoomChanged: (Float) -> Unit = {},
    onViewportScaleChanged: (ViewportScaleMode, Float) -> Unit = { _, _ -> },
    navigationActions: @Composable () -> Unit = {},
) {
    val consoleColors = LocalConsoleColorScheme.current
    val normalizedScrollSensitivity = normalizeScrollSensitivity(scrollSensitivity)
    var localSize by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberSaveable(
        remoteWidth,
        remoteHeight,
        saver = viewportTransformStateSaver(remoteWidth, remoteHeight),
    ) {
        mutableStateOf(
            ViewportTransform.fit(
                remoteWidth = remoteWidth,
                remoteHeight = remoteHeight,
                viewport = localSize.toViewportSize(),
            ),
        )
    }
    LaunchedEffect(remoteWidth, remoteHeight) {
        onZoomChanged(transformState.value.zoom)
        onViewportScaleChanged(
            transformState.value.scaleMode,
            transformState.value.contentScale,
        )
    }
    val transform = transformState.value
    var lastDirectAnchor by remember(remoteWidth, remoteHeight) {
        mutableStateOf<RemotePoint?>(null)
    }
    var accessibilityPointer by remember(remoteWidth, remoteHeight) {
        mutableStateOf(RemotePoint(remoteWidth / 2f, remoteHeight / 2f))
    }
    var lastDirectAnchorAtMillis by remember { mutableLongStateOf(0L) }
    var keyboardFramingActive by remember { mutableStateOf(false) }
    var handledFitRequest by rememberSaveable(
        inputGeneration,
        remoteWidth,
        remoteHeight,
    ) { mutableIntStateOf(fitRequest) }
    LaunchedEffect(fitRequest) {
        if (fitRequest != handledFitRequest) {
            handledFitRequest = fitRequest
            keyboardFramingActive = false
            transformState.value = transformState.value.fit()
            onZoomChanged(transformState.value.zoom)
            onViewportScaleChanged(
                transformState.value.scaleMode,
                transformState.value.contentScale,
            )
        }
    }
    LaunchedEffect(inputGeneration, pointerCaptureController) {
        pointerCaptureController?.release(PointerCaptureReleaseReason.SessionChanged)
    }
    LaunchedEffect(pointerMode, pointerCaptureController) {
        if (pointerMode != PointerMode.Captured) {
            pointerCaptureController?.release(PointerCaptureReleaseReason.PointerModeChanged)
        }
    }
    LaunchedEffect(keyboardVisible, pointerCaptureController) {
        if (keyboardVisible) {
            pointerCaptureController?.release(PointerCaptureReleaseReason.KeyboardOpened)
        }
    }
    LaunchedEffect(keyboardVisible) {
        if (!keyboardVisible) {
            keyboardFramingActive = false
            return@LaunchedEffect
        }
        keyboardFramingActive = lastDirectAnchor != null &&
            SystemClock.elapsedRealtime() - lastDirectAnchorAtMillis <= INPUT_ANCHOR_MAX_AGE_MILLIS
        if (keyboardFramingActive) {
            lastDirectAnchor?.let { anchor ->
                transformState.value = transformState.value.revealVertically(anchor)
            }
        }
    }

    fun absoluteAt(offset: Offset, buttons: Set<MouseButton> = emptySet()): Boolean {
        val point = transformState.value.screenToRemote(FloatPoint(offset.x, offset.y)) ?: return false
        lastDirectAnchor = point
        accessibilityPointer = point
        lastDirectAnchorAtMillis = SystemClock.elapsedRealtime()
        if (keyboardVisible) keyboardFramingActive = false
        val hid = point.toHidAbsolute(remoteWidth, remoteHeight)
        input.moveAbsolute(hid.x, hid.y, buttons)
        return true
    }

    fun clickAt(offset: Offset, button: MouseButton) {
        if (!absoluteAt(offset)) return
        input.mouseButton(button, true)
        input.mouseButton(button, false)
    }

    fun clickCurrentCursor(button: MouseButton) {
        input.mouseButton(button, true)
        input.mouseButton(button, false)
    }

    fun movePointerForAccessibility(deltaX: Int, deltaY: Int): Boolean {
        if (pointerMode != PointerMode.Direct) {
            input.moveRelative(deltaX, deltaY)
            return true
        }
        accessibilityPointer = RemotePoint(
            x = (accessibilityPointer.x + deltaX).coerceIn(0f, (remoteWidth - 1).coerceAtLeast(0).toFloat()),
            y = (accessibilityPointer.y + deltaY).coerceIn(0f, (remoteHeight - 1).coerceAtLeast(0).toFloat()),
        )
        lastDirectAnchor = accessibilityPointer
        lastDirectAnchorAtMillis = SystemClock.elapsedRealtime()
        val hid = accessibilityPointer.toHidAbsolute(remoteWidth, remoteHeight)
        input.moveAbsolute(hid.x, hid.y)
        return true
    }

    fun clickPointerForAccessibility(button: MouseButton): Boolean {
        if (pointerMode == PointerMode.Direct) {
            val hid = accessibilityPointer.toHidAbsolute(remoteWidth, remoteHeight)
            input.moveAbsolute(hid.x, hid.y)
        }
        clickCurrentCursor(button)
        return true
    }

    fun panView(delta: FloatPoint) {
        keyboardFramingActive = false
        transformState.value = transformState.value.panBy(delta)
    }

    fun zoomView(multiplier: Float) {
        keyboardFramingActive = false
        val viewport = transformState.value.viewport
        transformState.value = transformState.value.zoomBy(
            multiplier = multiplier,
            focalPoint = FloatPoint(viewport.width / 2f, viewport.height / 2f),
        )
        onZoomChanged(transformState.value.zoom)
        onViewportScaleChanged(
            transformState.value.scaleMode,
            transformState.value.contentScale,
        )
    }

    fun fitView() {
        keyboardFramingActive = false
        transformState.value = transformState.value.fit()
        onZoomChanged(transformState.value.zoom)
        onViewportScaleChanged(
            transformState.value.scaleMode,
            transformState.value.contentScale,
        )
    }

    fun actualSizeView() {
        keyboardFramingActive = false
        transformState.value = transformState.value.actualSize()
        onZoomChanged(transformState.value.zoom)
        onViewportScaleChanged(
            transformState.value.scaleMode,
            transformState.value.contentScale,
        )
    }

    LaunchedEffect(viewportCommand) {
        val action = viewportCommand?.action ?: return@LaunchedEffect
        keyboardFramingActive = false
        when (action) {
            ViewportAction.FocusTop -> transformState.value = transformState.value.focusTop()
            ViewportAction.Center -> transformState.value = transformState.value.center()
            ViewportAction.FocusBottom -> transformState.value = transformState.value.focusBottom()
            ViewportAction.ZoomOut -> zoomView(0.8f)
            ViewportAction.ZoomIn -> zoomView(1.25f)
            ViewportAction.Fit -> fitView()
            ViewportAction.ActualSize -> actualSizeView()
        }
        if (
            action == ViewportAction.FocusTop ||
            action == ViewportAction.Center ||
            action == ViewportAction.FocusBottom
        ) {
            onZoomChanged(transformState.value.zoom)
            onViewportScaleChanged(
                transformState.value.scaleMode,
                transformState.value.contentScale,
            )
        }
    }

    // Reset synchronously when the navigation pad is hidden or re-docked. An asynchronous
    // LaunchedEffect can race the first expand tap after re-docking and immediately collapse it.
    var viewControlsVisible by remember(viewNavigationVisible) { mutableStateOf(false) }
    LaunchedEffect(pointerMode) {
        if (pointerMode == PointerMode.Captured) viewControlsVisible = false
    }
    BackHandler(enabled = viewNavigationVisible && viewControlsVisible) {
        viewControlsVisible = false
    }

    val remotePointerDescription = stringResource(R.string.console_remote_pointer)
    val movePointerLeftLabel = stringResource(R.string.console_move_pointer_left)
    val movePointerRightLabel = stringResource(R.string.console_move_pointer_right)
    val movePointerUpLabel = stringResource(R.string.console_move_pointer_up)
    val movePointerDownLabel = stringResource(R.string.console_move_pointer_down)
    val clickPointerLabel = stringResource(R.string.console_click_pointer)
    val rightClickPointerLabel = stringResource(R.string.console_right_click_pointer)

    val normalViewNavigationPosition = rememberSaveable { mutableFloatStateOf(1f) }
    val imeViewNavigationPosition = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(keyboardVisible) {
        if (keyboardVisible) imeViewNavigationPosition.floatValue = 1f
    }
    val activeViewNavigationPosition = if (keyboardVisible) {
        imeViewNavigationPosition
    } else {
        normalViewNavigationPosition
    }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var viewNavigationPanelSize by remember { mutableStateOf(IntSize.Zero) }
    var viewNavigationControlsSize by remember { mutableStateOf(IntSize.Zero) }
    var viewNavigationActionsSize by remember { mutableStateOf(IntSize.Zero) }
    val viewNavigationGapPx = with(LocalDensity.current) { VIEW_NAVIGATION_GAP_DP.dp.roundToPx() }
    val viewNavigationTravelPx = (
        overlaySize.height - viewNavigationPanelSize.height
        ).coerceAtLeast(0)
    val viewNavigationTopPx = (
        activeViewNavigationPosition.floatValue * viewNavigationTravelPx
        ).roundToInt().coerceIn(0, viewNavigationTravelPx)
    val controlsTopPx = if (
        viewNavigationTopPx >= viewNavigationControlsSize.height + viewNavigationGapPx
    ) {
        viewNavigationTopPx - viewNavigationControlsSize.height - viewNavigationGapPx
    } else {
        (viewNavigationTopPx + viewNavigationPanelSize.height + viewNavigationGapPx)
            .coerceAtMost((overlaySize.height - viewNavigationControlsSize.height).coerceAtLeast(0))
    }
    val actionsTopPx = if (
        viewNavigationTopPx >= viewNavigationActionsSize.height + viewNavigationGapPx
    ) {
        viewNavigationTopPx - viewNavigationActionsSize.height - viewNavigationGapPx
    } else {
        (viewNavigationTopPx + viewNavigationPanelSize.height + viewNavigationGapPx)
            .coerceAtMost((overlaySize.height - viewNavigationActionsSize.height).coerceAtLeast(0))
    }

    Box(
        modifier = modifier
            .background(consoleColors.canvas)
            .onSizeChanged { overlaySize = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("remote-preview")
                .onSizeChanged { size ->
                    localSize = size
                    if (size.width > 0 && size.height > 0) {
                        var resized = transformState.value.withViewportPreservingCenter(
                            size.toViewportSize(),
                        )
                        if (keyboardVisible && keyboardFramingActive) {
                            lastDirectAnchor?.let { anchor ->
                                resized = resized.revealVertically(anchor)
                            }
                        }
                        transformState.value = resized
                        onZoomChanged(resized.zoom)
                        onViewportScaleChanged(resized.scaleMode, resized.contentScale)
                    }
                },
        ) {
            if (localSize.width > 0 && localSize.height > 0) {
                val rect = transform.contentRect
                key(videoSurfaceGeneration) {
                    AndroidView(
                            factory = { context ->
                                BackendTextureView(
                                    context = context,
                                    videoSurface = videoSurface,
                                    remoteWidth = remoteWidth,
                                    remoteHeight = remoteHeight,
                                )
                            },
                        update = {
                            it.videoSurface = videoSurface
                            it.updateRemoteDimensions(remoteWidth, remoteHeight)
                            it.updateContentTransform(rect, localSize)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("remote-input-layer")
                    .semantics {
                        contentDescription = remotePointerDescription
                        customActions = listOf(
                            CustomAccessibilityAction(movePointerLeftLabel) {
                                movePointerForAccessibility(-ACCESSIBILITY_POINTER_STEP, 0)
                            },
                            CustomAccessibilityAction(movePointerRightLabel) {
                                movePointerForAccessibility(ACCESSIBILITY_POINTER_STEP, 0)
                            },
                            CustomAccessibilityAction(movePointerUpLabel) {
                                movePointerForAccessibility(0, -ACCESSIBILITY_POINTER_STEP)
                            },
                            CustomAccessibilityAction(movePointerDownLabel) {
                                movePointerForAccessibility(0, ACCESSIBILITY_POINTER_STEP)
                            },
                            CustomAccessibilityAction(clickPointerLabel) {
                                clickPointerForAccessibility(MouseButton.Left)
                            },
                            CustomAccessibilityAction(rightClickPointerLabel) {
                                clickPointerForAccessibility(MouseButton.Right)
                            },
                        )
                    }
                    .pointerInput(
                        input,
                        inputGeneration,
                        pointerMode,
                        remoteWidth,
                        remoteHeight,
                        normalizedScrollSensitivity,
                    ) {
                        val router = ExternalPointerInputRouter(
                            mode = when (pointerMode) {
                                PointerMode.Direct -> ExternalPointerRoutingMode.Absolute
                                PointerMode.Trackpad,
                                PointerMode.Captured,
                                -> ExternalPointerRoutingMode.Relative
                            },
                            scrollSensitivity = normalizedScrollSensitivity,
                            mapAbsolutePosition = { position ->
                                transformState.value.screenToRemote(
                                    FloatPoint(position.x, position.y),
                                )?.toHidAbsolute(remoteWidth, remoteHeight)?.let { hid ->
                                    ExternalAbsolutePosition(hid.x, hid.y)
                                }
                            },
                        )
                        routeExternalMouseInput(router) { command ->
                            command.dispatchTo(input)
                        }
                    }
                    .pointerInput(
                        pointerMode,
                        remoteWidth,
                        remoteHeight,
                        normalizedScrollSensitivity,
                    ) {
                        var scrollRemainder = 0f
                        val scrollStepPixels =
                            SCROLL_STEP_DP.dp.toPx() / normalizedScrollSensitivity
                        detectTwoFingerTransformGestures(
                            onStart = {
                                scrollRemainder = 0f
                                input.releaseAllInput()
                            },
                            onTransform = { _, pan, _ ->
                                if (abs(pan.y) < abs(pan.x)) return@detectTwoFingerTransformGestures
                                scrollRemainder += pan.y
                                val steps = (scrollRemainder / scrollStepPixels).toInt()
                                if (steps != 0) {
                                    input.scrollWheel(-steps)
                                    scrollRemainder -= steps * scrollStepPixels
                                }
                            },
                        )
                    }
                    .pointerInput(pointerMode, remoteWidth, remoteHeight) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (pointerMode == PointerMode.Direct) clickAt(offset, MouseButton.Left)
                                else clickCurrentCursor(MouseButton.Left)
                            },
                            onDoubleTap = { offset ->
                                if (pointerMode == PointerMode.Direct) {
                                    clickAt(offset, MouseButton.Left)
                                    clickAt(offset, MouseButton.Left)
                                } else {
                                    clickCurrentCursor(MouseButton.Left)
                                    clickCurrentCursor(MouseButton.Left)
                                }
                            },
                            onLongPress = { offset ->
                                if (pointerMode == PointerMode.Direct) clickAt(offset, MouseButton.Right)
                                else clickCurrentCursor(MouseButton.Right)
                            },
                        )
                    }
                    .pointerInput(pointerMode, remoteWidth, remoteHeight) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (pointerMode == PointerMode.Direct && absoluteAt(offset)) {
                                    input.mouseButton(MouseButton.Left, true)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                when (pointerMode) {
                                    PointerMode.Direct -> absoluteAt(
                                        change.position,
                                        setOf(MouseButton.Left),
                                    )
                                    PointerMode.Trackpad,
                                    PointerMode.Captured,
                                    -> input.moveRelative(
                                        deltaX = dragAmount.x.roundToInt().coerceIn(-127, 127),
                                        deltaY = dragAmount.y.roundToInt().coerceIn(-127, 127),
                                    )
                                }
                            },
                            onDragEnd = {
                                if (pointerMode == PointerMode.Direct) {
                                    input.mouseButton(MouseButton.Left, false)
                                }
                            },
                            onDragCancel = { input.releaseAllInput() },
                        )
                    },
            )

            pointerCaptureController?.let { controller ->
                PointerCaptureHost(
                    controller = controller,
                    input = input,
                    scrollSensitivity = normalizedScrollSensitivity,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(1.dp)
                        .height(1.dp),
                )
            }
        }

        if (viewNavigationVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, viewNavigationTopPx) }
                    .widthIn(max = VIEW_NAVIGATION_MAX_WIDTH_DP.dp)
                    .fillMaxWidth()
                    .zIndex(2f),
            ) {
                ViewNavigationPad(
                    contentScale = transform.contentScale,
                    scaleMode = transform.scaleMode,
                    remoteWidth = remoteWidth,
                    remoteHeight = remoteHeight,
                    positionFraction = activeViewNavigationPosition.floatValue,
                    controlsVisible = viewControlsVisible,
                    onToggleControls = { viewControlsVisible = !viewControlsVisible },
                    onMoveBy = { deltaY ->
                        if (viewNavigationTravelPx > 0) {
                            activeViewNavigationPosition.floatValue = (
                                activeViewNavigationPosition.floatValue +
                                    deltaY / viewNavigationTravelPx.toFloat()
                                ).coerceIn(0f, 1f)
                        }
                    },
                    onMoveTo = { position ->
                        activeViewNavigationPosition.floatValue = position.coerceIn(0f, 1f)
                    },
                    onCyclePosition = {
                        activeViewNavigationPosition.floatValue = nextViewNavigationPosition(
                            activeViewNavigationPosition.floatValue,
                        )
                    },
                    input = input,
                    scrollSensitivity = normalizedScrollSensitivity,
                    onPan = { delta -> panView(delta) },
                    onZoom = { multiplier -> zoomView(multiplier) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("view-navigation-panel")
                        .onSizeChanged { viewNavigationPanelSize = it },
                )
            }
        }
        if (viewNavigationVisible && !viewControlsVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, actionsTopPx) }
                    .widthIn(max = VIEW_NAVIGATION_MAX_WIDTH_DP.dp)
                    .fillMaxWidth()
                    .zIndex(4f)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .onSizeChanged { viewNavigationActionsSize = it }
                        .testTag("view-navigation-actions"),
                ) {
                    navigationActions()
                }
            }
        }
        if (viewNavigationVisible && viewControlsVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, controlsTopPx) }
                    .widthIn(max = VIEW_NAVIGATION_MAX_WIDTH_DP.dp)
                    .fillMaxWidth()
                    .zIndex(3f),
            ) {
                ViewNavigationControls(
                    onPan = { delta -> panView(delta) },
                    onZoom = { multiplier -> zoomView(multiplier) },
                    onFit = { fitView() },
                    onActualSize = { actualSizeView() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .onSizeChanged { viewNavigationControlsSize = it },
                )
            }
        }
    }
}

@Composable
private fun <T> rememberReferentiallyUpdatedState(newValue: T): MutableState<T> =
    remember { mutableStateOf(newValue, referentialEqualityPolicy()) }.also { state ->
        state.value = newValue
    }

private fun viewportTransformStateSaver(
    remoteWidth: Int,
    remoteHeight: Int,
): Saver<MutableState<ViewportTransform>, FloatArray> = Saver(
    save = { state ->
        floatArrayOf(
            state.value.scaleMode.ordinal.toFloat(),
            state.value.customScale,
            state.value.pan.x,
            state.value.pan.y,
            state.value.viewport.width,
            state.value.viewport.height,
        )
    },
    restore = { values ->
        val currentFormat = values.size >= VIEWPORT_SAVER_VALUE_COUNT
        val viewportOffset = if (currentFormat) 4 else 3
        val savedViewport = FloatSize(
            values.getOrElse(viewportOffset) { 0f }.positiveFiniteOrZero(),
            values.getOrElse(viewportOffset + 1) { 0f }.positiveFiniteOrZero(),
        )
        val fitted = ViewportTransform.fit(
            remoteWidth = remoteWidth,
            remoteHeight = remoteHeight,
            viewport = savedViewport,
        )
        val restored = if (currentFormat) {
            fitted.copy(
                scaleMode = ViewportScaleMode.entries.getOrElse(
                    values.getOrElse(0) { 0f }.finiteOrZero().toInt(),
                ) { ViewportScaleMode.Fit },
                customScale = values.getOrElse(1) { fitted.fitScale }
                    .takeIf { it.isFinite() && it > 0f }
                    ?: fitted.fitScale,
                pan = FloatPoint(
                    values.getOrElse(2) { 0f }.finiteOrZero(),
                    values.getOrElse(3) { 0f }.finiteOrZero(),
                ),
            )
        } else {
            // Migrate the old fit-relative zoom save shape to an absolute custom scale.
            val legacyZoom = values.getOrElse(0) { 1f }
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f
            fitted.copy(
                scaleMode = if (legacyZoom == 1f) {
                    ViewportScaleMode.Fit
                } else {
                    ViewportScaleMode.Custom
                },
                customScale = fitted.fitScale * legacyZoom,
                pan = FloatPoint(
                    values.getOrElse(1) { 0f }.finiteOrZero(),
                    values.getOrElse(2) { 0f }.finiteOrZero(),
                ),
            )
        }
        mutableStateOf(restored.withViewport(savedViewport))
    },
)

private fun Float.finiteOrZero(): Float = takeIf(Float::isFinite) ?: 0f

private fun Float.positiveFiniteOrZero(): Float = takeIf { isFinite() && this > 0f } ?: 0f

@Composable
private fun ViewNavigationPad(
    contentScale: Float,
    scaleMode: ViewportScaleMode,
    remoteWidth: Int,
    remoteHeight: Int,
    positionFraction: Float,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onMoveBy: (Float) -> Unit,
    onMoveTo: (Float) -> Unit,
    onCyclePosition: () -> Unit,
    input: RemoteInputSink,
    scrollSensitivity: Float,
    onPan: (FloatPoint) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consoleColors = LocalConsoleColorScheme.current
    val cyclePositionLabel = stringResource(R.string.console_cycle_view_pad_position)
    val movePadDescription = stringResource(R.string.console_move_view_pad)
    val movePadUpLabel = stringResource(R.string.console_move_view_pad_up)
    val movePadDownLabel = stringResource(R.string.console_move_view_pad_down)
    val movePadTopLabel = stringResource(R.string.console_move_view_pad_top)
    val movePadBottomLabel = stringResource(R.string.console_move_view_pad_bottom)
    val positionDescription = viewNavigationPositionDescription(positionFraction)
    val panZoomDescription = stringResource(R.string.console_pan_zoom_view)
    val zoomDescription = when (scaleMode) {
        ViewportScaleMode.Fit -> stringResource(R.string.console_view_scale_fit)
        ViewportScaleMode.ActualSize -> stringResource(R.string.console_view_scale_actual_size)
        ViewportScaleMode.Custom -> stringResource(
            R.string.console_view_scale_custom,
            stringResource(R.string.console_multiplier_format, contentScale),
        )
    }
    val toggleControlsDescription = stringResource(
        if (controlsVisible) {
            R.string.console_hide_view_controls
        } else {
            R.string.console_show_view_controls
        },
    )
    // Local function references can compare structurally equal even when they capture a replaced
    // viewport state. Keep gesture callbacks current by identity so dimension changes cannot leave
    // this long-lived pointer handler mutating a detached transform.
    val currentOnPan by rememberReferentiallyUpdatedState(onPan)
    val currentOnZoom by rememberReferentiallyUpdatedState(onZoom)
    val currentPositionFraction by rememberUpdatedState(positionFraction)
    val currentOnMoveBy by rememberUpdatedState(onMoveBy)
    val currentOnMoveTo by rememberUpdatedState(onMoveTo)
    val currentOnCyclePosition by rememberUpdatedState(onCyclePosition)
    val moveHandleDragState = rememberDraggableState { delta -> currentOnMoveBy(delta) }

    fun moveToForAccessibility(target: Float): Boolean {
        val clampedTarget = target.coerceIn(0f, 1f)
        if (clampedTarget == currentPositionFraction) return false
        currentOnMoveTo(clampedTarget)
        return true
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("view-navigation-content"),
                color = consoleColors.controlSurface,
                contentColor = consoleColors.onSurface,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(68.dp)
                        .background(
                            color = consoleColors.controlSurfaceElevated,
                            shape = MaterialTheme.shapes.medium,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .testTag("view-navigation-move-handle")
                            .clickable(
                                onClickLabel = cyclePositionLabel,
                                role = Role.Button,
                                onClick = currentOnCyclePosition,
                            )
                            .draggable(
                                state = moveHandleDragState,
                                orientation = Orientation.Vertical,
                            )
                            .semantics {
                                contentDescription = movePadDescription
                                stateDescription = positionDescription
                                customActions = listOf(
                                    CustomAccessibilityAction(movePadUpLabel) {
                                        moveToForAccessibility(
                                            currentPositionFraction - VIEW_NAVIGATION_ACCESSIBILITY_STEP,
                                        )
                                    },
                                    CustomAccessibilityAction(movePadDownLabel) {
                                        moveToForAccessibility(
                                            currentPositionFraction + VIEW_NAVIGATION_ACCESSIBILITY_STEP,
                                        )
                                    },
                                    CustomAccessibilityAction(movePadTopLabel) {
                                        moveToForAccessibility(0f)
                                    },
                                    CustomAccessibilityAction(movePadBottomLabel) {
                                        moveToForAccessibility(1f)
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = null,
                            tint = consoleColors.onSurfaceMuted,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(68.dp)
                            .testTag("view-navigation-pad")
                            .semantics {
                                contentDescription = panZoomDescription
                                stateDescription = zoomDescription
                            }
                            .pointerInput(remoteWidth, remoteHeight) {
                                detectTransformGestures { _, pan, zoomChange, _ ->
                                    if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                                        currentOnZoom(zoomChange)
                                    }
                                    if (pan != Offset.Zero && pan.x.isFinite() && pan.y.isFinite()) {
                                        currentOnPan(FloatPoint(pan.x, pan.y))
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.console_pan_zoom_hint),
                            style = MaterialTheme.typography.labelLarge,
                            color = consoleColors.onSurfaceMuted,
                        )
                    }
                    IconButton(
                        onClick = onToggleControls,
                        modifier = Modifier.semantics {
                            contentDescription = toggleControlsDescription
                        },
                    ) {
                        Icon(
                            if (controlsVisible) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    RemoteScrollPad(
                        input = input,
                        sensitivity = scrollSensitivity,
                        modifier = Modifier
                            .width(REMOTE_SCROLL_PAD_WIDTH_DP.dp)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteScrollPad(
    input: RemoteInputSink,
    sensitivity: Float,
    modifier: Modifier = Modifier,
) {
    val consoleColors = LocalConsoleColorScheme.current
    val normalizedSensitivity = normalizeScrollSensitivity(sensitivity)
    val scrollDescription = stringResource(R.string.console_scroll_remote)
    val sensitivityDescription = stringResource(
        R.string.console_sensitivity_state,
        stringResource(R.string.console_multiplier_format, normalizedSensitivity),
    )
    val scrollUpLabel = stringResource(R.string.console_scroll_remote_up)
    val scrollDownLabel = stringResource(R.string.console_scroll_remote_down)
    val scrollLeftLabel = stringResource(R.string.console_scroll_remote_left)
    val scrollRightLabel = stringResource(R.string.console_scroll_remote_right)
    val stepPixels = with(LocalDensity.current) { REMOTE_SCROLL_STEP_DP.dp.toPx() }
    val currentInput by rememberUpdatedState(input)

    Surface(
        modifier = modifier
            .testTag("remote-scroll-pad")
            .semantics {
                contentDescription = scrollDescription
                stateDescription = sensitivityDescription
                customActions = listOf(
                    CustomAccessibilityAction(scrollUpLabel) {
                        currentInput.scrollWheel(1)
                        true
                    },
                    CustomAccessibilityAction(scrollDownLabel) {
                        currentInput.scrollWheel(-1)
                        true
                    },
                    CustomAccessibilityAction(scrollLeftLabel) {
                        currentInput.scrollHorizontal(1)
                        true
                    },
                    CustomAccessibilityAction(scrollRightLabel) {
                        currentInput.scrollHorizontal(-1)
                        true
                    },
                )
            }
            .pointerInput(currentInput, normalizedSensitivity, stepPixels) {
                val accumulator = RemoteScrollAccumulator(stepPixels, normalizedSensitivity)
                fun finishGesture() {
                    accumulator.reset()
                }
                detectDragGestures(
                    onDragStart = { finishGesture() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val steps = accumulator.add(dragAmount.x, dragAmount.y)
                        if (steps.horizontal != 0) {
                            currentInput.scrollHorizontal(steps.horizontal)
                        }
                        if (steps.vertical != 0) {
                            currentInput.scrollWheel(steps.vertical)
                        }
                    },
                    onDragEnd = ::finishGesture,
                    onDragCancel = ::finishGesture,
                )
            },
        shape = MaterialTheme.shapes.small,
        color = consoleColors.controlSurface,
        contentColor = consoleColors.active,
        border = BorderStroke(1.dp, consoleColors.outline),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.OpenWith,
                contentDescription = null,
                tint = consoleColors.active,
            )
        }
    }
}

@Suppress("DEPRECATION") // These arrows describe physical pan direction and must not mirror in RTL.
@Composable
private fun ViewNavigationControls(
    onPan: (FloatPoint) -> Unit,
    onZoom: (Float) -> Unit,
    onFit: () -> Unit,
    onActualSize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val consoleColors = LocalConsoleColorScheme.current
    Surface(
        modifier = modifier.testTag("view-navigation-controls"),
        shape = MaterialTheme.shapes.medium,
        color = consoleColors.controlSurfaceElevated,
        contentColor = consoleColors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavigationButton(
                    stringResource(R.string.console_pan_view_left),
                    Icons.Default.KeyboardArrowLeft,
                ) {
                    onPan(FloatPoint(VIEW_PAN_STEP_PIXELS, 0f))
                }
                NavigationButton(
                    stringResource(R.string.console_pan_view_up),
                    Icons.Default.KeyboardArrowUp,
                ) {
                    onPan(FloatPoint(0f, VIEW_PAN_STEP_PIXELS))
                }
                NavigationButton(
                    stringResource(R.string.console_pan_view_down),
                    Icons.Default.KeyboardArrowDown,
                ) {
                    onPan(FloatPoint(0f, -VIEW_PAN_STEP_PIXELS))
                }
                NavigationButton(
                    stringResource(R.string.console_pan_view_right),
                    Icons.Default.KeyboardArrowRight,
                ) {
                    onPan(FloatPoint(-VIEW_PAN_STEP_PIXELS, 0f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavigationButton(
                    stringResource(R.string.console_zoom_out),
                    Icons.Default.ZoomOut,
                ) { onZoom(0.8f) }
                ViewportModeButton(
                    label = stringResource(R.string.console_fit_remote_view),
                    visibleLabel = stringResource(R.string.console_view_fit_label),
                    testTag = "viewport-fit",
                    onClick = onFit,
                )
                ViewportModeButton(
                    label = stringResource(R.string.console_actual_size_remote_view),
                    visibleLabel = stringResource(R.string.console_view_actual_size_label),
                    testTag = "viewport-actual-size",
                    onClick = onActualSize,
                )
                NavigationButton(
                    stringResource(R.string.console_zoom_in),
                    Icons.Default.ZoomIn,
                ) { onZoom(1.25f) }
            }
        }
    }
}

@Composable
private fun ViewportModeButton(
    label: String,
    visibleLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val consoleColors = LocalConsoleColorScheme.current
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = consoleColors.active,
            disabledContentColor = consoleColors.onSurfaceMuted,
        ),
        modifier = Modifier
            .height(48.dp)
            .testTag(testTag)
            .semantics { contentDescription = label },
    ) {
        Text(visibleLabel)
    }
}

@Composable
private fun NavigationButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null)
    }
}

private suspend fun PointerInputScope.detectTwoFingerTransformGestures(
    onStart: () -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var transforming = false
        var previousCentroid = Offset.Zero
        var previousDistance = 0f
        var anyPressed: Boolean
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            anyPressed = pressed.isNotEmpty()
            if (pressed.size >= 2) {
                val first = pressed[0].position
                val second = pressed[1].position
                val centroid = Offset((first.x + second.x) / 2f, (first.y + second.y) / 2f)
                val distance = hypot(first.x - second.x, first.y - second.y)
                if (!transforming) {
                    transforming = true
                    previousCentroid = centroid
                    previousDistance = distance
                    onStart()
                } else {
                    val zoom = if (previousDistance > 0.5f) distance / previousDistance else 1f
                    onTransform(centroid, centroid - previousCentroid, zoom)
                    previousCentroid = centroid
                    previousDistance = distance
                }
                pressed.forEach { it.consume() }
            }
        } while (anyPressed)
    }
}

@SuppressLint("ViewConstructor")
private class BackendTextureView(
    context: Context,
    videoSurface: VideoSurfaceSink,
    remoteWidth: Int,
    remoteHeight: Int,
) : TextureView(context), TextureView.SurfaceTextureListener {
    var videoSurface: VideoSurfaceSink = videoSurface
    private var outputSurface: Surface? = null
    private val contentTransform = Matrix()
    private var remoteWidth = remoteWidth.coerceAtLeast(1)
    private var remoteHeight = remoteHeight.coerceAtLeast(1)

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(remoteWidth, remoteHeight)
        outputSurface = Surface(texture).also {
            videoSurface.attachVideoSurface(it, remoteWidth, remoteHeight)
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(remoteWidth, remoteHeight)
        videoSurface.resizeVideoSurface(remoteWidth, remoteHeight)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releaseSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun updateRemoteDimensions(width: Int, height: Int) {
        val normalizedWidth = width.coerceAtLeast(1)
        val normalizedHeight = height.coerceAtLeast(1)
        if (remoteWidth == normalizedWidth && remoteHeight == normalizedHeight) return
        remoteWidth = normalizedWidth
        remoteHeight = normalizedHeight
        surfaceTexture?.setDefaultBufferSize(remoteWidth, remoteHeight)
        if (outputSurface != null) {
            videoSurface.resizeVideoSurface(remoteWidth, remoteHeight)
        }
    }

    fun updateContentTransform(rect: FloatRect, viewport: IntSize) {
        if (viewport.width <= 0 || viewport.height <= 0) return
        contentTransform.reset()
        contentTransform.setRectToRect(
            RectF(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat()),
            RectF(rect.left, rect.top, rect.right, rect.bottom),
            Matrix.ScaleToFit.FILL,
        )
        setTransform(contentTransform)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        releaseSurface()
        super.onDetachedFromWindow()
    }

    private fun releaseSurface() {
        outputSurface?.let {
            videoSurface.detachVideoSurface(it)
            it.release()
        }
        outputSurface = null
    }
}

private fun IntSize.toViewportSize(): FloatSize = if (width > 0 && height > 0) {
    FloatSize(width.toFloat(), height.toFloat())
} else {
    FloatSize(1f, 1f)
}

internal data class RemoteScrollSteps(
    val horizontal: Int = 0,
    val vertical: Int = 0,
)

internal class RemoteScrollAccumulator(
    baseStepPixels: Float,
    sensitivity: Float,
) {
    init {
        require(baseStepPixels.isFinite() && baseStepPixels > 0f)
    }

    private val threshold = baseStepPixels / normalizeScrollSensitivity(sensitivity)
    private val axisLockDistance = baseStepPixels * SCROLL_AXIS_LOCK_FRACTION
    private var axis: Axis? = null
    private var remainder = 0f
    private var pendingX = 0f
    private var pendingY = 0f

    fun add(deltaX: Float, deltaY: Float): RemoteScrollSteps {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return RemoteScrollSteps()
        if (axis == null) {
            pendingX += deltaX
            pendingY += deltaY
            val absoluteX = abs(pendingX)
            val absoluteY = abs(pendingY)
            axis = when {
                absoluteX >= axisLockDistance &&
                    absoluteX >= absoluteY * SCROLL_AXIS_DOMINANCE_RATIO -> Axis.Horizontal
                absoluteY >= axisLockDistance &&
                    absoluteY >= absoluteX * SCROLL_AXIS_DOMINANCE_RATIO -> Axis.Vertical
                else -> return RemoteScrollSteps()
            }
            remainder = if (axis == Axis.Horizontal) pendingX else pendingY
            pendingX = 0f
            pendingY = 0f
        } else {
            remainder += if (axis == Axis.Horizontal) deltaX else deltaY
        }
        val fingerSteps = (remainder / threshold).toInt()
        if (fingerSteps == 0) return RemoteScrollSteps()
        remainder -= fingerSteps * threshold
        val remoteSteps = -fingerSteps
        return if (axis == Axis.Horizontal) {
            RemoteScrollSteps(horizontal = remoteSteps)
        } else {
            RemoteScrollSteps(vertical = remoteSteps)
        }
    }

    fun reset() {
        axis = null
        remainder = 0f
        pendingX = 0f
        pendingY = 0f
    }

    private enum class Axis { Horizontal, Vertical }
}

private fun nextViewNavigationPosition(position: Float): Float = when {
    position >= 0.75f -> 0.5f
    position >= 0.25f -> 0f
    else -> 1f
}

@Composable
private fun viewNavigationPositionDescription(position: Float): String = when {
    position <= 0.01f -> stringResource(R.string.console_view_position_top)
    position >= 0.99f -> stringResource(R.string.console_view_position_bottom)
    else -> stringResource(
        R.string.console_view_position_percent,
        (position * 100f).roundToInt(),
    )
}

private const val SCROLL_STEP_DP = 28
private const val REMOTE_SCROLL_STEP_DP = 20
private const val SCROLL_AXIS_LOCK_FRACTION = 0.2f
private const val SCROLL_AXIS_DOMINANCE_RATIO = 1.2f
private const val REMOTE_SCROLL_PAD_WIDTH_DP = 64
private const val VIEW_NAVIGATION_MAX_WIDTH_DP = 840
private const val VIEW_PAN_STEP_PIXELS = 72f
private const val VIEW_NAVIGATION_GAP_DP = 8
private const val VIEW_NAVIGATION_ACCESSIBILITY_STEP = 0.25f
private const val ACCESSIBILITY_POINTER_STEP = 24
private const val INPUT_ANCHOR_MAX_AGE_MILLIS = 15_000L
private const val VIEWPORT_SAVER_VALUE_COUNT = 6
