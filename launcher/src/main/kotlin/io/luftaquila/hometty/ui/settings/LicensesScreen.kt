package io.luftaquila.hometty.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.ui.Header
import io.luftaquila.hometty.ui.HomeViewModel

internal data class License(val name: String, val license: String, val asset: String)

internal val LICENSES =
    listOf(
        License("hometty (luftaquila)", "MIT License", "licenses/hometty.txt"),
        License("libvterm (Paul Evans)", "MIT License", "licenses/libvterm.txt"),
        License("BusyBox", "GNU GPL v2.0", "licenses/BusyBox-GPL-2.0.txt"),
        License("libselinux", "Public domain", "licenses/libselinux.txt"),
        License("PCRE2", "BSD License", "licenses/PCRE2.txt"),
        License("D2Coding font (NAVER)", "SIL Open Font License 1.1", "licenses/D2Coding-OFL.txt"),
        License("Jetendard", "SIL Open Font License 1.1", "licenses/Jetendard-OFL.txt"),
        License("JetBrains Mono", "SIL Open Font License 1.1", "licenses/JetBrainsMono-OFL.txt"),
        License("Fira Code", "SIL Open Font License 1.1", "licenses/FiraCode-OFL.txt"),
        License("Cascadia Code (Microsoft)", "SIL Open Font License 1.1", "licenses/CascadiaCode-OFL.txt"),
        License("Source Code Pro (Adobe)", "SIL Open Font License 1.1", "licenses/SourceCodePro-OFL.txt"),
        License("IBM Plex Mono", "SIL Open Font License 1.1", "licenses/IBMPlexMono-OFL.txt"),
        License("Hack", "MIT License + Bitstream Vera License", "licenses/Hack-LICENSE.txt"),
        License("Iosevka (Renzhi Li)", "SIL Open Font License 1.1", "licenses/Iosevka-OFL.txt"),
        License("0xProto (0xType)", "SIL Open Font License 1.1", "licenses/0xProto-OFL.txt"),
        License("AndroidX / Jetpack Compose", "Apache License 2.0", "licenses/Apache-2.0.txt"),
        License("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0", "licenses/Apache-2.0.txt"),
        License("Dagger / Hilt", "Apache License 2.0", "licenses/Apache-2.0.txt"),
        License("OkHttp, Okio (Square)", "Apache License 2.0", "licenses/Apache-2.0.txt"),
        License("jsoup", "MIT License", "licenses/jsoup.txt"),
        License("RE2/J", "BSD 3-Clause License", "licenses/RE2J.txt"),
    )

@Composable
fun LicensesScreen(model: HomeViewModel) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<License?>(null) }
    Column(Modifier.fillMaxSize()) {
        Header(context.getString(TextR.string.label_open_source_notices), model::back)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(LICENSES) { license ->
                PreferenceRow(license.name, license.license, onClick = { shown = license }, trailing = {})
            }
        }
    }
    shown?.let { license ->
        val text =
            remember(license) {
                runCatching { context.assets.open(license.asset).bufferedReader().use { it.readText() } }
                    .getOrDefault(license.license)
            }
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(license.name) },
            text = {
                Text(
                    text,
                    Modifier.verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { shown = null }) { Text(context.getString(TextR.string.label_close)) }
            },
        )
    }
}
