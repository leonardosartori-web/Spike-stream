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
                    .padding(16.dp)
            ) {
            Text(
                text = stringResource(id = R.string.how_to_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(id = R.string.app_purpose),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(id = R.string.how_to_steps_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

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

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Buy Me a Coffee Button
                SpikeStreamPrimaryButton(
                    text = stringResource(R.string.support_project),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/spikestream"))
                        context.startActivity(intent)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // PayPal alternative or Privacy Policy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${Constants.BASE_URL}/privacy.html"))
                        context.startActivity(intent)
                    }) {
                        Text(
                            text = stringResource(R.string.privacy_policy),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )
                    }
                    
                    Text(
                        text = " • ",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )

                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${Constants.BASE_URL}/terms.html"))
                        context.startActivity(intent)
                    }) {
                        Text(
                            text = stringResource(R.string.terms_of_service),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "v1.2 - Powered by Spikestream",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }
        }
    }


    @Composable
    fun InfoSection(title: String, descriptionResId: Int) {
        val context = LocalContext.current

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            text = HtmlCompat.fromHtml(
                                context.getString(descriptionResId),
                                HtmlCompat.FROM_HTML_MODE_LEGACY
                            )
                             setTextColor(android.graphics.Color.parseColor("#0B1120")) // Midnight Navy
                            movementMethod = LinkMovementMethod.getInstance()
                            setLinkTextColor(android.graphics.Color.parseColor("#0077B6")) // Deep Star Cyan
                            textSize = 16f
                        }
                    },
                    update = { view ->
                        view.text = HtmlCompat.fromHtml(
                            context.getString(descriptionResId),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )
                    }
                )
            }
        }
    }



}


