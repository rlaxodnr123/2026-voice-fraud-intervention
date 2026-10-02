package com.example.callguard.testapp.presentation.ui

import androidx.compose.ui.graphics.Color

// 참가자 화면은 실제 통화 화면처럼 보여야 하므로 iOS/안드로이드 통화 UI 계열의 무채색을 쓴다.
val CallBackground = Color(0xFF1C1C1E)
val CallSurface = Color(0xFF2C2C2E)
val CallControl = Color(0xFF3A3A3C)
val CallSubText = Color(0xFF8E8E93)

// 연구자 전용 화면에서만 쓰는 강조색 — 참가자에게 노출되지 않는다.
val AccentRed = Color(0xFFFF3B30)
val WarnAmber = Color(0xFFFFCC00)
val SafeGreen = Color(0xFF34C759)
val InfoBlue = Color(0xFF0A84FF)

// 오버레이가 아래 화면으로 터치를 흘리는 문제는 포인터 이벤트를 삼켜 막지 않는다.
// 모든 이벤트를 consume하면 오버레이 자신의 버튼까지 죽는다 — 실제로 [통화 종료]와
// 연구자 패널 [닫기]가 둘 다 눌리지 않았다. 대신 TestApp이 오버레이가 덮는 동안
// **아래 화면을 아예 그리지 않는다** (그리지 않은 화면은 눌릴 수도 없다).
