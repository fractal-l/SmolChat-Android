package io.shubham0204.smollmandroid.data

import io.shubham0204.smollmandroid.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

@Single
class ThemeStore(private val sharedPrefStore: SharedPrefStore) {
    private val key = "theme_mode"
    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private fun loadThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(sharedPrefStore.get(key, ThemeMode.SYSTEM.name)) }
            .getOrDefault(ThemeMode.SYSTEM)

    fun setThemeMode(themeMode: ThemeMode) {
        sharedPrefStore.put(key, themeMode.name)
        _themeMode.value = themeMode
    }
}
