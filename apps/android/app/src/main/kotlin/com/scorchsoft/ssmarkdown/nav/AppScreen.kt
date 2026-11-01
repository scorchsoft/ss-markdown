package com.scorchsoft.ssmarkdown.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The app's screens. The editor is always at the bottom of the stack. */
sealed interface AppScreen {
    data object Editor : AppScreen

    data object Settings : AppScreen

    data object Storage : AppScreen

    data object About : AppScreen

    data object Licences : AppScreen

    /** Version history of the document on screen. */
    data object History : AppScreen

    /** MergeResolve: settle the overlaps one at a time. */
    data object Merge : AppScreen

    /** ComparePhone: one scrolling diff with a Changes / File's / Mine toggle. */
    data object Compare : AppScreen

    /** The full-screen image and diagram viewer. */
    data object Viewer : AppScreen

    /** The custom theme editor. */
    data object ThemeEditor : AppScreen

    /** Choose a font, for the theme editor. */
    data object FontPicker : AppScreen
}

/**
 * A tiny back stack. Process-scoped (in the graph) so a configuration
 * change keeps the screen; Back pops it, and the editor is never popped.
 */
class ScreenStack {
    private val stack = MutableStateFlow<List<AppScreen>>(listOf(AppScreen.Editor))

    /** The stack, bottom first. */
    val screens: StateFlow<List<AppScreen>> = stack.asStateFlow()

    /** The screen on top. */
    val current: AppScreen get() = stack.value.last()

    /** Shows [screen] on top, unless it already is. */
    fun push(screen: AppScreen) = stack.update { if (it.last() == screen) it else it + screen }

    /** Goes back one screen; false at the editor. */
    fun pop(): Boolean {
        if (stack.value.size <= 1) return false
        stack.update { it.dropLast(1) }
        return true
    }

    /** Goes back to the editor. */
    fun popToEditor() = stack.update { listOf(AppScreen.Editor) }
}
