package pl.visualizer.montaz

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

internal val AppVersion get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

private data class WelcomePage(val title: String, val text: String)

private fun welcomePages(lang: Lang) = listOf(
    WelcomePage(lang.tr("Zdjęcia montażu w prawdziwym rozmiarze", "Assembly photos at their real size"),
        lang.tr("frame / studio prowadzi od pomiaru miejsca na ramie do arkusza PDF gotowego do wycięcia.", "frame / studio takes you from measuring a spot on the frame to a PDF sheet ready to cut out.")),
    WelcomePage(lang.tr("Najpierw wymiar", "Size first"),
        lang.tr("Wpisujesz zmierzone miejsce w milimetrach. Aparat pokaże kadr o dokładnie tej proporcji.", "Enter the measured spot in millimetres. The camera shows a frame with exactly that proportion.")),
    WelcomePage(lang.tr("Zdjęcie i opis", "Photo and details"),
        lang.tr("Dotknij detalu, aby zablokować ostrość. Potem dodaj numer PN i kroki, a aplikacja umieści je na zdjęciu.", "Tap a detail to lock focus. Then add the PN number and steps, and the app places them on the photo.")),
    WelcomePage(lang.tr("Drukuj w skali 100%", "Print at 100% scale"),
        lang.tr("Wycinanka ma linie cięcia i linijkę 50 mm. Gdy wydruk wychodzi za ciemny, pomogą ustawienia drukarki.", "The cut-out sheet has cut lines and a 50 mm ruler. If prints come out too dark, the printer settings help.")),
)

@Composable
internal fun WelcomeScreen(tipsEnabled: Boolean, onTipsChange: (Boolean) -> Unit, onToggleLanguage: () -> Unit, onFinish: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val welcomePages = welcomePages(LocalLang.current)
    val pager = rememberPagerState { welcomePages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == welcomePages.lastIndex
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioLogo(Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Text("frame", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text(" / studio", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            // Language can be chosen before anything else is read.
            TextButton(onClick = onToggleLanguage) { Text(if (LocalLang.current == Lang.PL) "English" else "Polski") }
            if (!last) TextButton(onClick = onFinish) { Text(tr("Pomiń", "Skip")) }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(28.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                    WelcomeIllustration(page, active = pager.currentPage == page)
                }
                Text(welcomePages[page].title, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                Text(welcomePages[page].text, fontSize = 16.sp, lineHeight = 23.sp, color = colors.onSurfaceVariant)
                if (page == welcomePages.lastIndex) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant).clickable { onTipsChange(!tipsEnabled) }
                        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Wskazówki przy pierwszym użyciu", "Tips on first use"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(tr("Podświetlają elementy każdego ekranu. Zawsze wrócisz do nich przyciskiem ?", "They highlight the parts of each screen. The ? button always brings them back."), fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(tipsEnabled, onTipsChange)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            welcomePages.indices.forEach { index ->
                val selected = index == pager.currentPage
                val width by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dotWidth")
                val color by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, label = "dotColor")
                Box(Modifier.padding(end = 6.dp).height(8.dp).width(width).clip(CircleShape).background(color))
            }
            Spacer(Modifier.weight(1f))
            Text(tr("Wersja", "Version") + " ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        StudioDock {
            StudioAction(if (last) tr("Zaczynamy", "Get started") else tr("Dalej", "Next"), if (last) StudioSymbol.Check else StudioSymbol.Arrow,
                { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, Modifier.fillMaxWidth())
        }
    }
}

// Each page animates only while it is on screen; the drawing restarts when the user swipes back to it.
@Composable
private fun WelcomeIllustration(page: Int, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val intro = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) { intro.snapTo(0f); intro.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    }
    val loop by rememberInfiniteTransition(label = "welcomeLoop").animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "loop")
    val primary = colors.primary
    val outline = colors.outline
    val surface = colors.surface
    Canvas(Modifier.fillMaxSize().padding(24.dp)) {
        when (page) {
            0 -> drawLogoIllustration(intro.value)
            1 -> drawDimensionIllustration(loop, primary, outline, surface)
            2 -> drawPhotoIllustration(intro.value, loop)
            else -> drawSheetIllustration(intro.value, outline)
        }
    }
}

