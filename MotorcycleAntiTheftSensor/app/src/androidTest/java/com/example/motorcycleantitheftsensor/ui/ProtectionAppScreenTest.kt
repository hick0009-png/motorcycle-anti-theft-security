package com.example.motorcycleantitheftsensor.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorReadingSummary

class ProtectionAppScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shellHasExactlyThreePrimaryDestinationsAndNoDemoControls() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }

        compose.onNodeWithText("ปกป้อง").assertExists()
        compose.onNodeWithText("เหตุการณ์").assertExists()
        compose.onNodeWithText("ตั้งค่า").assertExists()
        compose.onAllNodes(hasTestTag("primary_destination")).assertCountEquals(3)
        compose.onAllNodes(hasText("Demo", substring = true, ignoreCase = true))
            .assertCountEquals(0)
    }

    @Test
    fun selectedPrimaryDestinationRendersWithNavyAndWhitePixels() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }

        val pixels = compose.onAllNodes(hasTestTag("primary_destination"))[0]
            .captureToImage()
            .toPixelMap()
        var hasNavyPixel = false
        var hasNearWhitePixel = false

        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.alpha < OPAQUE_ALPHA) continue

                hasNavyPixel = hasNavyPixel || isCloseToNavy(color)
                hasNearWhitePixel = hasNearWhitePixel || isNearWhite(color)
            }
        }

        assertTrue("Selected destination must render a navy surface", hasNavyPixel)
        assertTrue("Selected destination must render a white indicator/content", hasNearWhitePixel)
    }

    @Test
    fun protectionHasOneStateCorrectPrimaryAction() {
        compose.setContent { ProtectionAppScreen(disarmedState(), fakeActions()) }

        compose.onAllNodes(hasText("เปิดระบบป้องกัน")).assertCountEquals(1)
        compose.onAllNodes(hasText("ปิดระบบป้องกัน")).assertCountEquals(0)
        compose.onNodeWithText("เปิดระบบป้องกัน").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun protectionStartsWithMotoGuardHeaderAndOutcomeCard() {
        compose.setContent { ProtectionAppScreen(disarmedState(), fakeActions()) }

        compose.onNodeWithText("Moto Guard").assertIsDisplayed()
        compose.onNodeWithText("สถานะการปกป้อง").assertIsDisplayed()
        compose.onNodeWithText("การป้องกันปิดอยู่").assertIsDisplayed()
        compose.onAllNodes(hasText("เปิดระบบป้องกัน")).assertCountEquals(1)
    }

    @Test
    fun heroShowsOutcomeFirstThaiCopyWithoutEnglishFragmentsForEachState() {
        listOf(
            ProtectionState.DISARMED_ONLINE to "การป้องกันปิดอยู่",
            ProtectionState.ARMING to "กำลังเปิดการป้องกัน",
            ProtectionState.ARMED_HEALTHY to "การป้องกันทำงานปกติ",
            ProtectionState.ARMED_DEGRADED to "การป้องกันทำงานแบบจำกัด",
            ProtectionState.ALERT_ACTIVE to "กำลังส่งสัญญาณเตือนภัย",
            ProtectionState.OFFLINE to "ระบบออฟไลน์",
        ).forEach { (protectionState, expectedHeroTitle) ->
            compose.setContent { ProtectionAppScreen(baseState(protectionState), fakeActions()) }

            compose.onNodeWithText(expectedHeroTitle, substring = true).assertExists()
            compose.onAllNodes(hasText("(Arm)", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("(Disarm)", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("Armed in", substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun primaryNavigationReservesEnoughHeightForIconsAndLabels() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }

        compose.onNodeWithTag("primary_navigation").assertHeightIsAtLeast(80.dp)
        repeat(3) { index ->
            compose.onAllNodes(hasTestTag("primary_destination"))[index]
                .assertHeightIsAtLeast(80.dp)
        }
    }

    @Test
    fun everySelectedDestinationHasStableVisibleLabelAndIconBounds() {
        showWithLocalNavigation(healthyState())

        listOf(
            "PROTECTION" to "แท็บปกป้อง",
            "EVENTS" to "แท็บเหตุการณ์",
            "SETTINGS" to "แท็บตั้งค่า",
        ).forEach { (enumName, contentDescription) ->
            val labelTag = "primary_destination_label_$enumName"
            compose.onNodeWithTag(labelTag, useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription(contentDescription)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(80.dp)
            compose.onNodeWithTag(labelTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(16.dp)
            compose.onNodeWithTag(
                "primary_destination_icon_$enumName",
                useUnmergedTree = true,
            ).assertHeightIsEqualTo(24.dp)
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("การวินิจฉัยขั้นสูง"))
        listOf(
            "PROTECTION" to "แท็บปกป้อง",
            "EVENTS" to "แท็บเหตุการณ์",
            "SETTINGS" to "แท็บตั้งค่า",
        ).forEach { (enumName, contentDescription) ->
            compose.onNodeWithContentDescription(contentDescription)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag(
                "primary_destination_label_$enumName",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            compose.onNodeWithTag(
                "primary_destination_icon_$enumName",
                useUnmergedTree = true,
            ).assertIsDisplayed()
        }
    }

    @Test
    fun missingMicrophoneIsShownAsReducedCoverageWithSettingsAction() {
        var selectedDestination: ProtectionDestination? = null
        val state = baseState(ProtectionState.OFFLINE).copy(
            settings = baseState(ProtectionState.OFFLINE).settings.copy(
                missingPermissions = setOf("android.permission.RECORD_AUDIO"),
            ),
        )
        compose.setContent {
            ProtectionAppScreen(
                state,
                fakeActions().copy(selectDestination = { selectedDestination = it }),
            )
        }

        compose.onNodeWithText(
            "ยังไม่ได้ให้สิทธิ์ไมโครโฟน การตรวจจับเสียงผิดปกติจะใช้ไม่ได้",
        ).assertExists()
        compose.onNodeWithText("การตรวจจับที่ลดลง").assertExists()
        compose.onNodeWithText("สิ่งที่ยังขาดก่อนป้องกันได้").assertDoesNotExist()
        compose.onNodeWithText("ตรวจสอบสิทธิ์").performClick()
        compose.runOnIdle {
            assertEquals(ProtectionDestination.SETTINGS, selectedDestination)
        }
    }

    @Test
    fun settingsDistinguishesReducedCoverageFromBlockingPermissions() {
        val audioPermission = "android.permission.RECORD_AUDIO"
        val notificationPermission = "android.permission.POST_NOTIFICATIONS"
        val state = baseState(ProtectionState.SETUP_REQUIRED).copy(
            destination = ProtectionDestination.SETTINGS,
            protection = baseState(ProtectionState.SETUP_REQUIRED).protection.copy(
                permissionBlockers = setOf("POST_NOTIFICATIONS"),
            ),
            settings = baseState(ProtectionState.SETUP_REQUIRED).settings.copy(
                missingPermissions = setOf(audioPermission, notificationPermission),
            ),
        )
        compose.setContent { ProtectionAppScreen(state, fakeActions()) }

        compose.onNodeWithText("⛔ ปิดกั้นการทำงานหลัก").assertExists()
        compose.onNodeWithText("การแจ้งเตือน").assertExists()
        compose.onNodeWithText("⚠️ ลดความครอบคลุม").assertExists()
        compose.onNodeWithText("ไมโครโฟน").assertExists()
    }

    @Test
    fun settingsNeverDisplaysStoredCredential() {
        showWithLocalNavigation(configuredSettingsState())

        openSettings()

        compose.onNode(hasText("ตั้งค่าแล้ว", substring = true)).assertExists()
        compose.onNodeWithText(TEST_ONLY_TOKEN).assertDoesNotExist()
    }

    @Test
    fun settingsSecretsDoNotSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val tokenField = hasSetTextAction() and hasText("เปลี่ยน Bot Token ใหม่")
        compose.onNode(tokenField).performTextInput(UNSAVED_TOKEN)
        compose.onNode(hasScrollAction()).performScrollToNode(
            hasText("คีย์เข้ารหัส SMS (Encryption Key)"),
        )
        val smsKeyField = hasSetTextAction() and hasText("คีย์เข้ารหัส SMS (Encryption Key)")
        compose.onNode(smsKeyField).performTextInput(UNSAVED_SMS_KEY)

        restoration.emulateSavedInstanceStateRestore()

        compose.onAllNodes(hasText(UNSAVED_TOKEN, substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText(UNSAVED_SMS_KEY, substring = true)).assertCountEquals(0)
    }

    @Test
    fun savingBotTokenClearsTextFieldFocus() {
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val tokenField = hasSetTextAction() and hasText("เปลี่ยน Bot Token ใหม่")
        compose.onNode(tokenField).performTextInput(UNSAVED_TOKEN)
        compose.onNodeWithText("บันทึก Bot Token").performClick()

        compose.onNode(tokenField).assertIsNotFocused()
    }

    @Test
    fun smsFallbackExplainsRealIncidentEligibility() {
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val copy =
            "SMS Fallback จะทำงานเฉพาะเมื่อเหตุการณ์วิกฤต (CRITICAL_BREACH) และการส่ง Telegram ล้มเหลวเท่านั้น (ไม่ส่งพิกัด GPS เพื่อความปลอดภัย)"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(copy))
        compose.onNodeWithText(copy).assertExists()
    }

    @Test
    fun eventsClearRequiresConfirmation() {
        var clearCalls = 0
        val actions = fakeActions().copy(clearHistory = { clearCalls += 1 })
        showWithLocalNavigation(historyState(), actions)

        openEvents()
        compose.onNodeWithText("ล้างประวัติ").performClick()
        assertEquals(0, clearCalls)
        compose.onNodeWithText("ยืนยันการล้างประวัติ").assertExists()
        compose.onNodeWithText("ยกเลิก").performClick()
        assertEquals(0, clearCalls)
        compose.onNodeWithText("ล้างประวัติ").performClick()
        compose.onNodeWithText("ยืนยัน").performClick()
        assertEquals(1, clearCalls)
    }

    @Test
    fun populatedEventsShowTimelineHeadingWithoutEmojiTitles() {
        compose.setContent {
            ProtectionAppScreen(
                historyState().copy(destination = ProtectionDestination.EVENTS),
                fakeActions(),
            )
        }

        compose.onNodeWithText("เหตุการณ์ล่าสุด").assertIsDisplayed()
        compose.onNodeWithText("รถอาจถูกเคลื่อนย้าย").assertIsDisplayed()
        compose.onAllNodes(hasText("🚨", substring = true)).assertCountEquals(0)
    }

    @Test
    fun degradedStateIsReadableWithoutColor() {
        compose.setContent {
            ProtectionAppScreen(degradedState("VIBRATION not healthy"), fakeActions())
        }

        compose.onNodeWithText("การป้องกันทำงานแบบจำกัด").assertExists()
        compose.onNodeWithText("VIBRATION not healthy").assertExists()
    }

    @Test
    fun eventsLoadingStateIsExplicit() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.ARMED_HEALTHY).copy(
                    destination = ProtectionDestination.EVENTS,
                    eventsLoading = true,
                ),
                fakeActions(),
            )
        }

        compose.onNodeWithText("กำลังโหลดเหตุการณ์").assertExists()
        compose.onAllNodes(hasText("Loading events", substring = true)).assertCountEquals(0)
    }

    @Test
    fun eventsErrorIsRetryable() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.ARMED_HEALTHY).copy(
                    destination = ProtectionDestination.EVENTS,
                    eventsError = "History unavailable",
                ),
                fakeActions(),
            )
        }
        compose.onNodeWithText("History unavailable").assertExists()
        compose.onNodeWithText("ลองใหม่").assertExists()
    }

    @Test
    fun settingsInitialLoadDoesNotShowUnconfiguredDefaults() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.DISARMED_ONLINE).copy(
                    destination = ProtectionDestination.SETTINGS,
                    settingsLoading = true,
                    settingsLoaded = false,
                ),
                fakeActions(),
            )
        }

        compose.onNodeWithText("กำลังโหลดการตั้งค่า...").assertExists()
        compose.onNodeWithText("ยังไม่ตั้งค่า ⚠️").assertDoesNotExist()
    }

    @Test
    fun settingsLoadFailureOffersRetry() {
        var retrySettingsCalls = 0
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.DISARMED_ONLINE).copy(
                    destination = ProtectionDestination.SETTINGS,
                    settingsLoaded = false,
                    settingsError = "Settings unavailable",
                ),
                fakeActions().copy(retrySettings = { retrySettingsCalls += 1 }),
            )
        }

        compose.onNodeWithText("Settings unavailable").assertExists()
        compose.onNodeWithText("ลองใหม่อีกครั้ง").performClick()
        compose.runOnIdle { assertEquals(1, retrySettingsCalls) }
    }

    @Test
    fun pairingResetRequiresConfirmation() {
        var resetPairingCalls = 0
        compose.setContent {
            ProtectionAppScreen(
                configuredSettingsState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions().copy(resetPairing = { resetPairingCalls += 1 }),
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(
            hasText("รีเซ็ตการจับคู่เจ้าของ (Reset Pairing)"),
        )
        compose.onNodeWithText("รีเซ็ตการจับคู่เจ้าของ (Reset Pairing)").performClick()
        compose.runOnIdle { assertEquals(0, resetPairingCalls) }
        compose.onNodeWithText("ยืนยันรีเซ็ต").performClick()
        compose.runOnIdle { assertEquals(1, resetPairingCalls) }
    }

    @Test
    fun shellShowsOneShotMessageOnSettingsAndConsumesItsId() {
        var consumedMessageId: Long? = null
        compose.setContent {
            ProtectionAppScreen(
                configuredSettingsState().copy(
                    destination = ProtectionDestination.SETTINGS,
                    message = ProtectionUiMessage(
                        id = 42L,
                        content = com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                            titleTh = "Pairing reset",
                            bodyTh = "Pairing reset",
                            telegramTh = null,
                            severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.INFO,
                            action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.NONE,
                            persistent = false,
                        ),
                    ),
                ),
                fakeActions().copy(consumeMessage = { consumedMessageId = it }),
            )
        }

        compose.onNodeWithText("Pairing reset").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000L) { consumedMessageId != null }
        compose.runOnIdle { assertEquals(42L, consumedMessageId) }
    }

    @Test
    fun eventsEmptyStateIsExplicit() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.ARMED_HEALTHY).copy(
                    destination = ProtectionDestination.EVENTS,
                ),
                fakeActions(),
            )
        }
        compose.onNode(hasText("ยังไม่มีเหตุการณ์") and isHeading()).assertExists()
        compose.onNodeWithText("เหตุการณ์จะแสดงที่นี่เมื่อระบบป้องกันบันทึกไว้").assertExists()
    }

    @Test
    fun disarmedStateShowsLiveSamplesAfterArming() {
        compose.setContent { ProtectionAppScreen(disarmedState(), fakeActions()) }
        openAdvancedDiagnostics()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("การสั่นสะเทือน"))
        compose.onAllNodes(hasText("การวัดสดจะเริ่มหลังเปิดระบบ"))[0].assertHeightIsAtLeast(10.dp)
    }

    @Test
    fun armedStateShowsTelemetryValues() {
        val armedState = healthyState().copy(
            protection = healthyState().protection.copy(
                sensorHealth = mapOf(
                    SensorKind.VIBRATION to SensorHealth(
                        state = SensorHealthState.HEALTHY,
                        lastSampleAtMs = TEST_TIMESTAMP_MS,
                        latestReading = SensorReadingSummary(9.8, "m/s²", "Acceleration")
                    )
                )
            )
        )
        compose.setContent { ProtectionAppScreen(armedState, fakeActions()) }
        openAdvancedDiagnostics()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Acceleration: 9.8 m/s²"))
        compose.onNodeWithText("Acceleration: 9.8 m/s²").assertExists()
    }

    @Test
    fun eventCardsRenderThaiLabelsWithoutRawEnumsOrEnglishPrefixes() {
        compose.setContent {
            ProtectionAppScreen(
                historyState().copy(destination = ProtectionDestination.EVENTS),
                fakeActions(),
            )
        }

        compose.onNodeWithText("รถอาจถูกเคลื่อนย้าย").assertExists()
        compose.onNodeWithText("แหล่งข้อมูล: เหตุการณ์จริง").assertExists()
        compose.onNodeWithText("ความรุนแรง: เตือนภัย").assertExists()
        compose.onNodeWithText("สถานะเหตุการณ์: สิ้นสุดแล้ว").assertExists()
        compose.onNodeWithText("หลักฐาน: ตรวจพบแรงสั่นต่อเนื่อง (2.5 m/s²)").assertExists()
        compose.onNodeWithText("การแจ้งเตือน: ส่งสำเร็จ").assertExists()
        listOf(
            "OPEN",
            "CLOSED",
            "INTERRUPTED",
            "PENDING",
            "SENT",
            "FAILED",
            "NOT_ELIGIBLE",
            "Source:",
            "Severity:",
            "Lifecycle:",
            "Evidence:",
            "Time:",
            "Delivery:",
            "REAL",
        ).forEach { fragment ->
            compose.onAllNodes(hasText(fragment, substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun eventsUseReadableLocalTimestampInsteadOfRawEpochMillis() {
        val state = historyState().copy(destination = ProtectionDestination.EVENTS)
        compose.setContent { ProtectionAppScreen(state, fakeActions()) }

        compose.onNodeWithText(
            "เวลา: ${formatProtectionTimestamp(TEST_TIMESTAMP_MS)}",
        ).assertExists()
        compose.onAllNodes(hasText(TEST_TIMESTAMP_MS.toString(), substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun protectionUsesReadableLocalTimestampInsteadOfRawEpochMillis() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }
        openAdvancedDiagnostics()

        compose.onNodeWithText(formatProtectionTimestamp(TEST_TIMESTAMP_MS)).assertExists()
        compose.onAllNodes(hasText(TEST_TIMESTAMP_MS.toString(), substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun settingsUseReadableLocalTimestampInsteadOfRawEpochMillis() {
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val timestamp = formatProtectionTimestamp(TEST_TIMESTAMP_MS)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(timestamp))
        compose.onNodeWithText(timestamp).assertExists()
        compose.onAllNodes(hasText(TEST_TIMESTAMP_MS.toString(), substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun sensitivityHasAccessibleNameStateAndMinimumTouchTarget() {
        var changedSensitivity: Int? = null
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions().copy(changeSensitivity = { changedSensitivity = it }),
            )
        }

        val slider = hasTestTag("sensitivity_slider") and
            hasContentDescription("ระดับการตรวจจับ 5 เต็ม 10")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("sensitivity_slider"))
        val sliderNode = compose.onNode(slider)
        val semanticsNode = sliderNode.fetchSemanticsNode()
        val density = compose.activity.resources.displayMetrics.density
        val visualHeightDp = semanticsNode.boundsInRoot.height / density
        val touchHeightDp = semanticsNode.touchBoundsInRoot.height / density
        assertTrue(
            "Visual height was $visualHeightDp dp; touch height was $touchHeightDp dp",
            visualHeightDp >= 48f && touchHeightDp >= 48f,
        )

        sliderNode.performTouchInput {
            click(Offset(center.x * 1.5f, 1f))
        }
        compose.runOnIdle {
            assertTrue("Expanded touch edge did not change sensitivity", changedSensitivity != null)
        }
    }

    @Test
    fun settingsRendersNoAuthenticatorOrQrControls() {
        showWithLocalNavigation(configuredSettingsState())

        openSettings()

        compose.onAllNodes(hasText("Authenticator", substring = true, ignoreCase = true))
            .assertCountEquals(0)
        compose.onAllNodes(hasText("Set up authenticator")).assertCountEquals(0)
        compose.onAllNodes(hasText("Replace authenticator")).assertCountEquals(0)
        compose.onAllNodes(hasText("Show QR code")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("authenticator_qr_code")).assertCountEquals(0)
    }

    @Test
    fun protectionScreenDisplaysTruthfulMicrophoneSensorHealth() {
        val state = baseState(ProtectionState.ARMED_HEALTHY).copy(
            protection = baseState(ProtectionState.ARMED_HEALTHY).protection.copy(
                sensorHealth = mapOf(
                    SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE),
                ),
            ),
        )
        showWithLocalNavigation(state)
        openAdvancedDiagnostics()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("ไมโครโฟนพร้อมใช้งาน"))
        compose.onNodeWithText("ไมโครโฟนพร้อมใช้งาน").assertExists()
    }

    @Test
    fun audioThreatRuntimeCardRendersWhenAudioIsActive() {
        val state = baseState(ProtectionState.ARMED_HEALTHY).copy(
            audio = AudioUiTelemetry(
                state = com.example.motorcycleantitheftsensor.protection.AudioRuntimeState.LISTENING,
                modelReady = true,
                approximateLevelDbfs = -32.5,
            ),
        )
        showWithLocalNavigation(state)
        openAdvancedDiagnostics()

        compose.onNodeWithTag(com.example.motorcycleantitheftsensor.ui.protection.AUDIO_RUNTIME_CARD_TAG).assertExists()
        compose.onNodeWithText("กำลังฟังเสียง").assertExists()
        compose.onAllNodes(hasText("listening", substring = true, ignoreCase = true))
            .assertCountEquals(0)
    }

    @Test
    fun diagnosticsStayBehindSingleAdvancedDisclosureUntilExpanded() {
        compose.setContent { ProtectionAppScreen(baseState(ProtectionState.DISARMED_ONLINE), fakeActions()) }

        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performScrollTo()
        compose.onNodeWithText("สถานะระบบ").assertDoesNotExist()
        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.AUDIO_RUNTIME_CARD_TAG,
        ).assertDoesNotExist()

        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performClick()

        compose.onNodeWithText("สถานะระบบ").assertExists()
    }

    @Test
    fun settingsHasAutomaticAudioDiagnosticsButNoManualMicrophoneTest() {
        showWithLocalNavigation(configuredSettingsState())
        openSettings()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("การวิเคราะห์เสียงคุกคาม"))
        compose.onNodeWithText("การวิเคราะห์เสียงคุกคาม").assertExists()
        compose.onNodeWithText("สถานะ Audio Runtime").assertExists()
        compose.onNodeWithText("โมเดลจำแนกเสียง (YamNet)").assertExists()
        compose.onAllNodes(hasText("Test Microphone", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Last Self-Test", substring = true)).assertCountEquals(0)
    }

    @Test
    fun settingsCategoryPagesOpenAndReturnToOverview() {
        showWithLocalNavigation(configuredSettingsState())
        openSettingsOverview()

        listOf(
            "การปกป้อง",
            "การแจ้งเตือนและความปลอดภัย",
            "ความต่อเนื่องของระบบ",
            "การวินิจฉัยขั้นสูง",
        ).forEach { category ->
            compose.onNodeWithText(category).performClick()
            compose.onNodeWithText(category).assertExists()
            compose.onNodeWithText("กลับไปหน้าตั้งค่า").performClick()
        }

        compose.onNodeWithText("การปกป้อง").performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("การแจ้งเตือนและความปลอดภัย").assertExists()
    }

    private fun showWithLocalNavigation(
        initialState: ProtectionUiState,
        initialActions: ProtectionAppActions = fakeActions(),
    ) {
        compose.setContent {
            var state by remember { mutableStateOf(initialState) }
            ProtectionAppScreen(
                state = state,
                actions = initialActions.copy(
                    selectDestination = { destination ->
                        state = state.copy(destination = destination)
                        initialActions.selectDestination(destination)
                    },
                ),
            )
        }
    }

    private fun openEvents() {
        compose.onNodeWithText("เหตุการณ์").performClick()
    }

    private fun openSettings() {
        openSettingsOverview()
        compose.onNodeWithText("การปกป้อง").performClick()
    }

    private fun openSettingsOverview() {
        compose.onNodeWithText("ตั้งค่า").performClick()
    }

    private fun openAdvancedDiagnostics() {
        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performScrollTo()
        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performClick()
    }
}

private fun healthyState(): ProtectionUiState = baseState(ProtectionState.ARMED_HEALTHY)

private fun disarmedState(): ProtectionUiState = baseState(ProtectionState.DISARMED_ONLINE)

private fun degradedState(reason: String): ProtectionUiState =
    baseState(ProtectionState.ARMED_DEGRADED).copy(
        protection = baseState(ProtectionState.ARMED_DEGRADED).protection.copy(
            degradationReasons = setOf(reason),
        ),
    )

private fun configuredSettingsState(): ProtectionUiState =
    baseState(ProtectionState.ARMED_HEALTHY).copy(
        settings = baseState(ProtectionState.ARMED_HEALTHY).settings.copy(
            tokenConfigured = true,
            pairedOwnerCount = 1,
            pairingCode = null,
            smsFallbackConfigured = true,
        ),
    )

private fun historyState(): ProtectionUiState =
    baseState(ProtectionState.ARMED_HEALTHY).copy(
        events = listOf(
            ProtectionEventRow(
                id = "incident-1",
                type = IncidentType.VIBRATION,
                severity = IncidentSeverity.WARNING,
                lifecycle = IncidentLifecycle.CLOSED,
                evidenceSummary = "ตรวจพบแรงสั่นต่อเนื่อง (2.5 m/s²)",
                updatedAtMs = TEST_TIMESTAMP_MS,
                deliveryState = DeliveryState.SENT,
            ),
        ),
    )

private fun baseState(protectionState: ProtectionState): ProtectionUiState = ProtectionUiState(
    destination = ProtectionDestination.PROTECTION,
    protection = ProtectionStatusUiState(
        state = protectionState,
        lastTransitionAtMs = TEST_TIMESTAMP_MS,
        serviceRunning = true,
        telegramPolling = true,
        telegramReachable = true,
        lastTelegramContactAtMs = TEST_TIMESTAMP_MS,
        permissionBlockers = emptySet(),
        sensorHealth = emptyMap(),
        degradationReasons = emptySet(),
        batteryLevelPercent = 82,
        batteryTemperatureCelsius = 31.5f,
        lastIncident = null,
        lastDeliveryState = null,
    ),
    events = emptyList(),
    settings = ProtectionSettingsSummary(
        tokenConfigured = false,
        pairedOwnerCount = 0,
        pairingCode = "271828",
        sensitivity = 5,
        smsFallbackConfigured = false,
        missingPermissions = emptySet(),
    ),
    armingSecondsRemaining = null,
    eventsLoading = false,
    eventsError = null,
    operationInFlight = false,
    message = null,
)

private fun fakeActions(): ProtectionAppActions = ProtectionAppActions(
    selectDestination = {},
    arm = {},
    disarm = {},
    clearHistory = {},
    changeSensitivity = {},
    requestPermissions = {},
    replaceBotToken = {},
    configureSmsFallback = { _, _ -> },
    retry = {},
    retrySettings = {},
    resetPairing = {},
    consumeMessage = {},
)

private const val TEST_ONLY_TOKEN = "123456:TEST_ONLY_NOT_A_REAL_TOKEN"
private const val UNSAVED_TOKEN = "123456:UNSAVED_TEST_TOKEN"
private const val UNSAVED_SMS_KEY = "UNSAVED_SMS_KEY"
private const val TEST_TIMESTAMP_MS = 1_725_000_000_000L
private const val OPAQUE_ALPHA = 0.95f
private const val NEAR_WHITE = 0.90f
private const val NAVY_RED = 0.086f
private const val NAVY_GREEN = 0.196f
private const val NAVY_BLUE = 0.310f
private const val NAVY_CHANNEL_TOLERANCE = 0.06f

private fun isCloseToNavy(color: Color): Boolean =
    kotlin.math.abs(color.red - NAVY_RED) <= NAVY_CHANNEL_TOLERANCE &&
        kotlin.math.abs(color.green - NAVY_GREEN) <= NAVY_CHANNEL_TOLERANCE &&
        kotlin.math.abs(color.blue - NAVY_BLUE) <= NAVY_CHANNEL_TOLERANCE

private fun isNearWhite(color: Color): Boolean =
    color.red >= NEAR_WHITE && color.green >= NEAR_WHITE && color.blue >= NEAR_WHITE
