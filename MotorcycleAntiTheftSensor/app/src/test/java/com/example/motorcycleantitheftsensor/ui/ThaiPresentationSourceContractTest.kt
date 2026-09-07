package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.AudioThreatCategory
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.protection.thaiLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whole-app language contract (profile-aware Thai UX, Task 8).
 *
 * Scans ONLY the reachable production entry path rooted at `Navigation.kt ->
 * ProtectionAppScreen` plus the service/channel formatters. Test tags, enum
 * identifiers, protocol commands, resource names, and developer-only logs are not
 * user-facing; disconnected legacy screens (`DashboardScreen`, `ui/main/MainScreen`)
 * are deliberately NOT scanned and must never be edited just to satisfy this test.
 */
class ThaiPresentationSourceContractTest {

    // ---------------------------------------------------------------------
    // Forbidden patterns (approved plan, Task 8)
    // ---------------------------------------------------------------------

    private val forbiddenDisplayTransforms = listOf(
        ".name.lowercase()",
        ".replace('_', ' ')",
        "fun Enum<*>.displayName",
    )

    private val forbiddenSharedVehicleTerms = listOf(
        "ใต้เบาะ",
        "รอบตัวรถ",
        "สตาร์ทเครื่องยนต์",
    )

    private val forbiddenOldLabels = listOf(
        "ระดับความไว",
        "Advanced Role Mapping",
        "Clear history",
        "Review permissions",
    )

    /**
     * Reachable user-facing production files: the Compose entry path plus the
     * service/channel presentation formatters. Keep this list explicit so coverage
     * cannot silently shrink when files move.
     */
    private val reachableKotlinFiles = listOf(
        "MainActivity.kt",
        "Navigation.kt",
        "ui/ProtectionAppScreen.kt",
        "ui/ProtectionUiModels.kt",
        "ui/ProtectionUiText.kt",
        "ui/ProtectionTimeFormatter.kt",
        "ui/protection/ProtectionScreen.kt",
        "ui/protection/EntryGuardSection.kt",
        "ui/protection/PowerGuardSection.kt",
        "ui/protection/BlackBoxExportCard.kt",
        "ui/events/EventsScreen.kt",
        "ui/settings/SettingsScreen.kt",
        "protection/PresentationTextCatalog.kt",
        "protection/UserGuidance.kt",
        "protection/IncidentMessageFormatter.kt",
        "protection/ProtectionStateTelegramNotifier.kt",
        "telegram/ProtectionStatusFormatter.kt",
        "telegram/ProtectionStatusProjection.kt",
        "service/SensorService.kt",
        "service/DirectBootBootstrapService.kt",
    )

    /** Static shared copy also lives in resources; scan it for wording terms. */
    private val reachableResourceFiles = listOf(
        "../../../../res/values/strings.xml",
    )

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/example/motorcycleantitheftsensor"),
            File("app/src/main/java/com/example/motorcycleantitheftsensor"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate module source root from ${File(".").absolutePath}")
    }

    private fun reachableSources(): List<Pair<String, String>> =
        reachableKotlinFiles.map { relative ->
            val file = File(sourceRoot(), relative)
            require(file.isFile) { "Reachable source file missing: $relative" }
            relative to file.readText()
        }

    private fun reachableResources(): List<Pair<String, String>> =
        reachableResourceFiles.map { relative ->
            val file = File(sourceRoot(), relative)
            require(file.isFile) { "Reachable resource file missing: $relative" }
            relative to file.readText()
        }

    private fun violations(
        sources: List<Pair<String, String>>,
        patterns: List<String>,
    ): List<String> = buildList {
        for ((relative, content) in sources) {
            for (pattern in patterns) {
                if (pattern in content) {
                    add("$relative contains forbidden pattern \"$pattern\"")
                }
            }
        }
    }

    @Test
    fun reachableFileListIsStableSoCoverageCannotShrink() {
        assertTrue("Expected a meaningful reachable set", reachableKotlinFiles.size >= 20)
        reachableSources()
        reachableResources()
    }

    @Test
    fun noRawEnumDisplayTransformsInReachableUserFacingCode() {
        val found = violations(reachableSources(), forbiddenDisplayTransforms)
        assertTrue(
            "Raw enum display transforms reached user-facing code:\n${found.joinToString("\n")}",
            found.isEmpty(),
        )
    }

