package app.gamenative.ui.component.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Draws a pad-mouse cursor over the currently-open Steam-Controller dialog and injects taps at the cursor location,
 * so the right trackpad can drive arbitrary Compose UI (lists, chips, dropdowns, text fields) that d-pad focus
 * traversal can't reliably reach. The BLE Triton isn't an Android input device, so the [ProfileInterpreter] emits
 * pixel deltas / clicks through [app.gamenative.steamcontroller.ScUiBridge] and the bridge calls into this.
 *
 * The cursor is a small View added to the **top dialog window's decor** (so it floats above the Compose content);
 * coordinates are tracked in that decor's space and taps are dispatched to the decor, which hit-tests down to the
 * Compose view beneath the (non-clickable) cursor. When the top dialog changes the cursor re-attaches and recenters.
 */
class ScCursorController(private val context: Context) {
    private var cursorView: View? = null
    private var host: ViewGroup? = null
    private var x = 0f
    private var y = 0f

    private val sizePx: Int get() = (22 * context.resources.displayMetrics.density).toInt()

    /** Move the cursor by a pixel delta over [topView]'s window, attaching/recentering if the top window changed. */
    fun move(topView: View?, dx: Int, dy: Int) {
        val decor = topView?.rootView as? ViewGroup ?: run { detach(); return }
        if (host !== decor || cursorView == null) attach(decor)
        val cv = cursorView ?: return
        val w = decor.width.coerceAtLeast(1)
        val h = decor.height.coerceAtLeast(1)
        x = (x + dx).coerceIn(0f, (w - 1).toFloat())
        y = (y + dy).coerceIn(0f, (h - 1).toFloat())
        cv.translationX = x - cv.width / 2f
        cv.translationY = y - cv.height / 2f
        cv.visibility = View.VISIBLE
        cv.bringToFront()
    }

    /** Inject a tap (down+up) at the current cursor position into the top dialog. */
    fun tap(topView: View?) {
        val decor = topView?.rootView as? ViewGroup ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 12, MotionEvent.ACTION_UP, x, y, 0)
        decor.dispatchTouchEvent(down)
        decor.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun attach(decor: ViewGroup) {
        detach()
        val dot = View(context).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 255, 255, 255))
                setStroke((2 * context.resources.displayMetrics.density).toInt(), Color.argb(230, 30, 30, 30))
            }
        }
        decor.addView(dot, FrameLayout.LayoutParams(sizePx, sizePx))
        cursorView = dot
        host = decor
        // Start centered in the window.
        x = decor.width / 2f
        y = decor.height / 2f
    }

    /** Remove the cursor (top window closed / capture ended). Safe to call repeatedly. */
    fun detach() {
        val cv = cursorView
        val h = host
        if (cv != null && h != null) h.removeView(cv)
        cursorView = null
        host = null
    }
}
