package com.example.callguard.testapp.domain.script

/**
 * 실험 시나리오 2종 — **발신자 관계**(지인 / 모르는 사람) 하나만 달라진다.
 *
 * | ID | 관계 | 상황 | 요구 |
 * |---|---|---|---|
 * | S1 | 지인 | 계좌가 잠겼다며 타인 명의 계좌로 급전 요구 | 50만 원 송금 |
 * | S2 | 모르는 사람 | 은행 보안팀 사칭, 이상 거래 확인 | 계좌 비밀번호 앞 두 자리 |
 *
 * 둘 다 **진짜 사기**다. 2026-09 팀 논의에서 애매한 상황 조건이 빠지면서 남은 축은
 * "상대가 아는 사람으로 보이는가" 하나가 됐다. 저장된 이름이 뜨는 것만으로 경계가
 * 늦어지는지를 보는 설계다.
 *
 * 대신 잃은 것: 두 시나리오 모두 진짜 사기라 **개입은 항상 옳은 판단**이 된다.
 * "멀쩡한 통화를 얼마나 방해했는가"(과잉 개입 비용)는 이 구성으로는 잴 수 없다.
 * 그 지표가 필요해지면 애매한 상황 대본을 이 목록에 추가하면 된다 — 엔진은 그대로다.
 *
 * 대사는 팀이 확정한 대본을 글자 그대로 옮겼다. 공격자 역할이 매번 즉흥으로 말하면
 * 참가자마다 자극이 달라져 "개입 방식의 효과"와 "대본 차이의 효과"가 분리되지 않는다.
 *
 * `afterIntervention` 줄만 팀 대본에 없던 추가분이다 — 개입 1에서 참가자가
 * [통화 계속하기]를 고르면 대화가 이어지는데, 그때 상대가 받아칠 말이 없으면 통화가
 * 어색하게 멈춰 그 선택의 결과를 관찰할 수 없다.
 */
object AttackerScriptCatalog {

    // 기관명과 발신번호는 **전부 가상**이다. 실존 금융기관 이름이나 실제 대표번호를 쓰면
    // 이 저장소의 대본이 그대로 쓸 수 있는 피싱 템플릿이 되고, 해당 기업을 사칭하는
    // 자료가 된다. 팀 대본의 "__은행" 빈칸은 TTS가 읽어야 하므로 여기서 채웠다.
    // 참가자에게는 "모르는 번호에서 온 금융기관 전화"로만 보이면 충분하다.

