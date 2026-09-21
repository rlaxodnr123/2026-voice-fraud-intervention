package com.example.callguard.testapp.domain.script

/**
 * 실험 시나리오 4종 — **사기 의심 정도 × 발신자 관계**의 2×2.
 *
 * | | 지인 | 모르는 사람 |
 * |---|---|---|
 * | 진짜 사기 | S1 급전 요구 | S3 은행 사칭 |
 * | 애매한 상황 | S2 자녀 송금 부탁 | S4 카드사 확인 전화 |
 *
 * **애매한 시나리오(S2·S4)가 왜 있는가:** 상대가 단계적으로 정보를 물을 때 첫 요구에서
 * 바로 차단하면, 노출돼도 문제없는 일상 통화까지 끊는 과잉 개입이 된다. 개입의 가치는
 * "사기를 얼마나 막았는가"만으로 정해지지 않고 "정상 통화를 얼마나 망쳤는가"와 함께 정해진다.
 * S2·S4는 **의도된 오탐 조건**이며, 여기서 측정하는 것은 보호 효과가 아니라
 * 방해감·신뢰 하락이다.
 *
 * 대사는 2026-09 팀 논의에서 확정된 대본을 글자 그대로 옮겼다. 공격자 역할이 매번
 * 즉흥으로 말하면 참가자마다 자극이 달라져 "개입 방식의 효과"와 "대본 차이의 효과"가
 * 분리되지 않는다.
 *
 * `afterIntervention` 줄만 팀 대본에 없던 추가분이다 — 개입 1(팝업+TTS)에서 참가자가
 * [통화 계속하기]를 고르면 대화가 이어지는데, 그때 상대가 받아칠 말이 없으면 통화가
 * 어색하게 멈춰 그 선택의 결과를 관찰할 수 없다. 각 시나리오의 성격을 그대로 유지했다
 * (사기 조건은 계속 재촉하고, 애매한 조건은 압박하지 않는다).
 */
object AttackerScriptCatalog {

    // 기관명과 발신번호는 **전부 가상**이다. 실존 금융기관 이름이나 실제 대표번호를 쓰면
    // 이 저장소의 대본이 그대로 쓸 수 있는 피싱 템플릿이 되고, 해당 기업을 사칭하는
    // 자료가 된다. 참가자에게는 "모르는 번호에서 온 금융기관 전화"로만 보이면 충분하므로
    // 실험 타당성에는 영향이 없다.


