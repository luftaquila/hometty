package io.luftaquila.hometty.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.luftaquila.hometty.platform.Products
import io.luftaquila.hometty.platform.Purchases
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.ui.Header
import io.luftaquila.hometty.ui.HomeViewModel

/** Donations through Google Play, with prices from Play, and GitHub Sponsors. */
@Composable
fun DonateScreen(model: HomeViewModel) {
    val context = LocalContext.current
    val purchases = model.purchases
    val prices by purchases.prices.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { purchases.start() }
    LaunchedEffect(Unit) {
        purchases.events.collect { event ->
            val message =
                when (event) {
                    Purchases.Event.Thanks -> TextR.string.donate_thanks
                    Purchases.Event.Failed -> TextR.string.purchase_failed
                }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    fun donate(productId: String) {
        val activity = context.activity() ?: return
        if (!purchases.launch(activity, productId))
            Toast.makeText(context, TextR.string.purchase_unavailable, Toast.LENGTH_LONG).show()
    }

    val options =
        listOf(
            TextR.string.donate_small to Products.DONATIONS[0],
            TextR.string.donate_medium to Products.DONATIONS[1],
            TextR.string.donate_large to Products.DONATIONS[2],
        )
    Column(Modifier.fillMaxSize()) {
        Header(context.getString(TextR.string.donate_title), model::back)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    context.getString(TextR.string.donate_headline),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    context.getString(TextR.string.donate_desc),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionLabel("Google Play")
            }
            options.forEach { (label, id) ->
                item {
                    val price = prices[id]
                    PreferenceRow(
                        context.getString(label),
                        onClick = { donate(id) },
                        trailing = {
                            if (price != null)
                                Text(
                                    price,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                        },
                    )
                }
            }
            item {
                SectionLabel("GitHub")
                SettingsLink("GitHub Sponsors", "github.com/sponsors/luftaquila") {
                    model.device.open("https://github.com/sponsors/luftaquila")
                }
            }
        }
    }
}

/** The activity behind a Compose context, which may be wrapped (themes, locales). */
private fun Context.activity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
