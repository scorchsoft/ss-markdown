package com.scorchsoft.ssmarkdown.bench

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.ScrollView
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.text.DecodeResult
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.Utf8Decoder
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/** The engines under test. Each run creates a fresh [Engine], so nothing is shared between runs. */
enum class EngineKind(
    val label: String,
) {
    EDIT_TEXT_WRAP("EditText wrap"),
    EDIT_TEXT_NO_WRAP("EditText nowrap"),
    CODE_VIEW("MarkdownCodeView"),
    SORA("Sora"),
    ;

    fun create(): Engine =
        when (this) {
            EDIT_TEXT_WRAP -> EditTextEngine(wrap = true)
            EDIT_TEXT_NO_WRAP -> EditTextEngine(wrap = false)
            CODE_VIEW -> CodeViewEngine()
            SORA -> SoraEngine()
        }
}

/**
 * One editor, driven the same way as the others. [load] runs on a
 * background thread where the engine allows it; [show] runs on the main
 * thread and is what the user waits for.
 */
interface Engine {
    /** Reads [input] into the engine's own model, off the main thread. */
    fun load(input: InputStream)

    /** Builds the view showing the loaded text. Main thread. */
    fun show(context: Context): View

    /** The view a finger would drag to scroll. */
    val scrollTarget: View

    /** Puts the caret at the start of the middle line, where the keystroke test types. */
    fun placeCaretInMiddle()

    /** The connection a keyboard would get; the keystroke test commits through it. */
    fun inputConnection(): InputConnection?

    /** Lets go of everything, so the next run starts from a clean heap. */
    fun release()
}

private const val TEXT_SIZE_SP = 14f
private const val READ_CHUNK = 1 shl 16

private fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

/**
 * The platform `EditText` in a `ScrollView`, which is how a simple editor
 * shows a whole document. `setText` must run on the main thread, and the
 * text has to exist as one `CharSequence` first: both are the costs this
 * baseline exists to show.
 */
private class EditTextEngine(
    private val wrap: Boolean,
) : Engine {
    private var text: StringBuilder? = null
    private var editText: EditText? = null
    private var scroll: ScrollView? = null

    override val scrollTarget: View get() = checkNotNull(scroll)

    override fun load(input: InputStream) {
        val out = StringBuilder()
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val buffer = CharArray(READ_CHUNK)
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                out.appendRange(buffer, 0, n)
            }
        }
        text = out
    }

    override fun show(context: Context): View {
        val edit =
            EditText(context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                gravity = Gravity.TOP or Gravity.START
                background = null
                setHorizontallyScrolling(!wrap)
                setText(text)
            }
        text = null // EditText copied it into its own Editable.
        editText = edit
        scroll =
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    edit,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        return checkNotNull(scroll)
    }

    override fun placeCaretInMiddle() {
        val edit = checkNotNull(editText)
        val content = edit.text
        val middle = content.length / 2
        val lineStart = content.lastIndexOf('\n', middle).let { if (it < 0) 0 else it + 1 }
        edit.requestFocus()
        edit.setSelection(lineStart)
    }

    override fun inputConnection(): InputConnection? = editText?.onCreateInputConnection(EditorInfo())

    override fun release() {
        editText?.text?.clear()
        editText = null
        scroll = null
        text = null
    }
}

/** The spike: decoded straight into the piece tree off the main thread; `show` only hands it over. */
private class CodeViewEngine : Engine {
    private var document: TextDocument? = null
    private var view: MarkdownCodeView? = null

    override val scrollTarget: View get() = checkNotNull(view)

    override fun load(input: InputStream) {
        document =
            when (val result = Utf8Decoder.decode(input)) {
                is DecodeResult.Decoded -> TextDocument.from(result)
                is DecodeResult.ReadFailed -> throw result.cause
                else -> throw IOException("corpus did not decode: $result")
            }
    }

    override fun show(context: Context): View {
        val codeView = MarkdownCodeView(context)
        codeView.textSizePx = context.sp(TEXT_SIZE_SP)
        codeView.document = checkNotNull(document)
        view = codeView
        return codeView
    }

    override fun placeCaretInMiddle() {
        val codeView = checkNotNull(view)
        val text = codeView.document.text
        codeView.requestFocus()
        codeView.setCaret(text.lineStartOffset(text.lineCount / 2))
    }

    override fun inputConnection(): InputConnection? = view?.onCreateInputConnection(EditorInfo())

    override fun release() {
        view = null
        document = null
    }
}

/**
 * Sora Editor (LGPL-2.1, benchmark baseline only). Its `Content` is built
 * off the main thread by appending chunks, with undo off so the load is
 * not recorded as an edit, then handed to the editor without a copy.
 */
private class SoraEngine : Engine {
    private var content: Content? = null
    private var editor: CodeEditor? = null

    override val scrollTarget: View get() = checkNotNull(editor)

    override fun load(input: InputStream) {
        val built = Content()
        built.isUndoEnabled = false
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val buffer = CharArray(READ_CHUNK)
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                val lastLine = built.lineCount - 1
                built.insert(lastLine, built.getColumnCount(lastLine), String(buffer, 0, n))
            }
        }
        built.isUndoEnabled = true
        content = built
    }

    override fun show(context: Context): View {
        val codeEditor = CodeEditor(context)
        codeEditor.typefaceText = Typeface.MONOSPACE
        codeEditor.setTextSize(TEXT_SIZE_SP)
        codeEditor.setWordwrap(false)
        codeEditor.setText(checkNotNull(content), true, null)
        content = null
        editor = codeEditor
        return codeEditor
    }

    override fun placeCaretInMiddle() {
        val codeEditor = checkNotNull(editor)
        codeEditor.requestFocus()
        codeEditor.setSelection(codeEditor.text.lineCount / 2, 0)
    }

    override fun inputConnection(): InputConnection? = editor?.onCreateInputConnection(EditorInfo())

    override fun release() {
        editor?.release()
        editor = null
        content = null
    }
}
