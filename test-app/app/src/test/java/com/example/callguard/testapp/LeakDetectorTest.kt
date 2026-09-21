package com.example.callguard.testapp

import com.example.callguard.testapp.domain.stt.LeakDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LeakDetectorTest {

    @Test
    fun `연속 숫자는 확정 유출로 잡는다`() {
        val d = LeakDetector()
        val leak = d.analyzeLeak("네 901231 입니다")
        assertNotNull(leak)
        assertEquals(LeakDetector.Confidence.CONFIRMED, leak!!.confidence)
        assertEquals("901231", leak.matched)
    }

    @Test
    fun `띄어 읽은 숫자도 하나의 숫자열로 본다`() {
        // STT는 "9 0 1 2 3 1"처럼 숫자 사이에 공백을 넣는 경우가 많다
        val d = LeakDetector()
        assertNotNull(d.analyzeLeak("9 0 1 2 3 1"))
    }

    @Test
    fun `한글 숫자 음절은 의심으로만 표시한다`() {
        // "사실", "이제" 같은 일상 단어와 겹쳐 오탐이 나므로 자동 확정하지 않는다
        val d = LeakDetector()
        val leak = d.analyzeLeak("구공일이삼일")
        assertNotNull(leak)
        assertEquals(LeakDetector.Confidence.SUSPECTED, leak!!.confidence)
        assertEquals("구공일이삼일 (→ 901231)", leak.matched)
    }

    @Test
    fun `짧은 숫자는 유출로 보지 않는다`() {
        val d = LeakDetector()
        assertNull(d.analyzeLeak("네 12 층이요"))
    }

    @Test
    fun `유출은 세션당 한 번만 보고된다`() {
        // partial STT는 같은 구간을 초당 여러 번 반복해 넘긴다
        val d = LeakDetector()
        assertNotNull(d.analyzeLeak("901231"))
        assertNull(d.analyzeLeak("901231"))
        assertNull(d.analyzeLeak("9012311234"))
    }

    @Test
    fun `초기화하면 다음 세션에서 다시 잡는다`() {
        val d = LeakDetector()
        d.analyzeLeak("901231")
        d.reset()
        assertNotNull(d.analyzeLeak("901231"))
    }

    @Test
    fun `거부 표현을 잡는다`() {
        val d = LeakDetector()
        assertEquals("안 알려", d.analyzeRefusal("그건 안 알려드릴게요"))
        assertNull(d.analyzeRefusal("안 알려드립니다"))
    }

    @Test
    fun `보이스피싱 언급도 거부 신호로 본다`() {
        val d = LeakDetector()
        assertEquals("보이스피싱", d.analyzeRefusal("이거 보이스피싱 아니에요?"))
    }
}
