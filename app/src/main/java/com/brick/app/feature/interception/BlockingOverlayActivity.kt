package com.brick.app.feature.interception

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.MainActivity
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.home.SessionManager
import com.brick.app.theme.BrickTheme

/**
 * Full-screen blocking activity displayed when a user attempts to launch a blocked app while bricked.
 * Stark black and white aesthetic, back-press overridden to go home, with 24-hour recovery link.
 */
class BlockingOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
        const val EXTRA_MODE_NAME = "extra_mode_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Intercept back button to return to home screen rather than falling back into blocked app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: "App"
        val modeName = intent.getStringExtra(EXTRA_MODE_NAME) ?: "Focus Mode"
        val sessionManager = SessionManager.getInstance(applicationContext)
        val recoveryEngine = RecoveryEngine.getInstance(applicationContext)

        setContent {
            BrickTheme {
                val isBricked by sessionManager.isBrickedFlow.collectAsState()
                val recoveryState by recoveryEngine.recoveryStateFlow.collectAsState()

                // If unbricked, dismiss overlay immediately
                if (!isBricked) {
                    finish()
                }

                BlockingOverlayContent(
                    blockedPackage = blockedPackage,
                    modeName = modeName,
                    recoveryActive = recoveryState.isActive,
                    remainingRecoveryMs = recoveryState.remainingMs,
                    onGoHome = { goHome() },
                    onStartRecovery = {
                        recoveryEngine.startRecovery()
                    },
                    onOpenBrick = {
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(mainIntent)
                        finish()
                    }
                )
            }
        }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}

@Composable
fun BlockingOverlayContent(
    blockedPackage: String,
    modeName: String,
    recoveryActive: Boolean,
    remainingRecoveryMs: Long,
    onGoHome: () -> Unit,
    onStartRecovery: () -> Unit,
    onOpenBrick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "YOU'RE BRICKED",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "$modeName is active. Tap your physical Brick token or scan your paired QR card to unlock.",
                color = Color.LightGray,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onGoHome,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "RETURN HOME",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onOpenBrick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222222),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "OPEN BRICK APP",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (recoveryActive) {
                val hours = remainingRecoveryMs / (1000 * 60 * 60)
                val minutes = (remainingRecoveryMs / (1000 * 60)) % 60
                Text(
                    text = "24h Emergency Recovery Active\n${hours}h ${minutes}m remaining",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Button(
                    onClick = onStartRecovery,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.DarkGray
                    )
                ) {
                    Text(
                        text = "Lost all tokens? Start 24h cooldown unlock",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
