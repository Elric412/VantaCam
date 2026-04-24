package com.leica.cam.feature.camera.preview

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class SessionCommandBus @Inject constructor() {
    private val _commands = MutableSharedFlow<SessionCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<SessionCommand> = _commands.asSharedFlow()

    suspend fun send(command: SessionCommand) {
        _commands.emit(command)
    }

    fun trySend(command: SessionCommand): Boolean = _commands.tryEmit(command)
}

sealed class SessionCommand {
    data object Open : SessionCommand()
    data object Close : SessionCommand()
}