    val scripts: List<AttackerScript> = listOf(

        // ── S1. 진짜 사기 × 지인 ────────────────────────────────
        AttackerScript(
            id = "S1",
            label = "1. 진짜 사기 · 지인",
            scamLevel = ScamLevel.REAL_SCAM,
            relationship = CallerRelationship.ACQUAINTANCE,
            situation = "오랜만에 연락한 동창이 급하게 돈이 필요하다며 타인 명의 계좌로 송금을 재촉",
            defaultCallerName = "김민수",
            callerNumber = "010-2841-7···",
            requestedInfo = "50만 원 송금 (타인 명의 계좌)",
            riskPhrase = "다른 사람 계좌로 송금을 요구하는",
            keyFactors = listOf(
                "지인을 이용한 신뢰 형성",
                "급한 송금 요구",
                "타인 명의 계좌로 송금 요구",
                "시간적 압박",
                "반복적인 송금 재촉"
            ),
            lines = listOf(
                ScriptLine("야, 오랜만이다. 잘 지냈어?", pauseAfterMs = 900),
                ScriptLine("내가 지금 급하게 처리해야 할 일이 생겼는데 계좌가 잠겨서 이체를 못 하고 있어."),
                ScriptLine("혹시 50만 원 정도만 대신 보내줄 수 있어?", pauseAfterMs = 900),
                ScriptLine("내 계좌는 지금 사용을 못 해서 다른 계좌번호를 불러줄게."),
                ScriptLine("내가 아는 사람 계좌니까 거기로 보내주면 돼."),
                ScriptLine("지금 바로 처리해야 해서 시간이 없어. 일단 먼저 보내주면 오늘 저녁에 바로 돌려줄게."),
                ScriptLine("계좌번호 불러줄 테니까 지금 바로 보내줘.", isInterventionPoint = true),
                // ── 개입 ──
                ScriptLine("그거 그냥 자동으로 뜨는 거야. 신경 쓰지 말고 계좌번호 받아 적어.", afterIntervention = true),
                ScriptLine("나 진짜 급해서 그래. 지금 안 보내주면 큰일 나.", afterIntervention = true),
                ScriptLine("알았어, 그럼 이따 다시 전화할게.", afterIntervention = true)
            )
        ),

        // ── S2. 애매한 상황 × 지인 ──────────────────────────────
        AttackerScript(
            id = "S2",
            label = "2. 애매한 상황 · 지인",
            scamLevel = ScamLevel.AMBIGUOUS,
            relationship = CallerRelationship.ACQUAINTANCE,
            situation = "자녀가 실제로 있을 법한 이유로 본인 계좌에 소액 송금을 부탁 (압박·계좌 변경 없음)",
            defaultCallerName = "아들",
            callerNumber = "010-5520-3···",
            requestedInfo = "20만 원 송금 (평소 쓰던 본인 계좌)",
            riskPhrase = "송금을 요청하는",
            keyFactors = listOf(
                "가까운 지인의 송금 요청",
                "실제로 발생할 수 있는 자연스러운 사유",
                "기존에 사용하던 본인 계좌 이용",
                "개인정보나 인증번호 요구 없음",
                "과도한 재촉이나 위협 없음",
                "금융 관련 표현 때문에 시스템이 위험으로 판단할 수 있음"
            ),
            lines = listOf(
                ScriptLine("엄마, 지금 잠깐 통화 가능해?", pauseAfterMs = 900),
                ScriptLine("내가 지금 밖인데 오늘 결제해야 하는 게 하나 생겼어."),
                ScriptLine("생각보다 돈이 조금 부족해서 그런데 혹시 20만 원 정도 보내줄 수 있어?", pauseAfterMs = 900),
                ScriptLine("학교에서 필요한 거 결제하려고 하는데 오늘까지 해야 하거든."),
                ScriptLine("평소에 보내던 내 계좌로 보내주면 돼."),
                ScriptLine("지금 바로 안 보내도 괜찮으니까 확인하고 보내줘.", isInterventionPoint = true),
                // ── 개입 ──
                ScriptLine("어? 무슨 소리야 그게. 나야 나.", afterIntervention = true),
                ScriptLine("급한 거 아니니까 천천히 확인하고 보내줘도 돼.", afterIntervention = true),
                ScriptLine("응 알았어. 이따 집에서 봐.", afterIntervention = true)
            )
        ),

        // ── S3. 진짜 사기 × 모르는 사람 ─────────────────────────
        AttackerScript(
            id = "S3",
            label = "3. 진짜 사기 · 모르는 사람",
            scamLevel = ScamLevel.REAL_SCAM,
            relationship = CallerRelationship.STRANGER,
            situation = "은행 보안팀을 사칭해 이상 거래를 알리며 계좌 정보를 요구하고 전화를 끊지 못하게 압박",
            defaultCallerName = "",
            callerNumber = "02-3140-0000",
            requestedInfo = "거래 은행명, 계좌번호",
            riskPhrase = "계좌번호를 요구하는",
            keyFactors = listOf(
                "모르는 사람의 전화",
                "금융기관 사칭",
                "금융정보 요구",
                "피해 가능성을 이용한 불안감 조성",
                "시간적 압박",
                "전화를 끊지 못하도록 유도"
            ),
            lines = listOf(
                ScriptLine("안녕하세요. 충대은행 보안팀입니다.", pauseAfterMs = 900),
                ScriptLine("고객님 계좌에서 오늘 오전 비정상적인 이체 시도가 확인돼서 연락드렸습니다."),
                ScriptLine("현재 추가 피해가 발생할 가능성이 있어서 빠르게 본인 확인을 진행해야 합니다.", pauseAfterMs = 900),
                ScriptLine("현재 사용하고 계신 은행과 계좌번호를 말씀해주세요."),
                ScriptLine("본인 확인이 늦어지면 계좌가 정지되거나 추가 피해가 발생할 수 있습니다."),
                ScriptLine("지금 전화를 끊으시면 처리가 늦어질 수 있습니다."),
                ScriptLine("제가 바로 처리해드릴 테니까 계좌번호부터 말씀해주세요.", isInterventionPoint = true),
                // ── 개입 ──
                ScriptLine("그건 통신사에서 자동으로 뜨는 안내입니다. 무시하셔도 됩니다.", afterIntervention = true),
                ScriptLine("고객님, 지금 처리하지 않으시면 계좌가 정지됩니다. 계좌번호 말씀해주세요.", afterIntervention = true),
                ScriptLine("네, 확인했습니다. 처리 후 다시 연락드리겠습니다.", afterIntervention = true)
            )
        ),

        // ── S4. 애매한 상황 × 모르는 사람 ───────────────────────
        AttackerScript(
            id = "S4",
            label = "4. 애매한 상황 · 모르는 사람",
            scamLevel = ScamLevel.AMBIGUOUS,
            relationship = CallerRelationship.STRANGER,
            situation = "카드사 상담원이 결제 내역 본인 사용 여부를 확인하고, 공식 고객센터 재확인을 안내",
            defaultCallerName = "",
            callerNumber = "1588-0000",
            requestedInfo = "없음 (개인정보·인증번호 요구 없음)",
            riskPhrase = "결제 내역 확인을 안내하는",
            keyFactors = listOf(
                "모르는 사람의 전화",
                "금융기관 관련 내용",
                "결제·금융 위험 키워드 등장",
                "개인정보나 인증번호를 요구하지 않음",
                "송금 요구 없음",
                "공식 고객센터를 통한 재확인 안내",
                "정상 안내인지 사기인지 즉시 판단하기 어려움"
            ),
            lines = listOf(
                ScriptLine("안녕하세요. 충대카드 고객센터입니다.", pauseAfterMs = 900),
                ScriptLine("최근 고객님 카드에서 평소와 다른 결제가 확인돼서 본인 사용 여부 확인차 연락드렸습니다."),
                ScriptLine("오늘 오전에 15만 원 정도의 온라인 결제가 있었는데 직접 결제하신 건가요?", pauseAfterMs = 1200),
                ScriptLine("확인이 어려우시면 지금 개인정보를 말씀해주실 필요는 없습니다."),
                ScriptLine("카드 뒷면에 적힌 공식 고객센터 번호로 직접 전화해서 확인하셔도 됩니다."),
                ScriptLine("전화로 개인정보나 인증번호를 알려주실 필요는 없습니다."),
                ScriptLine("공식 고객센터를 통해 결제 내역을 확인해보시는 것을 권해드립니다.", isInterventionPoint = true),
                // ── 개입 ──
                ScriptLine("네, 그런 안내가 뜰 수 있습니다. 확인하고 다시 연락 주셔도 됩니다.", afterIntervention = true),
                ScriptLine("따로 요청드릴 정보는 없습니다. 고객센터로 확인만 부탁드립니다.", afterIntervention = true),
                ScriptLine("네, 확인 부탁드리겠습니다. 감사합니다.", afterIntervention = true)
            )
        )
    )

    fun findById(id: String): AttackerScript? = scripts.firstOrNull { it.id == id }

    /**
     * 대본 무결성 검사 — 단위 테스트가 호출한다.
     *
     * 개입 지점이 없으면 개입이 영영 자동 발동하지 않고, 둘이면 어느 쪽이 기준점인지
     * 알 수 없어 반응시간이 무의미해진다. 2×2 셀이 비면 설계 자체가 성립하지 않는다.
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

        // 2×2의 네 칸이 정확히 하나씩 채워져야 한다
        ScamLevel.values().forEach { level ->
            CallerRelationship.values().forEach { rel ->
                val n = scripts.count { it.scamLevel == level && it.relationship == rel }
                if (n != 1) {
                    problems += level.label + "×" + rel.label + " 셀에 대본이 " + n + "개입니다 (1개여야 함)"
                }
            }
        }
        return problems
    }
}
