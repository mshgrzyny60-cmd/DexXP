package com.dexxp.desktop.browser

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

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
    private var onToggleFullscreenImmersionAction: (() -> Unit)? = null
    private var onExitFullscreenImmersionAction: (() -> Boolean)? = null
    private var onZoomStepAction: ((Int) -> Unit)? = null
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
                        onRegisterCustomVideoHandlers = { onHide, onToggleFullscreen, onExitFullscreen, onZoomStep ->
                            onHideCustomVideoAction = onHide
                            onToggleFullscreenImmersionAction = onToggleFullscreen
                            onExitFullscreenImmersionAction = onExitFullscreen
                            onZoomStepAction = onZoomStep
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_F11 -> {
                    onToggleFullscreenImmersionAction?.invoke()
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    if (customVideoView != null) {
                        onHideCustomVideoAction?.invoke()
                        return true
                    }
                    if (onExitFullscreenImmersionAction?.invoke() == true) {
                        return true
                    }
                }
            }
            val zoomAction = resolveZoomKeyAction(event.keyCode, event.isCtrlPressed)
            if (zoomAction != null) {
                onZoomStepAction?.invoke(zoomAction)
                return true
            }
        }
        val app = application as? DexXpApplication
        if (app != null && app.keyboardManager.onHardwareKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
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

        fun isRecordAlive(recordKey: String): Boolean {
            val activity = activeInstances[recordKey]?.get() ?: return false
            return !activity.isFinishing && !activity.isDestroyed
        }

        fun resolveActiveWindowForPointer(recordKey: String, outScreenBounds: android.graphics.Rect): android.view.Window? {
            val activity = activeInstances[recordKey]?.get() ?: return null
            if (activity.isFinishing || activity.isDestroyed) return null
            val win = activity.window ?: return null
            val decor = win.peekDecorView() ?: return null
            if (!decor.isAttachedToWindow || decor.visibility != View.VISIBLE) return null
            val loc = IntArray(2)
            decor.getLocationOnScreen(loc)
            outScreenBounds.set(loc[0], loc[1], loc[0] + decor.width, loc[1] + decor.height)
            return win
        }

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

        const val DEFAULT_ZOOM_PERCENT = 100
        const val MIN_ZOOM_PERCENT = 50
        const val MAX_ZOOM_PERCENT = 300
        const val ZOOM_STEP_PERCENT = 10

        /**
         * Computes the next clamped zoom percentage given a step delta (+1 = zoom in, -1 = zoom out, 0 = reset to 100%).
         */
        fun computeNextZoomPercent(currentPercent: Int, stepDelta: Int): Int {
            if (stepDelta == 0) return DEFAULT_ZOOM_PERCENT
            return (currentPercent + stepDelta * ZOOM_STEP_PERCENT).coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        }

        /**
         * Resolves a hardware keyboard zoom shortcut (`Ctrl + =/+`, `Ctrl + -`, `Ctrl + 0`) into a zoom step:
         * `+1` = zoom in, `-1` = zoom out, `0` = reset to 100%, `null` = not a zoom key event.
         */
        fun resolveZoomKeyAction(keyCode: Int, isCtrlPressed: Boolean): Int? {
            if (!isCtrlPressed) return null
            return when (keyCode) {
                KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_PLUS,
                KeyEvent.KEYCODE_NUMPAD_ADD -> 1
                KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> -1
                KeyEvent.KEYCODE_0,
                KeyEvent.KEYCODE_NUMPAD_0 -> 0
                else -> null
            }
        }

        fun applyWebZoom(webView: WebView?, targetPercent: Int, stepDelta: Int) {
            val wv = webView ?: return
            val clamped = targetPercent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
            wv.settings.textZoom = clamped
            if (stepDelta > 0) {
                runCatching { wv.zoomIn() }
            } else if (stepDelta < 0) {
                runCatching { wv.zoomOut() }
            }
            wv.evaluateJavascript(
                "try { document.documentElement.style.zoom = '${clamped}%'; } catch(e) {}",
                null
            )
        }
    }
}

