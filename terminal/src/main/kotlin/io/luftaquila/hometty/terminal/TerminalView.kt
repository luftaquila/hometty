package io.luftaquila.hometty.terminal

import io.luftaquila.hometty.resources.R as TextR
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.floor

/**
 * Canvas is intentional: terminal coordinates and alternate screens are not a list of text rows.
 */
class TerminalView(context: Context) : View(context) {
    var session: TerminalSession? = null
        set(value) {
            if (field === value) return
            field = value
            scrollback = 0
            lastTop = -1
            // A new shell starts once it knows its size, which this view may already have.
            removeCallbacks(resizeGrid)
            post(resizeGrid)
        }
    var controlLatch = false
    var altLatch = false
    var onModifiersConsumed: () -> Unit = {}
    /** Tap on an entered command: run it again. */
    var onCommandTap: (String) -> Unit = {}
    /**
     * Tap on a notice printed with an id (see [TerminalSession.announce]); null: such a line taps
     * like any other.
     */
    var onNoticeTap: ((Long) -> Unit)? = null
    /** The command under the last long press, for the context menu; null elsewhere. */
    var pressedCommand: String? = null
        private set
    /** The id of the notice under the last long press, for the context menu; null elsewhere. */
    var pressedNotice: Long? = null
        private set
    var cursorColor = android.graphics.Color.WHITE
    var foregroundColor = android.graphics.Color.WHITE
    var inputColor = 0xFF00FF00.toInt()
    var terminalBackground = android.graphics.Color.BLACK
    private val inputConnection by lazy { TerminalInputConnection() }
    val composingText: String
        get() = inputConnection.pendingText

