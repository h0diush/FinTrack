package app.fintrack

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import app.fintrack.data.repository.LockState
import app.fintrack.data.repository.SecurityRepository
import app.fintrack.data.repository.SettingsRepository
import app.fintrack.domain.model.AppSettings
import app.fintrack.domain.model.ThemeMode
import app.fintrack.notify.RecurringNotifier
import app.fintrack.ui.components.Motion
import app.fintrack.ui.lock.LockScreen
import app.fintrack.ui.navigation.FinTrackNavHost
import app.fintrack.ui.theme.FinTrackTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
}

/** FragmentActivity — нужна для системного окна входа по отпечатку (BiometricPrompt). */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var security: SecurityRepository

    /** Экран, который нужно открыть по нажатию на уведомление. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRequest.value = intent?.getStringExtra(RecurringNotifier.EXTRA_OPEN)
        enableEdgeToEdge()
        lifecycleScope.launch { security.initLock() }
        // «Скрывать в недавних»: окно не попадает на снимки экрана и в превью задач.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                security.settings.collect { s ->
                    if (s.secureScreen) {
                        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
        setContent {
            val vm: MainViewModel = hiltViewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val lock by security.lockState.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Значки строки состояния — по теме приложения, а не системы; экран блокировки всегда тёмный
            val lightIcons = dark || lock == LockState.LOCKED
            DisposableEffect(lightIcons) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (lightIcons) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            FinTrackTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // Под замком приложение не уничтожается (открытый экран сохраняется), но и не читается вслух.
                    Box(Modifier.fillMaxSize().then(if (lock != LockState.UNLOCKED) Modifier.clearAndSetSemantics { } else Modifier)) {
                        if (lock != LockState.CHECKING) {
                            FinTrackNavHost(openRequest = openRequest.value, onOpenHandled = { openRequest.value = null })
                        }
                    }
                    AnimatedVisibility(
                        visible = lock == LockState.LOCKED,
                        enter = fadeIn(tween(0)),
                        exit = fadeOut(tween(Motion.MEDIUM_MS)) + scaleOut(tween(Motion.MEDIUM_MS), targetScale = 1.06f),
                    ) {
                        LockScreen()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { security.onForeground() }
    }

    override fun onStop() {
        super.onStop()
        // Поворот экрана — не уход из приложения.
        if (!isChangingConfigurations) security.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(RecurringNotifier.EXTRA_OPEN)?.let { openRequest.value = it }
    }
}
