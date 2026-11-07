package com.nexiolab.socialai

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.ui.common.container
import com.nexiolab.socialai.ui.navigation.NexioRoot
import com.nexiolab.socialai.ui.theme.NexioTheme
import com.nexiolab.socialai.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** A FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private var onboarding: Boolean? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readCallback(intent)
    }

    private fun readCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "nexiosocial" && data.host == "oauth") InstagramCallback.pending.value = data.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readCallback(intent)
        val app = container
        splash.setKeepOnScreenCondition { onboarding == null }
        lifecycleScope.launch {
            onboarding = app.brand.get()?.interviewCompletedAt == null && savedInstanceState == null
            setContent {
                val theme by app.settings.observe(SettingKeys.THEME).collectAsStateWithLifecycle(null)
                val mode = ThemeMode.entries.firstOrNull { it.name == theme } ?: ThemeMode.DARK
                val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
                LaunchedEffect(dark) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                    enableEdgeToEdge(style, style)
                }
                NexioTheme(mode) { NexioRoot(onboarding == true) }
            }
        }
    }
}
