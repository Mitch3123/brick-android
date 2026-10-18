package com.brick.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brick.app.ui.main.MainScreen
import com.brick.app.ui.onboarding.OnboardingScreen

@Composable
fun MainNavigation() {
    var showOnboarding by remember { mutableStateOf(false) }

    if (showOnboarding) {
        OnboardingScreen(
            onFinishOnboarding = { showOnboarding = false },
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
        )
    } else {
        MainScreen(
            onNavigateOnboarding = { showOnboarding = true },
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
        )
    }
}
