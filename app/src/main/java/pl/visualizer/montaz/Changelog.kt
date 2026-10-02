package pl.visualizer.montaz

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What changed in each version, newest first.
 *
 * Adding a release: write the entry here, bump `versionCode` and `versionName` in `app/build.gradle.kts`
 * (the `code` below must equal the new `versionCode`) and mirror the entry in `CHANGELOG.md`. After the
 * update every user sees the releases newer than the one they last acknowledged — once.
 */
internal data class ReleaseNote(val title: String, val text: String)

internal data class Release(val version: String, val code: Int, val date: String, val notes: List<ReleaseNote>)

internal fun changelog(lang: Lang): List<Release> = listOf(
    Release("0.10.1", 12, "2026-10-02", listOf(
        ReleaseNote(lang.tr("Kolor PN: biały lub żółty", "PN colour: white or yellow"),
            lang.tr("Okienko PN może być teraz białe albo żółte. Zdjęcia, którym ustawiono inny kolor, wracają do białego.",
                "The PN label can now be white or yellow. Photos that were set to another colour go back to white.")),
    )),
    Release("0.10.0", 11, "2026-10-02", listOf(
        ReleaseNote(lang.tr("Kolor okienka PN", "PN label colour"),
            lang.tr("W zakładce Układ wybierzesz kolor okienka z numerem PN: biały, pomarańczowy, czerwony, zielony, niebieski lub czarny. Na ciemnym tle numer drukuje się na biało, a podgląd i PDF od razu pokazują wybrany kolor.",
                "In the Layout tab you can pick the colour of the PN label: white, orange, red, green, blue or black. On a dark background the number prints in white, and the preview and the PDF show the chosen colour right away.")),
    )),
    Release("0.9.0", 10, "2026-09-26", listOf(
        ReleaseNote(lang.tr("Wielkość oznaczeń", "Label size"),
            lang.tr("W zakładce Układ ustawisz suwakiem wielkość każdego oznaczenia osobno — od 60 do 200% rozmiaru wzorcowego. Panel pokazuje wysokość okienka w milimetrach, a podgląd i PDF od razu ją uwzględniają.",
                "In the Layout tab a slider sets the size of each label on its own — from 60 to 200% of the standard size. The panel shows the label height in millimetres, and the preview and the PDF follow it right away.")),
        ReleaseNote(lang.tr("Co nowego po aktualizacji", "What's new after an update"),
            lang.tr("Po każdej aktualizacji aplikacja pokaże raz listę zmian. Pełną historię znajdziesz w oknie O aplikacji.",
                "After every update the app shows the list of changes once. The full history is in the About window.")),
    )),
    Release("0.8.0", 9, "2026-09-22", listOf(
        ReleaseNote(lang.tr("Pierwsza wersja", "First version"),
            lang.tr("Projekty, aparat z kadrem w milimetrach, oznaczenia PN i kroków, kadrowanie, korekta światła, ustawienia drukarki oraz wycinanka PDF A4 do druku w skali 100%.",
                "Projects, a camera framed in millimetres, PN and step labels, framing, light correction, printer settings and an A4 PDF cut-out sheet for printing at 100% scale.")),
    )),
)

private const val SEEN_CODE = "changelog_seen_code"

/**
 * Releases the user has not acknowledged yet. A fresh install gets none — the welcome screen introduces the
 * app instead — while a phone updated from an older build sees everything that came after its last version.
 */
internal fun unseenReleases(settings: SharedPreferences, lang: Lang): List<Release> {
    val firstRun = !settings.getBoolean("welcome_done", false)
    val seen = settings.getInt(SEEN_CODE, if (firstRun) BuildConfig.VERSION_CODE else 0)
    return changelog(lang).filter { it.code > seen && it.code <= BuildConfig.VERSION_CODE }
}

internal fun markChangelogSeen(settings: SharedPreferences) {
    settings.edit().putInt(SEEN_CODE, BuildConfig.VERSION_CODE).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangelogSheet(releases: List<Release>, history: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lang = LocalLang.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                    StudioIcon(StudioSymbol.Sparkle, Modifier.size(24.dp), colors.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (history) tr("Historia zmian", "Changelog") else tr("Co nowego", "What's new"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                    Text(
                        if (history) tr("Wszystkie wersje aplikacji", "Every version of the app")
                        else tr("W wersji", "In version") + " ${releases.firstOrNull()?.version ?: BuildConfig.VERSION_NAME}",
                        fontSize = 13.sp, color = colors.onSurfaceVariant,
                    )
                }
            }
            releases.forEachIndexed { index, release ->
                if (index > 0 || history || releases.size > 1) ReleaseHeader(release, lang)
                release.notes.forEach { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(colors.primary))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(note.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(note.text, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            StudioAction(if (history) tr("Zamknij", "Close") else tr("Rozumiem", "Got it"), StudioSymbol.Check, onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ReleaseHeader(release: Release, lang: Lang) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(release.version, Modifier.clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = TabularNumbers)
        Text(dateText(release.date, lang), fontSize = 12.sp, color = colors.onSurfaceVariant, style = TabularNumbers)
        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
    }
}

private fun dateText(date: String, lang: Lang): String {
    val parts = date.split("-")
    return if (lang == Lang.PL && parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else date
}