    @Test
    fun noBannedSharedVehicleWordingInReachableCodeOrResources() {
        val found = violations(
            reachableSources() + reachableResources(),
            forbiddenSharedVehicleTerms,
        )
        assertTrue(
            "Installation-neutral surfaces contain vehicle-only wording:\n${found.joinToString("\n")}",
            found.isEmpty(),
        )
    }

    @Test
    fun noLegacyLabelsRemainInReachableCodeOrResources() {
        val found = violations(
            reachableSources() + reachableResources(),
            forbiddenOldLabels,
        )
        assertTrue(
            "Retired labels reappeared in reachable code:\n${found.joinToString("\n")}",
            found.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------
    // Catalog completeness: every relevant enum renders nonblank Thai without
    // exposing its enum name.
    // ---------------------------------------------------------------------

    private fun assertThaiDisplay(enumName: String, label: String) {
        assertTrue("$enumName display must not be blank", label.isNotBlank())
        assertNotEquals("$enumName must not render its raw enum name", enumName, label)
        assertFalse(
            "$enumName display must not contain enum-style underscores: $label",
            '_' in label,
        )
    }

    @Test
    fun everyProtectionProfileHasNonblankThaiPresentation() {
        ProtectionProfile.entries.forEach { profile ->
            val presentation = PresentationTextCatalog.profile(profile)
            assertThaiDisplay(profile.name, presentation.name)
            assertTrue(
                "${profile.name} promise must not be blank",
                presentation.promise.isNotBlank(),
            )
        }
    }

    @Test
    fun everyCapabilityHasNonblankThaiNameForEveryProfile() {
        ProtectionProfile.entries.forEach { profile ->
            SensorCapability.entries.forEach { capability ->
                val presentation = PresentationTextCatalog.capability(profile, capability)
                assertThaiDisplay(capability.name, presentation.title)
                assertTrue(
                    "${profile.name}/${capability.name} explanation must not be blank",
                    presentation.explanation.isNotBlank(),
                )
                assertThaiDisplay(capability.name, PresentationTextCatalog.capabilityName(capability))
            }
        }
    }

    @Test
    fun everyIncidentTypeSeverityLifecycleAndDeliveryStateHasThaiLabel() {
        IncidentType.entries.forEach { type ->
            assertThaiDisplay(type.name, PresentationTextCatalog.incidentTitle(type))
        }
        IncidentSeverity.entries.forEach { severity ->
            assertThaiDisplay(severity.name, PresentationTextCatalog.severityLabel(severity))
        }
        IncidentLifecycle.entries.forEach { lifecycle ->
            assertThaiDisplay(lifecycle.name, PresentationTextCatalog.incidentLifecycleLabel(lifecycle))
        }
        DeliveryState.entries.forEach { state ->
            assertThaiDisplay(state.name, PresentationTextCatalog.deliveryStateLabel(state))
        }
    }

    @Test
    fun everyProtectionStateHasNonblankThaiLabel() {
        ProtectionState.entries.forEach { state ->
            assertThaiDisplay(state.name, PresentationTextCatalog.protectionStateLabel(state))
        }
    }

    @Test
    fun everySensorSourceAndRoleHasNonblankThaiLabel() {
        SensorSource.entries.forEach { source ->
            assertThaiDisplay(source.name, PresentationTextCatalog.sourceName(source))
        }
        SensorRole.entries.forEach { role ->
            assertThaiDisplay(role.name, PresentationTextCatalog.roleName(role))
            assertThaiDisplay(role.name, PresentationTextCatalog.evidenceRoleLabel(role))
        }
    }

    @Test
    fun everyAudioThreatCategoryHasNonblankThaiLabel() {
        AudioThreatCategory.entries.forEach { category ->
            assertThaiDisplay(category.name, category.thaiLabel())
        }
    }

    @Test
    fun eventsAndNotificationConstantsStayNonblank() {
        assertEquals("กำลังโหลดเหตุการณ์", PresentationTextCatalog.EVENTS_LOADING)
        assertEquals("ยังไม่มีเหตุการณ์", PresentationTextCatalog.EVENTS_EMPTY_TITLE)
        assertEquals("เกิดข้อผิดพลาดในการโหลดเหตุการณ์", PresentationTextCatalog.EVENTS_ERROR_TITLE)
        assertEquals("ระบบป้องกัน", PresentationTextCatalog.NOTIFICATION_TITLE)
        assertTrue(PresentationTextCatalog.REAL_EVENT_SOURCE.isNotBlank())
    }
}
