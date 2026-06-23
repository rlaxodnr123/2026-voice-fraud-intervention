package com.example.callguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.callguard.presentation.ui.CallGuardApp
import com.example.callguard.presentation.viewmodel.CallViewModel

/**
 * MainActivity handles system permission checks (microphone, overlay, notification)
 * and hosts the Compose content view for the calling application.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CallViewModel by viewModels()

    private val PERMISSION_REQUEST_CODE = 201

    // Overlay Permission launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Overlay permission is required for real-time alert popups.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Trigger permissions check
        checkAndRequestPermissions()

        // 2. Setup content with viewmodel
        setContent {
            CallGuardApp(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to call service to synchronize call session updates
        viewModel.bindCallService(this)
    }

    override fun onStop() {
        super.onStop()
        // Unbind to prevent memory leaks when app is inactive
        viewModel.unbindCallService(this)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }

        // Check overlay permission separately (required for intervention overlay rendering outside app boundaries)
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
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
                    "Microphone and Notification permissions are critical for VoIP calling.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
