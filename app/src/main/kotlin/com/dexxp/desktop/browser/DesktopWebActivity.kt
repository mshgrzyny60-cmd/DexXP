package com.dexxp.desktop.browser

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dexxp.desktop.DexXpApplication
import com.dexxp.desktop.model.WindowSnapMode
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.DexXPTheme
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * High-Performance Hardware-Accelerated Desktop Web App Container.
 *
 * Runs desktop web applications (CapCut Desktop Studio, YouTube Desktop, WhatsApp Web, Gmail)
 * using an authentic 64-bit Windows 11 Chrome 130 User-Agent, HTTP Client Hints, and early
 * `navigator.platform` / `navigator.userAgentData` spoofing with hardware-accelerated WebGL 2.0.
 */
class DesktopWebActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var onHideCustomVideoAction: (() -> Unit)? = null
    private var hostedDisplayId: Int = Display.INVALID_DISPLAY
    private var displayManager: DisplayManager? = null
    private var recordPackageKey: String = "dexxp.webapp.browser"

    private val displayDisconnectListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == hostedDisplayId && hostedDisplayId != Display.DEFAULT_DISPLAY) {
                val active = displayManager?.getDisplay(displayId)
                if (active == null || !active.isValid) {
                    teardownOnSecondaryDisplayDisconnect()
                }
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == hostedDisplayId && hostedDisplayId != Display.DEFAULT_DISPLAY) {
                teardownOnSecondaryDisplayDisconnect()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enforce hardware acceleration on the host Window for WebGL 2.0 & 60-120fps compositing
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        hostedDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (hostedDisplayId != Display.DEFAULT_DISPLAY) {
            displayManager = getSystemService(DisplayManager::class.java)?.also { dm ->
                dm.registerDisplayListener(displayDisconnectListener, Handler(Looper.getMainLooper()))
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: "https://www.youtube.com"
        val appTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Desktop Web"
        recordPackageKey = intent.getStringExtra(EXTRA_RECORD_KEY) ?: inferRecordKey(targetUrl)
        activeInstances[recordPackageKey] = WeakReference(this)

        val app = application as DexXpApplication

        setContent {
            val runningWindows by app.sessionRepository.runningWindows.collectAsState()
            val isMaximized = runningWindows
                .firstOrNull { it.packageName == recordPackageKey }
                ?.snapMode == WindowSnapMode.MAXIMIZED

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                DexXPTheme(allowDynamicColorOnPhone = false) {
                    DesktopWebScreen(
                        initialUrl = targetUrl,
                        appTitle = appTitle,
                        isMaximized = isMaximized,
                        onInitWebView = { wv ->
                            webView = wv
                            configureDesktopWebSettings(wv)
                        },
                        onRegisterCustomVideoHandlers = { onHide ->
                            onHideCustomVideoAction = onHide
                        },
                        onCustomVideoShown = { view, callback ->
                            customVideoView = view
                            customViewCallback = callback
                        },
                        onCustomVideoHidden = {
                            customVideoView = null
                            customViewCallback?.onCustomViewHidden()
                            customViewCallback = null
                        },
                        onLaunchNativeCapCutLandscape = {
                            val launched = app.appLauncher.launchNativeCapCutInLandscapeContainer(this@DesktopWebActivity)
                            if (launched) {
                                app.sessionRepository.removeWindowRecord(recordPackageKey)
                                runCatching { finishAndRemoveTask() }.onFailure { finish() }
                            }
                        },
                        onMinimize = {
                            app.appLauncher.minimizeWindow(recordPackageKey, this@DesktopWebActivity)
                        },
                        onMaximizeOrRestore = {
                            app.appLauncher.toggleMaximizeOrRestoreWindow(recordPackageKey, this@DesktopWebActivity)
                        },
                        onClose = {
                            app.appLauncher.closeWindow(recordPackageKey, this@DesktopWebActivity)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_RECORD_KEY)?.let { key ->
            recordPackageKey = key
            activeInstances[key] = WeakReference(this)
        }
        val targetUrl = intent.getStringExtra(EXTRA_URL)
        if (!targetUrl.isNullOrBlank() && webView?.url != targetUrl) {
            webView?.let { wv ->
                injectWindowsDesktopPlatformSpoof(wv)
                wv.loadUrl(targetUrl, DESKTOP_CH_HEADERS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureDesktopWebSettings(wv: WebView) {
        // Explicit hardware layer for WebGL 2.0 timeline & video shader acceleration
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Authentic 64-bit Windows 11 Chrome 130 User-Agent (whitelisted by CapCut Online & desktop SaaS)
            userAgentString = DESKTOP_USER_AGENT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        injectWindowsDesktopPlatformSpoof(wv)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customVideoView != null) {
                onHideCustomVideoAction?.invoke()
                return true
            }
            val wv = webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        activeInstances[recordPackageKey] = WeakReference(this)
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    private fun teardownOnSecondaryDisplayDisconnect() {
        if (isFinishing || isDestroyed) return
        activeInstances.remove(recordPackageKey)
        runCatching {
            webView?.apply {
                stopLoading()
                onPause()
                loadUrl("about:blank")
            }
        }
        runCatching { finishAndRemoveTask() }.onFailure { finish() }
    }

    override fun onDestroy() {
        val currentRef = activeInstances[recordPackageKey]?.get()
        if (currentRef === this || currentRef == null) {
            activeInstances.remove(recordPackageKey)
        }
        runCatching { displayManager?.unregisterDisplayListener(displayDisconnectListener) }
        displayManager = null
        webView?.apply {
            stopLoading()
            onPause()
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
        const val EXTRA_RECORD_KEY = "extra_desktop_web_record_key"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        val DESKTOP_CH_HEADERS: Map<String, String> = mapOf(
            "Sec-CH-UA" to "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"",
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Windows\"",
            "Sec-CH-UA-Platform-Version" to "\"15.0.0\"",
            "Sec-CH-UA-Arch" to "\"x86\"",
            "Sec-CH-UA-Bitness" to "\"64\"",
            "Sec-CH-UA-Full-Version-List" to "\"Chromium\";v=\"130.0.6723.117\", \"Google Chrome\";v=\"130.0.6723.117\", \"Not?A_Brand\";v=\"99.0.0.0\""
        )

        /**
         * Client-side Windows 11 64-bit Chrome 130 platform spoofing script.
         * Overrides `navigator.platform`, `navigator.maxTouchPoints`, and `navigator.userAgentData`
         * (including `getHighEntropyValues` and `toJSON`) so CapCut Online and desktop web studios
         * never detect `Linux armv8l` or `Android` touch metadata.
         */
        const val PLATFORM_SPOOF_JS: String = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'platform', { get: () => 'Win32', configurable: true });
                    Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0, configurable: true });
                    Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.', configurable: true });
                    var uaBrands = [
                        { brand: 'Chromium', version: '130' },
                        { brand: 'Google Chrome', version: '130' },
                        { brand: 'Not?A_Brand', version: '99' }
                    ];
                    var fullVersionBrands = [
                        { brand: 'Chromium', version: '130.0.6723.117' },
                        { brand: 'Google Chrome', version: '130.0.6723.117' },
                        { brand: 'Not?A_Brand', version: '99.0.0.0' }
                    ];
                    var winUaData = {
                        brands: uaBrands,
                        mobile: false,
                        platform: 'Windows',
                        toJSON: function() {
                            return { brands: uaBrands, mobile: false, platform: 'Windows' };
                        },
                        getHighEntropyValues: function(hints) {
                            return Promise.resolve({
                                brands: uaBrands,
                                fullVersionList: fullVersionBrands,
                                mobile: false,
                                platform: 'Windows',
                                platformVersion: '15.0.0',
                                architecture: 'x86',
                                bitness: '64',
                                model: '',
                                uaFullVersion: '130.0.6723.117',
                                wow64: false
                            });
                        }
                    };
                    if (navigator.userAgentData || 'userAgentData' in navigator) {
                        Object.defineProperty(navigator, 'userAgentData', {
                            get: () => winUaData,
                            configurable: true
                        });
                    } else if (typeof Navigator !== 'undefined' && Navigator.prototype) {
                        Object.defineProperty(Navigator.prototype, 'userAgentData', {
                            get: () => winUaData,
                            configurable: true
                        });
                    }
                } catch (e) {}
            })();
        """

        private val activeInstances = ConcurrentHashMap<String, WeakReference<DesktopWebActivity>>()

        fun injectWindowsDesktopPlatformSpoof(webView: WebView?) {
            webView?.evaluateJavascript(PLATFORM_SPOOF_JS, null)
        }

        fun inferRecordKey(url: String): String = when {
            url.contains("capcut", ignoreCase = true) -> "dexxp.webapp.capcut_web"
            url.contains("whatsapp", ignoreCase = true) -> "dexxp.webapp.whatsapp_web"
            url.contains("youtube", ignoreCase = true) -> "dexxp.webapp.youtube_desktop"
            url.contains("mail.google", ignoreCase = true) -> "dexxp.webapp.gmail_web"
            else -> "dexxp.webapp.browser"
        }

        fun minimizeByRecordKey(recordKey: String): Boolean {
            val activity = activeInstances[recordKey]?.get() ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            return runCatching { activity.moveTaskToBack(true) }.getOrDefault(false)
        }

        fun bringToFrontByRecordKey(recordKey: String, optionsBundle: Bundle? = null): Boolean {
            val activity = activeInstances[recordKey]?.get() ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            val am = activity.getSystemService(ActivityManager::class.java) ?: return false
            return runCatching {
                if (optionsBundle != null) {
                    am.moveTaskToFront(activity.taskId, ActivityManager.MOVE_TASK_WITH_HOME, optionsBundle)
                } else {
                    am.moveTaskToFront(activity.taskId, ActivityManager.MOVE_TASK_WITH_HOME)
                }
                true
            }.getOrDefault(false)
        }

        fun finishByRecordKey(recordKey: String): Boolean {
            val activity = activeInstances.remove(recordKey)?.get() ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            activity.runOnUiThread {
                runCatching { activity.finishAndRemoveTask() }.onFailure { activity.finish() }
            }
            return true
        }

        fun finishAllSecondaryInstances() {
            val entries = activeInstances.entries.toList()
            activeInstances.clear()
            for ((_, ref) in entries) {
                val activity = ref.get() ?: continue
                if (activity.hostedDisplayId != Display.DEFAULT_DISPLAY &&
                    !activity.isFinishing && !activity.isDestroyed
                ) {
                    activity.runOnUiThread {
                        activity.teardownOnSecondaryDisplayDisconnect()
                    }
                }
            }
        }
    }
}

private val TopBarButtonShape = RoundedCornerShape(8.dp)
private val TopBarPillShape = RoundedCornerShape(50)

@Composable
private fun DesktopWebScreen(
    initialUrl: String,
    appTitle: String,
    isMaximized: Boolean,
    onInitWebView: (WebView) -> Unit,
    onRegisterCustomVideoHandlers: (onHide: () -> Unit) -> Unit,
    onCustomVideoShown: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onCustomVideoHidden: () -> Unit,
    onLaunchNativeCapCutLandscape: () -> Unit,
    onMinimize: () -> Unit,
    onMaximizeOrRestore: () -> Unit,
    onClose: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentTitle by remember { mutableStateOf(appTitle) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    var fullScreenVideoView by remember { mutableStateOf<View?>(null) }

    val hideVideo = {
        fullScreenVideoView = null
        onCustomVideoHidden()
    }

    onRegisterCustomVideoHandlers(hideVideo)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Material 3 Desktop Top Bar with Navigation, CapCut 16:10 Native Fallback & [—] [□] [X] Controls
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Navigation actions: Back, Forward, Refresh, Title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        FilledTonalIconButton(
                            onClick = { webViewRef?.goBack() },
                            enabled = canGoBack,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(32.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            Icon(DesktopIcons.ArrowLeft, contentDescription = "Back", modifier = Modifier.size(17.dp))
                        }

                        FilledTonalIconButton(
                            onClick = { webViewRef?.goForward() },
                            enabled = canGoForward,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(32.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            Icon(DesktopIcons.ArrowRight, contentDescription = "Forward", modifier = Modifier.size(17.dp))
                        }

                        FilledTonalIconButton(
                            onClick = {
                                hasError = false
                                DesktopWebActivity.injectWindowsDesktopPlatformSpoof(webViewRef)
                                webViewRef?.reload()
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .size(32.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            Icon(DesktopIcons.Maximize, contentDescription = "Reload", modifier = Modifier.size(15.dp))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(340.dp)
                        )
                    }

                    // Right Cluster: Native CapCut 16:10 Fallback, Desktop Engine Badge, & Window Controls [—] [□] [X]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isCapCut = currentUrl.contains("capcut", ignoreCase = true) ||
                            currentTitle.contains("CapCut", ignoreCase = true)
                        if (isCapCut) {
                            val mobileBtnHover = remember { MutableInteractionSource() }
                            val isMobileBtnHovered by mobileBtnHover.collectIsHoveredAsState()
                            Surface(
                                onClick = onLaunchNativeCapCutLandscape,
                                shape = TopBarPillShape,
                                color = if (isMobileBtnHovered) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                modifier = Modifier
                                    .height(28.dp)
                                    .hoverable(mobileBtnHover)
                                    .pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = DesktopIcons.AspectLandscape,
                                        contentDescription = null,
                                        tint = if (isMobileBtnHovered) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Mobile App (16:10)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isMobileBtnHovered) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        }
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = TopBarPillShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Text(
                                text = "WIN11 CHROME 130",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        // Minimize [—]
                        WebWindowControlIcon(
                            icon = DesktopIcons.Minimize,
                            contentDescription = "Minimize Window",
                            hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            onClick = onMinimize
                        )

                        // Maximize / Container Toggle [□]
                        WebWindowControlIcon(
                            icon = if (isMaximized) DesktopIcons.RestoreWindow else DesktopIcons.Maximize,
                            contentDescription = "Toggle Container / Fullscreen",
                            hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            onClick = onMaximizeOrRestore
                        )

                        // Close [X] (Red Hover)
                        WebWindowControlIcon(
                            icon = DesktopIcons.Close,
                            contentDescription = "Close Window",
                            hoverColor = Color(0xFFD93025),
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            hoverIconTint = Color.White,
                            onClick = onClose
                        )
                    }
                }
            }

            // Animated Loading Progress Bar
            AnimatedVisibility(
                visible = isLoading && progress < 1.0f && !hasError,
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

            // Hardware-Accelerated WebView Box
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

                            var didRecoverIncompatibilityRedirect = false
                            var injectedEarlyOnCurrentNav = false

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val uri = request?.url ?: return false
                                    val scheme = uri.scheme
                                    val uriStr = uri.toString()

                                    if (scheme == "http" || scheme == "https") {
                                        // Guard against stale cached redirects to capcut.com/incompatibility
                                        if (uriStr.contains("capcut.com/incompatibility", ignoreCase = true) &&
                                            !didRecoverIncompatibilityRedirect
                                        ) {
                                            didRecoverIncompatibilityRedirect = true
                                            DesktopWebActivity.injectWindowsDesktopPlatformSpoof(view)
                                            view?.loadUrl("https://www.capcut.com/my-edit", DesktopWebActivity.DESKTOP_CH_HEADERS)
                                            return true
                                        }
                                        DesktopWebActivity.injectWindowsDesktopPlatformSpoof(view)
                                        return false
                                    }

                                    // Block deep-link bounces to CapCut mobile app so web studio remains in 16:9 desktop mode
                                    if (uriStr.contains("lemon") ||
                                        uriStr.contains("lvoverseas") ||
                                        uriStr.contains("capcut") ||
                                        scheme?.startsWith("snssdk") == true
                                    ) {
                                        return true
                                    }
                                    return runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, uri)
                                        context.startActivity(intent)
                                        true
                                    }.getOrDefault(true)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    injectedEarlyOnCurrentNav = false
                                    DesktopWebActivity.injectWindowsDesktopPlatformSpoof(view)
                                    isLoading = true
                                    hasError = false
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                    if (!url.isNullOrBlank()) currentUrl = url
                                }

                                override fun onLoadResource(view: WebView?, url: String?) {
                                    if (!injectedEarlyOnCurrentNav) {
                                        injectedEarlyOnCurrentNav = true
                                        DesktopWebActivity.injectWindowsDesktopPlatformSpoof(view)
                                    }
                                }

                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                    DesktopWebActivity.injectWindowsDesktopPlatformSpoof(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    DesktopWebActivity.injectWindowsDesktopPlatformSpoof(view)
                                    isLoading = false
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                    view?.title?.let { t ->
                                        if (t.isNotBlank()) currentTitle = t
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        isLoading = false
                                        hasError = true
                                        errorMessage = error?.description?.toString() ?: "Network connection error"
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (newProgress <= 30) {
                                        DesktopWebActivity.injectWindowsDesktopPlatformSpoof(view)
                                    }
                                    progress = newProgress / 100f
                                    if (newProgress >= 100) isLoading = false
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) {
                                        currentTitle = title
                                    }
                                }

                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    request?.let { req ->
                                        runCatching { req.grant(req.resources) }
                                    }
                                }

                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    if (view != null && callback != null) {
                                        fullScreenVideoView = view
                                        onCustomVideoShown(view, callback)
                                    }
                                }

                                override fun onHideCustomView() {
                                    hideVideo()
                                }
                            }

                            onInitWebView(this)
                            webViewRef = this
                            loadUrl(initialUrl, DesktopWebActivity.DESKTOP_CH_HEADERS)
                        }
                    }
                )

                // Error State Overlay Card
                if (hasError) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp,
                            modifier = Modifier
                                .width(440.dp)
                                .padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    DesktopIcons.Close,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )

                                Text(
                                    text = "Unable to Load Page",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = if (errorMessage.isNotBlank()) errorMessage else "Please check your network connection and try again.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Button(
                                    onClick = {
                                        hasError = false
                                        DesktopWebActivity.injectWindowsDesktopPlatformSpoof(webViewRef)
                                        webViewRef?.reload()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = TopBarPillShape
                                ) {
                                    Text("Retry Connection")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen Custom Video Player Overlay (YouTube Fullscreen)
        fullScreenVideoView?.let { videoView ->
            AndroidView(
                factory = { videoView },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }
}

@Composable
private fun WebWindowControlIcon(
    icon: ImageVector,
    contentDescription: String,
    hoverColor: Color,
    iconTint: Color,
    hoverIconTint: Color = iconTint,
    onClick: () -> Unit
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(width = 32.dp, height = 28.dp)
            .clip(TopBarButtonShape)
            .hoverable(hoverSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (isHovered) hoverColor else Color.Transparent)
            .clickable(interactionSource = hoverSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isHovered) hoverIconTint else iconTint,
            modifier = Modifier.size(15.dp)
        )
    }
}
