package com.brick.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import android.app.Activity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.R

val ReferenceBackground = Color(0xFFD9D4D3)
val ReferenceCard = Color(0xFFE5E1DF)
val ReferenceInk = Color(0xFF272726)
val ReferenceMuted = Color(0xFF817D7B)
val ReferenceFont = FontFamily(Font(R.font.outfit))

@Composable
fun ReferenceTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = true
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
    val base = Typography()
    fun TextStyle.reference() = copy(fontFamily = ReferenceFont, letterSpacing = 0.sp)
    MaterialTheme(
        colorScheme = lightColorScheme(primary = ReferenceInk, onPrimary = Color.White,
            background = ReferenceBackground, onBackground = ReferenceInk,
            surface = ReferenceBackground, onSurface = ReferenceInk,
            surfaceVariant = ReferenceCard, onSurfaceVariant = ReferenceMuted,
            secondary = ReferenceMuted, outline = ReferenceMuted),
        typography = Typography(
            displayLarge = base.displayLarge.reference(), displayMedium = base.displayMedium.reference(),
            displaySmall = base.displaySmall.reference(), headlineLarge = base.headlineLarge.reference(),
            headlineMedium = base.headlineMedium.reference(), headlineSmall = base.headlineSmall.reference(),
            titleLarge = base.titleLarge.reference(), titleMedium = base.titleMedium.reference(),
            titleSmall = base.titleSmall.reference(), bodyLarge = base.bodyLarge.reference(),
            bodyMedium = base.bodyMedium.reference(), bodySmall = base.bodySmall.reference(),
            labelLarge = base.labelLarge.reference(), labelMedium = base.labelMedium.reference(),
            labelSmall = base.labelSmall.reference()
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides ReferenceInk, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ReferenceTheme {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = modifier,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            containerColor = ReferenceBackground,
            contentColor = ReferenceInk,
            scrimColor = Color.Black.copy(alpha = 0.32f),
            dragHandle = null,
            content = content
        )
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
               enabled: Boolean = true, secondary: Boolean = false) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondary) Color(0xFFCAC5C4) else ReferenceInk,
            contentColor = if (secondary) ReferenceInk else Color.White,
            disabledContainerColor = Color(0xFFC3BEBD), disabledContentColor = ReferenceMuted)) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RoundBackButton(onClick: () -> Unit, modifier: Modifier = Modifier, close: Boolean = false) {
    IconButton(onClick = onClick,
        modifier = modifier.size(48.dp).background(Color(0xFFCAC5C4), CircleShape)) {
        Text(if (close) "×" else "←", fontSize = 28.sp,
            modifier = Modifier.semantics { contentDescription = if (close) "Close" else "Back" })
    }
}

@Composable
fun SheetHeader(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        RoundBackButton(onBack, modifier = Modifier.align(Alignment.CenterStart))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}
