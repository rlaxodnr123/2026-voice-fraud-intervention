package com.example.callguard.presentation.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.callguard.domain.interfaces.InterventionEvent
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.interfaces.RiskScore
import com.example.callguard.domain.service.CallService
import com.example.callguard.presentation.viewmodel.CallViewModel
import com.example.callguard.presentation.viewmodel.LeakSurveyAnswers
import kotlinx.coroutines.launch

// ── 색상 팔레트 ──────────────────────────────────────────────────
val ThemeBackground = Color(0xFF0F0E17)
val ThemeCardBg     = Color(0xFF1E1B29)
val PrimaryCyan     = Color(0xFF00F2FE)
val PrimaryPurple   = Color(0xFF4FACFE)
val AccentRed       = Color(0xFFFF3B30)
val WarningAmber    = Color(0xFFFFCC00)
val SafeGreen       = Color(0xFF34C759)
val TextLight       = Color(0xFFE2E1E6)
val TextDark        = Color(0xFF9F9BA8)

@Composable
fun CallGuardApp(viewModel: CallViewModel) {
    val callState by viewModel.callState.collectAsState()
    val localLeakEvent by viewModel.localLeakEvent.collectAsState()
    val remotePhishingBlockedEvent by viewModel.remotePhishingBlockedEvent.collectAsState()
    val suspiciousWarningEvent by viewModel.suspiciousWarningEvent.collectAsState()
    val surveyAnswers by viewModel.surveyAnswers.collectAsState()
    val isSttReady by viewModel.isSttReady.collectAsState()
    val voiceQuestionIndex by viewModel.voiceSurveyQuestionIndex.collectAsState()
    val voiceListening by viewModel.voiceSurveyListening.collectAsState()

    // 서비스 연결 실패 등 사용자 안내 메시지를 Toast로 표시
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ThemeBackground) {
        Box(modifier = Modifier.fillMaxSize()) {

            when (callState) {
                CallService.CallState.IDLE        -> DialScreen(viewModel, isSttReady)
                CallService.CallState.CONNECTING  -> ConnectingScreen(viewModel)
                CallService.CallState.RINGING     -> RingingScreen(viewModel)
                CallService.CallState.CONNECTED   -> VictimCallScreen(viewModel)
                CallService.CallState.DISCONNECTED -> DisconnectedScreen()
            }

            // STT 모델 로딩 중 배너
            if (!isSttReady) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color(0xCC1A1200))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = WarningAmber,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "STT 음성인식 모델 로딩 중... (첫 실행 시 1~2분 소요)",
                            color = WarningAmber,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // SUSPICIOUS 확인 배너 — 차단하지 않는 비모달 배너라 통화를 막지 않는다.
            // "경고 팝업 확인 여부" 데이터 수집을 위해 사용자가 직접 확인(dismiss)해야 사라진다.
            suspiciousWarningEvent?.let { event ->
                SuspiciousWarningBanner(
                    event = event,
                    viewModel = viewModel,
                    onConfirm = { viewModel.dismissSuspiciousWarning() }
                )
            }

            // ── 팝업 레이어 ──────────────────────────────────────
            // 우선순위: 로컬 누출 설문 > 원격 피싱 확정 설문 > 원격 피싱 경고(SUSPICIOUS)
            localLeakEvent?.let { event ->
                LocalLeakSurveyOverlay(
                    event = event,
                    surveyAnswers = surveyAnswers,
                    voiceQuestionIndex = voiceQuestionIndex,
                    voiceListening = voiceListening,
                    viewModel = viewModel
                )
            } ?: remotePhishingBlockedEvent?.let { event ->
                RemotePhishingSurveyOverlay(
                    event = event,
                    surveyAnswers = surveyAnswers,
                    voiceQuestionIndex = voiceQuestionIndex,
                    voiceListening = voiceListening,
                    viewModel = viewModel
                )
            }
        }
    }
}

