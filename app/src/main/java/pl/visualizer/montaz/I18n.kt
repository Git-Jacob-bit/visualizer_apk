package pl.visualizer.montaz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

/**
 * The two interface languages. Texts stay next to where they are used as `tr("polski", "English")`, so a screen
 * reads as one piece in both languages; plurals follow each language's own rules.
 */
enum class Lang(val code: String, val locale: Locale) {
    PL("pl", Locale.forLanguageTag("pl")),
    EN("en", Locale.ENGLISH);

    fun tr(pl: String, en: String) = if (this == EN) en else pl

    fun count(n: Int, plOne: String, plFew: String, plMany: String, enOne: String, enMany: String) =
        if (this == EN) "$n ${if (n == 1) enOne else enMany}" else plural(n, plOne, plFew, plMany)

    fun decimal(value: Float, digits: Int = 1) = String.format(locale, "%.${digits}f", value)

    val other get() = if (this == PL) EN else PL

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code }
        fun system() = if (Locale.getDefault().language == "pl") PL else EN
    }
}

internal val LocalLang = compositionLocalOf { Lang.PL }

@Composable
@ReadOnlyComposable
internal fun tr(pl: String, en: String) = LocalLang.current.tr(pl, en)
