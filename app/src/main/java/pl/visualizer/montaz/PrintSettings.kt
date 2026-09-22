package pl.visualizer.montaz

import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** App-wide printer compensation, applied to every photo in previews and in the exported PDF. */
data class PrintProfile(val midtones: Float = 0f, val sharpen: Int = 0, val monochrome: Boolean = false) {
    val sharpenAmount get() = when (sharpen) { 1 -> 0.7f; 2 -> 1.4f; else -> 0f }

    fun adjustments(photo: PhotoItem, full: Boolean) = Adjustments(photo.brightness, photo.contrast, midtones, monochrome, if (full) sharpenAmount else 0f)

    fun save(settings: SharedPreferences) = settings.edit()
        .putFloat("print_midtones", midtones).putInt("print_sharpen", sharpen).putBoolean("print_monochrome", monochrome).apply()

    companion object {
        const val MAX_MIDTONES = 0.4f
        val TEST_STEPS = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f)

        fun load(settings: SharedPreferences) = PrintProfile(
            settings.getFloat("print_midtones", 0f), settings.getInt("print_sharpen", 0), settings.getBoolean("print_monochrome", false))
    }
}

internal val LocalPrintProfile = compositionLocalOf { PrintProfile() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrinterSheet(profile: PrintProfile, onChange: (PrintProfile) -> Unit, onTestPage: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Drukarka", "Printer"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                Text(tr("Dotyczy wszystkich zdjęć i eksportu PDF.", "Applies to all photos and to the PDF export."), color = colors.onSurfaceVariant, fontSize = 14.sp)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(tr("Rozjaśnienie pod druk", "Print brightening"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("${(profile.midtones * 100).roundToInt()}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.primary, style = TabularNumbers)
                }
                GrayWedge(profile.midtones)
                Slider(profile.midtones, { onChange(profile.copy(midtones = (it * 20).roundToInt() / 20f)) }, valueRange = 0f..PrintProfile.MAX_MIDTONES, steps = 7)
                Text(tr("Rozjaśnia tony średnie, a biel i czerń zostawia bez zmian. Kompensuje drukarki, które przyciemniają wydruk.", "Lifts the midtones and leaves white and black unchanged. Compensates printers that darken the print."),
                    color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Wyostrzanie", "Sharpening"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(tr("Brak", "None"), tr("Lekkie", "Light"), tr("Mocne", "Strong")).forEachIndexed { index, label ->
                        SegmentedButton(selected = profile.sharpen == index, onClick = { onChange(profile.copy(sharpen = index)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                    }
                }
                Text(tr("Małe wydruki tracą drobne detale. Wyostrzanie jest liczone w rozdzielczości druku.", "Small prints lose fine detail. Sharpening is computed at print resolution."), color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Druk czarno-biały", "Black-and-white print"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(tr("Dla drukarek laserowych. Daje lepszy kontrast niż konwersja w drukarce.", "For laser printers. Gives better contrast than the printer's own conversion."), color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                }
                Spacer(Modifier.width(16.dp))
                Switch(profile.monochrome, { onChange(profile.copy(monochrome = it)) })
            }

            HorizontalDivider(color = colors.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Nie wiesz, ile rozjaśnić? Wydrukuj stronę testową w skali 100% i wybierz wiersz, w którym pola 80% i 90% nadal się od siebie odróżniają.", "Not sure how much? Print the test page at 100% scale and pick the row where the 80% and 90% patches still differ."),
                    color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                StudioAction(tr("Zapisz stronę testową PDF", "Save PDF test page"), StudioSymbol.Print, onTestPage, Modifier.fillMaxWidth(), secondary = true)
            }
        }
    }
}

// Eleven ink steps (0–100 %) through the current curve: what the printer will be asked to print.
@Composable
private fun GrayWedge(midtones: Float) {
    val animated by animateFloatAsState(midtones, label = "wedge")
    val curve = remember(animated) { ImageTools.toneCurve(Adjustments(midtones = animated)) }
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, outline, RoundedCornerShape(8.dp))) {
        val cell = size.width / 11f
        for (step in 0..10) {
            val value = curve[(255 * (1f - step / 10f)).roundToInt()]
            drawRect(Color(value, value, value), Offset(cell * step, 0f), Size(cell + 1f, size.height))
        }
    }
}
