package fr.nidsdepoule.capture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class SessionAnnotation(
    val routeOrigin: String,
    val routeDestination: String,
    val labelingMethod: String,
    val labelingReliability: String,
)

/**
 * Dialog shown after stopping a capture session.
 * Lets the user annotate the session with route info and labeling quality.
 */
@Composable
fun StopSessionDialog(
    sessionId: String,
    durationMs: Long,
    eventCounts: Map<String, Long>,
    onConfirm: (SessionAnnotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var selectedMethod by remember { mutableStateOf("") }
    var selectedReliability by remember { mutableStateOf("") }

    val methods = listOf("Boutons", "Boutons+Voix", "Voix")
    val reliabilities = listOf(
        "Boutons très fiable",
        "Voix très fiable",
        "Fiable si combine Boutons et Voix",
        "Peu fiable",
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Session terminée",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Session summary
                val totalSec = durationMs / 1000
                val durText = "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
                Text(sessionId, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("Durée: $durText", fontSize = 14.sp)

                // Event counts
                if (eventCounts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val summary = eventCounts.entries.joinToString(", ") { "${it.value} ${it.key}" }
                    Text("Événements: $summary", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Route
                Text("Trajet", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = origin,
                    onValueChange = { origin = it },
                    label = { Text("Origine") },
                    placeholder = { Text("ex: Rue Sherbrooke / Montréal") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text("Destination") },
                    placeholder = { Text("ex: Rue Saint-Denis / Laval") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Labeling method
                Text("Méthode d'étiquetage", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                methods.forEach { method ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = selectedMethod == method,
                            onClick = { selectedMethod = method },
                        )
                        Text(method, modifier = Modifier.padding(start = 4.dp), fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Reliability
                Text("Fiabilité", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                reliabilities.forEach { reliability ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = selectedReliability == reliability,
                            onClick = { selectedReliability = reliability },
                        )
                        Text(reliability, modifier = Modifier.padding(start = 4.dp), fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            // Save with whatever is filled in (allow empty)
                            onConfirm(SessionAnnotation(origin, destination, selectedMethod, selectedReliability))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Passer")
                    }
                    Button(
                        onClick = {
                            onConfirm(SessionAnnotation(origin, destination, selectedMethod, selectedReliability))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Enregistrer")
                    }
                }
            }
        }
    }
}