private fun DrawScope.drawLogoIllustration(t: Float) {
    val unit = minOf(size.width, size.height) / 60f
    translate(size.width / 2f - 30f * unit, size.height / 2f - 30f * unit) {
        val spread = spring(t)
        rotate(-16f * spread, pivot = Offset(26f * unit, 32f * unit)) {
            drawRoundRect(StudioPaper, Offset(12f * unit, 8f * unit), Size(26f * unit, 38f * unit), CornerRadius(2f * unit))
            drawRoundRect(StudioInk, Offset(12f * unit, 8f * unit), Size(26f * unit, 38f * unit), CornerRadius(2f * unit), style = Stroke(1.6f * unit))
        }
        val lift = (1f - spread) * 10f * unit
        drawRoundRect(StudioInk, Offset(22f * unit, 16f * unit + lift), Size(26f * unit, 38f * unit), CornerRadius(2f * unit))
        drawRect(StudioPaper, Offset(25.5f * unit, 19.5f * unit + lift), Size(11f * unit * t, 5f * unit))
        drawRect(StudioOrange, Offset(38f * unit, 46f * unit + lift), Size(6.5f * unit, 5f * unit * t))
        drawRect(LabelYellow.copy(alpha = 0.9f * t), Offset(4f * unit, 50f * unit), Size(6f * unit, 6f * unit))
    }
}

private fun spring(t: Float): Float {
    // Slight overshoot so the cards "settle" into place.
    val s = 1f - (1f - t) * (1f - t)
    return s + kotlin.math.sin(t * Math.PI.toFloat()) * 0.08f
}

