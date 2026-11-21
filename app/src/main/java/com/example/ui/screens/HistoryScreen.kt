package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.DispatchLogEntity
import com.example.ui.theme.LocalStatusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    logs: List<DispatchLogEntity>,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()) }

    val successCount = remember(logs) { logs.count { it.isSuccess } }
    val successRate = remember(logs) {
        if (logs.isEmpty()) 0 else ((successCount.toFloat() / logs.size) * 100f).toInt()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Dispatch Audit Log", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${logs.size} executions • $successRate% success rate",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (logs.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearHistory,
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Clear")
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                OutlinedCard(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("No dispatch runs yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Every automated send records its timestamp, duration, target instance, and status here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(logs, key = { it.id }) { log ->
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (log.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    contentDescription = if (log.isSuccess) "Succeeded" else "Failed",
                                    tint = if (log.isSuccess) statusColors.onSuccessContainer else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(log.recipientSnapshot, style = MaterialTheme.typography.titleSmall)
                                if (log.hasMedia) {
                                    Icon(
                                        Icons.Filled.AttachFile,
                                        contentDescription = "Media included",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (log.isSuccess) statusColors.successContainer else MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = if (log.isSuccess) "SENT" else "FAILED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (log.isSuccess) statusColors.onSuccessContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Text(
                            text = log.messagePreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${log.instanceLabel} • ${log.detailMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (log.isSuccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${dateFormat.format(Date(log.executedAtMillis))} (${log.durationMillis}ms)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
