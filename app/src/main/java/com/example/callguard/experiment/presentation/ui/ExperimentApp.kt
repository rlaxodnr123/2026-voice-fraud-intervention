package com.example.callguard.experiment.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.callguard.experiment.domain.service.ExperimentCallService
import com.example.callguard.experiment.presentation.viewmodel.ExperimentViewModel

// ── 색상 팔레트 (원본 CallGuard와 동일 계열) ─────────────────────
val ThemeBackground = Color(0xFF0F0E17)
val ThemeCardBg     = Color(0xFF1E1B29)
val PrimaryCyan     = Color(0xFF00F2FE)
val PrimaryPurple   = Color(0xFF4FACFE)
val AccentRed       = Color(0xFFFF3B30)
val WarningAmber    = Color(0xFFFFCC00)
val SafeGreen       = Color(0xFF34C759)
val TextLight       = Color(0xFFE2E1E6)
val TextDark        = Color(0xFF9F9BA8)

/**
 * 실험 앱 루트.
 *
 * - IDLE: 연구자용 설정 화면(서버/방/시나리오 선택)
 * - CONNECTED: 참가자용 통화 화면 (일반 전화앱 모양 — 내부 정보 미노출)
 * - 어느 상태에서든 연구자 패널을 숨김 제스처(우상단 5회 탭)로 열 수 있다 (§5.2)
 */
@Composable
fun ExperimentApp(viewModel: ExperimentViewModel) {
    val callState by viewModel.callState.collectAsState()
    val researcherPanelVisible by viewModel.researcherPanelVisible.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ThemeBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (callState) {
                ExperimentCallService.CallState.IDLE,
                ExperimentCallService.CallState.DISCONNECTED -> ScenarioSelectScreen(viewModel)
                ExperimentCallService.CallState.CONNECTING,
                ExperimentCallService.CallState.RINGING -> ConnectingScreen(viewModel)
                ExperimentCallService.CallState.CONNECTED -> ParticipantCallScreen(viewModel)
            }

            // 연구자 패널 — 항상 최상위 오버레이
            if (researcherPanelVisible) {
                ResearcherPanelScreen(viewModel)
            }
        }
    }
}
