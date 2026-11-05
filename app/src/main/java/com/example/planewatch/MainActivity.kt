package com.example.planewatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Notifier.createChannels(this)
        setContent {
            val mode by Store.themeMode.collectAsState()
            val dark = when (mode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val colors = if (dark) darkColorScheme() else lightColorScheme()
            val view = LocalView.current

            // Make the status/navigation bars match the chosen theme.
            SideEffect {
                val w = this@MainActivity.window
                w.statusBarColor = colors.surface.toArgb()
                w.navigationBarColor = colors.surface.toArgb()
                val controller = WindowCompat.getInsetsController(w, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }

            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    } else {
        Screen(onOpenSettings = { showSettings = true })
    }
}

@Composable
private fun Screen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val regs by Store.registrations.collectAsState()
    val statuses by Store.statuses.collectAsState()
    val monitoring by Store.monitoring.collectAsState()
    val notifyLanding by Store.notifyLanding.collectAsState()
    val interval by Store.intervalSeconds.collectAsState()
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
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("PlaneWatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
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
                    if (monitoring) "Checking every ${intervalLabel(interval)}" else "Off",
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

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val themeMode by Store.themeMode.collectAsState()
    val iconName by Store.iconColor.collectAsState()
    val interval by Store.intervalSeconds.collectAsState()

    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionTitle("Theme")
        RadioRow("Follow system", themeMode == 0) { Store.setThemeMode(0) }
        RadioRow("Light", themeMode == 1) { Store.setThemeMode(1) }
        RadioRow("Dark", themeMode == 2) { Store.setThemeMode(2) }

        SectionTitle("App icon color")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (icon in AppIcon.values()) {
                val selected = iconName == icon.name
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(icon.color))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable {
                            Store.setIconColor(icon.name)
                            AppIcons.apply(context, icon)
                        },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Selected: ${AppIcon.values().firstOrNull { it.name == iconName }?.label ?: "Blue"}. " +
                "The home screen icon can take a few seconds to update.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Check interval")
        for (seconds in listOf(30, 60, 120, 300)) {
            RadioRow("Every ${intervalLabel(seconds)}", interval == seconds) {
                Store.setIntervalSeconds(seconds)
            }
        }
        Text(
            "Shorter is faster but uses more battery and may hit the free feeds' rate limits.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private fun intervalLabel(seconds: Int): String =
    if (seconds < 60) "$seconds seconds"
    else if (seconds == 60) "minute"
    else "${seconds / 60} minutes"

private fun startMonitor(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
}
