package com.livevoice.assistant

import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase { Off, Waiting, Connecting, Live }
data class KaliStatus(val phase: Phase = Phase.Off, val note: String? = null)

/** Shared between the background service and the screen. */
object KaliState {
    val status = MutableStateFlow(KaliStatus())
}