// ── 다이얼/입장 화면 ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialScreen(viewModel: CallViewModel, isSttReady: Boolean) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf("ws://192.168.") }
    var roomId    by remember { mutableStateOf("") }
    var simPhrase by remember { mutableStateOf("") }

    val presetRemote = listOf(
        // 검찰 사칭
        "안녕하세요 저는 서울중앙지검 수사관입니다",
        "고객님 명의 대포통장 범죄 수사 중입니다 구속될 수 있습니다",
        "안전계좌로 즉시 송금하지 않으면 체포영장 발부됩니다",
        // 금융감독원 사칭
        "금융감독원입니다 명의도용으로 계좌 압류 예정입니다",
        "보안 앱 설치 후 원격 제어 허용해 주세요",
        // 가족 납치 사칭
        "엄마 나 납치됐어 합의금 빨리 보내줘",
        // 택배 사칭
        "택배 미수령 건 통관 문제로 연락드렸습니다"
    )
    val presetLocal = listOf(
        "제 비밀번호는 1234입니다",
        "인증번호는 678910 입니다",
        "카드 비밀번호는 0000이에요",
        "주민등록번호는 901231 입니다",
        "지금 바로 이체할게요"
    )
    // 공격자 요구 발화 → (지연) → 피해자 숫자 발화 콤보 (SensitiveDisclosureDetector 검증용)
    val presetSensitiveCombo = listOf(
        "주민등록번호 뒤 7자리까지 말씀해 주세요" to "901231 1234567",
        "계좌번호 불러주세요" to "110 452 123456"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 40.dp, bottom = 24.dp)
    ) {
        // 앱 헤더
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "CallGuard",
                    style = TextStyle(
                        brush = Brush.horizontalGradient(listOf(PrimaryCyan, PrimaryPurple)),
                        fontSize = 38.sp, fontWeight = FontWeight.ExtraBold
                    )
                )
                Text(
                    "보이스피싱 실시간 감지 · 즉시 차단",
                    fontSize = 12.sp, color = TextDark, textAlign = TextAlign.Center
                )
            }
        }

        // ── P2P 통화 입장 카드 ───────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("인터넷 통화 (P2P)", color = PrimaryCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("서버 주소", color = TextDark) },
                        placeholder = { Text("예: ws://192.168.0.10", color = TextDark) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = ThemeCardBg,
                            containerColor = ThemeBackground
                        ),
                        textStyle = LocalTextStyle.current.copy(color = TextLight),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                "PC의 로컬 IP 주소 입력 (ipconfig로 확인)",
                                color = TextDark,
                                fontSize = 10.sp
                            )
                        }
                    )
                    OutlinedTextField(
                        value = roomId,
                        onValueChange = { roomId = it },
                        label = { Text("방 코드 (상대방과 동일하게 입력)", color = TextDark) },
                        placeholder = { Text("예: room-1234", color = TextDark) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = ThemeCardBg,
                            containerColor = ThemeBackground
                        ),
                        textStyle = LocalTextStyle.current.copy(color = TextLight),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            if (serverUrl.isNotBlank() && roomId.isNotBlank()) {
                                viewModel.joinRoom(context, serverUrl, roomId)
                            }
                        },
                        enabled = isSttReady,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(Brush.horizontalGradient(listOf(PrimaryCyan, PrimaryPurple))),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Call, null, tint = Color.Black)
                                Spacer(Modifier.width(8.dp))
                                Text("방 입장 / 통화 시작", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (!isSttReady) {
                        Text(
                            "음성인식 모델 로딩 완료 후 사용 가능합니다",
                            color = WarningAmber,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // ── 루프백 테스트 ────────────────────────────────────────
        item {
            OutlinedButton(
                onClick = {
                    // serverUrl로 모니터링 서버 URL 구성 (포트 8081)
                    val monitorUrl = if (serverUrl.isNotBlank()) {
                        val base = if (serverUrl.startsWith("ws://")) serverUrl else "ws://$serverUrl"
                        if (base.contains(":8080")) base.replace(":8080", ":8081")
                        else if (!base.contains(Regex(":\\d+$"))) "$base:8081"
                        else base
                    } else null
                    viewModel.startLoopbackCall(context, monitorUrl)
                },
                enabled = isSttReady,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TextDark),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("루프백 테스트 (단일 기기)", fontSize = 13.sp)
            }
        }

        // ── AI 파이프라인 시뮬레이션 ─────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Text("시뮬레이션 도구", color = PrimaryPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // 원격 발화 (피싱 상대방)
                    Text("상대방 발화 (피싱 감지 테스트)", color = TextDark, fontSize = 11.sp)
                    presetRemote.forEach { phrase ->
                        OutlinedButton(
                            onClick = {
                                viewModel.startLoopbackCall(context)
                                viewModel.simulateRemoteSpeech(phrase)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningAmber),
                            border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f))
                        ) {
                            Text(phrase, fontSize = 11.sp, maxLines = 2)
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Divider(color = ThemeBackground)
                    Spacer(Modifier.height(4.dp))

                    // 공격자 요구 + 피해자 숫자 발화 콤보 (SensitiveDisclosureDetector 테스트)
                    Text("요구 직후 숫자 발화 콤보 (즉시 차단 테스트)", color = TextDark, fontSize = 11.sp)
                    presetSensitiveCombo.forEach { (requestPhrase, digitPhrase) ->
                        OutlinedButton(
                            onClick = {
                                viewModel.startLoopbackCall(context)
                                viewModel.simulateSensitiveDisclosureCombo(requestPhrase, digitPhrase)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryPurple),
                            border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.4f))
                        ) {
                            Text("공격자: \"$requestPhrase\" → 나: \"$digitPhrase\"", fontSize = 11.sp, maxLines = 2)
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Divider(color = ThemeBackground)
                    Spacer(Modifier.height(4.dp))

                    // 로컬 발화 (내가 개인정보 말하려는 순간)
                    Text("내 발화 (즉시 차단 테스트)", color = TextDark, fontSize = 11.sp)
                    presetLocal.forEach { phrase ->
                        OutlinedButton(
                            onClick = {
                                viewModel.startLoopbackCall(context)
                                viewModel.simulateLocalSpeech(phrase)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.4f))
                        ) {
                            Text(phrase, fontSize = 11.sp, maxLines = 2)
                        }
                    }

                    // 커스텀 입력
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = simPhrase,
                            onValueChange = { simPhrase = it },
                            placeholder = { Text("직접 입력", color = TextDark, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = PrimaryPurple,
                                unfocusedBorderColor = ThemeBackground,
                                containerColor = ThemeBackground
                            ),
                            textStyle = LocalTextStyle.current.copy(color = TextLight, fontSize = 12.sp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (simPhrase.isNotBlank()) {
                                    viewModel.startLoopbackCall(context)
                                    viewModel.simulateLocalSpeech(simPhrase)
                                    simPhrase = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                        ) {
                            Text("내 발화\n주입", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

// ── 연결 중 화면 ─────────────────────────────────────────────────

@Composable
fun ConnectingScreen(viewModel: CallViewModel) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        0f, 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "angle"
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = PrimaryCyan, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text("시그널링 서버에 연결 중...", color = TextLight, fontSize = 16.sp)
        Text("상대방이 방에 입장하면 자동 통화됩니다.", color = TextDark, fontSize = 12.sp)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(
            onClick = { viewModel.endCall() },
            border = BorderStroke(1.dp, AccentRed),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
        ) {
            Text("취소")
        }
    }
}

// ── 링잉 화면 ────────────────────────────────────────────────────

@Composable
fun RingingScreen(viewModel: CallViewModel) {
    val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.85f, 1.15f,
        infiniteRepeatable(tween(1200, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceAround
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("통화 연결 중", color = TextDark, fontSize = 16.sp)
            Text("상대방과 협상 중...", color = TextLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            Box(modifier = Modifier.size(160.dp).scale(pulseScale).clip(CircleShape)
                .background(PrimaryPurple.copy(alpha = 0.2f)))
            Box(modifier = Modifier.size(120.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(PrimaryCyan, PrimaryPurple)))) {
                Icon(Icons.Filled.Call, null, tint = Color.Black,
                    modifier = Modifier.size(48.dp).align(Alignment.Center))
            }
        }
        FloatingActionButton(
            onClick = { viewModel.endCall() },
            containerColor = AccentRed, shape = CircleShape,
            modifier = Modifier.size(72.dp)
        ) {
            Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

// ── SUSPICIOUS 확인 배너: 차단 없는 비모달 경고 ───────────────────

@Composable
fun BoxScope.SuspiciousWarningBanner(
    event: InterventionEvent.RemotePhishingDetected,
    viewModel: CallViewModel,
    onConfirm: () -> Unit
) {
    LaunchedEffect(event) {
        viewModel.logUserAction("팝업 노출: 보이스피싱 의심 경고(SUSPICIOUS)")
    }
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2200)),
            modifier = Modifier.fillMaxWidth().border(1.5.dp, WarningAmber, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("보이스피싱 의심 표현 감지", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Text(
                    "통화를 계속하시더라도 계좌번호, 비밀번호 등 개인정보는 알려주지 마세요.",
                    color = TextLight, fontSize = 13.sp, lineHeight = 18.sp
                )
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("확인", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

// ── 경고 팝업 1: 로컬 누출 차단 + 상황 확인 설문 ───────────────────

@Composable
fun LocalLeakSurveyOverlay(
    event: InterventionEvent.LocalLeakBlocked,
    surveyAnswers: LeakSurveyAnswers,
    voiceQuestionIndex: Int,
    voiceListening: Boolean,
    viewModel: CallViewModel
) {
    Dialog(onDismissRequest = {}) {
        LaunchedEffect(Unit) {
            viewModel.logUserAction("팝업 노출: 개인정보 누출 경고")
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0005)),
            modifier = Modifier.fillMaxWidth().border(2.dp, AccentRed, RoundedCornerShape(24.dp))
        ) {
            LazyColumn(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── 헤더 ──
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "마이크와 상대방 음성이 차단되었습니다",
                            color = AccentRed, fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentRed.copy(0.15f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "감지: \"${event.triggerPhrase}\"",
                                color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "개인정보 유출 위험이 감지되었습니다.\n아래 질문에 답해 주세요.",
                            color = TextLight, fontSize = 14.sp,
                            textAlign = TextAlign.Center, lineHeight = 20.sp
                        )
                    }
                }

                // ── 설문 문항 ──
                item {
                    Divider(color = AccentRed.copy(0.3f))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "상황 확인 설문",
                            color = WarningAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        if (voiceListening) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("듣고 있어요... \"예\" 또는 \"아니오\"라고 말해주세요", color = PrimaryCyan, fontSize = 11.sp)
                        } else {
                            Text("터치 또는 음성으로 답변할 수 있어요", color = TextDark, fontSize = 11.sp)
                        }
                    }
                }

                item {
                    SurveyQuestion(
                        number = "1",
                        question = "전화를 건 사람이\n직접 아는 분인가요?",
                        answer = surveyAnswers.q1KnownPerson,
                        isVoiceActive = voiceQuestionIndex == 0,
                        onYes = { viewModel.answerSurvey(q1 = true) },
                        onNo  = { viewModel.answerSurvey(q1 = false) }
                    )
                }
                item {
                    SurveyQuestion(
                        number = "2",
                        question = "가족·지인과의\n실제 상황인가요?",
                        answer = surveyAnswers.q2VerifiedReal,
                        isVoiceActive = voiceQuestionIndex == 1,
                        onYes = { viewModel.answerSurvey(q2 = true) },
                        onNo  = { viewModel.answerSurvey(q2 = false) }
                    )
                }

                // ── 결과 & 액션 버튼 ──
                item {
                    if (surveyAnswers.allAnswered) {
                        SurveyResultSection(surveyAnswers, viewModel)
                    } else {
                        Button(
                            onClick = { viewModel.endCall() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("전화 끊기 (권장)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── 경고 팝업 2: 원격 피싱 확정 차단 (양쪽 음성 차단) + 상황 확인 설문 ──

@Composable
fun RemotePhishingSurveyOverlay(
    event: InterventionEvent.RemotePhishingBlocked,
    surveyAnswers: LeakSurveyAnswers,
    voiceQuestionIndex: Int,
    voiceListening: Boolean,
    viewModel: CallViewModel
) {
    Dialog(onDismissRequest = {}) {
        LaunchedEffect(Unit) {
            viewModel.logUserAction("팝업 노출: 원격 피싱 차단 경고")
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0005)),
            modifier = Modifier.fillMaxWidth().border(2.dp, AccentRed, RoundedCornerShape(24.dp))
        ) {
            LazyColumn(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── 헤더 ──
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (event.remoteAlsoMuted) "마이크와 상대방 음성이 차단되었습니다"
                            else "마이크가 차단되었습니다",
                            color = AccentRed, fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentRed.copy(0.15f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "감지: ${event.riskScore.matchedKeywords.joinToString(", ")}",
                                color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (event.remoteAlsoMuted)
                                "상대방의 발화에서 보이스피싱 위험이 감지되었습니다.\n아래 질문에 답해 주세요."
                            else
                                "상대방의 발화에서 보이스피싱이 의심되는 표현이 감지되었습니다.\n확인을 위해 마이크를 차단했습니다. 아래 질문에 답해 주세요.",
                            color = TextLight, fontSize = 14.sp,
                            textAlign = TextAlign.Center, lineHeight = 20.sp
                        )
                    }
                }

                // ── 설문 문항 ──
                item {
                    Divider(color = AccentRed.copy(0.3f))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "상황 확인 설문",
                            color = WarningAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        if (voiceListening) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("듣고 있어요... \"예\" 또는 \"아니오\"라고 말해주세요", color = PrimaryCyan, fontSize = 11.sp)
                        } else {
                            Text("터치 또는 음성으로 답변할 수 있어요", color = TextDark, fontSize = 11.sp)
                        }
                    }
                }

                item {
                    SurveyQuestion(
                        number = "1",
                        question = "전화를 건 사람이\n직접 아는 분인가요?",
                        answer = surveyAnswers.q1KnownPerson,
                        isVoiceActive = voiceQuestionIndex == 0,
                        onYes = { viewModel.answerSurvey(q1 = true) },
                        onNo  = { viewModel.answerSurvey(q1 = false) }
                    )
                }
                item {
                    SurveyQuestion(
                        number = "2",
                        question = "가족·지인과의\n실제 상황인가요?",
                        answer = surveyAnswers.q2VerifiedReal,
                        isVoiceActive = voiceQuestionIndex == 1,
                        onYes = { viewModel.answerSurvey(q2 = true) },
                        onNo  = { viewModel.answerSurvey(q2 = false) }
                    )
                }

                // ── 결과 & 액션 버튼 ──
                item {
                    if (surveyAnswers.allAnswered) {
                        SurveyResultSection(surveyAnswers, viewModel)
                    } else {
                        Button(
                            onClick = { viewModel.endCall() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("전화 끊기 (권장)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurveyQuestion(
    number: String,
    question: String,
    answer: Boolean?,
    isVoiceActive: Boolean = false,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isVoiceActive)
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(PrimaryCyan.copy(0.1f))
                        .border(1.dp, PrimaryCyan.copy(0.6f), RoundedCornerShape(10.dp))
                        .padding(6.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 번호 뱃지
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when (answer) {
                        true  -> AccentRed.copy(0.8f)
                        false -> SafeGreen.copy(0.8f)
                        null  -> TextDark.copy(0.3f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            question,
            color = TextLight, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        // 예/아니오 버튼
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SurveyChoiceButton(
                label = "예",
                selected = answer == true,
                selectedColor = AccentRed,
                onClick = onYes
            )
            SurveyChoiceButton(
                label = "아니오",
                selected = answer == false,
                selectedColor = SafeGreen,
                onClick = onNo
            )
        }
    }
}

@Composable
private fun SurveyChoiceButton(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit
) {
    val bg = if (selected) selectedColor else ThemeCardBg
    val border = if (selected) selectedColor else TextDark.copy(0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SurveyResultSection(answers: LeakSurveyAnswers, viewModel: CallViewModel) {
    val isHighRisk = answers.shouldKeepBlocked

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 결과 배너
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isHighRisk) AccentRed.copy(0.15f) else SafeGreen.copy(0.12f))
                .border(1.dp, if (isHighRisk) AccentRed else SafeGreen, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isHighRisk) "보이스피싱 위험 높음" else "실제 상황으로 확인됨",
                    color = if (isHighRisk) AccentRed else SafeGreen,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isHighRisk)
                        "보이스피싱으로 판단되어 통화를 종료합니다.\n절대 개인정보를 알려주지 마세요."
                    else
                        "마이크 차단을 해제하고 통화를 재개합니다.\n하지만 여전히 주의가 필요합니다.",
                    color = TextLight, fontSize = 12.sp,
                    textAlign = TextAlign.Center, lineHeight = 18.sp
                )
            }
        }

        // 전화 끊기 (항상 노출)
        Button(
            onClick = { viewModel.endCall() },
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(
                if (isHighRisk) "전화 끊기 (강력 권장)" else "전화 끊기",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }

        // 보이스피싱 위험 시: 통화 종료 확정 | 실제 상황 시: 마이크 해제하고 재개
        if (isHighRisk) {
            OutlinedButton(
                onClick = { viewModel.submitSurveyAndDecide() },
                border = BorderStroke(1.dp, AccentRed),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("판정 확정 (통화 종료)", fontSize = 12.sp)
            }
        } else {
            OutlinedButton(
                onClick = { viewModel.submitSurveyAndDecide() },
                border = BorderStroke(1.dp, SafeGreen),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SafeGreen),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("마이크 해제하고 통화 계속", fontSize = 12.sp)
            }
        }
    }
}


// ── 피해자 전화 화면 (일반 전화앱 모양, 내부 분석 정보 미노출) ────

@Composable
fun VictimCallScreen(viewModel: CallViewModel) {
    val riskScore     by viewModel.riskScore.collectAsState()
    val isLocalMuted  by viewModel.isLocalMuted.collectAsState()
    val callDuration  by viewModel.callDuration.collectAsState()

    // 위험도에 따라 아바타 테두리 색상만 변경 (수치/텍스트 미노출)
    val ringColor = when (riskScore.level) {
        RiskLevel.SAFE       -> SafeGreen
        RiskLevel.SUSPICIOUS -> WarningAmber
        RiskLevel.SCAM       -> AccentRed
    }
    val ringAlpha by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = if (riskScore.level == RiskLevel.SCAM) 0.5f else 1f,
        targetValue = 1f,
        animationSpec = if (riskScore.level == RiskLevel.SCAM)
            infiniteRepeatable(tween(600, easing = FastOutLinearInEasing), RepeatMode.Reverse)
        else
            infiniteRepeatable(tween(1000), RepeatMode.Restart),
        label = "alpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            // 발신자 정보
            // 공격자 쪽 텍스트 발화 전송은 이제 server/client.html의 "공격자" 버튼에서 처리하므로
            // 참여자 폰에는 디버그 뷰로 전환하는 숨겨진 제스처를 두지 않는다.
            Text(
                "통화 중",
                color = Color(0xFF8E8E93),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("알 수 없는 번호", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(callDuration, color = Color(0xFF8E8E93), fontSize = 16.sp)

            Spacer(Modifier.weight(1f))

            // 아바타 + 위험도 링
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                // 외부 위험도 링
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .border(3.dp, ringColor.copy(alpha = ringAlpha), CircleShape)
                )
                // 아바타 원
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3C)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(72.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 통화 컨트롤 (iOS/기본 전화앱 스타일)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 음소거 버튼
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(if (isLocalMuted) Color.White else Color(0xFF3A3A3C))
                            .clickable { viewModel.toggleLocalMute() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isLocalMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null,
                            tint = if (isLocalMuted) Color.Black else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("음소거", color = Color.White, fontSize = 12.sp)
                }

                // 통화 종료 버튼
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AccentRed)
                            .clickable { viewModel.endCall() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CallEnd,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("종료", color = Color.White, fontSize = 12.sp)
                }

                // 기만 설계 유지: 참여자에게 보이는 디버그 버튼 제거.
                // 레이아웃 균형을 위해 스피커 버튼 자리에 스피커(더미) 버튼을 둔다.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3A3A3C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("스피커", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── 통화 종료 화면 ───────────────────────────────────────────────

@Composable
fun DisconnectedScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("통화가 종료되었습니다.", color = TextLight, fontSize = 16.sp)
    }
}
