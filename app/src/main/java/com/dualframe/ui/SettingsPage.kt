package com.dualframe.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.rounded.CameraSwitch
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A))
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ArrowBack, null, tint = Color.White,
                modifier = Modifier.size(28.dp).clickable { onBack() }.padding(4.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_title), color = Color.White, fontSize = 20.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))

        // PRO Card
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1A1A))
                .clickable {
                    if (isPro) {
                        android.widget.Toast.makeText(context,
                            context.getString(R.string.settings_pro_active_msg),
                            android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        showProSheet = true
                    }
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.WorkspacePremium, null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_pro_title), color = Color.White, fontSize = 18.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isPro) stringResource(R.string.settings_pro_active)
                    else stringResource(R.string.settings_pro_upgrade),
                    color = Color(0xFF888888), fontSize = 13.sp,
                    fontFamily = PretendardFont)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isPro) Color(0xFF4CAF50).copy(alpha = 0.15f)
                        else Color(0xFF4CAF50)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    if (isPro) stringResource(R.string.settings_pro_active)
                    else stringResource(R.string.settings_pro_upgrade),
                    color = if (isPro) Color(0xFF4CAF50) else Color.White,
                    fontSize = 13.sp, fontFamily = PretendardFont,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null,
                tint = Color.White.copy(alpha = 0.54f),
                modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.height(24.dp))

        SettingsCard {
            SettingsRow(
                icon = Icons.Rounded.CameraSwitch,
                title = stringResource(R.string.settings_default_camera),
                value = if (settings.defaultFrontCamera) stringResource(R.string.settings_camera_front)
                        else stringResource(R.string.settings_camera_rear),
                onClick = { showCameraDialog = true },
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.SaveAlt,
                title = stringResource(R.string.settings_auto_save),
                value = if (settings.autoSave) "ON" else "OFF",
                onClick = { showAutoSavePage = true },
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.settings_language),
                value = when (settings.appLanguage) {
                    "en" -> stringResource(R.string.settings_language_english)
                    else -> stringResource(R.string.settings_language_system)
                },
                onClick = { showLanguageDialog = true },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsCard {
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
            SettingsDivider()
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
        }

        Spacer(Modifier.height(24.dp))

        // Version
        Text("DualFrame ${BuildConfig.VERSION_NAME}", color = Color(0xFF555555), fontSize = 12.sp,
            fontFamily = PretendardFont,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

    // Camera selection dialog
    if (showCameraDialog) {
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
                CameraOption(stringResource(R.string.settings_camera_rear), !settings.defaultFrontCamera) {
                    onSettingsChange(settings.copy(defaultFrontCamera = false))
                    showCameraDialog = false
                }
                Spacer(Modifier.height(8.dp))
                CameraOption(stringResource(R.string.settings_camera_front), settings.defaultFrontCamera) {
                    onSettingsChange(settings.copy(defaultFrontCamera = true))
                    showCameraDialog = false
                }
            }
        }
    }

    // Language selection dialog
    if (showLanguageDialog) {
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
                CameraOption(stringResource(R.string.settings_language_system), settings.appLanguage == "system") {
                    applyLanguage(context, settings, "system", onSettingsChange)
                    showLanguageDialog = false
                }
                Spacer(Modifier.height(8.dp))
                CameraOption(stringResource(R.string.settings_language_english), settings.appLanguage == "en") {
                    applyLanguage(context, settings, "en", onSettingsChange)
                    showLanguageDialog = false
                }
            }
        }
    }

    if (showProSheet) {
        ProUpgradeSheet(
            context = context,
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
    (context as? Activity)?.recreate()
}

@Composable
private fun CameraOption(label: String, selected: Boolean, onClick: () -> Unit) {
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
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A))
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ArrowBack, null, tint = Color.White,
                modifier = Modifier.size(28.dp).clickable { onBack() }.padding(4.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_auto_save), color = Color.White, fontSize = 20.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))

        // Auto save toggle card
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1A1A))
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WorkspacePremium, null, tint = Color(0xFFD4A828),
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("PRO", color = Color(0xFFD4A828), fontSize = 14.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_auto_save_pro_only), color = Color(0xFFAAAAAA), fontSize = 13.sp,
                    fontFamily = PretendardFont)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF4CAF50))
                        .clickable { onShowPro() }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.settings_view_pro), color = Color.White, fontSize = 15.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
                }
            }
        } else if (settings.autoSave) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
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
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_auto_save_battery), color = Color(0xFF666666), fontSize = 12.sp,
                fontFamily = PretendardFont, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

// ── PRO Upgrade Bottom Sheet ──

@Composable
fun ProUpgradeSheet(
    context: Context,
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
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF141414))
                .clickable(enabled = false) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Crown icon
            Icon(Icons.Outlined.WorkspacePremium, null, tint = Color(0xFF4CAF50),
                modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.pro_sheet_title), color = Color.White, fontSize = 22.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(20.dp))

            // Benefits
            ProBenefitRow(Icons.Outlined.WorkspacePremium,
                stringResource(R.string.pro_sheet_benefit1_title),
                stringResource(R.string.pro_sheet_benefit1_desc))
            Spacer(Modifier.height(12.dp))
            ProBenefitRow(Icons.Outlined.Star,
                stringResource(R.string.pro_sheet_benefit2_title),
                stringResource(R.string.pro_sheet_benefit2_desc))
            Spacer(Modifier.height(12.dp))
            ProBenefitRow(Icons.Outlined.SaveAlt,
                stringResource(R.string.pro_sheet_benefit3_title),
                stringResource(R.string.pro_sheet_benefit3_desc))

            Spacer(Modifier.height(24.dp))

            // Buy button
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF4CAF50))
                    .clickable {
                        val activity = context as? Activity
                        if (activity != null) {
                            BillingManager.getInstance(context)
                                .launchPurchase(activity) { success ->
                                    if (success) onPurchased()
                                }
                        }
                    }
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (price != null) "${stringResource(R.string.pro_sheet_buy)}  $price"
                    else stringResource(R.string.pro_sheet_buy),
                    color = Color.White, fontSize = 17.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.pro_sheet_later), color = Color(0xFF888888), fontSize = 14.sp,
                fontFamily = PretendardFont,
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
        }
    }
}

@Composable
private fun ProBenefitRow(icon: ImageVector, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Color(0xFF888888), fontSize = 13.sp,
                fontFamily = PretendardFont)
        }
    }
}

// ── Shared Components ──

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1A1A)),
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, fontSize = 16.sp, fontFamily = PretendardFont,
            modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, color = Color(0xFF4CAF50), fontSize = 14.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.54f),
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .height(0.5.dp).background(Color(0xFF2A2A2A))
    )
}
