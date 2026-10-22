package com.brick.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brick.app.ui.main.MainScreen
import com.brick.app.ui.onboarding.OnboardingScreen

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("brick_setup", 0) }
    var showOnboarding by remember { mutableStateOf(!preferences.getBoolean("onboarding_complete", false)) }

    if (showOnboarding) {
        OnboardingScreen(
            onFinishOnboarding = {
                preferences.edit { putBoolean("onboarding_complete", true) }
                showOnboarding = false
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        MainScreen(
            onNavigateOnboarding = { showOnboarding = true },
            modifier = Modifier.fillMaxSize()
        )
    }
}
