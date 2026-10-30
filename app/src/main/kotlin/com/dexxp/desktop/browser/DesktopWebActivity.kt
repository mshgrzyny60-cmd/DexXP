package com.dexxp.desktop.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.DexXPTheme

/**
 * High-Performance Hardware-Accelerated Desktop Web App Container.
 *
 * Runs desktop web applications (such as YouTube Desktop, WhatsApp Web, Gmail)
 * using an authentic Linux Desktop Chrome User-Agent. Completely eliminates
 * mobile browser redirects, condensed phone layouts, and mobile app hijacking.
 */
class DesktopWebActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullScreenContainer: FrameLayout? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: "https://www.youtube.com"
        val appTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Desktop Web"

        setContent {
            DexXPTheme(allowDynamicColorOnPhone = false) {
                DesktopWebScreen(
                    initialUrl = targetUrl,
                    appTitle = appTitle,
                    onInitWebView = { wv ->
                        webView = wv
                        configureDesktopWebSettings(wv)
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureDesktopWebSettings(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Authentic Linux x86_64 Desktop Chrome User-Agent: Guarantees full desktop UI rendering
            userAgentString = DESKTOP_USER_AGENT
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val wv = webView
            if (customVideoView != null) {
                hideFullscreenVideo()
                return true
            }
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideFullscreenVideo() {
        customVideoView?.let {
            fullScreenContainer?.removeView(it)
            customViewCallback?.onCustomViewHidden()
            customVideoView = null
            customViewCallback = null
            webView?.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_desktop_web_url"
        const val EXTRA_TITLE = "extra_desktop_web_title"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

@Composable
private fun DesktopWebScreen(
    initialUrl: String,
    appTitle: String,
    onInitWebView: (WebView) -> Unit,
    onClose: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentTitle by remember { mutableStateOf(appTitle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Material 3 Desktop Top Bar
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Navigation actions: Back, Forward, Refresh
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                        shape = CircleShape,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(DesktopIcons.ArrowLeft, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    }

                    FilledTonalIconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                        shape = CircleShape,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(DesktopIcons.ArrowRight, contentDescription = "Forward", modifier = Modifier.size(18.dp))
                    }

                    FilledTonalIconButton(
                        onClick = { webViewRef?.reload() },
                        shape = CircleShape,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(DesktopIcons.Maximize, contentDescription = "Reload", modifier = Modifier.size(16.dp))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(360.dp)
                    )
                }

                // Desktop Badge & Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "DESKTOP ENGINE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onClose,
                        shape = CircleShape,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(DesktopIcons.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Animated Loading Progress Bar
        AnimatedVisibility(
            visible = isLoading && progress < 1.0f,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }

        // Hardware-Accelerated WebView
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return false
                                val scheme = uri.scheme
                                if (scheme == "http" || scheme == "https") {
                                    // Keep all browsing strictly inside the desktop WebView!
                                    return false
                                }
                                return runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                    true
                                }.getOrDefault(true)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                view?.title?.let { t ->
                                    if (t.isNotBlank()) currentTitle = t
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                                if (newProgress >= 100) isLoading = false
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) {
                                    currentTitle = title
                                }
                            }
                        }

                        onInitWebView(this)
                        webViewRef = this
                        loadUrl(initialUrl)
                    }
                }
            )
        }
    }
}