    val scripts: List<AttackerScript> = listOf(

        // ── S1. 지인 ────────────────────────────────────────────
        AttackerScript(
            id = "S1",
            label = "1. 지인 (급전 요구)",
            scamLevel = ScamLevel.REAL_SCAM,
            relationship = CallerRelationship.ACQUAINTANCE,
            situation = "지인이 계좌가 잠겼다며 타인 명의 계좌로 50만 원 송금을 요구",
            defaultCallerName = "김민수",
            callerNumber = "010-2841-7···",
            requestedInfo = "50만 원 송금 (타인 명의 계좌)",
            keyFactors = listOf(
                "지인을 이용한 신뢰 형성",
                "본인 계좌를 못 쓴다는 설명",
                "타인 명의 계좌로 송금 요구",
                "급한 송금 요구",
                "오늘 저녁에 갚겠다는 시간 압박"
            ),
            lines = listOf(
                ScriptLine("어 난데, 내가 지금 급하게 처리해야 할 일이 생겼는데 계좌가 잠겨서 이체를 못 하고 있어. 혹시 50만 원 정도만 대신 보내줄 수 있어? 내 계좌는 지금 사용을 못 해서 다른 계좌번호를 보내줄게. 내가 아는 사람 계좌니까 거기로 보내주면 오늘 저녁에 바로 돌려줄게.",
                    isInterventionPoint = true
                ),
                // ── 개입 ──
                ScriptLine("그거 그냥 자동으로 뜨는 거야. 신경 쓰지 말고 계좌번호 받아 적어.", afterIntervention = true),
                ScriptLine("나 진짜 급해서 그래. 지금 안 보내주면 큰일 나.", afterIntervention = true),
                ScriptLine("알았어, 그럼 이따 다시 전화할게.", afterIntervention = true)
            )
        ),

        // ── S2. 모르는 사람 ─────────────────────────────────────
        AttackerScript(
            id = "S2",
            label = "2. 모르는 사람 (은행 사칭)",
            scamLevel = ScamLevel.REAL_SCAM,
            relationship = CallerRelationship.STRANGER,
            situation = "은행 보안팀을 사칭해 이상 거래를 알리며 계좌 비밀번호를 요구",
            defaultCallerName = "",
            callerNumber = "02-3140-0000",
            requestedInfo = "계좌 비밀번호 앞 두 자리",
            keyFactors = listOf(
                "모르는 사람의 전화",
                "금융기관 사칭",
                "계좌 비밀번호 요구 (금융기관은 전화로 묻지 않음)",
                "피해 가능성을 이용한 불안감 조성",
                "즉시 확인하라는 압박"
            ),
            lines = listOf(
                ScriptLine("안녕하세요. 충대은행 보안팀입니다.", pauseAfterMs = 900),
                ScriptLine("고객님 계좌에서 오늘 오전 비정상적인 이체 시도가 확인돼서 연락드렸습니다."),
                ScriptLine("현재 추가 피해가 발생할 가능성이 있으니 빠르게 본인 확인을 진행해야 합니다.", pauseAfterMs = 900),
                ScriptLine(
                    "현재 사용하고 계신 계좌의 비밀번호 앞 두 자리를 말씀해주시면 바로 검토해드리겠습니다.",
                    isInterventionPoint = true
                ),
                // ── 개입 ──
                ScriptLine("그건 통신사에서 자동으로 뜨는 안내입니다. 무시하셔도 됩니다.", afterIntervention = true),
                ScriptLine("고객님, 지금 확인하지 않으시면 계좌가 정지됩니다. 앞 두 자리만 말씀해주세요.", afterIntervention = true),
                ScriptLine("네, 확인했습니다. 처리 후 다시 연락드리겠습니다.", afterIntervention = true)
            )
        )
    )

    fun findById(id: String): AttackerScript? = scripts.firstOrNull { it.id == id }

    /**
     * 대본 무결성 검사 — 단위 테스트가 호출한다.
     *
     * 개입 지점이 없으면 개입이 영영 자동 발동하지 않고, 둘이면 어느 쪽이 기준점인지
     * 알 수 없어 반응시간이 무의미해진다. 관계 조건이 한쪽만 있으면 설계가 성립하지 않는다.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()

        scripts.forEach { script ->
            val count = script.mainLines.count { it.isInterventionPoint }
            when {
                count == 0 -> problems += script.id + ": 개입 지점(isInterventionPoint)이 없습니다"
                count > 1 -> problems += script.id + ": 개입 지점이 " + count + "개입니다 (1개여야 함)"
                !script.mainLines.last().isInterventionPoint ->
                    problems += script.id + ": 개입 지점이 마지막 줄이 아닙니다 — 대사가 끝나면 개입이라는 설계와 어긋납니다"
            }
            if (script.relationship == CallerRelationship.ACQUAINTANCE && script.defaultCallerName.isBlank()) {
                problems += script.id + ": 지인 조건인데 화면에 뜰 이름이 없습니다"
            }
            if (script.relationship == CallerRelationship.STRANGER && script.defaultCallerName.isNotBlank()) {
                problems += script.id + ": 모르는 사람 조건인데 저장된 이름이 있습니다"
            }
        }

        // 관계 조건이 정확히 하나씩 있어야 한다
        CallerRelationship.values().forEach { rel ->
            val n = scripts.count { it.relationship == rel }
            if (n != 1) {
                problems += rel.label + " 조건에 대본이 " + n + "개입니다 (1개여야 함)"
            }
        }
        return problems
    }
}
