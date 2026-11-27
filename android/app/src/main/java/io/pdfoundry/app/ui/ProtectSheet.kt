package io.pdfoundry.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shown before a file is protected, word for word on every platform. */
const val PasswordWarning = "If you forget the password, the file can't be opened, and it can't be recovered."

/** True when Protect may go ahead: both fields hold the same, non-empty password. */
fun canProtect(password: String, confirm: String): Boolean = password.isNotEmpty() && password == confirm

/**
 * Asks for a new password twice, with the warning. Protect stays disabled until both match. The
 * password is handed to [onProtect] and kept nowhere else.
 */
@Composable
fun ProtectDialog(name: String, onDismiss: () -> Unit, onProtect: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val secret = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text("Protect with Password") },
        text = {
            Column {
                Text("“$name” will need this password to open.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = secret, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    confirm, { confirm = it }, label = { Text("Confirm") }, singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != password,
                    supportingText = { if (confirm.isNotEmpty() && confirm != password) Text("The passwords don't match.") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = secret, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(PasswordWarning, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = canProtect(password, confirm), onClick = { onProtect(password) }) { Text("Protect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Asks for the password of a protected file a tool is about to read (Split, Extract). Only a password
 * that gives full access is taken, as for editing.
 */
@Composable
fun UnlockDialog(name: String, file: File, onDismiss: () -> Unit, onUnlocked: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text("Enter the password") },
        text = {
            Column {
                Text("“$name” is protected with a password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    password, { password = it; problem = null }, label = { Text("Password") }, singleLine = true,
                    isError = problem != null, supportingText = { problem?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                if (checking) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && !checking, onClick = {
                checking = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { PdfEngine.access(file, password) }.getOrDefault(UnlockResult.Wrong) }
                    checking = false
                    when (result) {
                        UnlockResult.Full -> onUnlocked(password)
                        UnlockResult.Limited -> { problem = "This password only opens the file for reading. Enter its owner password."; password = "" }
                        UnlockResult.Wrong -> { problem = "That password didn't work. Try again."; password = "" }
                    }
                }
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
