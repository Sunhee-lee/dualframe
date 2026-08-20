package com.dualframe.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualframe.data.AppSettings
import com.dualframe.monetize.BillingManager
import com.dualframe.monetize.ProEntitlement
import com.dualframe.ui.theme.PretendardFont
import com.sunnlab.dualframe.BuildConfig
import com.sunnlab.dualframe.R

@Composable
fun SettingsPage(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isPro = ProEntitlement.isProOwned(context)
    var showProSheet by remember { mutableStateOf(false) }
    var showAutoSavePage by remember { mutableStateOf(false) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    if (showAutoSavePage) {
        AutoSavePage(
            settings = settings,
            isPro = isPro,
            onSettingsChange = onSettingsChange,
            onBack = { showAutoSavePage = false },
            onShowPro = { showProSheet = true },
        )
        if (showProSheet) {
            ProUpgradeSheet(
                context = context,
                onDismiss = { showProSheet = false },
                onPurchased = { showProSheet = false },
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color.White,
                modifier = Modifier.size(28.dp).clickable { onBack() }.padding(4.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_title), color = Color.White, fontSize = 20.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {

        Spacer(Modifier.height(12.dp))

        // PRO Card - Gold Gradient
        val goldGradient = Brush.horizontalGradient(
            colors = listOf(Color(0xFFD4AF37), Color(0xFFF5D76E), Color(0xFFC89B2A))
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .heightIn(min = 100.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(goldGradient)
                .border(0.5.dp, Color(0xFFE8C85A).copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                .clickable { showProSheet = true }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                val iconGold = Brush.linearGradient(
                    colors = listOf(Color(0xFFF5D76E), Color.White, Color(0xFFF5D76E))
                )
                Icon(Icons.Rounded.WorkspacePremium, null,
                    modifier = Modifier.size(34.dp)
                        .graphicsLayer(alpha = 0.99f)
                        .drawWithCache {
                            onDrawWithContent {
                                drawContent()
                                drawRect(brush = iconGold, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                            }
                        })
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pro_sheet_title_full),
                    color = Color(0xFF111111), fontSize = 20.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isPro) stringResource(R.string.settings_pro_active)
                    else stringResource(R.string.settings_pro_subtitle),
                    color = if (isPro) Color.White else Color(0xFF6B5E3A),
                    fontSize = 14.sp,
                    fontFamily = PretendardFont)
            }
            if (!isPro) {
                val price = BillingManager.getInstance(context).formattedPrice
                if (price != null) {
                    Text(price, color = Color.White, fontSize = 16.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                }
            }
            Icon(Icons.Rounded.ChevronRight, null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.height(32.dp))

        // Settings items - individual cards
        SettingsRow(
            icon = Icons.Rounded.SaveAlt,
            title = stringResource(R.string.settings_auto_save),
            value = if (settings.autoSave) "ON" else "OFF",
            onClick = { showAutoSavePage = true },
        )
        Spacer(Modifier.height(12.dp))
        SettingsRow(
            icon = Icons.Rounded.Cameraswitch,
            title = stringResource(R.string.settings_default_camera),
            value = if (settings.defaultFrontCamera) stringResource(R.string.settings_camera_front)
                    else stringResource(R.string.settings_camera_rear),
            onClick = { showCameraDialog = true },
        )
        Spacer(Modifier.height(12.dp))
        SettingsRow(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.settings_language),
            value = when (settings.appLanguage) {
                "en"    -> stringResource(R.string.settings_language_english)
                "hi"    -> stringResource(R.string.settings_language_hindi)
                "in"    -> stringResource(R.string.settings_language_indonesian)
                "pt-BR" -> stringResource(R.string.settings_language_portuguese_br)
                else    -> stringResource(R.string.settings_language_system)
            },
            onClick = { showLanguageDialog = true },
        )

        Spacer(Modifier.height(32.dp))

        SettingsRow(
            icon = Icons.Rounded.StarBorder,
            title = stringResource(R.string.settings_review),
            onClick = {
                val uri = Uri.parse("market://details?id=${context.packageName}")
                try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                catch (_: Exception) {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")))
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        SettingsRow(
            icon = Icons.Rounded.MailOutline,
            title = stringResource(R.string.settings_contact),
            onClick = {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:dualframe.support@gmail.com")
                    putExtra(Intent.EXTRA_SUBJECT, "DualFrame Feedback")
                    putExtra(Intent.EXTRA_TEXT,
                        "기기명: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Android 버전: ${Build.VERSION.RELEASE}\n" +
                        "앱 버전: ${BuildConfig.VERSION_NAME}\n\n" +
                        "문의 내용 또는 오류 상황을 적어주세요.\n")
                }
                try { context.startActivity(intent) } catch (_: Exception) {}
            },
        )

        Spacer(Modifier.height(32.dp))

        // Version
        Text("DualFrame ${BuildConfig.VERSION_NAME}", color = Color(0xFF444444), fontSize = 12.sp,
            fontFamily = PretendardFont,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }

    // Camera selection dialog
    if (showCameraDialog) {
        BackHandler { showCameraDialog = false }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable { showCameraDialog = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.7f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.settings_default_camera), color = Color.White, fontSize = 18.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                DialogOption(stringResource(R.string.settings_camera_rear), !settings.defaultFrontCamera) {
                    onSettingsChange(settings.copy(defaultFrontCamera = false))
                    showCameraDialog = false
                }
                Spacer(Modifier.height(8.dp))
                DialogOption(stringResource(R.string.settings_camera_front), settings.defaultFrontCamera) {
                    onSettingsChange(settings.copy(defaultFrontCamera = true))
                    showCameraDialog = false
                }
            }
        }
    }

    // Language selection dialog
    if (showLanguageDialog) {
        BackHandler { showLanguageDialog = false }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable { showLanguageDialog = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.7f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.settings_language), color = Color.White, fontSize = 18.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                DialogOption(stringResource(R.string.settings_language_system), settings.appLanguage == "system") {
                    applyLanguage(context, settings, "system", onSettingsChange)
                    showLanguageDialog = false
                }
                Spacer(Modifier.height(8.dp))
                DialogOption(stringResource(R.string.settings_language_english), settings.appLanguage == "en") {
                    applyLanguage(context, settings, "en", onSettingsChange)
                    showLanguageDialog = false
                }
                Spacer(Modifier.height(8.dp))
                DialogOption(stringResource(R.string.settings_language_hindi), settings.appLanguage == "hi") {
                    applyLanguage(context, settings, "hi", onSettingsChange)
                    showLanguageDialog = false
                }
                Spacer(Modifier.height(8.dp))
                DialogOption(stringResource(R.string.settings_language_indonesian), settings.appLanguage == "in") {
                    applyLanguage(context, settings, "in", onSettingsChange)
                    showLanguageDialog = false
                }
                Spacer(Modifier.height(8.dp))
                DialogOption(stringResource(R.string.settings_language_portuguese_br), settings.appLanguage == "pt-BR") {
                    applyLanguage(context, settings, "pt-BR", onSettingsChange)
                    showLanguageDialog = false
                }
            }
        }
    }

    if (showProSheet) {
        BackHandler { showProSheet = false }
        ProUpgradeSheet(
            context = context,
            isPro = isPro,
            onDismiss = { showProSheet = false },
            onPurchased = { showProSheet = false },
        )
    }

}

private fun applyLanguage(
    context: Context,
    settings: AppSettings,
    lang: String,
    onSettingsChange: (AppSettings) -> Unit,
) {
    if (settings.appLanguage == lang) return
    onSettingsChange(settings.copy(appLanguage = lang))
    val prefs = context.getSharedPreferences("dualframe_settings", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("reopen_settings", true).apply()
    (context as? Activity)?.recreate()
}

@Composable
private fun DialogOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFF2A2A2A))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Color(0xFF4CAF50) else Color.White, fontSize = 16.sp,
            fontFamily = PretendardFont, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Text("✓", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Auto Save Page ──

@Composable
private fun AutoSavePage(
    settings: AppSettings,
    isPro: Boolean,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onShowPro: () -> Unit,
) {
    BackHandler { onBack() }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        // Close button
        Box(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 8.dp),
        ) {
            Icon(Icons.Rounded.Close, null, tint = Color(0xFF888888),
                modifier = Modifier.size(24.dp)
                    .align(Alignment.TopEnd)
                    .clickable { onBack() })
        }

        // Auto save toggle card
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF141414))
                .border(0.5.dp, Color(0xFF252525), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_save), color = Color.White, fontSize = 17.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_auto_save_desc), color = Color(0xFF888888), fontSize = 13.sp,
                    fontFamily = PretendardFont)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.autoSave,
                onCheckedChange = { enabled ->
                    if (enabled && !isPro) {
                        onShowPro()
                    } else {
                        onSettingsChange(settings.copy(autoSave = enabled))
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF4CAF50),
                    uncheckedThumbColor = Color(0xFFCCCCCC),
                    uncheckedTrackColor = Color(0xFF444444),
                ),
            )
        }

        Spacer(Modifier.height(16.dp))

        // PRO notice or enabled notice
        if (!isPro) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF141414))
                    .border(0.5.dp, Color(0xFF252525), RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WorkspacePremium, null, tint = Color(0xFFD4A828),
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DualFrame PRO", color = Color(0xFFD4A828), fontSize = 14.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_auto_save_pro_only), color = Color(0xFFAAAAAA), fontSize = 13.sp,
                    fontFamily = PretendardFont)
                Spacer(Modifier.height(12.dp))
                val goldBrush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFD4AF37), Color(0xFFF5D76E), Color(0xFFC89B2A))
                )
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(goldBrush)
                        .border(1.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                        .clickable { onShowPro() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.settings_view_pro), color = Color(0xFF1A1A1A), fontSize = 17.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                }
            }
        } else if (settings.autoSave) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF4CAF50).copy(alpha = 0.1f))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_auto_save_enabled), color = Color(0xFFCCCCCC), fontSize = 13.sp,
                        fontFamily = PretendardFont)
                }
            }
        }
    }
}

