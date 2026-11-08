package com.example.mediaplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How far (share of the width) a swipe must go to change songs. */
private const val SWITCH_FRACTION = 0.28f

/**
 * Swipe left for the next song, right for the previous one. While dragging, the neighbouring song
 * ([PlayQueue.peekNext] / [PlayQueue.peekPrevious]) slides in, drawn by [page] with its queue label
 * ("Moving to Play next"...) when it's in the other queue. Let go past [SWITCH_FRACTION] of the width
 * to switch; with no song on that side it springs back.
 */
@Composable
fun TrackSwiper(
    current: Song?,
    previous: PlayQueue.Peek?,
    next: PlayQueue.Peek?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (song: Song?, label: String?) -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        Box(
            modifier = Modifier.pointerInput(previous?.song?.uri, next?.song?.uri, width) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        val x = offset.value
                        // Nothing on that side: resist (rubber band).
                        val blocked = (x + delta < 0 && next == null) || (x + delta > 0 && previous == null)
                        scope.launch { offset.snapTo((x + if (blocked) delta * 0.25f else delta).coerceIn(-width, width)) }
                    },
                    onDragEnd = {
                        scope.launch {
                            val x = offset.value
                            when {
                                x < -width * SWITCH_FRACTION && next != null -> {
                                    offset.animateTo(-width, tween(160))
                                    onNext()
                                    offset.snapTo(0f)
                                }
                                x > width * SWITCH_FRACTION && previous != null -> {
                                    offset.animateTo(width, tween(160))
                                    onPrevious()
                                    offset.snapTo(0f)
                                }
                                else -> offset.animateTo(0f, tween(200))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, tween(200)) } },
                )
            },
        ) {
            val x = offset.value
            Box(modifier = Modifier.fillMaxWidth().offset { IntOffset(x.roundToInt(), 0) }) { page(current, null) }
            if (x < 0 && next != null) {
                Box(modifier = Modifier.fillMaxWidth().offset { IntOffset((x + width).roundToInt(), 0) }) { page(next.song, next.label) }
            }
            if (x > 0 && previous != null) {
                Box(modifier = Modifier.fillMaxWidth().offset { IntOffset((x - width).roundToInt(), 0) }) { page(previous.song, previous.label) }
            }
        }
    }
}
