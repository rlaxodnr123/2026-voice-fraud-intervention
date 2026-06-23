package com.example.callguard.presentation.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.callguard.domain.interfaces.InterventionEvent
import com.example.callguard.domain.interfaces.RiskLevel
import com.example.callguard.domain.interfaces.RiskScore
import com.example.callguard.domain.service.CallService
import com.example.callguard.presentation.viewmodel.CallViewModel
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
    val showRemoteWarning by viewModel.showRemotePhishingWarning.collectAsState()
    val localLeakEvent by viewModel.localLeakEvent.collectAsState()
    val isSttReady by viewModel.isSttReady.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = ThemeBackground) {
        Box(modifier = Modifier.fillMaxSize()) {

            when (callState) {
                CallService.CallState.IDLE        -> DialScreen(viewModel)
                CallService.CallState.CONNECTING  -> ConnectingScreen(viewModel)
                CallService.CallState.RINGING     -> RingingScreen(viewModel)
                CallService.CallState.CONNECTED   -> ActiveCallScreen(viewModel)
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

            // ── 팝업 레이어 ──────────────────────────────────────
            // 우선순위: 로컬 누출 경고 > 원격 피싱 경고
            localLeakEvent?.let { event ->
                LocalLeakBlockedOverlay(event = event, viewModel = viewModel)
            } ?: run {
                if (showRemoteWarning) {
                    RemotePhishingWarningOverlay(viewModel = viewModel)
                }
            }
        }
    }
}

