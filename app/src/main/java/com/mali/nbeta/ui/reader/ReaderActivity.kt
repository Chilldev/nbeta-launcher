package com.mali.nbeta.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mali.nbeta.NbetaApp
import com.mali.nbeta.R
import com.mali.nbeta.data.reader.Article
import com.mali.nbeta.data.reader.Block
import com.mali.nbeta.ui.feed.CustomTabs
import com.mali.nbeta.ui.theme.NbetaTheme
import com.mali.nbeta.ui.theme.isDark

/** Clean, native article view. Falls back to a Custom Tab when the page isn't readable. */
class ReaderActivity : ComponentActivity() {
    private val tabs by lazy { CustomTabs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as NbetaApp).graph
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val itemId = intent.getStringExtra(EXTRA_ITEM)
        val source = intent.getStringExtra(EXTRA_SOURCE)
        tabs.warmup()
        setContent {
            val settings by graph.settings.flow.collectAsStateWithLifecycle()
            val cache by graph.feed.cache.collectAsStateWithLifecycle()
            NbetaTheme(settings) {
                val dark = isDark(settings)
                var article by remember { mutableStateOf(graph.reader.cached(url)) }
                var loading by remember { mutableStateOf(article == null) }
                LaunchedEffect(url) {
                    if (article == null) {
                        article = graph.reader.load(url)
                        loading = false
                        if (article == null) {
                            tabs.open(url, dark, useCustomTab = true)
                            finish()
                        }
                    }
                }
                val item = itemId?.let { id -> cache.items.firstOrNull { it.id == id } ?: cache.saved.firstOrNull { it.id == id } }
                val saved = itemId != null && cache.saved.any { it.id == itemId }
                val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
                    topBar = {
                        TopAppBar(
                            title = { Text(source ?: article?.siteName ?: Uri.parse(url).host.orEmpty(), maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                            actions = {
                                TextButton(onClick = { graph.settings.update { it.copy(readerTextScale = (it.readerTextScale - 0.1f).coerceAtLeast(0.8f)) } }) { Text(stringResource(R.string.reader_text_smaller)) }
                                TextButton(onClick = { graph.settings.update { it.copy(readerTextScale = (it.readerTextScale + 0.1f).coerceAtMost(1.6f)) } }) { Text(stringResource(R.string.reader_text_larger)) }
                                if (item != null) IconButton(onClick = { graph.feed.toggleSaved(item) }) {
                                    Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(if (saved) R.string.feed_unsave else R.string.common_save))
                                }
                                IconButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                                    startActivity(Intent.createChooser(send, null))
                                }) { Icon(Icons.Default.Share, stringResource(R.string.common_share)) }
                            },
                            scrollBehavior = scroll,
                        )
                    },
                ) { padding ->
                    val a = article
                    if (a == null) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            if (loading) CircularProgressIndicator()
                        }
                    } else {
                        CompositionLocalProvider(LocalLayoutDirection provides if (a.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            ArticleBody(a, settings.readerTextScale, padding, onLink = { tabs.open(it, dark, true) }, onOriginal = { tabs.open(url, dark, true) })
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tabs.unbind()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_SOURCE = "source"

        fun intent(context: Context, url: String, itemId: String?, source: String?) =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ITEM, itemId)
                .putExtra(EXTRA_SOURCE, source)
    }
}

@Composable
private fun ArticleBody(a: Article, scale: Float, padding: PaddingValues, onLink: (String) -> Unit, onOriginal: () -> Unit) {
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp * scale, lineHeight = 29.sp * scale)
    val linkColor = MaterialTheme.colorScheme.primary
    val links = remember(linkColor) { TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) }
    fun html(s: String): AnnotatedString = AnnotatedString.fromHtml(s, linkStyles = links) { l -> (l as? LinkAnnotation.Url)?.url?.let(onLink) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 48.dp, start = 22.dp, end = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                Text(a.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp * scale, lineHeight = 36.sp * scale), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    listOfNotNull(a.byline, a.siteName, pluralStringResource(R.plurals.reader_min_read, a.minutes, a.minutes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                a.leadImage?.let {
                    AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)))
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
        items(a.blocks.size) { i ->
            Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(vertical = 8.dp)) {
                when (val b = a.blocks[i]) {
                    is Block.Heading -> Text(b.text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp * scale), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    is Block.Paragraph -> Text(html(b.html), style = body)
                    is Block.Quote -> Row {
                        Box(Modifier.width(3.dp).height(48.dp).background(MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.width(14.dp))
                        Text(html(b.html), style = body.copy(fontStyle = FontStyle.Italic))
                    }
                    is Block.Bullets -> Column {
                        b.items.forEachIndexed { n, t ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(if (b.ordered) "${n + 1}." else "•", style = body, modifier = Modifier.width(28.dp))
                                Text(t, style = body)
                            }
                        }
                    }
                    is Block.Image -> Column {
                        AsyncImage(b.url, b.caption, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                        b.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                    }
                    is Block.Code -> Text(
                        b.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp * scale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
        }
        item {
            TextButton(onClick = onOriginal, modifier = Modifier.padding(top = 24.dp)) { Text(stringResource(R.string.reader_view_original)) }
        }
    }
}
