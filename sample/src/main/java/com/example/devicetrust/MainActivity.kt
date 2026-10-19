package com.example.devicetrust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.devicetrust.ui.theme.DeviceTrustTheme
import com.xheghun.devicetrust.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DeviceTrustTheme { TrustRoute() } }
    }
}

@Composable
private fun TrustRoute(viewModel: TrustViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrustScreen(state = state, onScan = viewModel::scan)
}

@Composable
fun TrustScreen(state: TrustUiState, onScan: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background) { padding ->
        when {
            state.scanning && state.assessment == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Inspecting device evidence…")
                }
            }
            state.error != null && state.assessment == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Assessment unavailable", style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(state.error))
                    Button(onClick = onScan) { Text("Try again") }
                }
            }
            state.assessment != null -> AssessmentContent(state, onScan, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AssessmentContent(state: TrustUiState, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val assessment = state.assessment ?: return
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("DEVICE TRUST", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Environment assessment", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Signals are probabilistic. Server-verified Play Integrity should authorize sensitive actions.", color = Color(0xFFA9BAB3))
        }
        item {
            RiskCard(assessment)
            Text(stringResource(R.string.monitor_last_updated, state.lastUpdated))
            Text(pluralStringResource(R.plurals.monitor_coverage, assessment.evidence.evaluatedSignalIds.size, assessment.evidence.evaluatedSignalIds.size))
            Text(stringResource(R.string.monitor_polling_note), style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Root / hooks", assessment.isRootOrHookingSuspected, Modifier.weight(1f))
                StatusChip("Emulator", assessment.isEmulatorSuspected, Modifier.weight(1f))
                StatusChip("ROM / boot", assessment.isSystemIntegritySuspected, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Evidence", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onScan, enabled = !state.scanning) { Text(stringResource(R.string.monitor_refresh)) }
            }
        }
        if (assessment.signals.isEmpty()) item {
            EvidenceCard("No local risk indicators", "This does not prove the device is genuine or uncompromised.", Color(0xFF5EE3B1))
        }
        items(assessment.signals, key = { "signal:${it.id}" }) { signal ->
            EvidenceCard(signal.title, signal.detail, categoryColor(signal.category))
        }
        item { Text(stringResource(R.string.monitor_timeline), style = MaterialTheme.typography.titleLarge) }
        items(state.timeline, key = { "scan:${it.sequence}" }) { entry ->
            TimelineCard(entry)
        }
    }
}

@Composable
private fun TimelineCard(entry: TimelineEntryUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.time, fontWeight = FontWeight.Bold)
            Text(stringResource(if (entry.baseline) R.string.monitor_baseline else R.string.monitor_score_delta, entry.scoreDelta))
            if (!entry.baseline && entry.previousLevel != entry.currentLevel) Text(
                stringResource(R.string.monitor_level_change, entry.previousLevel, entry.currentLevel),
            )
            if (entry.added.isNotEmpty()) Text(stringResource(R.string.monitor_added, entry.added))
            if (entry.removed.isNotEmpty()) Text(stringResource(R.string.monitor_removed, entry.removed))
            if (entry.changed.isNotEmpty()) Text(stringResource(R.string.monitor_changed, entry.changed))
            if (entry.unconfirmed.isNotEmpty()) Text(
                stringResource(R.string.monitor_unconfirmed, entry.unconfirmed),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RiskCard(assessment: TrustAssessment) {
    val accent = when (assessment.level) {
        TrustLevel.LOW_RISK -> Color(0xFF5EE3B1)
        TrustLevel.REVIEW -> Color(0xFFFFCA65)
        TrustLevel.HIGH_RISK -> Color(0xFFFF7C7C)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(assessment.level.name.replace('_', ' '), color = accent, fontWeight = FontWeight.Bold)
            Text("${assessment.score}", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { assessment.score / 100f }, modifier = Modifier.fillMaxWidth(), color = accent)
            Text("Local risk score · ${assessment.signals.size} indicator(s)", color = Color(0xFFA9BAB3))
        }
    }
}

@Composable
private fun StatusChip(label: String, flagged: Boolean, modifier: Modifier = Modifier) {
    val color = if (flagged) Color(0xFFFFCA65) else Color(0xFF5EE3B1)
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(if (flagged) "FLAG" else stringResource(R.string.monitor_not_detected), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EvidenceCard(title: String, detail: String, accent: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(4.dp)))
            Column { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, color = Color(0xFFA9BAB3), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun categoryColor(category: SignalCategory) = when (category) {
    SignalCategory.ROOT -> Color(0xFFFF7C7C)
    SignalCategory.HOOKING -> Color(0xFFD89BFF)
    SignalCategory.EMULATOR -> Color(0xFF8BB8FF)
    SignalCategory.SYSTEM_INTEGRITY -> Color(0xFFFFCA65)
}

@Preview(showBackground = true)
@Composable
private fun TrustScreenPreview() = DeviceTrustTheme {
    TrustScreen(
        TrustUiState(
            scanning = false,
            assessment = TrustAssessment(
                score = 68,
                level = TrustLevel.HIGH_RISK,
                evidence = DeviceEvidence(1, 0, listOf(
                    TrustSignal("mount", SignalCategory.ROOT, 35, "Suspicious mount namespace", "Magisk marker exposed"),
                    TrustSignal("boot", SignalCategory.SYSTEM_INTEGRITY, 35, "Bootloader unlocked", "ro.boot.flash.locked=0"),
                )),
            ),
        ),
        onScan = {},
    )
}