// ── 다이얼/입장 화면 ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialScreen(viewModel: CallViewModel) {
    val context = LocalContext.current
    var serverIp by remember { mutableStateOf("192.168.0.") }
    var roomId   by remember { mutableStateOf("") }
    var simPhrase by remember { mutableStateOf("") }

    val presetRemote = listOf(
        "안녕하세요, 저는 검찰청 수사관입니다.",
        "명의 도용 사건으로 수사 중입니다. 안전 계좌로 송금하세요.",
        "주민등록번호와 비밀번호를 알려주시면 확인해 드리겠습니다."
    )
    val presetLocal = listOf(
        "제 비밀번호는 1234입니다.",
        "인증번호는 678910 입니다.",
        "카드 비밀번호는 0000이에요."
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
                        value = serverIp,
                        onValueChange = { serverIp = it },
                        label = { Text("시그널링 서버 IP", color = TextDark) },
                        placeholder = { Text("예: 192.168.0.10", color = TextDark) },
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = ThemeCardBg,
                            containerColor = ThemeBackground
                        ),
                        textStyle = LocalTextStyle.current.copy(color = TextLight),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = roomId,
                        onValueChange = { roomId = it },
                        label = { Text("방 코드 (상대방과 동일하게 입력)", color = TextDark) },
                        placeholder = { Text("예: room-1234", color = TextDark) },
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
                            if (serverIp.isNotBlank() && roomId.isNotBlank()) {
                                viewModel.joinRoom(context, serverIp, roomId)
                            }
                        },
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
                }
            }
        }

        // ── 루프백 테스트 ────────────────────────────────────────
        item {
            OutlinedButton(
                onClick = { viewModel.startLoopbackCall(context) },
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

// ── 통화 중 화면 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(viewModel: CallViewModel) {
    val transcripts   by viewModel.transcripts.collectAsState()
    val riskScore     by viewModel.riskScore.collectAsState()
    val isLocalMuted  by viewModel.isLocalMuted.collectAsState()
    val isRemoteMuted by viewModel.isRemoteMuted.collectAsState()
    var simText       by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(transcripts.size - 1) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 위험도 미터
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Text("통화 연결됨", color = TextDark, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            RiskMeter(riskScore)
        }

        // 실시간 녹취록
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("실시간 통화 녹취록", color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (transcripts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("음성이 감지되면 여기에 표시됩니다.", color = TextDark, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(transcripts) { item ->
                            val isLocal = item.speaker == "LOCAL"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isLocal) Arrangement.End else Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(
                                            topStart = 12.dp, topEnd = 12.dp,
                                            bottomStart = if (isLocal) 12.dp else 0.dp,
                                            bottomEnd = if (isLocal) 0.dp else 12.dp
                                        ))
                                        .background(if (isLocal) PrimaryPurple.copy(alpha = 0.25f) else ThemeBackground)
                                        .border(
                                            if (!item.isFinal) 1.dp else 0.dp,
                                            if (isLocal) PrimaryPurple.copy(0.5f) else PrimaryCyan.copy(0.5f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(10.dp)
                                        .widthIn(max = 260.dp)
                                ) {
                                    Column {
                                        Text(
                                            if (isLocal) "나 (송신)" else "상대방 (수신)",
                                            fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                            color = if (isLocal) PrimaryCyan else PrimaryPurple
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            item.text + if (!item.isFinal) "..." else "",
                                            color = if (item.isFinal) TextLight else TextDark,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 시뮬레이션 입력
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = simText, onValueChange = { simText = it },
                placeholder = { Text("대사 주입 (테스트)", color = TextDark, fontSize = 11.sp) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = PrimaryPurple, unfocusedBorderColor = ThemeCardBg,
                    containerColor = ThemeCardBg
                ),
                textStyle = LocalTextStyle.current.copy(color = TextLight, fontSize = 12.sp)
            )
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { if (simText.isNotBlank()) { viewModel.simulateRemoteSpeech(simText); simText = "" } },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("상대방", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { if (simText.isNotBlank()) { viewModel.simulateLocalSpeech(simText); simText = "" } },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("내 발화", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }

        // 통화 컨트롤 버튼
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.toggleLocalMute() },
                modifier = Modifier.size(54.dp)
                    .background(if (isLocalMuted) AccentRed.copy(0.2f) else ThemeCardBg, CircleShape)
                    .border(1.dp, if (isLocalMuted) AccentRed else Color.Transparent, CircleShape)
            ) {
                Icon(
                    if (isLocalMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    null, tint = if (isLocalMuted) AccentRed else TextLight
                )
            }

            IconButton(
                onClick = { viewModel.endCall() },
                modifier = Modifier.size(68.dp).background(AccentRed, CircleShape)
            ) {
                Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }

            IconButton(
                onClick = { viewModel.toggleRemoteMute() },
                modifier = Modifier.size(54.dp)
                    .background(if (isRemoteMuted) WarningAmber.copy(0.2f) else ThemeCardBg, CircleShape)
                    .border(1.dp, if (isRemoteMuted) WarningAmber else Color.Transparent, CircleShape)
            ) {
                Icon(
                    if (isRemoteMuted) Icons.Filled.VolumeMute else Icons.Filled.VolumeUp,
                    null, tint = if (isRemoteMuted) WarningAmber else TextLight
                )
            }
        }
    }
}

// ── 위험도 게이지 ────────────────────────────────────────────────

@Composable
fun RiskMeter(riskScore: RiskScore) {
    val levelColor = when (riskScore.level) {
        RiskLevel.SAFE -> SafeGreen
        RiskLevel.SUSPICIOUS -> WarningAmber
        RiskLevel.SCAM -> AccentRed
    }
    val borderAlpha by rememberInfiniteTransition(label = "border").animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(800, easing = FastOutLinearInEasing), RepeatMode.Reverse),
        label = "alpha"
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
        modifier = Modifier.fillMaxWidth().border(
            1.5.dp,
            if (riskScore.level == RiskLevel.SCAM) levelColor.copy(borderAlpha) else Color.Transparent,
            RoundedCornerShape(20.dp)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("실시간 피싱 위험도", fontSize = 11.sp, color = TextDark)
                Text("${(riskScore.probability * 100).toInt()}%", fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, color = levelColor)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = riskScore.probability, color = levelColor, trackColor = ThemeBackground,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            if (riskScore.matchedKeywords.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("감지 단어:", fontSize = 10.sp, color = TextDark, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    riskScore.matchedKeywords.forEach { kw ->
                        Box(modifier = Modifier.padding(end = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(levelColor.copy(0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(kw, color = levelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

// ── 경고 팝업 1: 원격 피싱 감지 (상대방이 피싱 뉘앙스 발화) ────────

@Composable
fun RemotePhishingWarningOverlay(viewModel: CallViewModel) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200)),
            modifier = Modifier.fillMaxWidth().border(2.dp, WarningAmber, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("⚠️ 보이스피싱 의심!", color = WarningAmber, fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text(
                    "상대방의 발화에서 금융 사기 패턴이 감지되었습니다.\n\n" +
                    "절대 개인정보, 계좌번호, 비밀번호를 알려주지 마세요.\n" +
                    "공공기관은 전화로 개인정보를 요구하지 않습니다.",
                    color = TextLight, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp
                )
                Button(
                    onClick = { viewModel.endCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("지금 바로 전화 끊기", color = Color.White, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { viewModel.dismissRemotePhishingWarning() },
                    border = BorderStroke(1.dp, TextDark),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) { Text("경고 닫고 통화 계속", fontSize = 12.sp) }
            }
        }
    }
}

// ── 경고 팝업 2: 로컬 누출 차단 (내가 개인정보를 말하려는 순간) ────

@Composable
fun LocalLeakBlockedOverlay(
    event: InterventionEvent.LocalLeakBlocked,
    viewModel: CallViewModel
) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0005)),
            modifier = Modifier.fillMaxWidth().border(2.dp, AccentRed, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("🚨 마이크 즉시 차단됨!", color = AccentRed, fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)

                // 감지된 민감 정보 패턴 태그
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentRed.copy(0.15f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("감지 패턴: \"${event.triggerPhrase}\"",
                        color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    "개인 금융 정보를 말하려는 순간을 감지하여\n마이크를 즉시 차단했습니다.\n\n" +
                    "전화로 비밀번호·인증번호·카드번호를\n절대로 알려주지 마세요.\n" +
                    "이는 100% 보이스피싱입니다.",
                    color = TextLight, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp
                )

                Button(
                    onClick = { viewModel.endCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("전화 끊기 (권장)", color = Color.White, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { viewModel.dismissLocalLeakWarning() },
                    border = BorderStroke(1.dp, TextDark),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) { Text("마이크 복구 후 통화 계속", fontSize = 12.sp) }
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
