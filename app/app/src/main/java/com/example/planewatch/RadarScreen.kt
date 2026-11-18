package com.example.planewatch

import android.Manifest
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val RareColor = Color(0xFFFF5252)
private val WatchedColor = Color(0xFFFFD740)
private val OtherColor = Color(0xFF69F0AE)
private val RadarBackground = Color(0xFF06140B)
private val RadarLine = Color(0xFF69F0AE)

@Composable
fun RadarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rareTypes by Store.rareTypes.collectAsState()
    val rareMilitary by Store.rareMilitary.collectAsState()
    val watched by Store.registrations.collectAsState()

    var range by rememberSaveable { mutableStateOf(50) }
    var granted by remember { mutableStateOf(LocationHelper.hasPermission(context)) }
    var location by remember { mutableStateOf<Location?>(null) }
    var planes by remember { mutableStateOf<List<Plane>>(emptyList()) }
    var status by remember { mutableStateOf("Getting your location…") }
    var selectedHex by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.any { it } || LocationHelper.hasPermission(context)
        if (Store.monitoring.value) startMonitor(context)
    }

    BackHandler(onBack = onBack)

    // Keep the phone's position fresh while the radar is open.
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        while (true) {
            val loc = LocationHelper.current(context)
            if (loc != null) {
                location = loc
            } else if (location == null) {
                status = "Couldn't get your location. Is Location switched on in your phone's settings?"
            }
            delay(30_000)
        }
    }

    // Refresh the aircraft around you every few seconds.
    val latestLocation by rememberUpdatedState(location)
    LaunchedEffect(range, location != null) {
        if (location == null) return@LaunchedEffect
        while (true) {
            val here = latestLocation ?: break
            val result = withContext(Dispatchers.IO) {
                NearbyClient.fetch(here.latitude, here.longitude, range)
            }
            result.onSuccess { list ->
                planes = list
                status = "${list.size} aircraft within $range nm"
            }
            result.onFailure { status = "Couldn't reach the data feed. Retrying…" }
            delay(6_000)
        }
    }

    val here = location
    val watchedSet = remember(watched) { watched.map { it.uppercase() }.toSet() }

    fun isRare(p: Plane) = RareTypes.matches(p, rareTypes, rareMilitary)
    fun isWatched(p: Plane) = p.reg?.uppercase()?.let { it in watchedSet } == true

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Radar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (!granted) {
            Spacer(Modifier.height(16.dp))
            Text("The radar needs your location to show aircraft around you.")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
            ) { Text("Allow location") }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (nm in listOf(10, 25, 50, 100, 200)) {
                RangeChip("$nm nm", range == nm) {
                    range = nm
                    selectedHex = null
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (here == null) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
        } else {
            val textPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
            val sweepTransition = rememberInfiniteTransition(label = "sweep")
            val sweepAngle by sweepTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart),
                label = "sweepAngle",
            )

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(planes, here, range) {
                        detectTapGestures { tap ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val r = minOf(size.width, size.height) / 2f - 6.dp.toPx()
                            val nearest = planes
                                .map { it to project(it, c, r, range, here) }
                                .minByOrNull { (_, pos) -> hypot(pos.x - tap.x, pos.y - tap.y) }
                            selectedHex = nearest
                                ?.takeIf { (_, pos) -> hypot(pos.x - tap.x, pos.y - tap.y) < 40.dp.toPx() }
                                ?.first?.hex
                        }
                    },
            ) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) / 2f - 6.dp.toPx()

                drawCircle(RadarBackground, radius, c)
                for (i in 1..3) {
                    drawCircle(
                        RadarLine.copy(alpha = 0.35f),
                        radius * i / 3f,
                        c,
                        style = Stroke(1.dp.toPx()),
                    )
                }
                drawLine(RadarLine.copy(alpha = 0.25f), Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.dp.toPx())
                drawLine(RadarLine.copy(alpha = 0.25f), Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.dp.toPx())

                // Sweep (decorative).
                drawArc(
                    color = RadarLine.copy(alpha = 0.15f),
                    startAngle = sweepAngle - 50f,
                    sweepAngle = 50f,
                    useCenter = true,
                    topLeft = Offset(c.x - radius, c.y - radius),
                    size = Size(radius * 2, radius * 2),
                )
                val rad = Math.toRadians(sweepAngle.toDouble())
                drawLine(
                    RadarLine.copy(alpha = 0.7f),
                    c,
                    Offset(c.x + radius * cos(rad).toFloat(), c.y + radius * sin(rad).toFloat()),
                    1.5.dp.toPx(),
                )

                // Ring distance labels and compass letters.
                textPaint.textSize = 11.sp.toPx()
                textPaint.color = 0xFF9EE6B0.toInt()
                drawText(textPaint, "${range / 3} nm", c.x + 4.dp.toPx(), c.y - radius / 3f - 3.dp.toPx())
                drawText(textPaint, "${range * 2 / 3} nm", c.x + 4.dp.toPx(), c.y - radius * 2f / 3f - 3.dp.toPx())
                textPaint.textSize = 13.sp.toPx()
                drawText(textPaint, "N", c.x - 4.dp.toPx(), c.y - radius + 16.dp.toPx())
                drawText(textPaint, "S", c.x - 4.dp.toPx(), c.y + radius - 6.dp.toPx())
                drawText(textPaint, "E", c.x + radius - 14.dp.toPx(), c.y + 5.dp.toPx())
                drawText(textPaint, "W", c.x - radius + 6.dp.toPx(), c.y + 5.dp.toPx())

                // Aircraft: ordinary ones first so rare/watched ones draw on top.
                val ordered = planes.sortedBy { if (isRare(it)) 2 else if (isWatched(it)) 1 else 0 }
                for (p in ordered) {
                    val pos = project(p, c, radius, range, here)
                    val rare = isRare(p)
                    val watch = isWatched(p)
                    val color = if (rare) RareColor else if (watch) WatchedColor else OtherColor
                    val dot = if (rare || watch) 6.dp.toPx() else 4.dp.toPx()

                    p.track?.let { t ->
                        val tr = Math.toRadians(t)
                        val len = 14.dp.toPx()
                        drawLine(
                            color,
                            pos,
                            Offset(pos.x + len * sin(tr).toFloat(), pos.y - len * cos(tr).toFloat()),
                            1.5.dp.toPx(),
                        )
                    }
                    drawCircle(color, dot, pos)
                    if (p.hex == selectedHex) {
                        drawCircle(Color.White, dot + 5.dp.toPx(), pos, style = Stroke(1.5.dp.toPx()))
                    }
                    if (rare || watch || p.hex == selectedHex) {
                        textPaint.textSize = 11.sp.toPx()
                        textPaint.color = color.toArgbInt()
                        drawText(textPaint, p.label, pos.x + dot + 3.dp.toPx(), pos.y - dot)
                    }
                }

                drawCircle(Color.White, 4.dp.toPx(), c)
            }

            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(RareColor, "Rare")
                Legend(WatchedColor, "Watched")
                Legend(OtherColor, "Other")
            }
            Text(
                "Tap an aircraft for details. Lines show its heading.",
                style = MaterialTheme.typography.bodySmall,
            )

            val selected = planes.firstOrNull { it.hex == selectedHex }
            if (selected != null) {
                Spacer(Modifier.height(12.dp))
                PlaneCard(selected, here, isRare(selected), isWatched(selected))
            }
        }
    }
}

