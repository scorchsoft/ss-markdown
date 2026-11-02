package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshTraceTest {
    private val begun = mutableListOf<Int>()
    private val ended = mutableListOf<Int>()
    private val trace = RefreshTrace(begin = { begun += it }, end = { ended += it })

    private fun edit(rev: Int) = HostMessage.Edit(rev, emptyList())

    @Test
    fun aRenderClosesEveryEditUpToItsRevision() {
        trace.onSent(edit(1))
        trace.onSent(edit(2))
        trace.onSent(edit(3))
        trace.onRendered(2)
        assertEquals(listOf(1, 2, 3), begun)
        assertEquals(listOf(1, 2), ended)
        trace.onRendered(5)
        assertEquals(listOf(1, 2, 3), ended)
    }

    @Test
    fun onlyEditsAreTraced() {
        trace.onSent(HostMessage.Ack(4))
        trace.onRendered(4)
        assertEquals(emptyList<Int>(), begun)
        assertEquals(emptyList<Int>(), ended)
    }

    @Test
    fun anUnansweredBacklogIsClosedNotLeaked() {
        repeat(70) { trace.onSent(edit(it + 1)) }
        assertEquals((1..6).toList(), ended)
    }
}