private val TopBarButtonShape = RoundedCornerShape(8.dp)
private val TopBarPillShape = RoundedCornerShape(50)

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun DesktopWebScreen(
    initialUrl: String,
    appTitle: String,
    isMaximized: Boolean,
    onInitWebView: (WebView) -> Unit,
    onRegisterCustomVideoHandlers: (
        onHide: () -> Unit,
        onToggleFullscreen: () -> Unit,
        onExitFullscreen: () -> Boolean,
        onZoomStep: (Int) -> Unit
    ) -> Unit,
    onCustomVideoShown: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onCustomVideoHidden: () -> Unit,
    onLaunchNativeCapCutLandscape: () -> Unit,
    onMinimize: () -> Unit,
    onMaximizeOrRestore: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentTitle by remember { mutableStateOf(appTitle) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    // Feature 1.1: True Fullscreen Immersion (F11) & top-edge cursor reveal state
    var isFullscreenImmersion by remember { mutableStateOf(false) }
    var isTopEdgeHeaderRevealed by remember { mutableStateOf(false) }

    // Feature 1.2: Keyboard & Mouse Zoom state
    var zoomPercent by remember { mutableIntStateOf(DesktopWebActivity.DEFAULT_ZOOM_PERCENT) }

    // Feature 1.3: Desktop Right-Click M3 Context Menu & Inspect/Source Drawer state
    var isContextMenuOpen by remember { mutableStateOf(false) }
    var contextMenuOffsetPx by remember { mutableStateOf(IntOffset.Zero) }
    var inspectSourceContent by remember { mutableStateOf<String?>(null) }

    var fullScreenVideoView by remember { mutableStateOf<View?>(null) }

    val hideVideo: () -> Unit = {
        fullScreenVideoView = null
        onCustomVideoHidden()
    }

    val toggleFullscreenImmersion: () -> Unit = {
        val next = !isFullscreenImmersion
        isFullscreenImmersion = next
        isTopEdgeHeaderRevealed = false
    }

    val exitFullscreenImmersion: () -> Boolean = {
        if (inspectSourceContent != null) {
            inspectSourceContent = null
            true
        } else if (isContextMenuOpen) {
            isContextMenuOpen = false
            true
        } else if (isFullscreenImmersion) {
            isFullscreenImmersion = false
            isTopEdgeHeaderRevealed = false
            true
        } else {
            false
        }
    }

    val applyZoomStep: (Int) -> Unit = { stepDelta ->
        val next = DesktopWebActivity.computeNextZoomPercent(zoomPercent, stepDelta)
        zoomPercent = next
        DesktopWebActivity.applyWebZoom(webViewRef, next, stepDelta)
    }

    onRegisterCustomVideoHandlers(
        hideVideo,
        toggleFullscreenImmersion,
        exitFullscreenImmersion,
        applyZoomStep
    )

    val showTopHeader = !isFullscreenImmersion || isTopEdgeHeaderRevealed

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Material 3 Desktop Top Bar with Navigation, Fullscreen Immersion (F11), Zoom Reset, CapCut 16:10 & [—] [□] [X]
            AnimatedVisibility(
                visible = showTopHeader,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
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
                                modifier = Modifier.width(300.dp)
                            )
                        }

                        // Right Cluster: Zoom Badge, Native CapCut 16:10 Fallback, Engine Badge, Fullscreen (F11) & [—] [□] [X]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (zoomPercent != DesktopWebActivity.DEFAULT_ZOOM_PERCENT) {
                                Surface(
                                    onClick = { applyZoomStep(0) },
                                    shape = TopBarPillShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier
                                        .height(26.dp)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${zoomPercent}% • Reset",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }

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

                            // Fullscreen Immersion (F11) Toggle Icon
                            WebWindowControlIcon(
                                icon = DesktopIcons.ResizeHandle,
                                contentDescription = if (isFullscreenImmersion) {
                                    "Exit Fullscreen Immersion (F11 / Esc)"
                                } else {
                                    "Fullscreen Immersion (F11)"
                                },
                                hoverColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = if (isFullscreenImmersion) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                hoverIconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = toggleFullscreenImmersion
                            )

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
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true

                            var didRecoverIncompatibilityRedirect = false
                            var injectedEarlyOnCurrentNav = false

                            // Intercept Ctrl + Mouse Scroll Wheel zooming, top-edge fullscreen reveal, and secondary right-click
                            setOnGenericMotionListener { _, event ->
                                val d = resources.displayMetrics.density
                                if (isFullscreenImmersion) {
                                    if (!isTopEdgeHeaderRevealed && event.y <= 10f * d) {
                                        isTopEdgeHeaderRevealed = true
                                    } else if (isTopEdgeHeaderRevealed && event.y > 54f * d) {
                                        isTopEdgeHeaderRevealed = false
                                    }
                                }
                                if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                                    (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                                ) {
                                    contextMenuOffsetPx = IntOffset(event.x.roundToInt(), event.y.roundToInt())
                                    isContextMenuOpen = true
                                    return@setOnGenericMotionListener true
                                }
                                if (event.actionMasked == MotionEvent.ACTION_SCROLL &&
                                    (event.metaState and KeyEvent.META_CTRL_ON) != 0
                                ) {
                                    val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                                    if (vScroll > 0.05f) {
                                        applyZoomStep(1)
                                        return@setOnGenericMotionListener true
                                    } else if (vScroll < -0.05f) {
                                        applyZoomStep(-1)
                                        return@setOnGenericMotionListener true
                                    }
                                }
                                false
                            }

                            setOnTouchListener { _, event ->
                                val d = resources.displayMetrics.density
                                if (isFullscreenImmersion) {
                                    if (!isTopEdgeHeaderRevealed && event.y <= 10f * d) {
                                        isTopEdgeHeaderRevealed = true
                                    } else if (isTopEdgeHeaderRevealed && event.y > 54f * d) {
                                        isTopEdgeHeaderRevealed = false
                                    }
                                }
                                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                                    (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                                ) {
                                    contextMenuOffsetPx = IntOffset(event.x.roundToInt(), event.y.roundToInt())
                                    isContextMenuOpen = true
                                    return@setOnTouchListener true
                                }
                                false
                            }

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
                                        ctx.startActivity(intent)
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
                                    if (zoomPercent != DesktopWebActivity.DEFAULT_ZOOM_PERCENT) {
                                        DesktopWebActivity.applyWebZoom(view, zoomPercent, 0)
                                    }
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

                // Top-Edge Hover Sensor Strip for Fullscreen Immersion Mode (`F11`)
                if (isFullscreenImmersion && !isTopEdgeHeaderRevealed) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .align(Alignment.TopCenter)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Enter ||
                                            event.type == PointerEventType.Move ||
                                            event.type == PointerEventType.Press
                                        ) {
                                            isTopEdgeHeaderRevealed = true
                                        }
                                    }
                                }
                            }
                    )
                }

                // Feature 1.3: Material 3 Desktop Right-Click Context Menu
                if (isContextMenuOpen) {
                    val menuOffsetDp = with(density) {
                        DpOffset(contextMenuOffsetPx.x.toDp(), contextMenuOffsetPx.y.toDp())
                    }
                    Box(modifier = Modifier.offset { contextMenuOffsetPx }) {
                        DropdownMenu(
                            expanded = isContextMenuOpen,
                            onDismissRequest = { isContextMenuOpen = false },
                            offset = DpOffset(0.dp, 0.dp),
                            shape = RoundedCornerShape(14.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(DesktopIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    webViewRef?.evaluateJavascript(
                                        "(window.getSelection ? window.getSelection().toString() : '')"
                                    ) { rawJson ->
                                        val selected = decodeJsStringResult(rawJson)
                                        if (selected.isNotEmpty()) {
                                            val cm = context.getSystemService(ClipboardManager::class.java)
                                            cm?.setPrimaryClip(ClipData.newPlainText("Web Selection", selected))
                                        }
                                    }
                                    webViewRef?.evaluateJavascript(
                                        "try { document.execCommand('copy'); } catch(e) {}",
                                        null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Paste", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(DesktopIcons.Clipboard, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                    val clipText = cm?.primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                        ?.toString()
                                        .orEmpty()
                                    if (clipText.isNotEmpty()) {
                                        val escaped = escapeJsString(clipText)
                                        webViewRef?.evaluateJavascript(
                                            """
                                            (function() {
                                                try {
                                                    if (!document.execCommand('insertText', false, '$escaped')) {
                                                        var el = document.activeElement;
                                                        if (el && typeof el.value === 'string') {
                                                            var s = el.selectionStart || 0;
                                                            var e = el.selectionEnd || 0;
                                                            el.value = el.value.slice(0, s) + '$escaped' + el.value.slice(e);
                                                            el.dispatchEvent(new Event('input', { bubbles: true }));
                                                        }
                                                    }
                                                } catch (err) {}
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Cut", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(DesktopIcons.FileGeneric, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    webViewRef?.evaluateJavascript(
                                        "(window.getSelection ? window.getSelection().toString() : '')"
                                    ) { rawJson ->
                                        val selected = decodeJsStringResult(rawJson)
                                        if (selected.isNotEmpty()) {
                                            val cm = context.getSystemService(ClipboardManager::class.java)
                                            cm?.setPrimaryClip(ClipData.newPlainText("Web Cut", selected))
                                        }
                                        webViewRef?.evaluateJavascript(
                                            "try { document.execCommand('cut'); } catch(e) {}",
                                            null
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Select All", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(DesktopIcons.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    webViewRef?.evaluateJavascript(
                                        "try { document.execCommand('selectAll'); } catch(e) {}",
                                        null
                                    )
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text("Back", style = MaterialTheme.typography.bodyMedium) },
                                enabled = canGoBack,
                                leadingIcon = {
                                    Icon(DesktopIcons.ArrowLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    webViewRef?.goBack()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Forward", style = MaterialTheme.typography.bodyMedium) },
                                enabled = canGoForward,
                                leadingIcon = {
                                    Icon(DesktopIcons.ArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    webViewRef?.goForward()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reload", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(DesktopIcons.Maximize, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    DesktopWebActivity.injectWindowsDesktopPlatformSpoof(webViewRef)
                                    webViewRef?.reload()
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text("Inspect / Source", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(DesktopIcons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    isContextMenuOpen = false
                                    webViewRef?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var html = document.documentElement ? document.documentElement.outerHTML : '';
                                                return 'URL: ' + location.href + '\nTitle: ' + document.title + '\nPlatform: ' + navigator.platform + ' | UA: ' + navigator.userAgent + '\n\n' + html.slice(0, 16000);
                                            } catch (e) {
                                                return 'Unable to inspect DOM: ' + e;
                                            }
                                        })();
                                        """.trimIndent()
                                    ) { rawJson ->
                                        inspectSourceContent = decodeJsStringResult(rawJson).ifBlank {
                                            "URL: $currentUrl\nTitle: $currentTitle"
                                        }
                                    }
                                }
                            )
                        }
                    }
                    // Consume unused reference warning while keeping density calculation ready
                    if (menuOffsetDp.x < 0.dp) Unit
                }

                // Inline Material 3 Inspect / Source Drawer Overlay
                inspectSourceContent?.let { sourceDump ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.52f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { inspectSourceContent = null },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 8.dp,
                            modifier = Modifier
                                .fillMaxWidth(0.84f)
                                .heightIn(min = 280.dp, max = 560.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {}
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = DesktopIcons.Terminal,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "DOM Inspector & Page Source",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                val cm = context.getSystemService(ClipboardManager::class.java)
                                                cm?.setPrimaryClip(ClipData.newPlainText("Page Source", sourceDump))
                                            },
                                            shape = TopBarPillShape
                                        ) {
                                            Icon(DesktopIcons.Copy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Copy Source", style = MaterialTheme.typography.labelSmall)
                                        }
                                        WebWindowControlIcon(
                                            icon = DesktopIcons.Close,
                                            contentDescription = "Close Inspector",
                                            hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            iconTint = MaterialTheme.colorScheme.onSurface,
                                            onClick = { inspectSourceContent = null }
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    Text(
                                        text = sourceDump,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

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

private fun decodeJsStringResult(rawJson: String?): String {
    if (rawJson.isNullOrBlank() || rawJson == "null") return ""
    val unwrapped = if (rawJson.length >= 2 && rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
        rawJson.substring(1, rawJson.length - 1)
    } else {
        rawJson
    }
    return unwrapped
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

private fun escapeJsString(text: String): String {
    return text
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
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
