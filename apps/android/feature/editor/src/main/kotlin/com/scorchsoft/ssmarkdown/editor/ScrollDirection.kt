package com.scorchsoft.ssmarkdown.editor

/**
 * Which way the content last moved. The render margin leans the same way,
 * so the lines a fling is about to reveal are laid out before they arrive
 * (docs/robustness.md, "Editor view").
 */
enum class ScrollDirection {
    /** Scrolling back towards line 0. */
    TOWARDS_START,

    /** Scrolling on towards the last line. */
    TOWARDS_END,

    /** Not scrolling: the margin is split evenly. */
    IDLE,
}
