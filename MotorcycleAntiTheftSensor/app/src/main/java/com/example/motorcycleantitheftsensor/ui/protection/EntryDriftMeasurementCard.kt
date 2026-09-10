package com.example.motorcycleantitheftsensor.ui.protection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.motorcycleantitheftsensor.protection.EntryDriftBudgetPolicy
import com.example.motorcycleantitheftsensor.protection.EntryDriftMeasurement
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.service.SensorService

/**
 * The step where a phone finds out whether its own door watch can be believed.
 *
 * This began as a developer's diagnostic on the settings screen, and it stayed there while
 * the number it produces decided nothing. It decides something now: the measurement it
 * writes is what the profile picker reads before offering the door watch at all, and what
 * tells an owner their watch is good for six hours rather than a night. That makes it part
 * of setting the door watch up, so it lives where setting up happens — and only there, so
 * there is one control rather than two.
 */
@Composable
fun EntryDriftMeasurementCard(
    alertAngleDeg: Int,
    onSetRecording: (Boolean) -> Unit,
    onClearMeasurement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by SensorService.driftRecorderStatus.collectAsState()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = PresentationTextCatalog.DRIFT_LOG_TITLE,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = PresentationTextCatalog.DRIFT_LOG_EXPLANATION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let { current ->
                Text(
                    text = PresentationTextCatalog.driftLogStatusLine(
                        recording = current.recording,
                        sensorName = current.sensorName,
                        // The hours that count are the hours the phone was left alone, not
                        // the hours the recording was open.
                        elapsedMinutes = current.cleanMeasuredMs / 60_000L,
                        rows = current.rowCount,
                        maxTwistDeg = current.cleanMaxTwistDeg,
                        disturbances = current.disturbanceCount,
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.testTag(DRIFT_MEASUREMENT_STATUS_TAG),
                )
                Text(
                    // The source is not compared here: this is the recording running now, on
                    // whatever it registered, and the reading is about that run.
                    text = PresentationTextCatalog.driftBudgetLine(
                        EntryDriftBudgetPolicy.verdict(
                            measurement = EntryDriftMeasurement(
                                sourceLabel = "",
                                degPerHour = ratePerHour(
                                    current.cleanMaxTwistDeg,
                                    current.cleanMeasuredMs,
                                ),
                                measuredMs = current.cleanMeasuredMs,
                                measuredAtWallMs = 0L,
                            ),
                            alertAngleDeg = alertAngleDeg,
                            currentSource = null,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val recording = status?.recording == true
            OutlinedButton(
                onClick = { onSetRecording(!recording) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(DRIFT_MEASUREMENT_TOGGLE_TAG),
            ) {
                Text(
                    text = if (recording) {
                        PresentationTextCatalog.DRIFT_LOG_STOP
                    } else {
                        PresentationTextCatalog.DRIFT_LOG_START
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Offered here as well as on a refused picker card: this is where an owner
            // stands when they realise the recording caught them holding the phone.
            if (!recording) {
                Text(
                    text = PresentationTextCatalog.DRIFT_LOG_CLEAR_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onClearMeasurement,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(DRIFT_MEASUREMENT_CLEAR_TAG),
                ) {
                    Text(PresentationTextCatalog.DRIFT_LOG_CLEAR)
                }
            }
        }
    }
}

private fun ratePerHour(maxTwistDeg: Double, elapsedMs: Long): Double {
    val hours = elapsedMs / 3_600_000.0
    return if (hours <= 0.0) 0.0 else maxTwistDeg / hours
}

const val DRIFT_MEASUREMENT_TOGGLE_TAG = "ui.protection.drift.TOGGLE"
const val DRIFT_MEASUREMENT_CLEAR_TAG = "ui.protection.drift.CLEAR"
const val DRIFT_MEASUREMENT_STATUS_TAG = "ui.protection.drift.STATUS"
