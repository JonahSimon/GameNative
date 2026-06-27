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
    width: Dp = 4.dp,
): Modifier = this.drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent
    val viewport = size.height
    val total = viewport + state.maxValue            // full content height (visible + scrolled-off)
    val thumbH = (viewport * (viewport / total)).coerceAtLeast(24.dp.toPx())
    val maxOffset = viewport - thumbH
    val offset = maxOffset * (state.value.toFloat() / state.maxValue)
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - w, offset),
        size = Size(w, thumbH),
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
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    return this.verticalScroll(state).verticalScrollbar(state, color)
}
