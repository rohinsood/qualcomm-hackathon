package dev.quad.shepherd

import dev.quad.shepherd.llm.ThinkFilter
import dev.quad.shepherd.llm.drainSentences
import org.junit.Assert.assertEquals
import org.junit.Test

class GenieChatStreamTest {

    @Test
    fun `think blocks are stripped even when tags split across tokens`() {
        val f = ThinkFilter()
        val out = StringBuilder()
        listOf("<th", "ink>let me ", "reason</th", "ink>Hey", " there. All", " clear.")
            .forEach { out.append(f.feed(it)) }
        out.append(f.finish())
        assertEquals("Hey there. All clear.", out.toString())
    }

    @Test
    fun `text without think tags passes through untouched`() {
        val f = ThinkFilter()
        val out = StringBuilder()
        listOf("Curb on", " your right, about", " one meter.").forEach { out.append(f.feed(it)) }
        out.append(f.finish())
        assertEquals("Curb on your right, about one meter.", out.toString())
    }

    @Test
    fun `unclosed think content is never emitted`() {
        val f = ThinkFilter()
        val out = StringBuilder()
        listOf("Okay. <think>secret ", "reasoning that never closes").forEach {
            out.append(f.feed(it))
        }
        out.append(f.finish())
        assertEquals("Okay. ", out.toString())
    }

    @Test
    fun `sentences stream out as they complete and the remainder stays buffered`() {
        val got = mutableListOf<String>()
        val buf = StringBuilder("Watch the curb. It is ")
        drainSentences(buf) { got.add(it) }
        assertEquals(listOf("Watch the curb."), got)
        assertEquals("It is ", buf.toString())

        buf.append("about 2.5 meters away. More")
        drainSentences(buf) { got.add(it) }
        assertEquals(listOf("Watch the curb.", "It is about 2.5 meters away."), got)
        assertEquals("More", buf.toString())
    }

    @Test
    fun `decimals do not split sentences`() {
        val got = mutableListOf<String>()
        val buf = StringBuilder("About 2.5 meters. ")
        drainSentences(buf) { got.add(it) }
        assertEquals(listOf("About 2.5 meters."), got)
    }

    @Test
    fun `newlines end sentences too`() {
        val got = mutableListOf<String>()
        val buf = StringBuilder("First thought\nSecond half")
        drainSentences(buf) { got.add(it) }
        assertEquals(listOf("First thought"), got)
        assertEquals("Second half", buf.toString())
    }
}
