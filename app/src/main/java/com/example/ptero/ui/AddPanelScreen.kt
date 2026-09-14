package com.example.ptero

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ptero.ui.NookColors
import com.example.ptero.ui.NookShapes
import com.example.ptero.viewmodel.ServersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AddPanelScreen(vm: ServersViewModel, onBack: () -> Unit) {

    // ─── Form state ───────────────────────────────────────────────────────────
    var label    by remember { mutableStateOf("") }
    var url      by remember { mutableStateOf("") }
    var apiKey   by remember { mutableStateOf("") }
    var serverId by remember { mutableStateOf("") }
    var showKey  by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var saving   by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope() // NEW: Coroutine scope for background work

    Scaffold(
        containerColor = NookColors.AppBackground,
        topBar = {
            Surface(color = NookColors.CardSurface) {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = NookColors.TextSecondary)
                    }
                    Text(
                        text  = "Connect Panel",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ─── Required fields ──────────────────────────────────────────────

            NookInputField(
                label        = "Panel Label",
                value        = label,
                onChange     = { label = it },
                placeholder  = "Host 1",
                leadingIcon  = Icons.Default.Label
            )

            NookInputField(
                label        = "Panel URL",
                value        = url,
                onChange     = { url = it },
                placeholder  = "https://panel.example.com",
                leadingIcon  = Icons.Default.Link,
                keyboardType = KeyboardType.Uri
            )

            NookInputField(
                label       = "API Key",
                value       = apiKey,
                onChange    = { apiKey = it },
                placeholder = "ptlc_xxxxxxxxxxxxxxxxxxxx",
                leadingIcon = Icons.Default.Key,
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff
                            else         Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                            tint = NookColors.TextSecondary
                        )
                    }
                },
                visualTransformation = if (showKey) VisualTransformation.None
                                       else         PasswordVisualTransformation()
            )

            // ─── Optional Server ID field ─────────────────────────────────────

            NookInputField(
                label        = "Server ID (optional)",
                value        = serverId,
                onChange     = {
                    if (it.length <= 8) serverId = it.filter { c -> c.isLetterOrDigit() }
                },
                placeholder  = "a1b2c3d4",
                leadingIcon  = Icons.Default.Tag,
                supportingText = "Leave blank to load all servers on this panel."
            )

            // ─── Inline validation banner ─────────────────────────────────────

            if (errorMsg != null) {
                NookErrorBanner(errorMsg!!)
            }

            Spacer(Modifier.height(8.dp))

            // ─── Save button ──────────────────────────────────────────────────

            Button(
                onClick = {
                    // NEW: Launch inside a coroutine so we don't freeze/crash the app
                    scope.launch {
                        saving   = true
                        errorMsg = null

                        val trimUrl      = url.trim().trimEnd('/')
                        val trimKey      = apiKey.trim()
                        val trimServerId = serverId.trim().takeIf { it.isNotBlank() }

                        errorMsg = when {
                            label.isBlank() -> "Panel label is required."
                            trimUrl.isBlank() -> "Panel URL is required."
                            !trimUrl.startsWith("https://") && !trimUrl.startsWith("http://") -> "Panel URL must start with https:// or http://"
                            trimKey.isBlank() -> "API key is required."
                            trimServerId != null && trimServerId.length != 8 -> "Server ID must be exactly 8 characters (e.g. a1b2c3d4)."
                            else -> null
                        }

                        if (errorMsg != null) {
                            saving = false
                            return@launch
                        }

                        // NEW: Push the heavy API/Database work to the background IO thread
                        val result = withContext(Dispatchers.IO) {
                            try {
                                vm.addAccount(
                                    label    = label.trim(),
                                    panelUrl = trimUrl,
                                    apiKey   = trimKey,
                                    serverId = trimServerId
                                )
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }

                        if (result.isSuccess) {
                            onBack()
                        } else {
                            errorMsg = result.exceptionOrNull()?.message ?: "Failed to save."
                            saving   = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = NookColors.AccentBlue),
                shape   = NookShapes.Button,
                enabled = !saving
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        color       = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save & Connect", style = MaterialTheme.typography.titleMedium)
                }
            }

            // ─── Info card ────────────────────────────────────────────────────

            Card(
                colors = CardDefaults.cardColors(containerColor = NookColors.AccentBlueDim),
                shape  = NookShapes.Small,
                border = BorderStroke(1.dp, NookColors.AccentBlue.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint     = NookColors.AccentBlue,
                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text  = "Create a Client API key in your panel under\nAccount → API Credentials. Keys start with ptlc_.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NookColors.TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Tag,
                            contentDescription = null,
                            tint     = NookColors.AccentBlue,
                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text  = "The Server ID is the 8-character short identifier shown next to your server name in the panel (e.g. a1b2c3d4). Set this to pin the connection to a single server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NookColors.TextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─── NookInputField — local copy for standalone file compilation ──────────────
@Composable
private fun NookInputField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelMedium,
            color = NookColors.TextSecondary
        )
        OutlinedTextField(
            value         = value,
            onValueChange = onChange,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NookColors.TextMuted
                )
            },
            leadingIcon  = leadingIcon?.let {
                { Icon(it, contentDescription = null, tint = NookColors.TextSecondary, modifier = Modifier.size(18.dp)) }
            },
            trailingIcon = trailingIcon,
            singleLine   = true,
            shape        = NookShapes.Input,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor      = NookColors.AccentBlue,
                unfocusedBorderColor    = NookColors.CardBorder,
                focusedContainerColor   = NookColors.InputBackground,
                unfocusedContainerColor = NookColors.InputBackground,
                cursorColor             = NookColors.AccentBlue
            )
        )
        if (supportingText != null) {
            Text(
                text  = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = NookColors.TextMuted
            )
        }
    }
}

// Ensure NookErrorBanner is either in this file or imported from elsewhere.
// If it's complaining about NookErrorBanner, make sure you import it!
