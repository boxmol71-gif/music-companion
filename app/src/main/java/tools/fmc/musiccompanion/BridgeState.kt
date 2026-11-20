package tools.fmc.musiccompanion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-sensitive connection state shown by the UI. */
sealed interface BridgeStatus {
    data object Stopped : BridgeStatus
    data object Connecting : BridgeStatus
    data object Connected : BridgeStatus
    data class Error(val message: String) : BridgeStatus
}

object BridgeState {
    private val mutableStatus = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)
    val status: StateFlow<BridgeStatus> = mutableStatus.asStateFlow()

    fun set(value: BridgeStatus) {
        mutableStatus.value = value
    }
}

/** Whether the user wants the foreground watch bridge to run. */
class BridgePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(ENABLED, value).apply()
        }

    private companion object {
        const val ENABLED = "enabled"
    }
}