    private val longPress = Runnable {
        dragged = true
        pressedCommand = commandUnder(downY)
        pressedNotice = noticeUnder(downY)
        performLongClick()
    }
    var terminalTypeface: Typeface = Typeface.MONOSPACE
        set(value) {
            if (field != value) {
                field = value
                paint.typeface = value
                measureGrid()
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    /** Width of one terminal column in pixels. */
    var charWidth = 10f
        private set

    /** Height of one terminal row in pixels. */
    var lineHeight = 20f
        private set

    private var baseline = 16f
    private var scrollback = 0
    private val resizeGrid = Runnable {
        if (width > 0 && height > 0)
            session?.resize(floor(height / lineHeight).toInt(), floor(width / charWidth).toInt())
    }
    private var touchY = 0f
    private var downY = 0f
    private var dragged = false
    // Scrolling follows the finger with sub-line remainders and keeps going after a fling.
    private val scroller = android.widget.OverScroller(context)
    private var velocity: android.view.VelocityTracker? = null
    private var scrollRemainder = 0f
    private var flingY = 0
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity
    var fontSize: Float = 14f
        set(value) {
            if (field == value) return
            field = value
            measureGrid()
            invalidate()
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The terminal always holds focus for typing; Android's gray focus overlay (shown after
        // hardware key input) would tint the whole terminal.
        defaultFocusHighlightEnabled = false
        contentDescription = context.getString(TextR.string.label_interactive_terminal)
    }

    private fun measureGrid() {
        // Like sp in Compose, including Android 14's nonlinear font scaling.
        paint.textSize =
            android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                fontSize,
                resources.displayMetrics,
            )
        charWidth = paint.measureText("M")
        lineHeight = paint.fontSpacing
        baseline = -paint.fontMetrics.top
        // The launcher changes the view size once per keyboard show or hide, so the grid follows
        // at once rather than after a delay that leaves rows under the keyboard.
        removeCallbacks(resizeGrid)
        post(resizeGrid)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        measureGrid()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(terminalBackground)
        val session = session ?: return
        val cells = session.cells(scrollback)
        if (cells.isEmpty()) return
        for (r in 0 until session.rows) {
            var c = 0
            while (c < session.cols) {
                val i = 2 + (r * session.cols + c) * 10
                if (i + 9 >= cells.size) break
                // A wide glyph's second column keeps whatever colors that column had before, so
                // the glyph's own cell colors both columns.
                val width = if (cells[i + 9] == 2 && c + 1 < session.cols) 2 else 1
                val reverse = cells[i + 8] and 4 != 0
                val fg = if (cells[i + 8] and 8 != 0) foregroundColor else cells[i + 6]
                val bg = if (cells[i + 8] and 16 != 0) terminalBackground else cells[i + 7]
                paint.color = if (reverse) fg else bg
                canvas.drawRect(
                    c * charWidth,
                    r * lineHeight,
                    (c + width) * charWidth,
                    (r + 1) * lineHeight,
                    paint,
                )
                c += width
            }
        }
        // Paint all backgrounds first: a wide glyph must survive the next cell's background.
        for (r in 0 until session.rows) for (c in 0 until session.cols) {
            val i = 2 + (r * session.cols + c) * 10
            if (i + 9 >= cells.size || cells[i] == 0) continue
            val reverse = cells[i + 8] and 4 != 0
            val fg = if (cells[i + 8] and 8 != 0) foregroundColor else cells[i + 6]
            val bg = if (cells[i + 8] and 16 != 0) terminalBackground else cells[i + 7]
            paint.color = if (reverse) bg else fg
            paint.isFakeBoldText = cells[i + 8] and 1 != 0
            paint.isUnderlineText = cells[i + 8] and 2 != 0
            val text = buildString {
                for (j in 0..5) {
                    val cp = cells[i + j]
                    if (cp <= 0) break
                    if (Character.isValidCodePoint(cp)) append(String(Character.toChars(cp)))
                }
            }
            canvas.drawText(text, c * charWidth, r * lineHeight + baseline, paint)
        }
        paint.isUnderlineText = false
        paint.isFakeBoldText = false
        if (scrollback == 0) {
            var row = cells[0]
            var col = cells[1]
            // IME preedit belongs at the terminal cursor, never in a separate input field.
            composingText.codePoints().toArray().forEach { cp ->
                val width = Native.characterWidth(cp).coerceAtLeast(0)
                if (col + width > session.cols) {
                    row++
                    col = 0
                }
                paint.color = terminalBackground
                canvas.drawRect(
                    col * charWidth,
                    row * lineHeight,
                    (col + width) * charWidth,
                    (row + 1) * lineHeight,
                    paint,
                )
                paint.color = if (session.atPrompt.value) inputColor else foregroundColor
                paint.isUnderlineText = true
                canvas.drawText(
                    String(Character.toChars(cp)),
                    col * charWidth,
                    row * lineHeight + baseline,
                    paint,
                )
                col += width
            }
            paint.isUnderlineText = false
            paint.color = cursorColor
            canvas.drawRect(
                col * charWidth,
                row * lineHeight,
                (col + 1) * charWidth,
                (row + 1) * lineHeight,
                paint,
            )
        }
    }

    /** The absolute line on the first row when last drawn; -1: none yet. */
    private var lastTop = -1L

    fun refresh() {
        val session = session
        val top = session?.topRow() ?: -1L
        // Scrolled back, the view keeps showing the same lines while output arrives below.
        if (scrollback > 0 && lastTop >= 0 && top > lastTop) scrollback += (top - lastTop).toInt()
        lastTop = top
        scrollback = scrollback.coerceAtMost(session?.historySize() ?: 0)
        invalidate()
    }

    /** Sends unfinished IME text and starts the IME afresh, before the line changes elsewhere. */
    fun finishInput() = inputConnection.finishForTerminalKey()

    override fun onCheckIsTextEditor() = true

    private fun send(text: String) {
        scrollback = 0
        val value =
            if (controlLatch && text.length == 1 && text[0].code in 64..127)
                (text[0].code and 31).toChar().toString()
            else text
        runCatching { session?.write((if (altLatch) "\u001b" else "") + value) }
        if (controlLatch || altLatch) {
            controlLatch = false
            altLatch = false
            onModifiersConsumed()
        }
        invalidate()
    }

    fun paste(text: String) {
        inputConnection.finishForTerminalKey()
        session?.paste(text.take(32768))
        scrollback = 0
        invalidate()
    }

    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        info.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        info.initialSelStart =
            android.text.Selection.getSelectionStart(inputConnection.getEditable()).coerceAtLeast(0)
        info.initialSelEnd =
            android.text.Selection.getSelectionEnd(inputConnection.getEditable()).coerceAtLeast(0)
        return inputConnection
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, true) {
        private val buffer =
            Editable.Factory.getInstance().newEditable("").also {
                android.text.Selection.setSelection(it, 0)
            }

        override fun getEditable() = buffer

        // Keep the document the IME sees after commitText. Gboard can reopen a committed
        // syllable with setComposingRegion/replaceText; clearing this document appends duplicates.
        private var sentText = ""
        val pendingText: String
            get() = getComposingSpanStart(buffer).let { if (it >= 0) buffer.substring(it) else "" }

        private fun synchronizeText() {
            val composing = getComposingSpanStart(buffer)
            val desired = buffer.substring(0, if (composing >= 0) composing else buffer.length)
            val previous = sentText.codePoints().toArray()
            val next = desired.codePoints().toArray()
            var common = 0
            while (
                common < minOf(previous.size, next.size) && previous[common] == next[common]
            ) common++
            val delta =
                "\u007f".repeat(previous.size - common) + String(next, common, next.size - common)
            if (delta.isNotEmpty()) send(delta)
            sentText = desired
        }

        fun finishForTerminalKey() {
            finishComposingText()
            // Enter may be delivered before the IME closes its outer batch.
            synchronizeText()
            // A control key hands cursor ownership back to the terminal application.
            // Reset context without erasing the text already sent to the PTY.
            buffer.clear()
            removeComposingSpans(buffer)
            android.text.Selection.setSelection(buffer, 0)
            sentText = ""
            changed()
        }

        private var batchDepth = 0
        private val notifySelection = Runnable {
            if (batchDepth == 0) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .updateSelection(
                        this@TerminalView,
                        android.text.Selection.getSelectionStart(buffer),
                        android.text.Selection.getSelectionEnd(buffer),
                        getComposingSpanStart(buffer),
                        getComposingSpanEnd(buffer),
                    )
            }
        }

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchDepth > 0) batchDepth--
            changed()
            return batchDepth > 0
        }

        private fun changed() {
            removeCallbacks(notifySelection)
            if (batchDepth == 0) {
                synchronizeText()
                post(notifySelection)
            }
            invalidate()
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.setComposingText(text, newCursorPosition)
            changed()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            super.setComposingRegion(start, end)
            changed()
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            val result = super.setSelection(start, end)
            changed()
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            changed()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            changed()
            return true
        }

        private fun deleteAround(
            beforeLength: Int,
            afterLength: Int,
            codePoints: Boolean,
        ): Boolean {
            if (buffer.isEmpty()) {
                repeat(beforeLength.coerceIn(0, 100)) { session?.key(3) }
                repeat(afterLength.coerceIn(0, 100)) { session?.key(10) }
            } else {
                val start =
                    android.text.Selection.getSelectionStart(buffer).coerceIn(0, buffer.length)
                val end =
                    android.text.Selection.getSelectionEnd(buffer).coerceIn(start, buffer.length)
                val before =
                    if (codePoints)
                        Character.offsetByCodePoints(
                            buffer,
                            start,
                            -beforeLength.coerceIn(0, Character.codePointCount(buffer, 0, start)),
                        )
                    else (start - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
                val after =
                    if (codePoints)
                        Character.offsetByCodePoints(
                            buffer,
                            end,
                            afterLength.coerceIn(
                                0,
                                Character.codePointCount(buffer, end, buffer.length),
                            ),
                        )
                    else
                        (end.toLong() + afterLength.coerceAtLeast(0))
                            .coerceAtMost(buffer.length.toLong())
                            .toInt()
                buffer.delete(end, after)
                buffer.delete(before, start)
                changed()
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            deleteAround(beforeLength, afterLength, false)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            deleteAround(beforeLength, afterLength, true)

        fun backspace(): Boolean {
            if (buffer.isEmpty()) return false
            val end = android.text.Selection.getSelectionEnd(buffer).coerceAtLeast(0)
            if (end > 0) buffer.delete(Character.offsetByCodePoints(buffer, end, -1), end)
            changed()
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            finishForTerminalKey()
            session?.key(1)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean =
            this@TerminalView.dispatchKeyEvent(event)
    }

    fun terminalKey(code: Int, modifiers: Int = 0) {
        inputConnection.finishForTerminalKey()
        session?.key(code, modifiers)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val code =
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> 1
                KeyEvent.KEYCODE_TAB -> 2
                KeyEvent.KEYCODE_DEL -> 3
                KeyEvent.KEYCODE_ESCAPE -> 4
                KeyEvent.KEYCODE_DPAD_UP -> 5
                KeyEvent.KEYCODE_DPAD_DOWN -> 6
                KeyEvent.KEYCODE_DPAD_LEFT -> 7
                KeyEvent.KEYCODE_DPAD_RIGHT -> 8
                KeyEvent.KEYCODE_FORWARD_DEL -> 10
                KeyEvent.KEYCODE_MOVE_HOME -> 11
                KeyEvent.KEYCODE_MOVE_END -> 12
                KeyEvent.KEYCODE_PAGE_UP -> 13
                KeyEvent.KEYCODE_PAGE_DOWN -> 14
                else -> 0
            }
        if (code != 0) {
            if (code == 3 && inputConnection.backspace()) return true
            inputConnection.finishForTerminalKey()
            session?.key(
                code,
                (if (event.isCtrlPressed) 4 else 0) or
                    (if (event.isAltPressed) 2 else 0) or
                    (if (event.isShiftPressed) 1 else 0),
            )
            return true
        }
        var cp =
            event.getUnicodeChar(
                event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
            )
        var escape = event.isAltPressed
        // Right Alt is AltGr on layouts that put characters on it (@ { } [ ] on German or
        // French keyboards); elsewhere it is Alt.
        if (event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0) {
            val altGr = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
            if (altGr > 0 && altGr != cp) {
                cp = altGr
                escape = false
            }
        }
        // A dead key (an accent waiting for its letter) has no character of its own.
        if (cp and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) return true
        if (cp > 0 && Character.isValidCodePoint(cp)) {
            inputConnection.finishForTerminalKey()
            if (event.isCtrlPressed && cp in 64..127) send((cp and 31).toChar().toString())
            else send((if (escape) "\u001b" else "") + String(Character.toChars(cp)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Moves the view [pixels] down through the history (positive shows older output). */
    private fun scrollByPixels(pixels: Float): Boolean {
        scrollRemainder += pixels
        val lines = (scrollRemainder / lineHeight).toInt()
        if (lines == 0) return true
        scrollRemainder -= lines * lineHeight
        val target = (scrollback + lines).coerceIn(0, session?.historySize() ?: 0)
        val moved = target != scrollback
        scrollback = target
        if (!moved) scrollRemainder = 0f
        invalidate()
        return moved
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            if (scrollByPixels((y - flingY).toFloat())) postInvalidateOnAnimation()
            else scroller.forceFinished(true)
            flingY = y
        }
    }

    private fun commandUnder(y: Float): String? =
        session?.commandAt(floor(y / lineHeight).toInt(), scrollback)

    private fun noticeUnder(y: Float): Long? =
        session?.noticeAt(floor(y / lineHeight).toInt(), scrollback)

    /** What a tap at [y] does: open the notice there or run the command there again. */
    private fun tapAction(y: Float): (() -> Unit)? {
        onNoticeTap?.let { tap -> noticeUnder(y)?.let { id -> return { tap(id) } } }
        commandUnder(y)?.let { command ->
            return {
                finishInput()
                onCommandTap(command)
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = android.view.VelocityTracker.obtain().also { it.addMovement(event) }
                touchY = event.y
                downY = event.y
                scrollRemainder = 0f
                dragged = false
                pressedCommand = null
                pressedNotice = null
                postDelayed(
                    longPress,
                    android.view.ViewConfiguration.getLongPressTimeout().toLong(),
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged && kotlin.math.abs(event.y - downY) > touchSlop) {
                    removeCallbacks(longPress)
                    dragged = true
                    touchY = event.y
                }
                if (dragged) {
                    scrollByPixels(event.y - touchY)
                    touchY = event.y
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                velocity?.recycle()
                velocity = null
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val tracker = velocity
                if (dragged && tracker != null) {
                    tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                    val speed = tracker.yVelocity
                    if (kotlin.math.abs(speed) > minFling) {
                        flingY = 0
                        scroller.fling(0, 0, 0, speed.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                        postInvalidateOnAnimation()
                    }
                }
                if (!dragged) {
                    val action = tapAction(event.y)
                    if (action == null) performClick() else action()
                }
                velocity?.recycle()
                velocity = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        removeCallbacks(resizeGrid)
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, 0)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = visibleText()
        info.isEditable = true
        info.className = "android.widget.EditText"
    }

    fun visibleText(): String {
        val session = session ?: return ""
        val cells = session.cells(scrollback)
        return buildString {
            for (r in 0 until session.rows) {
                for (c in 0 until session.cols) {
                    val i = 2 + (r * session.cols + c) * 10
                    if (i < cells.size) {
                        val cp = cells[i]
                        if (cp > 0 && Character.isValidCodePoint(cp))
                            append(String(Character.toChars(cp)))
                        else append(' ')
                    }
                }
                append('\n')
            }
        }
    }
}
