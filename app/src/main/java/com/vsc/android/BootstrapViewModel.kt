package com.vsc.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vsc.android.runtime.BootstrapState
import com.vsc.android.runtime.RuntimeRepository
import kotlinx.coroutines.flow.StateFlow

class BootstrapViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<BootstrapState> = RuntimeRepository.state

    fun start() {
        RuntimeRepository.ensureStarted(getApplication())
    }

    fun retry() {
        RuntimeRepository.restart(getApplication())
    }

    fun restart() {
        RuntimeRepository.restart(getApplication())
    }

    fun resetRuntime() {
        RuntimeRepository.resetRuntime(getApplication())
    }

    fun stop() {
        RuntimeRepository.stop(getApplication())
    }
}
