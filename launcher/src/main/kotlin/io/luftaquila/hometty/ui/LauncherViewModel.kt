package io.luftaquila.hometty.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.luftaquila.hometty.runtime.ErrorBanner
import kotlinx.coroutines.launch

/** A screen's model: actions run in its scope and failures go to the [ErrorBanner]. */
abstract class LauncherViewModel(
    protected val context: Context,
    protected val banner: ErrorBanner,
) : ViewModel() {
    fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch { banner.catching(context, block = block) }
    }
}
