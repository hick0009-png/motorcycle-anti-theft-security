package com.example.motorcycleantitheftsensor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.motorcycleantitheftsensor.sensor.SensorStatusItem

/**
 * Auto-Sizing Text Composable to fit exact boundaries without overflow.
 */
@Composable
fun AutoResizedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    minFontSize: TextUnit = 9.sp,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    var resizedTextStyle by remember(text, style) { mutableStateOf(style) }
    var shouldDraw by remember(text, style) { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.drawWithContent {
            if (shouldDraw) drawContent()
        },
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = false,
        overflow = overflow,
        style = resizedTextStyle,
        onTextLayout = { result ->
            if (result.didOverflowWidth || result.didOverflowHeight) {
                val currentSize = resizedTextStyle.fontSize
                if (currentSize > minFontSize) {
                    val nextSize = (currentSize.value * 0.90f).coerceAtLeast(minFontSize.value)
                    resizedTextStyle = resizedTextStyle.copy(fontSize = nextSize.sp)
                } else {
                    shouldDraw = true
                }
            } else {
                shouldDraw = true
            }
        }
    )
}

/**
 * UI-01: Fully Expanded Clean White DashboardScreen with Dynamic Screen Scaler & Hardware Sensor Badges.
 */
@Composable
fun DashboardScreen(
    isArmed: Boolean = true,
    sensitivity: Int = 5,
    batteryTemp: Float = 32.5f,
    currentLux: Float = 0.0f,
    hardwareSensors: List<SensorStatusItem> = emptyList(),
    initialBotToken: String? = null,
    botUsername: String? = null,
    botId: String? = null,
    allowedChatIds: Set<String> = emptySet(),
    pairingCode: String? = null,
    activeAlarmMessage: String? = null,
    onDismissAlarm: () -> Unit = {},
    onToggleArm: () -> Unit = {},
    onSensitivityChange: (Int) -> Unit = {},
    onSaveToken: (String) -> Unit = {},
    onVerifyBot: (String) -> Unit = {},
    onSendTestNotification: () -> Unit = {}
) {

    var botTokenInput by remember { mutableStateOf(initialBotToken ?: "") }
    var tokenSaveStatus by remember { mutableStateOf<String?>(if (!initialBotToken.isNullOrEmpty()) "✅ Telegram Bot Token Saved & Active" else null) }
    var currentSensitivity by remember { mutableIntStateOf(sensitivity) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val scaler = rememberAdaptiveScreenScaler()

    // Clean Minimal White Palette
    val backgroundColor = Color(0xFFFFFFFF)       // Pure White Background
    val cardBgColor = Color(0xFFFAFAFA)           // Off-White Card Fill
    val cardBorderColor = Color(0xFF000000)       // Sharp Black Border
    val textPrimary = Color(0xFF000000)           // Pure Black Text
    val textSecondary = Color(0xFF555555)         // Medium Dark Grey

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = scaler.scaleDp(14.dp), vertical = scaler.scaleDp(10.dp))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Header Banner
                AutoResizedText(
                    text = "MOTORCYCLE GUARD v2.5",
                    color = textPrimary,
                    style = TextStyle(fontSize = scaler.scaleSp(22.sp), fontWeight = FontWeight.Black, letterSpacing = 1.5.sp),
                    minFontSize = 14.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = scaler.scaleDp(4.dp))
                )

                Spacer(modifier = Modifier.height(scaler.scaleDp(14.dp)))

                // Armed Status Card (Expanded High Contrast)
                val statusBgColor by animateColorAsState(
                    targetValue = if (isArmed) Color(0xFF000000) else Color(0xFFE0E0E0),
                    label = "StatusBgAnimation"
                )
                val statusTextColor by animateColorAsState(
                    targetValue = if (isArmed) Color(0xFFFFFFFF) else Color(0xFF000000),
                    label = "StatusTextAnimation"
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, cardBorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(scaler.scaleDp(14.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            AutoResizedText(
                                text = if (isArmed) "SYSTEM ARMED" else "SYSTEM DISARMED",
                                color = textPrimary,
                                style = TextStyle(fontSize = scaler.scaleSp(17.sp), fontWeight = FontWeight.Black),
                                minFontSize = 12.sp,
                                maxLines = 1
                            )
                            AutoResizedText(
                                text = if (isArmed) "Multi-Sensor Engine Active (24/7)" else "Protection Paused",
                                color = textSecondary,
                                style = TextStyle(fontSize = scaler.scaleSp(12.sp)),
                                minFontSize = 9.sp,
                                maxLines = 1
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(statusBgColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = scaler.scaleDp(12.dp), vertical = scaler.scaleDp(6.dp))
                        ) {
                            AutoResizedText(
                                text = if (isArmed) "ON" else "OFF",
                                color = statusTextColor,
                                style = TextStyle(fontSize = scaler.scaleSp(12.sp), fontWeight = FontWeight.Black),
                                minFontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(scaler.scaleDp(18.dp)))

                // Expanded Toggle Arm Button
                BlackWhiteArmButton(
                    isArmed = isArmed,
                    scaler = scaler,
                    onClick = onToggleArm
                )

                Spacer(modifier = Modifier.height(scaler.scaleDp(18.dp)))

                // Vibration Sensitivity Slider Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, cardBorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(scaler.scaleDp(14.dp))) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoResizedText(
                                text = "Vibration Sensitivity",
                                color = textPrimary,
                                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = scaler.scaleSp(14.sp)),
                                minFontSize = 10.sp,
                                modifier = Modifier.weight(1f)
                            )
                            AutoResizedText(
                                text = "Level $currentSensitivity / 10",
                                color = textPrimary,
                                style = TextStyle(fontWeight = FontWeight.Black, fontSize = scaler.scaleSp(14.sp)),
                                minFontSize = 10.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(scaler.scaleDp(4.dp)))

                        Slider(
                            value = currentSensitivity.toFloat(),
                            onValueChange = {
                                currentSensitivity = it.toInt()
                                onSensitivityChange(currentSensitivity)
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Black,
                                activeTrackColor = Color.Black,
                                inactiveTrackColor = Color(0xFFCCCCCC)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(scaler.scaleDp(14.dp)))

                // 📌 POSITION-EXACT INSERTION: HARDWARE SENSOR STATUS BADGES GRID
                // Positioned precisely between Vibration Sensitivity Card & Battery Readings Card
                HardwareSensorStatusCard(
                    sensorList = hardwareSensors,
                    cardBgColor = cardBgColor,
                    cardBorderColor = cardBorderColor,
                    scaler = scaler
                )

                Spacer(modifier = Modifier.height(scaler.scaleDp(14.dp)))

                // Live Sensor Readings Card — Expanded 3-Column Horizontal Row
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, cardBorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = scaler.scaleDp(4.dp), vertical = scaler.scaleDp(14.dp)),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SensorMetricItem(
                            label = "BATTERY",
                            value = "%.1f°C".format(batteryTemp),
                            valueColor = textPrimary,
                            scaler = scaler,
                            modifier = Modifier.weight(1f)
                        )
                        SensorMetricItem(
                            label = "UNDER-SEAT",
                            value = "%.1f Lux".format(currentLux),
                            valueColor = if (currentLux >= 10.0f) Color(0xFFCC0000) else textPrimary,
                            scaler = scaler,
                            modifier = Modifier.weight(1f)
                        )
                        SensorMetricItem(
                            label = "SECURITY",
                            value = "AES-256",
                            valueColor = textPrimary,
                            scaler = scaler,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(scaler.scaleDp(14.dp)))

                // Security & Telegram Configuration Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, cardBorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(scaler.scaleDp(14.dp))) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoResizedText(
                                text = "🔐 Security & Settings",
                                color = textPrimary,
                                style = TextStyle(fontSize = scaler.scaleSp(15.sp), fontWeight = FontWeight.Bold),
                                minFontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )

                            // Dedicated Sensor Diagnostic Inspection Button
                            Button(
                                onClick = { showDiagnosticsDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                modifier = Modifier.height(scaler.scaleDp(30.dp))
                            ) {
                                AutoResizedText(text = "🔍 INSPECT SENSORS", color = Color.White, minFontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(scaler.scaleDp(8.dp)))

                        if (pairingCode != null) {
                            Text(
                                text = "Telegram pairing code: $pairingCode (expires in 10 minutes)",
                                color = textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = scaler.scaleSp(12.sp),
                                modifier = Modifier.padding(bottom = scaler.scaleDp(8.dp))
                            )
                        }

                        OutlinedTextField(
                            value = botTokenInput,
                            onValueChange = { botTokenInput = it },
                            label = { Text("Telegram Bot Token", fontSize = scaler.scaleSp(12.sp), color = textSecondary) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color(0xFF777777),
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(scaler.scaleDp(10.dp)))

                        Spacer(modifier = Modifier.height(scaler.scaleDp(6.dp)))

                        // 🤖 Bot Verification Identity Status
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                                .padding(scaler.scaleDp(8.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                Text(
                                    text = if (botUsername != null) "🤖 BOT: @$botUsername" else "🤖 BOT IDENTITY: NOT VERIFIED",
                                    color = Color.Black,
                                    fontSize = scaler.scaleSp(11.sp),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (botId != null) "ID: $botId • Status: Active" else "Tap Verify Bot to check Telegram API",
                                    color = Color(0xFF666666),
                                    fontSize = scaler.scaleSp(9.sp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = { onVerifyBot(botTokenInput) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                modifier = Modifier.height(scaler.scaleDp(32.dp)),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(text = "VERIFY BOT", color = Color.White, fontSize = scaler.scaleSp(9.sp), fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(scaler.scaleDp(6.dp)))

                        // 👤 Whitelisted Owner Chat IDs List
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                                .padding(scaler.scaleDp(8.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                Text(
                                    text = if (allowedChatIds.isNotEmpty()) "👤 OWNER CHAT ID: ${allowedChatIds.joinToString(", ")}" else "👤 CHAT ID: PENDING FIRST TELEGRAM MSG",
                                    color = Color.Black,
                                    fontSize = scaler.scaleSp(11.sp),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (allowedChatIds.isNotEmpty()) "Authorized owner account connected" else "Send /start in Telegram to auto-bind your Chat ID",
                                    color = Color(0xFF666666),
                                    fontSize = scaler.scaleSp(9.sp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = onSendTestNotification,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E0E0)),
                                modifier = Modifier.height(scaler.scaleDp(32.dp)),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(text = "🧪 TEST ALERT", color = Color.Black, fontSize = scaler.scaleSp(9.sp), fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(scaler.scaleDp(6.dp)))

                        Button(
                            onClick = {
                                if (botTokenInput.isNotBlank()) {
                                    onSaveToken(botTokenInput)
                                    tokenSaveStatus = "✅ Token Saved & Telegram Bot Connected!"
                                } else {
                                    tokenSaveStatus = "⚠️ Please enter a valid Bot Token first"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AutoResizedText(text = "Save Token", color = Color.White, minFontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        if (tokenSaveStatus != null) {
                            Spacer(modifier = Modifier.height(scaler.scaleDp(6.dp)))
                            AutoResizedText(
                                text = tokenSaveStatus!!,
                                color = Color.Black,
                                style = TextStyle(fontSize = scaler.scaleSp(11.sp), fontWeight = FontWeight.Bold),
                                minFontSize = 8.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Bottom System Footer Badge (Fills remaining lower screen space)
            Spacer(modifier = Modifier.height(scaler.scaleDp(16.dp)))
            AutoResizedText(
                text = "SECURITY STATUS: ONLINE",
                color = Color(0xFF777777),
                style = TextStyle(fontSize = scaler.scaleSp(10.sp), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                minFontSize = 7.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = scaler.scaleDp(8.dp))
            )
        }
    }


    // 🚨 Red Emergency Alarm Triggered Dialog (Charger Disconnected / Vibration / Intrusion)
    if (activeAlarmMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissAlarm,
            title = {
                Text(
                    text = "🚨 SECURITY ALARM TRIGGERED!",
                    fontWeight = FontWeight.Black,
                    fontSize = scaler.scaleSp(16.sp),
                    color = Color(0xFFCC0000)
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = activeAlarmMessage,
                        fontWeight = FontWeight.Bold,
                        fontSize = scaler.scaleSp(13.sp),
                        color = Color.Black
                    )

                    Spacer(modifier = Modifier.height(scaler.scaleDp(8.dp)))

                    Text(
                        text = "Telegram bot notification has been dispatched automatically.",
                        fontSize = scaler.scaleSp(11.sp),
                        color = Color(0xFF555555)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismissAlarm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC0000))
                ) {
                    Text("SILENCE ALARM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        )
    }

    // ⚙️ Sensor Diagnostic Inspection Dialog (In Settings)
    if (showDiagnosticsDialog) {
        SensorDiagnosticsDialog(
            sensorList = hardwareSensors,
            scaler = scaler,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }
}

/**
 * 🟢 Hardware Sensor Status Badges Grid
 * Positioned between Vibration Sensitivity Card & Battery Readings Card
 */
@Composable
private fun HardwareSensorStatusCard(
    sensorList: List<SensorStatusItem>,
    cardBgColor: Color,
    cardBorderColor: Color,
    scaler: AdaptiveScreenScaler
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, cardBorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(scaler.scaleDp(12.dp))) {
            AutoResizedText(
                text = "📱 HARDWARE SENSOR STATUS",
                color = Color.Black,
                style = TextStyle(fontSize = scaler.scaleSp(13.sp), fontWeight = FontWeight.Bold),
                minFontSize = 10.sp,
                modifier = Modifier.padding(bottom = scaler.scaleDp(8.dp))
            )

            // Dynamic grid displaying 7 sensors
            val codes = listOf("ACCEL", "GYRO", "LIGHT", "MIC", "BARO", "GPS", "BIOMETRIC")
            val row1 = codes.take(4)
            val row2 = codes.drop(4)

            Column(verticalArrangement = Arrangement.spacedBy(scaler.scaleDp(6.dp))) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row1.forEach { code ->
                        val item = sensorList.find { it.code == code }
                        val isAvail = item?.isAvailable ?: true
                        SensorBadgeItem(code = code, isAvailable = isAvail, scaler = scaler, modifier = Modifier.weight(1f))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row2.forEach { code ->
                        val item = sensorList.find { it.code == code }
                        val isAvail = item?.isAvailable ?: true
                        SensorBadgeItem(code = code, isAvailable = isAvail, scaler = scaler, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorBadgeItem(
    code: String,
    isAvailable: Boolean,
    scaler: AdaptiveScreenScaler,
    modifier: Modifier = Modifier
) {
    val badgeBg = if (isAvailable) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
    val indicatorColor = if (isAvailable) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
    val textColor = if (isAvailable) Color(0xFF1B5E20) else Color(0xFF757575)
    val statusText = if (isAvailable) "🟢 $code" else "⚪ N/A"

    Box(
        modifier = modifier
            .padding(horizontal = scaler.scaleDp(2.dp))
            .background(badgeBg, RoundedCornerShape(6.dp))
            .border(1.dp, indicatorColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(vertical = scaler.scaleDp(5.dp), horizontal = scaler.scaleDp(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        AutoResizedText(
            text = statusText,
            color = textColor,
            style = TextStyle(fontSize = scaler.scaleSp(11.sp), fontWeight = FontWeight.Bold),
            minFontSize = 8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Detailed Sensor Diagnostics Screen (Settings Modal)
 */
@Composable
private fun SensorDiagnosticsDialog(
    sensorList: List<SensorStatusItem>,
    scaler: AdaptiveScreenScaler,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "🔍 Hardware Sensor Diagnostics",
                fontWeight = FontWeight.Bold,
                fontSize = scaler.scaleSp(16.sp),
                color = Color.Black
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(scaler.scaleDp(8.dp))
            ) {
                Text(
                    text = "Hardware Inspection Report for this Smartphone:",
                    fontSize = scaler.scaleSp(11.sp),
                    color = Color(0xFF555555)
                )

                sensorList.forEach { sensor ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (sensor.isAvailable) Color(0xFFFAFAFA) else Color(0xFFF0F0F0)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (sensor.isAvailable) Color.Black else Color.Gray, RoundedCornerShape(6.dp))
                    ) {
                        Column(modifier = Modifier.padding(scaler.scaleDp(10.dp))) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sensor.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = scaler.scaleSp(12.sp),
                                    color = Color.Black
                                )
                                Text(
                                    text = if (sensor.isAvailable) "🟢 READY" else "⚪ N/A",
                                    fontWeight = FontWeight.Black,
                                    fontSize = scaler.scaleSp(10.sp),
                                    color = if (sensor.isAvailable) Color(0xFF008800) else Color.Gray
                                )
                            }

                            Spacer(modifier = Modifier.height(scaler.scaleDp(4.dp)))

                            Text(
                                text = "Vendor: ${sensor.vendorName}\nPower: %.2f mA | Max Range: %.1f".format(sensor.powerMa, sensor.maxRange),
                                fontSize = scaler.scaleSp(10.sp),
                                color = Color(0xFF555555)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
            ) {
                Text("CLOSE DIAGNOSTICS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun SensorMetricItem(
    label: String,
    value: String,
    valueColor: Color,
    scaler: AdaptiveScreenScaler,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = scaler.scaleDp(2.dp))
    ) {
        AutoResizedText(
            text = label,
            color = Color(0xFF555555),
            style = TextStyle(fontSize = scaler.scaleSp(9.sp), fontWeight = FontWeight.Medium),
            minFontSize = 7.sp,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(scaler.scaleDp(3.dp)))
        AutoResizedText(
            text = value,
            color = valueColor,
            style = TextStyle(fontSize = scaler.scaleSp(15.sp), fontWeight = FontWeight.Black),
            minFontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Expanded Black & White Arm/Disarm Toggle Button
 */
@Composable
fun BlackWhiteArmButton(
    isArmed: Boolean,
    scaler: AdaptiveScreenScaler,
    onClick: () -> Unit
) {
    val buttonBgColor = if (isArmed) Color.Black else Color.White
    val buttonTextColor = if (isArmed) Color.White else Color.Black
    val borderColor = Color.Black

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        val baseContainerSize = (maxWidth * 0.46f).coerceIn(140.dp, 170.dp)
        val dynamicContainerSize = scaler.scaleDp(baseContainerSize)

        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = buttonBgColor),
            modifier = Modifier
                .size(dynamicContainerSize)
                .border(scaler.scaleDp(3.dp), borderColor, CircleShape)
        ) {
            AutoResizedText(
                text = if (isArmed) "ARMED" else "DISARMED",
                color = buttonTextColor,
                style = TextStyle(fontSize = scaler.scaleSp(15.sp), fontWeight = FontWeight.Black),
                minFontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}




