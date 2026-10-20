package com.brick.app

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.brick.app.core.database.BrickDatabase
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.nfc.DefaultTokenScanController
import com.brick.app.feature.nfc.DefaultTokenPairingUseCase
import com.brick.app.feature.nfc.DefaultTokenScanUseCase
import com.brick.app.feature.nfc.LocalTokenScanController
import com.brick.app.feature.nfc.NfcManager
import com.brick.app.feature.nfc.NfcManagerGateway
import com.brick.app.theme.BrickTheme
import com.brick.app.ui.scan.TokenScannerHost
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

  private lateinit var nfcManager: NfcManager
  private lateinit var tokenScanController: DefaultTokenScanController

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    nfcManager = NfcManager(this)
    val database = BrickDatabase.getInstance(this)
    val sessionManager = SessionManager.getInstance(this)
    tokenScanController = DefaultTokenScanController(
      scope = lifecycleScope,
      scanUseCase = DefaultTokenScanUseCase(database, sessionManager),
      pairingUseCase = DefaultTokenPairingUseCase(database, sessionManager),
      isBricked = sessionManager::isBricked,
      nfc = NfcManagerGateway(nfcManager)
    )

    enableEdgeToEdge()
    setContent {
      BrickTheme {
        CompositionLocalProvider(LocalTokenScanController provides tokenScanController) {
          Box(modifier = Modifier.fillMaxSize()) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
              MainNavigation()
            }
            // Hosted here, above the whole app, so no screen has to lay the scanner out.
            TokenScannerHost(controller = tokenScanController)
          }
        }
      }
    }

    // A tag tapped while the app was closed launches the activity with the tag intent attached.
    // Only on a fresh start: a recreation redelivers the same intent and must not re-toggle.
    if (savedInstanceState == null) handleNfcIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleNfcIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    tokenScanController.onHostResumed()
  }

  override fun onPause() {
    tokenScanController.onHostPaused()
    super.onPause()
  }

  private fun handleNfcIntent(intent: Intent?) {
    if (intent == null) return
    val uid = NfcManager.extractTagUid(intent) ?: return
    intent.removeExtra(NfcAdapter.EXTRA_TAG)
    tokenScanController.onNfcTagUid(uid)
  }
}
