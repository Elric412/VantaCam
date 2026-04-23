package com.leica.cam.feature.camera.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.leica.cam.ui_components.theme.LeicaTokens

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(
    content: @Composable () -> Unit,
) {
    val state: MultiplePermissionsState = rememberMultiplePermissionsState(
        permissions = RequiredPermissions.all,
    )

    // Auto-request exactly once on first composition. The user can tap the
    // button to re-request if they deny.
    LaunchedEffect(Unit) {
        if (!state.allPermissionsGranted) {
            state.launchMultiplePermissionRequest()
        }
    }

    val mustHaveGranted = remember(state.permissions) {
        state.permissions
            .filter { it.permission in RequiredPermissions.mustHave }
            .all { it.status.isGranted() }
    }

    if (mustHaveGranted) {
        content()
    } else {
        PermissionRationaleScreen(state)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun PermissionState.Companion_isGranted_placeholder() {}

@OptIn(ExperimentalPermissionsApi::class)
private fun com.google.accompanist.permissions.PermissionStatus.isGranted(): Boolean =
    this is com.google.accompanist.permissions.PermissionStatus.Granted

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionRationaleScreen(state: MultiplePermissionsState) {
    val context = LocalContext.current
    val colors = LeicaTokens.colors
    val spacing = LeicaTokens.spacing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.l),
        ) {
            Text(
                text = "LeicaCam needs your permission",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
            )
            Text(
                text = "Camera and microphone access are required to capture photos " +
                    "and video. Location is optional (adds EXIF geotag). Media access " +
                    "is needed to save and review images.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceMuted,
            )

            val permanentlyDenied = state.permissions.any {
                !it.status.isGranted() &&
                    !it.status.shouldShowRationale &&
                    it.permission in RequiredPermissions.mustHave
            }

            Spacer(Modifier.height(spacing.s))

            Button(
                onClick = {
                    if (permanentlyDenied) {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    } else {
                        state.launchMultiplePermissionRequest()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onBackground,
                ),
                shape = RoundedCornerShape(4.dp_),
            ) {
                Text(if (permanentlyDenied) "OPEN SETTINGS" else "GRANT PERMISSIONS")
            }
        }
    }
}

// Local helper so we don't import extra compose-ui dp alias in the button shape.
private val Int.dp_: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(this.toFloat())