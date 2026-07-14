package com.example.callguard.experiment.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.callguard.experiment.presentation.viewmodel.ExperimentViewModel

/**
 * 온디바이스 연구자 패널 (§5.1, §5.2) — 참가자 화면 우상단 5회 탭으로 진입하는 숨김 화면.
 *
 * 원격 콘솔(admin-dashboard.html)과 동일한 공통 명령 집합을 1:1로 제공한다:
 * 시나리오 무장 / 지금 개입 실행 / 세션 리셋 / 발화 강제 주입 / 설문 강제 응답 /
 * 통화 종료 / 관찰 메모.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearcherPanelScreen(viewModel: ExperimentViewModel) {
    val armedScenario by viewModel.armedScenario.collectAsState()
    val scenarioFired by viewModel.scenarioFired.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()
    val surveyAnswers by viewModel.surveyAnswers.collectAsState()
    val callState by viewModel.callState.collectAsState()

    var customRemote by remember { mutableStateOf("") }
    var customLocal by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }

    // 발화 강제 주입 프리셋 — 원본 presetLocal/presetSensitiveCombo 확장 (§5.2)
    val presetLocal = listOf(
        "제 비밀번호는 1234입니다",
        "인증번호는 678910 입니다",
        "카드 비밀번호는 0000이에요",
        "주민등록번호는 901231 입니다",
        "계좌번호는 110 452 123456 입니다",
        "지금 바로 이체할게요"
    )
    val presetCombo = listOf(
        "주민등록번호 뒤 7자리까지 말씀해 주세요" to "901231 1234567",
        "계좌번호 불러주세요" to "110 452 123456",
        "카드 비밀번호 알려주세요" to "0 0 0 0"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = ThemeBackground.copy(alpha = 0.98f)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)
        ) {
            // ── 헤더 ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔬 연구자 패널", color = PrimaryCyan, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Button(
                        onClick = { viewModel.closeResearcherPanel() },
                        colors = ButtonDefaults.buttonColors(containerColor = ThemeCardBg),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("닫기", color = TextLight, fontSize = 13.sp)
                    }
                }
            }

            // ── 상태 요약 ──
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("통화 상태: ${callState.name}", color = TextLight, fontSize = 13.sp)
                        Text(
                            "무장 시나리오: ${armedScenario?.label ?: "없음"}",
                            color = if (armedScenario != null) SafeGreen else TextDark,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (scenarioFired) "⚡ 개입 발동됨 (세션 리셋 전까지 재발동 안 됨)" else "대기 중 (미발동)",
                            color = if (scenarioFired) WarningAmber else TextDark,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ── 시나리오 무장 ──
            item {
                SectionCard("① 시나리오 선택 (무장)") {
                    viewModel.scenarioCatalog.forEach { scenario ->
                        val isArmed = armedScenario?.id == scenario.id
                        OutlinedButton(
                            onClick = { viewModel.armScenario(scenario.id) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isArmed) SafeGreen else TextLight
                            ),
                            border = BorderStroke(
                                if (isArmed) 2.dp else 1.dp,
                                if (isArmed) SafeGreen else TextDark.copy(alpha = 0.4f)
                            )
                        ) {
                            Text((if (isArmed) "✓ " else "") + scenario.label, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }

            // ── 개입 실행 / 세션 리셋 ──
            item {
                SectionCard("② 개입 실행") {
                    Button(
                        onClick = { viewModel.triggerNow() },
                        enabled = armedScenario != null && !scenarioFired,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("⚡ 지금 개입 실행 (자동 탐지 무시)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    // 다음 시나리오로 넘어가기 전 누적 위험도·뮤트·설문·배너를 한 번에 초기화
                    // (통화는 유지). 10개 시나리오 연속 진행 시 매 시나리오 사이에 누른다.
                    Button(
                        onClick = { viewModel.resetSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(
                            "🔄 초기화 (다음 시나리오 준비 · 위험도 리셋)",
                            color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                    }
                }
            }

            // ── 발화 강제 주입 ──
            item {
                SectionCard("③ 발화 강제 주입 (STT 우회)") {
                    Text("피해자(참가자) 발화 프리셋", color = TextDark, fontSize = 11.sp)
                    presetLocal.forEach { phrase ->
                        OutlinedButton(
                            onClick = { viewModel.injectLocalSpeech(phrase) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.4f))
                        ) {
                            Text(phrase, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("요구 직후 숫자 발화 콤보", color = TextDark, fontSize = 11.sp)
                    presetCombo.forEach { (request, digits) ->
                        OutlinedButton(
                            onClick = { viewModel.injectSensitiveCombo(request, digits) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryPurple),
                            border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.4f))
                        ) {
                            Text("공격자: \"$request\" → 나: \"$digits\"", fontSize = 11.sp, maxLines = 2)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    InjectRow(
                        value = customLocal,
                        onValueChange = { customLocal = it },
                        placeholder = "피해자 발화 직접 입력",
                        buttonLabel = "주입",
                        buttonColor = AccentRed
                    ) {
                        if (customLocal.isNotBlank()) {
                            viewModel.injectLocalSpeech(customLocal)
                            customLocal = ""
                        }
                    }
                    InjectRow(
                        value = customRemote,
                        onValueChange = { customRemote = it },
                        placeholder = "공격자 발화 직접 입력",
                        buttonLabel = "주입",
                        buttonColor = WarningAmber
                    ) {
                        if (customRemote.isNotBlank()) {
                            viewModel.injectRemoteSpeech(customRemote)
                            customRemote = ""
                        }
                    }
                }
            }

            // ── 설문 강제 응답 ──
            item {
                SectionCard("④ 설문 수동 응답 (STT 인식 실패 시 대신 기록)") {
                    listOf(0 to "Q1. 아는 사람인가요?", 1 to "Q2. 실제 상황인가요?").forEach { (idx, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = TextLight, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { viewModel.forceSurveyAnswer(idx, true) },
                                colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                            ) { Text("예", fontSize = 12.sp, color = Color.White) }
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = { viewModel.forceSurveyAnswer(idx, false) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                            ) { Text("아니오", fontSize = 12.sp, color = Color.White) }
                        }
                    }
                    if (surveyAnswers.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "기록된 응답: " + surveyAnswers.joinToString(" / ") { (type, idx, ans) ->
                                "[$type] Q${idx + 1}=${if (ans) "예" else "아니오"}"
                            },
                            color = TextDark, fontSize = 11.sp
                        )
                    }
                }
            }

            // ── 관찰 메모 / 세션 종료 ──
            item {
                SectionCard("⑤ 관찰 메모 · 세션 종료") {
                    InjectRow(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = "관찰 메모 (타임스탬프와 함께 로그 저장)",
                        buttonLabel = "기록",
                        buttonColor = PrimaryCyan
                    ) {
                        viewModel.addObservationNote(noteText)
                        noteText = ""
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { viewModel.endCall() },
                        colors = ButtonDefaults.buttonColors(containerColor = ThemeCardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AccentRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📵 통화/세션 강제 종료", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ── 실시간 녹취록 (연구자 전용 뷰) ──
            item {
                SectionCard("⑥ 실시간 STT (참가자 화면엔 표시 안 됨)") {
                    if (transcripts.isEmpty()) {
                        Text("아직 발화 없음", color = TextDark, fontSize = 12.sp)
                    } else {
                        transcripts.takeLast(12).forEach { item ->
                            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                                Text(
                                    if (item.speaker == "LOCAL") "나" else "상대",
                                    color = if (item.speaker == "LOCAL") PrimaryPurple else PrimaryCyan,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(32.dp)
                                )
                                Text(
                                    item.text + if (!item.isFinal) "…" else "",
                                    color = if (item.isFinal) TextLight else TextDark,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = PrimaryPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InjectRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    buttonLabel: String,
    buttonColor: Color,
    onSubmit: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = TextDark, fontSize = 11.sp) },
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = buttonColor,
                unfocusedBorderColor = ThemeBackground,
                containerColor = ThemeBackground
            ),
            textStyle = LocalTextStyle.current.copy(color = TextLight, fontSize = 12.sp)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSubmit,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(buttonLabel, fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
