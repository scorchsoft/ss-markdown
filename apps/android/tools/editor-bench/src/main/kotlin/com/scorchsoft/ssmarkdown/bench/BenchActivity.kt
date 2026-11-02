package com.scorchsoft.ssmarkdown.bench

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The benchmark's one screen. Tabs pick the engine, a spinner picks the
 * corpus, "Measure" runs that pair and leaves the engine on screen to try
 * by hand (Gboard included), and "Run all" runs every engine on every
 * corpus. "Copy results" puts the plain-text table on the clipboard.
 *
 * Built in code from platform widgets: a benchmark should not measure a UI
 * toolkit it does not need. Results survive process death (see
 * [ResultStore]), so a run that kills the app still shows up after relaunch.
 */
@Suppress("TooManyFunctions") // One small function per control on the screen.
class BenchActivity : Activity() {
    private val scope = MainScope()
    private lateinit var store: ResultStore
    private lateinit var runner: BenchRunner
    private lateinit var statusView: TextView
    private lateinit var resultsView: TextView
    private lateinit var corpusPicker: Spinner
    private val tabs = LinkedHashMap<EngineKind, Button>()
    private val actions = ArrayList<Button>()
    private var selected = EngineKind.CODE_VIEW
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ResultStore(filesDir)
        val host = FrameLayout(this)
        setContentView(buildLayout(host))
        runner = BenchRunner(this, host, store) { statusView.text = it }
        selectTab(selected)
        showResults()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildLayout(host: FrameLayout): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            row(
                EngineKind.entries.map { kind ->
                    button(kind.label) { selectTab(kind) }.also { tabs[kind] = it }
                },
            ),
        )
        corpusPicker =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_dropdown_item,
                        Corpus.entries.map { it.label },
                    )
            }
        root.addView(corpusPicker)
        root.addView(
            row(
                listOf(
                    button("Measure") { measureSelected() },
                    button("Run all") { runAll() },
                    button("Copy results") { copyResults() },
                    button("Clear") { clearResults() },
                ).onEach { actions += it },
            ),
        )
        statusView = TextView(this).apply { text = "Ready. Corpora are generated on first use." }
        root.addView(statusView)
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, HOST_WEIGHT))
        resultsView =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = RESULTS_TEXT_SP
                setTextIsSelectable(true)
            }
        val results = ScrollView(this).apply { addView(HorizontalScrollView(context).apply { addView(resultsView) }) }
        root.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, RESULTS_WEIGHT))
        return root
    }

    private fun row(children: List<View>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        children.forEach { row.addView(it) }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun button(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun selectTab(kind: EngineKind) {
        selected = kind
        tabs.forEach { (k, button) -> button.isSelected = k == kind }
        tabs.forEach { (k, button) -> button.alpha = if (k == kind) 1f else UNSELECTED_ALPHA }
    }

    private fun measureSelected() {
        val corpus = Corpus.entries[corpusPicker.selectedItemPosition]
        launchRun { runner.run(selected, corpus) }
    }

    private fun runAll() {
        launchRun {
            for (corpus in Corpus.entries) {
                for (kind in EngineKind.entries) {
                    runner.run(kind, corpus)
                    showResults()
                }
            }
            runner.releaseCurrent()
            statusView.text = "Run all finished. Copy results and paste them back."
        }
    }

    private fun launchRun(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                actions.forEach { it.isEnabled = false }
                try {
                    block()
                } finally {
                    actions.forEach { it.isEnabled = true }
                    showResults()
                }
            }
    }

    private fun showResults() {
        resultsView.text = ResultsTable.format(store.load(), refreshRate())
    }

    private fun refreshRate(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display.refreshRate
        } else {
            @Suppress("DEPRECATION") // The only way to ask on API 29.
            windowManager.defaultDisplay.refreshRate
        }

    private fun copyResults() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("SS editor bench", resultsView.text))
        Toast.makeText(this, "Results copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearResults() {
        store.clear()
        showResults()
    }

    private companion object {
        const val HOST_WEIGHT = 3f
        const val RESULTS_WEIGHT = 2f
        const val RESULTS_TEXT_SP = 10f
        const val UNSELECTED_ALPHA = 0.55f
    }
}
