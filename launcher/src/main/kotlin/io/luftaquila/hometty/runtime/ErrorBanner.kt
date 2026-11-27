package io.luftaquila.hometty.runtime

import android.content.Context
import io.luftaquila.hometty.resources.message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The one error message the screens show above their content, from any screen or the runtime. */
@Singleton
class ErrorBanner @Inject constructor() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun show(text: String) {
        _message.value = text
    }

    fun dismiss() {
        _message.value = null
    }

    /** Runs [block]; a failure other than cancellation shows its message, after [label] if any. */
    suspend fun catching(context: Context, label: String? = null, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = context.message(e)
            show(label?.let { "$it: $message" } ?: message)
        }
    }
}

