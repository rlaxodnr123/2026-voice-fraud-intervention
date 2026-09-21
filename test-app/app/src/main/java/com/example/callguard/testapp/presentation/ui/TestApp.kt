package com.example.callguard.testapp.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.callguard.testapp.domain.session.ExperimentSessionService
import com.example.callguard.testapp.presentation.viewmodel.TestAppViewModel

/**
 * 앱 루트.
 *
 *  - IDLE     : 연구자 설정 화면
 *  - IN_CALL  : 참가자 통화 화면 (일반 전화앱처럼 보여야 한다)
 *  - ENDED    : 통화 종료 화면 (개입 2의 안전 안내 포함)
 *
 * 연구자 패널은 어느 상태에서든 최상위 오버레이로 열린다.
 *
 * composable 람다에서 이른 `return`을 쓰지 않는다 — Compose는 그런 비지역 반환에서
 * 그룹 종료 호출을 맞춰 내보내지 못해 슬롯 테이블이 깨지고 첫 컴포지션에서 죽는다.
 * 분기는 반드시 if/else로 표현한다.
 */
@Composable
fun TestApp(viewModel: TestAppViewModel) {
    val service by viewModel.service.collectAsState()
    val panelVisible by viewModel.researcherPanelVisible.collectAsState()
    val logVisible by viewModel.logScreenVisible.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CallBackground)) {
        val svc = service
        if (svc == null) {
            ConnectingPlaceholder()
        } else {
            val callState by svc.callState.collectAsState()

            when {
                logVisible -> LogScreen(viewModel)
                callState == ExperimentSessionService.CallState.IDLE -> SetupScreen(viewModel, svc)
                callState == ExperimentSessionService.CallState.IN_CALL -> CallScreen(viewModel, svc)
                else -> CallEndedScreen(viewModel, svc)
            }

            if (panelVisible) {
                ResearcherPanel(viewModel, svc)
            }
        }
    }
}

@Composable
private fun ConnectingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("세션 서비스를 연결하는 중…", color = CallSubText)
    }
}
