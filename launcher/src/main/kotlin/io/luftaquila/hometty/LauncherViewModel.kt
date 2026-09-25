package io.luftaquila.hometty

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.luftaquila.hometty.resources.message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

/** A screen's model: actions run in its scope and failures go to the [ErrorBanner]. */
abstract class LauncherViewModel(
    protected val context: Context,
    protected val banner: ErrorBanner,
) : ViewModel() {
    fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch { banner.catching(context, block = block) }
    }
}

