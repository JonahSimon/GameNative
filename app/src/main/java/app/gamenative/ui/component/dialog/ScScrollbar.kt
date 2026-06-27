package app.gamenative.ui.component.dialog

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a simple vertical scrollbar thumb on the right edge of a `Modifier.verticalScroll` container, so users can
 * SEE that a settings screen has more content below the fold (a plain `Column(verticalScroll)` shows no scrollbar,
 * unlike `LazyColumn`). The thumb only appears when the content actually overflows ([ScrollState.maxValue] > 0); its
 * height is proportional to the visible fraction and it tracks the scroll position. Pass the same [state] used for the
 * `verticalScroll`, and a [color] resolved from the theme at the call site.
 */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color,
    trackColor: Color,
    width: Dp = 8.dp,
): Modifier = this.drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent
    val viewport = size.height
    val total = viewport + state.maxValue            // full content height (visible + scrolled-off)
    // Browser-style proportional thumb: its height = the visible fraction of the content (small when there's a lot
    // below, large when there's little). Capped at 85% so a sliver of track is ALWAYS visible above + below it — that
    // gap is what makes the thumb read as a distinct floating handle rather than a uniform bar.
    val thumbH = (viewport * (viewport / total)).coerceIn(24.dp.toPx(), viewport * 0.85f)
    val maxOffset = viewport - thumbH
    val offset = maxOffset * (state.value.toFloat() / state.maxValue)
    val w = width.toPx()
    val x = size.width - w
    // Faint full-height gutter track.
    drawRoundRect(
        color = trackColor,
        topLeft = Offset(x, 0f),
        size = Size(w, viewport),
        cornerRadius = CornerRadius(w / 2f, w / 2f),
    )
    // Solid thumb, inset 1px from the gutter edges so it reads as a handle sitting inside the track.
    val inset = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(x + inset, offset + inset),
        size = Size(w - 2 * inset, thumbH - 2 * inset),
        cornerRadius = CornerRadius(w / 2f, w / 2f),
    )
}

/**
 * Convenience: a `verticalScroll` that also shows a [verticalScrollbar] thumb when the content overflows. Drop-in
 * replacement for `Modifier.verticalScroll(rememberScrollState())` in the SC settings dialogs so long screens reveal
 * that there's more below. (Reserves no extra layout width — the thumb overdraws the right edge.)
 */
@Composable
fun Modifier.verticalScrollWithBar(): Modifier {
    val state = rememberScrollState()
    // Solid neutral thumb on a faint gutter — a conventional browser-style scrollbar.
    val thumb = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    return this.verticalScroll(state).verticalScrollbar(state, thumb, track)
}
