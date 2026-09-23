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
    fun `지인과 모르는 사람 대본이 정확히 하나씩 있다`() {
        val rels = AttackerScriptCatalog.scripts.map { it.relationship }
        assertEquals(2, rels.size)
        CallerRelationship.values().forEach { rel ->
            assertEquals(rel.label + " 대본 개수", 1, rels.count { it == rel })
        }
    }

    @Test
    fun `두 시나리오 모두 진짜 사기다`() {
        // 애매한 상황 조건이 빠졌으므로 개입은 항상 옳은 판단이 된다.
        // 과잉 개입 비용을 다시 재려면 AMBIGUOUS 대본을 추가해야 한다는 뜻이다.
        AttackerScriptCatalog.scripts.forEach { script ->
            assertEquals(script.id, ScamLevel.REAL_SCAM, script.scamLevel)
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
                script.interventionPointIndex >= 3
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
    fun `모든 시나리오가 개입 안내에 넣을 상황 구절을 가진다`() {
        // 이 구절이 비면 안내가 "지금 통화 상황이…"로 뭉뚱그려져
        // 참가자가 무엇을 근거로 판단할지 알 수 없게 된다
        AttackerScriptCatalog.scripts.forEach { script ->
            assertTrue(script.id + ": 상황 구절 없음", script.riskPhrase.isNotBlank())
            // 관형형으로 끝나 "상황이 보이스피싱으로…"에 자연스럽게 이어져야 한다
            assertTrue(
                script.id + ": 구절이 '~하는' 형태가 아닙니다: " + script.riskPhrase,
                script.riskPhrase.endsWith("하는")
            )
        }
    }
}
