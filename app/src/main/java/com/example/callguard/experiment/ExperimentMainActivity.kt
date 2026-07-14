package com.example.callguard.experiment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.callguard.experiment.presentation.ui.ExperimentApp
import com.example.callguard.experiment.presentation.viewmodel.ExperimentViewModel

/**
 * 실험 앱 진입점 — 마이크/알림 권한 확인 후 Compose 콘텐츠를 띄운다.
 * (원본과 달리 오버레이 권한은 사용하지 않는다 — 실험 UI는 앱 내부에서만 표시)
 */
class ExperimentMainActivity : ComponentActivity() {

    private val viewModel: ExperimentViewModel by viewModels()

    private val PERMISSION_REQUEST_CODE = 202

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        setContent {
            ExperimentApp(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.bindService(this)
    }

    override fun onStop() {
        super.onStop()
        viewModel.unbindService(this)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "마이크·알림 권한이 없으면 통화 실험을 진행할 수 없습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
