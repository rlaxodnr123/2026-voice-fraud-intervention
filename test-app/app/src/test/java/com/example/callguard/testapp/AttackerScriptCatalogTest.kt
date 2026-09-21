package com.example.callguard.testapp

import com.example.callguard.testapp.domain.script.AttackerScriptCatalog
import com.example.callguard.testapp.domain.script.CallerRelationship
import com.example.callguard.testapp.domain.script.ScamLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttackerScriptCatalogTest {

    @Test
    fun `대본 무결성 검사를 통과한다`() {
        val problems = AttackerScriptCatalog.validate()
        assertTrue("대본 무결성 위반: " + problems.joinToString("; "), problems.isEmpty())
    }

    @Test
    fun `2x2 네 칸이 정확히 하나씩 채워져 있다`() {
        // [진짜사기·지인] [애매·지인] [진짜사기·모름] [애매·모름]
        val cells = AttackerScriptCatalog.scripts.map { it.scamLevel to it.relationship }
        assertEquals(4, cells.size)
        assertEquals(4, cells.toSet().size)
        ScamLevel.values().forEach { level ->
            CallerRelationship.values().forEach { rel ->
                assertTrue(
                    level.label + "×" + rel.label + " 셀이 비었습니다",
                    cells.contains(level to rel)
                )
            }
        }
    }

    @Test
    fun `대본 ID는 중복되지 않는다`() {
        val ids = AttackerScriptCatalog.scripts.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `녹음본 파일 순번은 1부터 시작해 대사 수와 일치한다`() {
        AttackerScriptCatalog.scripts.forEach { script ->
            val indices = script.mainLines.indices.map { script.audioFileIndex(it) }
            assertEquals((1..script.mainLines.size).toList(), indices)
        }
    }

    @Test
    fun `개입 지점 앞에 충분한 도입 대사가 있다`() {
        // 일방적 통보 설계 — 상대가 상황을 충분히 설명한 뒤 개입 지점에 도달해야
        // 참가자가 맥락 없이 경고만 받는 상황이 되지 않는다
        AttackerScriptCatalog.scripts.forEach { script ->
            assertTrue(
                script.id + ": 개입 지점 앞 도입 대사가 부족합니다",
                script.interventionPointIndex >= 4
            )
        }
    }

    @Test
    fun `모든 시나리오에 개입 후 후속 대사가 준비돼 있다`() {
        // 개입 1은 팝업에서 [통화 계속하기]를 고르면 대화가 이어진다.
        // 받아칠 대사가 없으면 통화가 어색하게 멈춰 그 선택의 결과를 관찰할 수 없다.
        AttackerScriptCatalog.scripts.forEach { script ->
            assertTrue(script.id + ": 후속 대사 없음", script.followUpLines.isNotEmpty())
        }
    }

    @Test
    fun `지인 조건만 화면에 뜰 이름을 가진다`() {
        AttackerScriptCatalog.scripts.forEach { script ->
            if (script.relationship == CallerRelationship.ACQUAINTANCE) {
                assertTrue(script.id, script.defaultCallerName.isNotBlank())
            } else {
                assertTrue(script.id, script.defaultCallerName.isBlank())
            }
            assertTrue(script.id + ": 발신번호 없음", script.callerNumber.isNotBlank())
        }
    }

    @Test
    fun `애매한 시나리오는 핵심 특징에 압박이 없음을 명시한다`() {
        // 애매 조건이 사실상 사기처럼 들리면 2x2가 무너진다.
        // 대본이 바뀔 때 이 성질이 유지되는지 확인한다.
        val ambiguous = AttackerScriptCatalog.scripts.filter { it.scamLevel == ScamLevel.AMBIGUOUS }
        assertEquals(2, ambiguous.size)
        ambiguous.forEach { script ->
            val factors = script.keyFactors.joinToString()
            assertTrue(
                script.id + ": 압박·요구 없음을 핵심 특징에 명시해야 합니다",
                factors.contains("없음")
            )
        }
    }
}