private fun DrawScope.drawDimensionIllustration(loop: Float, primary: Color, outline: Color, surface: Color) {
    val ratios = listOf(2f / 3f, 3f / 2f, 1f, 2f / 3f)
    val segment = loop * 3f
    val index = segment.toInt().coerceIn(0, 2)
    val local = FastOutSlowInEasing.transform(((segment - index) * 1.6f).coerceIn(0f, 1f))
    val ratio = ratios[index] + (ratios[index + 1] - ratios[index]) * local
    val maxH = size.height * 0.62f
    val maxW = size.width * 0.62f
    val h = minOf(maxH, maxW / ratio)
    val w = h * ratio
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f - 8.dp.toPx()
    drawRoundRect(surface, Offset(left, top), Size(w, h), CornerRadius(6.dp.toPx()))
    drawRoundRect(primary, Offset(left, top), Size(w, h), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
    val tick = 5.dp.toPx()
    val y = top + h + 14.dp.toPx()
    drawLine(outline, Offset(left, y), Offset(left + w, y), 1.5.dp.toPx())
    drawLine(outline, Offset(left, y - tick), Offset(left, y + tick), 1.5.dp.toPx())
    drawLine(outline, Offset(left + w, y - tick), Offset(left + w, y + tick), 1.5.dp.toPx())
    val x = left + w + 14.dp.toPx()
    drawLine(outline, Offset(x, top), Offset(x, top + h), 1.5.dp.toPx())
    drawLine(outline, Offset(x - tick, top), Offset(x + tick, top), 1.5.dp.toPx())
    drawLine(outline, Offset(x - tick, top + h), Offset(x + tick, top + h), 1.5.dp.toPx())
}

private fun DrawScope.drawPhotoIllustration(t: Float, loop: Float) {
    val w = size.width * 0.5f
    val h = w * 1.4f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawRect(Color(0xFF59634F), Offset(left, top), Size(w, h))
    // A stylised metal frame with bolt holes.
    drawRect(Color(0xFFB9BFB3), Offset(left + w * 0.18f, top), Size(w * 0.22f, h))
    for (i in 0..3) drawCircle(Color(0xFF3B4137), w * 0.045f, Offset(left + w * 0.29f, top + h * (0.18f + i * 0.21f)))
    drawRect(Color(0xFF8E968A), Offset(left + w * 0.4f, top + h * 0.45f), Size(w * 0.6f, h * 0.12f))
    val labelH = h * 0.1f
    drawRect(Color.White, Offset(left, top - labelH * (1f - t)), Size(w * 0.62f * t, labelH))
    drawRect(Color.Black, Offset(left, top - labelH * (1f - t)), Size(w * 0.62f * t, labelH), style = Stroke(1.dp.toPx()))
    val tag = w * 0.2f * t
    drawRect(LabelYellow, Offset(left + w - tag, top + h - labelH), Size(tag, labelH))
    val pulse = (loop * 2f) % 1f
    val centre = Offset(left + w * 0.29f, top + h * 0.39f)
    drawCircle(LabelYellow.copy(alpha = 1f - pulse), 18.dp.toPx() + 10.dp.toPx() * pulse, centre, style = Stroke(2.dp.toPx()))
    drawCircle(LabelYellow, 3.dp.toPx(), centre)
}

private fun DrawScope.drawSheetIllustration(t: Float, outline: Color) {
    val h = size.height * 0.86f
    val w = h / 1.414f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawRect(Color.White, Offset(left, top), Size(w, h))
    drawRect(outline, Offset(left, top), Size(w, h), style = Stroke(1.dp.toPx()))
    val tiles = listOf(Triple(0.1f, 0.08f, 0.26f to 0.3f), Triple(0.42f, 0.08f, 0.2f to 0.3f), Triple(0.68f, 0.08f, 0.22f to 0.22f), Triple(0.1f, 0.46f, 0.36f to 0.24f))
    tiles.forEachIndexed { index, (x, y, s) ->
        val appear = ((t * 5f) - index).coerceIn(0f, 1f)
        if (appear > 0f) {
            val tl = Offset(left + w * x, top + h * y - (1f - appear) * 12.dp.toPx())
            val sz = Size(w * s.first, h * s.second * 0.7f)
            drawRect(Color(0xFF59634F).copy(alpha = appear), tl, sz)
            drawRect(LabelYellow.copy(alpha = appear), Offset(tl.x + sz.width * 0.7f, tl.y + sz.height * 0.8f), Size(sz.width * 0.3f, sz.height * 0.2f))
            val mark = 4.dp.toPx()
            listOf(tl, Offset(tl.x + sz.width, tl.y), Offset(tl.x, tl.y + sz.height), Offset(tl.x + sz.width, tl.y + sz.height)).forEach { c ->
                drawLine(Color.Black.copy(alpha = appear), Offset(c.x - mark, c.y), Offset(c.x - mark / 3f, c.y), 0.8.dp.toPx())
                drawLine(Color.Black.copy(alpha = appear), Offset(c.x, c.y - mark), Offset(c.x, c.y - mark / 3f), 0.8.dp.toPx())
            }
        }
    }
    val rule = top + h * 0.92f
    drawLine(Color.Black, Offset(left + w * 0.1f, rule), Offset(left + w * 0.1f + w * 0.35f * t, rule), 1.5.dp.toPx())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSheet(tutorial: TutorialController?, onManual: () -> Unit, onWelcome: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var resetDone by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudioLogo(Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("frame / studio", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                    Text(tr("Wizualizator ramy · wersja", "Frame visualizer · version") + " $AppVersion", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
            }
            Text(tr("Zdjęcia kroków montażu w wymiarach podanych w milimetrach i arkusz PDF do wycięcia. Wszystko zostaje na telefonie.", "Photos of assembly steps at sizes given in millimetres, and a PDF sheet to cut out. Everything stays on the phone."),
                fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            StudioAction(tr("Instrukcja obsługi", "User manual"), StudioSymbol.Info, onManual, Modifier.fillMaxWidth())
            HorizontalDivider(color = colors.outlineVariant)
            if (tutorial != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Wskazówki w aplikacji", "In-app tips"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(tr("Podświetlają elementy ekranu przy pierwszym użyciu i podpowiadają w ważnych momentach.", "They highlight screen elements on first use and give hints at key moments."), fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(tutorial.enabled, tutorial::updateEnabled)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioAction(if (resetDone) tr("Pokażą się znowu", "They will show again") else tr("Pokaż wskazówki od nowa", "Show tips again"), if (resetDone) StudioSymbol.Check else StudioSymbol.Reset,
                        { tutorial.resetSeen(); tutorial.updateEnabled(true); resetDone = true }, Modifier.weight(1f), secondary = true)
                }
            }
            StudioAction(tr("Ekran powitalny", "Welcome screen"), StudioSymbol.Arrow, onWelcome, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ManualScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val lang = LocalLang.current
        StudioTopBar(onBack, title = tr("Instrukcja obsługi", "User manual"))
        AndroidView(factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val english = lang == Lang.EN && context.assets.list("")?.contains("instrukcja-en.html") == true
                loadUrl("file:///android_asset/" + if (english) "instrukcja-en.html" else "instrukcja.html")
            }
        }, modifier = Modifier.fillMaxSize())
    }
}
