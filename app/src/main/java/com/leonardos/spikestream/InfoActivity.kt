package com.leonardos.spikestream

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(id = R.string.how_to_title),
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(id = R.string.app_purpose),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(id = R.string.how_to_steps_title),
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
                    fontWeight = FontWeight.SemiBold,
                )

                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            text = HtmlCompat.fromHtml(
                                context.getString(descriptionResId),
                                HtmlCompat.FROM_HTML_MODE_LEGACY
                            )
                            movementMethod = LinkMovementMethod.getInstance()
                            setLinkTextColor(android.graphics.Color.BLUE)
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


