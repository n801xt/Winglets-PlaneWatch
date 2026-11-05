package com.example.planewatch

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Notifier.createChannels(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    val regs by Store.registrations.collectAsState()
    val statuses by Store.statuses.collectAsState()
    val monitoring by Store.monitoring.collectAsState()
    val notifyLanding by Store.notifyLanding.collectAsState()
    var input by remember { mutableStateOf("") }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Resume the service if the user left monitoring on and the process was restarted.
        if (Store.monitoring.value) startMonitor(context)
    }

    fun add() {
        if (Store.addRegistration(input)) input = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        Text("PlaneWatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Get a notification when these aircraft take off.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.uppercase() },
                label = { Text("Registration (e.g. N12345, G-ABCD)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(4.dp))
            Button(onClick = { add() }, enabled = input.isNotBlank()) { Text("Add") }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Monitoring", fontWeight = FontWeight.SemiBold)
                Text(
                    if (monitoring) "Checking every minute" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = monitoring,
                enabled = regs.isNotEmpty() || monitoring,
                onCheckedChange = { on ->
                    Store.setMonitoring(on)
                    if (on) startMonitor(context)
                    else context.stopService(Intent(context, MonitorService::class.java))
                },
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Also notify when it lands", Modifier.weight(1f))
            Switch(checked = notifyLanding, onCheckedChange = { Store.setNotifyLanding(it) })
        }

        Spacer(Modifier.height(16.dp))

        if (regs.isEmpty()) {
            Text("No aircraft yet. Add a registration above.", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(regs, key = { it }) { reg ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(reg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                statuses[reg] ?: if (monitoring) "Waiting for first check…" else "Monitoring is off",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { Store.removeRegistration(reg) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

private fun startMonitor(context: android.content.Context) {
    ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
}
