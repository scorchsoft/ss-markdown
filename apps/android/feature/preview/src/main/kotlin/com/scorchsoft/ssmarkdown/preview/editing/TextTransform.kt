package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit

/**
 * Operational transform for plain-text splices: the Kotlin twin of the page's
 * `web/src/core/ot.ts`, with the same algorithm and the same tie rule, so both
 * ends of the bridge rebase concurrent edits identically. Change them together.
 *
 * An edit set is a list of non-overlapping [TextEdit]s sorted by offset, all
 * relative to one text. For the transform it becomes components (retain,
 * insert, delete) with an implicit retain to the end, as in ot.js (MIT,
 * https://github.com/Operational-Transformation/ot.js), whose transform
 * converges for any pair (TP1):
 *
 *     apply(apply(s, host), page') == apply(apply(s, page), host')
 *
 * Inserted text always survives. When both sides insert at one offset, the
 * host's text goes first.
 */
internal object TextTransform {
    private sealed interface Component {
        @JvmInline value class Retain(
            val n: Int,
        ) : Component

        @JvmInline value class Insert(
            val s: String,
        ) : Component

        @JvmInline value class Delete(
            val n: Int,
        ) : Component
    }

    /** Both edit sets rebased: [host] to apply after the page's, [page] after the host's. */
    data class Result(
        val host: List<TextEdit>,
        val page: List<TextEdit>,
    )

    /** True when [edits] are sorted, non-overlapping and inside a text of [length]. */
    fun isValid(
        edits: List<TextEdit>,
        length: Int = Int.MAX_VALUE,
    ): Boolean {
        var at = 0
        return edits.all { e ->
            val fits = e.from >= at && e.to in e.from..length
            at = e.to
            fits
        }
    }

    fun transform(
        host: List<TextEdit>,
        page: List<TextEdit>,
    ): Result {
        val a = Cursor(components(host))
        val b = Cursor(components(page))
        val hostOut = Builder()
        val pageOut = Builder()
        while (!a.done || !b.done) {
            val ca = a.peek()
            val cb = b.peek()
            when {
                ca is Component.Insert -> {
                    hostOut.insert(ca.s)
                    pageOut.retain(ca.s.length)
                    a.take(1)
                }

                cb is Component.Insert -> {
                    hostOut.retain(cb.s.length)
                    pageOut.insert(cb.s)
                    b.take(1)
                }

                else -> {
                    val n = minOf(size(ca), size(cb))
                    val aDel = ca is Component.Delete
                    val bDel = cb is Component.Delete
                    when {
                        !aDel && !bDel -> {
                            hostOut.retain(n)
                            pageOut.retain(n)
                        }

                        aDel && !bDel -> {
                            hostOut.delete(n)
                        }

                        !aDel && bDel -> {
                            pageOut.delete(n)
                        }

                        else -> {
                            Unit
                        } // both deleted the same text: it is gone once
                    }
                    a.take(n)
                    b.take(n)
                }
            }
        }
        return Result(edits(hostOut.out), edits(pageOut.out))
    }

    /** Where [offset] lands after [edits]; inside deleted text it moves to the deletion. */
    fun mapOffset(
        offset: Int,
        edits: List<TextEdit>,
        after: Boolean = true,
    ): Int {
        var delta = 0
        for (e in edits) {
            val insertHere = offset == e.from && e.from == e.to
            if (offset < e.from || (insertHere && !after)) break
            if (offset < e.to) return e.from + delta + if (after) e.insert.length else 0
            delta += e.insert.length - (e.to - e.from)
        }
        return offset + delta
    }

    /** [text] with [edits] applied. O(length): tests and small texts only. */
    fun apply(
        text: CharSequence,
        edits: List<TextEdit>,
    ): String {
        val out = StringBuilder(text)
        for (e in edits.asReversed()) out.replace(e.from, e.to, e.insert)
        return out.toString()
    }

    private fun size(c: Component?): Int =
        when (c) {
            null -> Int.MAX_VALUE
            is Component.Retain -> c.n
            is Component.Delete -> c.n
            is Component.Insert -> c.s.length
        }

    private fun components(edits: List<TextEdit>): List<Component> {
        require(isValid(edits)) { "edits out of order: $edits" }
        val b = Builder()
        var at = 0
        for (e in edits) {
            b.retain(e.from - at)
            b.insert(e.insert)
            b.delete(e.to - e.from)
            at = e.to
        }
        return b.out
    }

    private fun edits(components: List<Component>): List<TextEdit> {
        val out = ArrayList<TextEdit>()
        var at = 0
        var open: TextEdit? = null
        for (c in components) {
            when (c) {
                is Component.Retain -> {
                    open?.let(out::add)
                    open = null
                    at += c.n
                }

                is Component.Insert -> {
                    val o = open ?: TextEdit(at, at, "")
                    open = o.copy(insert = o.insert + c.s)
                }

                is Component.Delete -> {
                    val o = open ?: TextEdit(at, at, "")
                    open = o.copy(to = o.to + c.n)
                    at += c.n
                }
            }
        }
        open?.let(out::add)
        return out
    }

    private class Builder {
        val out = ArrayList<Component>()

        fun retain(n: Int) {
            if (n <= 0) return
            val last = out.lastOrNull()
            if (last is Component.Retain) {
                out[out.lastIndex] = Component.Retain(last.n + n)
            } else {
                out +=
                    Component.Retain(n)
            }
        }

        fun insert(s: String) {
            if (s.isEmpty()) return
            val last = out.lastOrNull()
            when {
                // Inserts go before deletes at one point, so equal edits have one form.
                last is Component.Delete -> {
                    val prev = out.getOrNull(out.lastIndex - 1)
                    if (prev is Component.Insert) {
                        out[out.lastIndex - 1] = Component.Insert(prev.s + s)
                    } else {
                        out.add(out.lastIndex, Component.Insert(s))
                    }
                }

                last is Component.Insert -> {
                    out[out.lastIndex] = Component.Insert(last.s + s)
                }

                else -> {
                    out += Component.Insert(s)
                }
            }
        }

        fun delete(n: Int) {
            if (n <= 0) return
            val last = out.lastOrNull()
            if (last is Component.Delete) {
                out[out.lastIndex] = Component.Delete(last.n + n)
            } else {
                out +=
                    Component.Delete(n)
            }
        }
    }

    private class Cursor(
        private val components: List<Component>,
    ) {
        private var index = 0
        private var used = 0

        val done: Boolean get() = index >= components.size

        fun peek(): Component? =
            when (val c = components.getOrNull(index)) {
                is Component.Retain -> if (used == 0) c else Component.Retain(c.n - used)
                is Component.Delete -> if (used == 0) c else Component.Delete(c.n - used)
                else -> c
            }

        fun take(n: Int) {
            val c = components.getOrNull(index) ?: return
            val size =
                when (c) {
                    is Component.Retain -> c.n
                    is Component.Delete -> c.n
                    is Component.Insert -> 1
                }
            if (c is Component.Insert || used + n >= size) {
                index++
                used = 0
            } else {
                used += n
            }
        }
    }
}