private fun project(p: Plane, center: Offset, radiusPx: Float, rangeNm: Int, here: Location): Offset {
    val dist = Geo.distanceNm(here.latitude, here.longitude, p.lat, p.lon)
    val bearing = Math.toRadians(Geo.bearingDeg(here.latitude, here.longitude, p.lat, p.lon))
    val r = (dist / rangeNm * radiusPx).toFloat().coerceAtMost(radiusPx)
    return Offset(center.x + r * sin(bearing).toFloat(), center.y - r * cos(bearing).toFloat())
}

private fun DrawScope.drawText(paint: android.graphics.Paint, text: String, x: Float, y: Float) {
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

private fun Color.toArgbInt(): Int = this.toArgb()

@Composable
private fun PlaneCard(p: Plane, here: Location, rare: Boolean, watched: Boolean) {
    val dist = Geo.distanceNm(here.latitude, here.longitude, p.lat, p.lon)
    val dir = Geo.compass(Geo.bearingDeg(here.latitude, here.longitude, p.lat, p.lon))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(p.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val tags = listOfNotNull(
                if (rare) "Rare" else null,
                if (watched) "Watched" else null,
                if (p.military) "Military" else null,
            )
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val type = listOfNotNull(RareTypes.label(p.type) ?: p.desc, p.type?.let { "($it)" })
                .joinToString(" ")
            if (type.isNotBlank()) Text(type)
            p.reg?.let { Text("Registration: $it") }
            Text("%.1f nm %s of you".format(dist, dir))
            Text(
                if (p.onGround) "On the ground"
                else listOfNotNull(
                    p.altitudeFt?.let { "%,d ft".format(it) },
                    p.speedKt?.let { "$it kt" },
                ).joinToString(" · ").ifBlank { "Altitude unknown" },
            )
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.padding(3.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
