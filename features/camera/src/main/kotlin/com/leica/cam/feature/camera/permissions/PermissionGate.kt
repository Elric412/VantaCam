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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.leica.cam.ui_components.theme.LeicaTokens

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(
    content: @Composable () -> Unit,
) {
    val state = rememberMultiplePermissionsState(
        permissions = RequiredPermissions.startupPrompt,
    )

    val permissionState = LeicaPermissionReducer.reduce(
        grants = state.permissions.associate { permissionState ->
            permissionState.permission to (permissionState.status is PermissionStatus.Granted)
        },
        rationales = state.permissions.associate { permissionState ->
            permissionState.permission to when (val status = permissionState.status) {
                is PermissionStatus.Denied -> status.shouldShowRationale
                PermissionStatus.Granted -> false
            }
        },
        required = RequiredPermissions.requiredForViewfinder,
    )

    LaunchedEffect(Unit) {
        if (!state.allPermissionsGranted) {
            state.launchMultiplePermissionRequest()
        }
    }

    if (permissionState == LeicaPermissionState.AllGranted) {
        content()
    } else {
        PermissionRationaleScreen(
            state = state,
            permissionState = permissionState,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionRationaleScreen(
    state: MultiplePermissionsState,
    permissionState: LeicaPermissionState,
) {
    val context = LocalContext.current
    val colors = LeicaTokens.colors
    val spacing = LeicaTokens.spacing
    val isPermanentlyDenied = permissionState is LeicaPermissionState.PermanentlyDenied

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
                text = "Camera access is required",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
            )
            Text(
                text = if (isPermanentlyDenied) {
                    "LeicaCam cannot start the live viewfinder until Android camera access is re-enabled in Settings."
                } else {
                    "Grant camera access to open the live viewfinder. Media, audio, and notification permissions are requested now so capture, video, gallery, and long-running HDR features work without later crashes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceMuted,
            )

            Spacer(Modifier.height(spacing.s))

            Button(
                onClick = {
                    if (isPermanentlyDenied) {
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
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(if (isPermanentlyDenied) "OPEN SETTINGS" else "GRANT CAMERA PERMISSIONS")
            }
        }
    }
}
