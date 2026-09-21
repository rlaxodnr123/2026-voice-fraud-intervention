package com.example.callguard.testapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.callguard.testapp.presentation.ui.TestApp
import com.example.callguard.testapp.presentation.viewmodel.TestAppViewModel

/**
 * 앱 진입점. 마이크·알림 권한을 확인하고 Compose 화면을 띄운다.
 *
 * 통화 중 화면이 꺼지면 참가자가 개입 화면을 보지 못하므로 화면을 계속 켜 둔다.
 */
class TestAppActivity : ComponentActivity() {

    private val viewModel: TestAppViewModel by viewModels()
    private val PERMISSION_REQUEST_CODE = 301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions()
        setContent { TestApp(viewModel) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.bindService(this)
    }

    override fun onStop() {
        super.onStop()
        viewModel.unbindService(this)
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
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
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val micGranted = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            .let { i -> i >= 0 && grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED }
        if (!micGranted) {
            Toast.makeText(
                this,
                "마이크 권한이 없으면 참가자 발화를 기록할 수 없어 실험을 진행할 수 없습니다.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
