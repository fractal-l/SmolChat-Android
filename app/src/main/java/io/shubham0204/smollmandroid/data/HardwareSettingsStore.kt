package io.shubham0204.smollmandroid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

data class HardwareSettings(
    val nThreads: Int = HardwareSettingsStore.DEFAULT_N_THREADS,
    val useMmap: Boolean = HardwareSettingsStore.DEFAULT_USE_MMAP,
    val useMlock: Boolean = HardwareSettingsStore.DEFAULT_USE_MLOCK,
)

@Single
class HardwareSettingsStore(private val sharedPrefStore: SharedPrefStore) {
    companion object {
        const val DEFAULT_N_THREADS = 4
        const val DEFAULT_USE_MMAP = true
        const val DEFAULT_USE_MLOCK = false

        private const val KEY_N_THREADS = "hardware_n_threads"
        private const val KEY_USE_MMAP = "hardware_use_mmap"
        private const val KEY_USE_MLOCK = "hardware_use_mlock"
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<HardwareSettings> = _settings.asStateFlow()

    fun setNThreads(nThreads: Int) {
        val clamped = nThreads.coerceIn(1, maxThreads())
        if (clamped == _settings.value.nThreads) return
        sharedPrefStore.put(KEY_N_THREADS, clamped)
        _settings.value = _settings.value.copy(nThreads = clamped)
    }

    fun setUseMmap(useMmap: Boolean) {
        if (useMmap == _settings.value.useMmap) return
        sharedPrefStore.put(KEY_USE_MMAP, useMmap)
        _settings.value = _settings.value.copy(useMmap = useMmap)
    }

    fun setUseMlock(useMlock: Boolean) {
        if (useMlock == _settings.value.useMlock) return
        sharedPrefStore.put(KEY_USE_MLOCK, useMlock)
        _settings.value = _settings.value.copy(useMlock = useMlock)
    }

    private fun loadSettings(): HardwareSettings =
        HardwareSettings(
            nThreads =
                sharedPrefStore
                    .get(KEY_N_THREADS, DEFAULT_N_THREADS)
                    .coerceIn(1, maxThreads()),
            useMmap = sharedPrefStore.get(KEY_USE_MMAP, DEFAULT_USE_MMAP),
            useMlock = sharedPrefStore.get(KEY_USE_MLOCK, DEFAULT_USE_MLOCK),
        )

    private fun maxThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
}
