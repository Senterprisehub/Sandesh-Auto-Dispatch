package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.MessageTemplateEntity

@Composable
fun TemplatesScreen(
    templates: List<MessageTemplateEntity>,
    onUseTemplate: (MessageTemplateEntity) -> Unit,
    onSaveTemplate: (Long, String, String, String) -> Unit,
    onDeleteTemplate: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingTemplate by remember { mutableStateOf<MessageTemplateEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

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
                    Text("Quick Templates", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Reusable snippets for reminders, follow-ups & greetings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("add_template_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
        }

        items(templates, key = { it.id }) { tpl ->
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .testTag("template_card_${tpl.id}")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tpl.title, style = MaterialTheme.typography.titleMedium)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = tpl.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = tpl.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Used ${tpl.usageCount}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            IconButton(
                                onClick = { editingTemplate = tpl },
                                modifier = Modifier.testTag("edit_template_button_${tpl.id}")
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit template")
                            }
                            IconButton(
                                onClick = { onDeleteTemplate(tpl.id) },
                                modifier = Modifier.testTag("delete_template_button_${tpl.id}")
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete template")
                            }
                            FilledTonalButton(
                                onClick = { onUseTemplate(tpl) },
                                modifier = Modifier.testTag("use_template_button_${tpl.id}")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Schedule")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog || editingTemplate != null) {
        val initial = editingTemplate
        var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
        var category by remember(initial) { mutableStateOf(initial?.category ?: "Business") }
        var body by remember(initial) { mutableStateOf(initial?.body.orEmpty()) }

        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                editingTemplate = null
            },
            title = { Text(if (initial == null) "New Template" else "Edit Template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("template_title_input")
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category (Business, Personal, Work)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("template_category_input")
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("Message Body") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("template_body_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveTemplate(initial?.id ?: 0L, title, category, body)
                        showCreateDialog = false
                        editingTemplate = null
                    },
                    modifier = Modifier.testTag("save_template_dialog_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateDialog = false
                        editingTemplate = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
