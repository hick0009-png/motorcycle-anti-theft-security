package com.example.motorcycleantitheftsensor.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.R
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionEventRow
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp

@Composable
fun EventsScreen(
    state: ProtectionUiState,
    actions: ProtectionAppActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    when {
        state.eventsLoading -> EventMessage(
            title = stringResource(R.string.events_loading_title),
            contentPadding = contentPadding,
            modifier = modifier,
            progress = true,
        )

        state.eventsError != null -> EventError(
            error = state.eventsError,
            retry = actions.retry,
            contentPadding = contentPadding,
            modifier = modifier,
        )

        state.events.isEmpty() -> EventMessage(
            title = stringResource(R.string.events_empty_title),
            detail = stringResource(R.string.events_empty_detail),
            contentPadding = contentPadding,
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "events-header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.events_history_header),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = !state.eventsOperationInFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.events_clear_history_action))
                    }
                }
            }
            items(state.events, key = ProtectionEventRow::id) { event ->
                EventRow(event)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.events_clear_confirm_title)) },
            text = { Text(stringResource(R.string.events_clear_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        actions.clearHistory()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.events_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.events_cancel_action))
                }
            },
        )
    }
}

/**
 * Renders persisted incident facts through the shared Thai presentation catalog.
 * Evidence stays bounded to what the incident actually recorded; no raw enum
 * names, English field prefixes, diagnostics, tokens, or identifiers are shown.
 */
@Composable
private fun EventRow(event: ProtectionEventRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = PresentationTextCatalog.incidentTitle(event.type),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(PresentationTextCatalog.formatEventSourceLine())
            Text(PresentationTextCatalog.formatEventSeverityLine(event.severity))
            Text(PresentationTextCatalog.formatEventLifecycleLine(event.lifecycle))
            Text(PresentationTextCatalog.EVENT_EVIDENCE_PREFIX + event.evidenceSummary)
            Text(PresentationTextCatalog.EVENT_TIME_PREFIX + formatProtectionTimestamp(event.updatedAtMs))
            Text(PresentationTextCatalog.formatEventDeliveryLine(event.deliveryState))
        }
    }
}

@Composable
private fun EventError(
    error: String,
    retry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.events_error_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(error)
            Button(
                onClick = retry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.events_retry_action))
            }
        }
    }
}

@Composable
private fun EventMessage(
    title: String,
    contentPadding: PaddingValues,
    modifier: Modifier,
    detail: String? = null,
    progress: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (progress) CircularProgressIndicator()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            detail?.let { Text(it) }
        }
    }
}
