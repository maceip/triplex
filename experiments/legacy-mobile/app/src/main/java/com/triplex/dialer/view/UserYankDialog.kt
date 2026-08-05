package com.triplex.dialer.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.triplex.dialer.model.YankInputFormat
import com.triplex.dialer.model.YankRequest
import androidx.compose.foundation.text.KeyboardOptions

/**
 * "User Yank" dialog — the AI pauses the call because the business
 * needs information only the user can provide (birthdate, order number, etc.).
 *
 * The user types the answer, the AI resumes the call and speaks it.
 *
 * Architecture:
 * - AI detects roadblock → calls [onYankRequired]
 * - Bottom sheet slides up with [YankPrompt]
 * - User types answer → calls [onYankResponse]
 * - AI resumes call, provides the answer
 */
@Composable
fun UserYankDialog(
    yankRequest: YankRequest?,
    onResponse: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only show when there's an active yank request
    if (yankRequest == null) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 16.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // ─── Header with context ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                    contentDescription = null,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${yankRequest.companyName} needs your info",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = yankRequest.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Close button
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            // ─── Input field ───
            var textValue by remember { mutableStateOf("") }
            val inputFormat = yankRequest.inputFormat

            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                label = { Text(inputFormat.label) },
                placeholder = { Text(inputFormat.placeholder) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (inputFormat) {
                        YankInputFormat.PHONE_NUMBER -> KeyboardType.Phone
                        YankInputFormat.DATE -> KeyboardType.Ascii
                        YankInputFormat.ORDER_NUMBER -> KeyboardType.Ascii
                        YankInputFormat.TEXT -> KeyboardType.Text
                        YankInputFormat.EMAIL -> KeyboardType.Email
                        YankInputFormat.ZIP_CODE -> KeyboardType.Number
                    }
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            // ─── Submit button ───
            Button(
                onClick = {
                    if (textValue.isNotBlank()) {
                        onResponse(textValue.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = textValue.isNotBlank(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send to ${yankRequest.companyName}")
            }

            // ─── Hint ───
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The AI will provide this info to the business representative.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