// ── PRO Upgrade Bottom Sheet ──

@Composable
fun ProUpgradeSheet(
    context: Context,
    isPro: Boolean = false,
    onDismiss: () -> Unit,
    onPurchased: () -> Unit,
) {
    val price = BillingManager.getInstance(context).formattedPrice

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xFF111111))
                .clickable(enabled = false) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Close button
            Box(Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Close, null, tint = Color(0xFF666666),
                    modifier = Modifier.size(24.dp)
                        .align(Alignment.TopEnd)
                        .clickable { onDismiss() })
            }

            Spacer(Modifier.height(8.dp))

            // Crown icon with decorative background
            val crownGoldBrush = Brush.linearGradient(
                colors = listOf(Color(0xFFD4AF37), Color(0xFFF5D76E), Color(0xFFD4AF37), Color(0xFFF0D060))
            )
            Box(
                modifier = Modifier.size(100.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF2A2215), Color(0xFF161616), Color(0xFF111111))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                )
                Icon(Icons.Rounded.WorkspacePremium, null,
                    modifier = Modifier.size(52.dp)
                        .graphicsLayer(alpha = 0.99f)
                        .drawWithCache {
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = crownGoldBrush,
                                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                                )
                            }
                        })
            }

            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.pro_sheet_title_full), color = Color.White, fontSize = 24.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(6.dp))

            if (isPro) {
                Text(stringResource(R.string.settings_pro_active), color = Color(0xFF22C55E), fontSize = 16.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.pro_sheet_thanks), color = Color(0xFF888888), fontSize = 14.sp,
                    fontFamily = PretendardFont)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pro_sheet_subtitle), color = Color(0xFF888888), fontSize = 14.sp,
                    fontFamily = PretendardFont)
            }

            Spacer(Modifier.height(24.dp))

            // Benefits
            ProBenefitRow(stringResource(R.string.pro_sheet_benefit1_title))
            Spacer(Modifier.height(10.dp))
            ProBenefitRow(stringResource(R.string.pro_sheet_benefit2_title))
            Spacer(Modifier.height(10.dp))
            ProBenefitRow(stringResource(R.string.pro_sheet_benefit3_title))

            Spacer(Modifier.height(28.dp))

            if (isPro) {
                // Close button (primary action)
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(0.5.dp, Color(0xFF333333), RoundedCornerShape(14.dp))
                        .clickable { onDismiss() }
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.pro_sheet_close), color = Color.White, fontSize = 16.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(12.dp))

                // Restore purchase (secondary text link)
                Text(stringResource(R.string.pro_sheet_restore), color = Color(0xFF666666), fontSize = 13.sp,
                    fontFamily = PretendardFont,
                    modifier = Modifier.clickable {
                        BillingManager.getInstance(context).restorePurchases(showToast = true, toastContext = context)
                        android.widget.Toast.makeText(context,
                            context.getString(R.string.settings_pro_active_msg),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }.padding(8.dp))
            } else {
                // Gold gradient buy button — same size as save popup PRO card
                val goldBrush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFD4AF37), Color(0xFFF5D76E), Color(0xFFC89B2A))
                )
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(goldBrush)
                        .border(1.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                        .clickable {
                            val activity = context as? Activity
                            if (activity != null) {
                                BillingManager.getInstance(context)
                                    .launchPurchase(activity) { success ->
                                        if (success) {
                                            android.widget.Toast.makeText(context,
                                                context.getString(R.string.pro_purchase_success),
                                                android.widget.Toast.LENGTH_LONG).show()
                                            onPurchased()
                                        } else {
                                            android.widget.Toast.makeText(context,
                                                context.getString(R.string.pro_purchase_failed),
                                                android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val label = stringResource(R.string.pro_sheet_get)
                    if (price != null) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color(0xFF1A1A1A))) { append("$label · ") }
                                withStyle(SpanStyle(color = Color.White)) { append(price) }
                            },
                            fontSize = 17.sp,
                            fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                    } else {
                        Text(label, color = Color(0xFF1A1A1A), fontSize = 17.sp,
                            fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(stringResource(R.string.pro_sheet_restore_prompt), color = Color(0xFF666666), fontSize = 12.sp,
                    fontFamily = PretendardFont,
                    modifier = Modifier.clickable {
                        BillingManager.getInstance(context).restorePurchases(showToast = true, toastContext = context)
                    }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun ProBenefitRow(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Check, null, tint = Color(0xFF22C55E), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color(0xFFCCCCCC), fontSize = 15.sp,
            fontFamily = PretendardFont)
    }
}

// ── Shared Components ──

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141414))
            .border(0.5.dp, Color(0xFF252525), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontFamily = PretendardFont,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
                fontFamily = PretendardFont)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.38f),
            modifier = Modifier.size(22.dp))
    }
}
