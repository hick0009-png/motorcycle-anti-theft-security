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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionEventRow
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState

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
            title = "Loading events",
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
            title = "No protection events",
            detail = "Incidents will appear here when protection records them.",
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
                        text = "Events",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = !state.operationInFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Clear history")
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
            title = { Text("Clear event history?") },
            text = { Text("This permanently removes the local event history.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        actions.clearHistory()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Confirm clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EventRow(event: ProtectionEventRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(event.type.displayName(), style = MaterialTheme.typography.titleMedium)
            Text("Source: REAL")
            Text("Severity: ${event.severity.displayName()}")
            Text("Lifecycle: ${event.lifecycle.displayName()}")
            Text("Evidence: ${event.evidenceSummary}")
            Text("Time: ${event.updatedAtMs} ms")
            Text("Delivery: ${event.deliveryState.displayName()}")
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
            Text("Unable to load events", style = MaterialTheme.typography.titleLarge)
            Text(error)
            Button(
                onClick = retry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Retry")
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
            Text(title, style = MaterialTheme.typography.titleLarge)
            detail?.let { Text(it) }
        }
    }
}

private fun Enum<*>.displayName(): String = name
    .lowercase()
    .replace('_', ' ')
    .replaceFirstChar(Char::uppercase)
