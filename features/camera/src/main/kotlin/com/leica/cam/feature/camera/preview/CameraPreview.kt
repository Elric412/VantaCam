package com.leica.cam.feature.camera.preview

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.leica.cam.sensor_hal.session.Camera2CameraController
import com.leica.cam.sensor_hal.session.CameraSessionManager
import kotlinx.coroutines.launch

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    controller: Camera2CameraController,
    sessionManager: CameraSessionManager,
    commandBus: SessionCommandBus,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycleScope = rememberCoroutineScope()
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(sessionManager, commandBus) {
        commandBus.commands.collect { command ->
            when (command) {
                SessionCommand.Open -> sessionManager.openSession()
                SessionCommand.Close -> sessionManager.closeSession()
            }
        }
    }

    DisposableEffect(owner, previewView, controller, sessionManager, lifecycleScope) {
        controller.attach(previewView, owner)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    controller.attach(previewView, owner)
                    lifecycleScope.launch { sessionManager.openSession() }
                }

                Lifecycle.Event.ON_STOP -> {
                    lifecycleScope.launch { sessionManager.closeSession() }
                }

                else -> Unit
            }
        }

        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleScope.launch { sessionManager.openSession() }
        }

        onDispose {
            owner.lifecycle.removeObserver(observer)
            lifecycleScope.launch { sessionManager.closeSession() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}
