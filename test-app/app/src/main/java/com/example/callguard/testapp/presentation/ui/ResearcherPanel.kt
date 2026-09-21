package com.example.callguard.testapp.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.callguard.testapp.domain.script.PlaybackMode
import com.example.callguard.testapp.domain.session.ExperimentSessionService
import com.example.callguard.testapp.presentation.viewmodel.TestAppViewModel

/**
 * 연구자 패널 — 참가자 화면 위에 덮이는 오버레이. 우상단 5회 탭으로 연다.
 *
 * 노트북이나 별도 콘솔 없이 폰 한 대로 실험을 돌릴 수 있게 하는 것이 목적이다.
 * 자동 탐지(STT)가 현장에서 실패하더라도 여기서 수동으로 발동·기록할 수 있어야
 * 세션을 통째로 버리지 않는다.
 */
@Composable
fun ResearcherPanel(viewModel: TestAppViewModel, service: ExperimentSessionService) {
    val callState by service.callState.collectAsState()
    val lineIndex by service.currentLineIndex.collectAsState()
    val lineText by service.currentLineText.collectAsState()
    val fired by service.interventionFired.collectAsState()
    val transcript by service.lastTranscript.collectAsState()
    val leakFlag by service.leakFlag.collectAsState()
    val micBlocked by service.micBlocked.collectAsState()
    val remoteBlocked by service.remoteAudioBlocked.collectAsState()
    val saving by viewModel.savingSession.collectAsState()
    val sttReady by service.sttReady.collectAsState()

    val script = service.activeScript
    val intervention = service.activeIntervention
    val mode = service.activeMode

    val selectedIntervention by viewModel.interventionId.collectAsState()
    val selectedScript by viewModel.scriptId.collectAsState()
    val inCall = callState == ExperimentSessionService.CallState.IN_CALL

    var injectText by remember { mutableStateOf("") }
    var leakDetail by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xE6000000))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("연구자 패널", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { viewModel.closeResearcherPanel() }) {
                    Text("닫기", color = InfoBlue, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ① 현재 상태 ─────────────────────────────────────
            PanelCard("① 현재 상태") {
                StatusLine("세션", if (callState == ExperimentSessionService.CallState.IDLE) "대기 중" else callState.name)
                StatusLine("조건", intervention?.label ?: "-")
                StatusLine("시나리오", script?.label ?: "-")
                StatusLine("셀", script?.let { it.scamLevel.label + " × " + it.relationship.label } ?: "-")
                StatusLine("요구", script?.requestedInfo ?: "-")
                StatusLine("재생 모드", mode.label)
                StatusLine("개입", if (fired) "발동됨" else "대기", if (fired) AccentRed else CallSubText)
                StatusLine("스피커 차단", if (remoteBlocked) "상대 음성 차단 중" else "정상", if (remoteBlocked) AccentRed else CallSubText)
                StatusLine("마이크 차단", if (micBlocked) "차단 중" else "정상", if (micBlocked) AccentRed else CallSubText)
                StatusLine("음성 인식", if (sttReady) "동작 중" else "미사용 (수동 기록으로 진행)", if (sttReady) SafeGreen else WarnAmber)
                if (leakFlag != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("⚠ 유출 감지: " + leakFlag, color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (transcript.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("최근 발화: " + transcript, color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ② 조건 선택 ────────────────────────────────────
            PanelCard("② 조건 선택") {
                Text("개입 방법", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                viewModel.interventionOptions.forEach { option ->
                    PanelChoice(
                        title = option.label,
                        subtitle = option.researcherNote,
                        selected = selectedIntervention == option.id,
                        // 발동한 뒤에 조건을 갈아끼우면 그 세션이 어떤 조건이었는지 말할 수 없게 된다
                        enabled = !fired,
                        onClick = { viewModel.selectIntervention(option.id) }
                    )
                }
                if (fired) {
                    Text("이미 개입이 발동해 조건을 바꿀 수 없습니다.", color = WarnAmber, fontSize = 11.sp)
                } else if (inCall) {
                    Text(
                        "통화 중에 바꾸면 곧바로 재무장됩니다 (로그에 intervention_rearmed 기록).",
                        color = CallSubText, fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("시나리오", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                viewModel.scriptOptions.forEach { option ->
                    PanelChoice(
                        title = option.label,
                        subtitle = option.scamLevel.label + " · " + option.relationship.label +
                            " · 요구: " + option.requestedInfo,
                        selected = selectedScript == option.id,
                        enabled = !inCall,
                        onClick = { viewModel.selectScript(option.id) }
                    )
                }
                if (inCall) {
                    Text(
                        "통화 중에는 시나리오를 바꿀 수 없습니다 — 대본이 이미 재생 중이라 " +
                            "바꾸면 참가자가 들은 내용과 기록이 어긋납니다.",
                        color = CallSubText, fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ③ 대본 진행 ────────────────────────────────────
            PanelCard("③ 대본 진행") {
                script?.let { sc ->
                    Text(sc.situation, color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "핵심 요소: " + sc.keyFactors.joinToString(" · "),
                        color = CallSubText, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                }
                val lines = service.currentScriptLines()
                if (lines.isEmpty()) {
                    Text("진행 중인 대본이 없습니다.", color = CallSubText, fontSize = 13.sp)
                } else {
                    lines.forEachIndexed { index, line ->
                        val isCurrent = index == lineIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) InfoBlue.copy(alpha = 0.25f) else Color.Transparent)
                                .padding(8.dp)
                        ) {
                            Text(
                                (index + 1).toString() + ".",
                                color = if (line.isInterventionPoint) AccentRed else CallSubText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    line.text,
                                    color = if (isCurrent) Color.White else Color(0xFFBBBBBB),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                                if (line.isInterventionPoint) {
                                    Text("▶ 이 줄이 끝나면 개입 자동 발동", color = AccentRed, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    if (mode == PlaybackMode.LIVE) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { service.advanceLiveLine() },
                            colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("방금 읽은 대사 완료 → 다음 대사", color = Color.White, fontSize = 15.sp)
                        }
                        Text(
                            "현재 읽을 대사: " + (if (lineText.isBlank()) "(대본 끝)" else lineText),
                            color = WarnAmber, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ④ 개입 ─────────────────────────────────────────
            PanelCard("④ 개입 실행") {
                Button(
                    onClick = { service.triggerInterventionNow() },
                    enabled = callState == ExperimentSessionService.CallState.IN_CALL && !fired,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(
                        if (fired) "이미 발동됨 (세션당 1회)" else "지금 개입 실행",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "자동 발동(요구 대사 종료 시점)과 완전히 같은 개입을 실행합니다. " +
                        "세션당 1회만 발동하며, 중복 요청은 로그에만 남습니다.",
                    color = CallSubText, fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── ⑤ 후속 대사 ────────────────────────────────────
            val followUps = script?.followUpLines.orEmpty()
            if (followUps.isNotEmpty()) {
                PanelCard("⑤ 개입 후 후속 대사") {
                    Text(
                        "참가자가 팝업에서 [통화 계속하기]를 고른 뒤 대화가 이어질 때 사용합니다. " +
                            "강제 종료 조건에서는 통화가 끊기므로 쓰이지 않습니다." +
                            if (mode == PlaybackMode.LIVE) " (라이브 모드에서는 직접 읽으세요)" else "",
                        color = CallSubText, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    followUps.forEachIndexed { index, line ->
                        OutlinedButton(
                            onClick = { service.playFollowUp(index) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Text(line.text, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── ⑥ 관찰 기록 ────────────────────────────────────
            PanelCard("⑥ 관찰 기록 (원클릭)") {
                Text(
                    "누르는 즉시 시각과 함께 저장됩니다. 개입 이후 경과시간도 같이 기록됩니다.",
                    color = CallSubText, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                val notes = listOf(
                    "경고를 알아차림", "놀람·당황 반응", "말을 멈춤", "계속 대답함",
                    "상대에게 되물음", "직접 통화를 끊음", "개입을 무시함", "휴대폰을 확인함",
                    // 애매한 시나리오(2·4)에서 과잉 개입의 비용을 잡는 항목
                    "개입이 과하다고 말함", "앱에 불만 표시", "정상 통화라고 판단함"
                )
                FlowButtons(notes) { service.logObservation(it) }
            }

            Spacer(Modifier.height(12.dp))

            // ── ⑦ 유출 판정 ────────────────────────────────────
            PanelCard("⑦ 유출 판정 (주 지표)") {
                Text(
                    "음성 인식이 놓치거나 잘못 잡았을 때 연구자 판단으로 확정합니다. " +
                        "연구자 기록은 로그에서 source=researcher 로 구분됩니다.",
                    color = CallSubText, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = leakDetail,
                    onValueChange = { leakDetail = it },
                    label = { Text("무엇을 말했는지 (예: 주민번호 앞 6자리)") },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            service.markLeak(true, leakDetail.ifBlank { "연구자 관찰" })
                            leakDetail = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("유출함", color = Color.White) }
                    Button(
                        onClick = {
                            service.markLeak(false, leakDetail.ifBlank { "연구자 관찰" })
                            leakDetail = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("유출 없음", color = Color.Black) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ⑧ 발화 수동 주입 ───────────────────────────────
            PanelCard("⑧ 참가자 발화 수동 입력") {
                Text(
                    "음성 인식이 동작하지 않을 때 참가자가 말한 내용을 대신 입력합니다. " +
                        "입력한 내용도 유출·거부 탐지를 그대로 통과합니다.",
                    color = CallSubText, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = injectText,
                    onValueChange = { injectText = it },
                    label = { Text("참가자 발화") },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { injectText = "901231" },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("숫자 예시", color = Color.White, fontSize = 12.sp) }
                    Button(
                        onClick = {
                            if (injectText.isNotBlank()) {
                                service.injectParticipantSpeech(injectText.trim())
                                injectText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("기록", color = Color.White) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── ⑨ 세션 종료 ────────────────────────────────────
            PanelCard("⑨ 세션 종료") {
                Button(
                    onClick = { viewModel.finishSession("researcher_finished") },
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = CallControl),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(
                        if (saving) "저장 중…" else "세션 저장하고 종료",
                        color = Color.White, fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.closeResearcherPanel()
                        viewModel.openLogScreen()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("저장된 기록 보기 · 내보내기", color = Color.White, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PanelCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1F1F22))
            .padding(14.dp)
    ) {
        Text(title, color = InfoBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** 패널 안에서 쓰는 선택지 한 줄 */
@Composable
private fun PanelChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) InfoBlue.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, color = Color.White.copy(alpha = alpha), fontSize = 13.sp)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = CallSubText.copy(alpha = alpha), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, valueColor: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = CallSubText, fontSize = 12.sp, modifier = Modifier.width(90.dp))
        Text(value, color = valueColor, fontSize = 12.sp)
    }
}

/** 관찰 기록 버튼을 두 개씩 줄바꿈해 배치한다 (Compose BOM의 FlowRow 미사용 — 실험적 API 회피) */
@Composable
private fun FlowButtons(items: List<String>, onClick: (String) -> Unit) {
    items.chunked(2).forEach { pair ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            pair.forEach { label ->
                OutlinedButton(
                    onClick = { onClick(label) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(label, color = Color.White, fontSize = 12.sp)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
