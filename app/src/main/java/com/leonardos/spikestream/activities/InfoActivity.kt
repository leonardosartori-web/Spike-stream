package com.leonardos.spikestream.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.leonardos.spikestream.R
import com.leonardos.spikestream.BuildConfig
import com.leonardos.spikestream.data.HelpContentParser
import com.leonardos.spikestream.data.HelpLabels
import com.leonardos.spikestream.data.HelpRepository
import com.leonardos.spikestream.ui.theme.RefinedApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamGlassCard
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.utils.Constants
import com.leonardos.spikestream.utils.RemoteConfigManager

class InfoActivity : AnimatedAppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RefinedApplicationTheme { HelpScreen(onBack = { finish() }) }
        }
    }
}

@Composable
private fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val labels = HelpLabels.forLanguage(language)
    val repository = remember(context) { HelpRepository(context) }
    var content by remember(language) { mutableStateOf(repository.read(language)) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Ignore late callbacks after navigation or a configuration change.
    val active = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) {
        active[0] = true
        onDispose { active[0] = false }
    }
    fun refresh(force: Boolean) {
        loading = true
        status = null
        RemoteConfigManager.refreshHelp(force) { success ->
            if (active[0]) {
                content = repository.read(language)
                val raw = RemoteConfigManager.helpContent(language)
                val valid = raw.isNullOrBlank() || HelpContentParser.parse(raw) != null
                loading = false
                status = if (!success || !valid) labels.unavailable else if (force) labels.updated else null
            }
        }
    }
    LaunchedEffect(language) { refresh(false) }
    val filtered = content.articles.filter {
        query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) ||
            it.body.contains(query.trim(), ignoreCase = true)
    }

    SpikeStreamScreen {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = labels.back)
                }
                TextButton(onClick = { refresh(true) }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(labels.refresh)
                }
            }
            Text(content.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text(labels.search) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                item { Text(content.introduction, style = MaterialTheme.typography.bodyLarge) }
                if (filtered.isEmpty()) item { Text(labels.empty) }
                items(filtered, key = { it.id }) { article ->
                    var expanded by rememberSaveable(article.id) { mutableStateOf(false) }
                    SpikeStreamGlassCard {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(article.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(if (expanded || query.isNotBlank()) "−" else "+")
                        }
                        if (expanded || query.isNotBlank()) {
                            HelpBody(article.body)
                        }
                    }
                }
                item {
                    SpikeStreamGlassCard {
                        Text(stringResource(R.string.support_project_title), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.support_project_desc), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/spikestream")))
                        }) { Text(stringResource(R.string.support_project)) }
                    }
                }
                item {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Constants.BASE_URL}/privacy.html")))
                    }) { Text(stringResource(R.string.privacy_policy)) }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Constants.BASE_URL}/terms.html")))
                    }) { Text(stringResource(R.string.terms_of_service)) }
                    Text("SpikeStream · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun HelpBody(body: String) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context -> TextView(context).apply { textSize = 16f } },
        update = { view ->
            view.text = body
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            Linkify.addLinks(view, Linkify.WEB_URLS)
            view.movementMethod = LinkMovementMethod.getInstance()
        },
    )
}
