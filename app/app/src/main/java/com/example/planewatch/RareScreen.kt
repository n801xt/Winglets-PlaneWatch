package com.example.planewatch

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
fun RareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by Store.rareEnabled.collectAsState()
    val selected by Store.rareTypes.collectAsState()
    val military by Store.rareMilitary.collectAsState()
    val radius by Store.rareRadiusNm.collectAsState()
    val monitoring by Store.monitoring.collectAsState()
    var custom by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf(false) }
    var hasLocation by remember { mutableStateOf(LocationHelper.hasPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasLocation = result.values.any { it } || LocationHelper.hasPermission(context)
        // Restart the service so it picks up the location foreground-service type.
        if (Store.monitoring.value) startMonitor(context)
    }

    fun askForLocation() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    fun addCustom() {
        if (Store.addRareType(custom)) {
            custom = ""
            customError = false
        } else {
            customError = true
        }
    }

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
            Text("Rare aircraft", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Alert me when one is nearby", fontWeight = FontWeight.SemiBold)
                Text(
                    "Uses your location to check for the aircraft types you pick below.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    Store.setRareEnabled(on)
                    if (on && !LocationHelper.hasPermission(context)) askForLocation()
                    else if (Store.monitoring.value) startMonitor(context)
                },
            )
        }

        if (enabled && !hasLocation) {
            Spacer(Modifier.height(8.dp))
            Text("Location access is needed for this to work.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { askForLocation() }) { Text("Allow location") }
        }
        if (enabled && !monitoring) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Monitoring is off. Switch it on from the main screen to receive these alerts.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionTitle("How close")
        for (nm in listOf(10, 25, 50, 100)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { Store.setRareRadius(nm) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = radius == nm, onClick = { Store.setRareRadius(nm) })
                Text("Within $nm nautical miles")
            }
        }

        SectionTitle("Aircraft types")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { Store.setRareMilitary(!military) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = military, onCheckedChange = { Store.setRareMilitary(it) })
            Column {
                Text("Any military aircraft")
                Text(
                    "Only those that broadcast ADS-B publicly.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        for (type in RareTypes.presets) {
            TypeRow(type.label, type.code, type.code in selected) { Store.setRareType(type.code, it) }
        }

        val customCodes = selected.filter { code -> RareTypes.presets.none { it.code == code } }.sorted()
        for (code in customCodes) {
            TypeRow("Custom type", code, true) { Store.setRareType(code, it) }
        }

        Spacer(Modifier.height(12.dp))
        Text("Add another type by its ICAO code", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = {
                    custom = it.uppercase()
                    customError = false
                },
                label = { Text("e.g. A225, F16, SR71") },
                isError = customError,
                supportingText = { if (customError) Text("Use 2 to 4 letters or digits") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { addCustom() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(4.dp))
            Button(onClick = { addCustom() }, enabled = custom.isNotBlank()) { Text("Add") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TypeRow(label: String, code: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text(label)
            Text(code, style = MaterialTheme.typography.bodySmall)
        }
    }
}
