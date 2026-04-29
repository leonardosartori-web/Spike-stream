package com.leonardos.spikestream

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.leonardos.spikestream.ui.theme.MyApplicationTheme
import com.leonardos.spikestream.ui.theme.SpikeStreamScreen
import com.leonardos.spikestream.ui.theme.SpikeStreamPrimaryButton
import com.leonardos.spikestream.ui.theme.SpikeStreamOutlinedButton
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import com.leonardos.spikestream.ui.theme.SpikeStreamGlassCard


class InfoActivity: ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                )
                {
                    HowToUseScreen()
                }
            }
        }
    }

    @Composable
    fun HowToUseScreen() {
        val scrollState = rememberScrollState()
        val context = LocalContext.current

        SpikeStreamScreen {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))

                Surface(
                    modifier = Modifier
                        .size(80.dp)
                        .shadow(12.dp, CircleShape),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = stringResource(id = R.string.how_to_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(id = R.string.app_purpose),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(40.dp))

                Text(
                    text = stringResource(id = R.string.how_to_steps_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(Modifier.height(16.dp))

                InfoSection(
                    title = stringResource(R.string.step1_title),
                    descriptionResId = R.string.step1_desc
                )
                InfoSection(
                    title = stringResource(R.string.step2_title),
                    descriptionResId = R.string.step2_desc
                )
                InfoSection(
                    title = stringResource(R.string.step3_title),
                    descriptionResId = R.string.step3_desc
                )
                InfoSection(
                    title = stringResource(R.string.step4_title),
                    descriptionResId = R.string.step4_desc
                )

                InfoSection(
                    title = stringResource(R.string.about_title),
                    descriptionResId = R.string.about_desc
                )

                Spacer(Modifier.height(32.dp))

                SpikeStreamGlassCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.support_project_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.support_project_desc),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(24.dp))
                        SpikeStreamPrimaryButton(
                            text = stringResource(R.string.support_project),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/spikestream"))
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${Constants.BASE_URL}/privacy.html"))
                        context.startActivity(intent)
                    }) {
                        Text(stringResource(R.string.privacy_policy), style = MaterialTheme.typography.labelLarge)
                    }
                    Text("•", modifier = Modifier.align(Alignment.CenterVertically))
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${Constants.BASE_URL}/terms.html"))
                        context.startActivity(intent)
                    }) {
                        Text(stringResource(R.string.terms_of_service), style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(24.dp))
                
                Text(
                    text = stringResource(R.string.app_version_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
                
                Spacer(Modifier.height(40.dp))
            }
        }
    }


    @Composable
    fun InfoSection(title: String, descriptionResId: Int) {
        val context = LocalContext.current
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val linkColor = MaterialTheme.colorScheme.primary.toArgb()

        SpikeStreamGlassCard(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply {
                        text = HtmlCompat.fromHtml(
                            context.getString(descriptionResId),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )
                        setTextColor(textColor)
                        movementMethod = LinkMovementMethod.getInstance()
                        setLinkTextColor(linkColor)
                        textSize = 15f
                    }
                },
                update = { view ->
                    view.text = HtmlCompat.fromHtml(
                        context.getString(descriptionResId),
                        HtmlCompat.FROM_HTML_MODE_LEGACY
                    )
                    view.setTextColor(textColor)
                    view.setLinkTextColor(linkColor)
                }
            )
        }
    }




}


