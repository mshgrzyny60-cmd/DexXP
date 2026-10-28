package com.dexxp.desktop.continuity

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dexxp.desktop.bridge.DesktopPointerAccessibilityService
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.model.CallPhase
import com.dexxp.desktop.model.DetectedOtpCode
import com.dexxp.desktop.model.IncomingCallState
import com.dexxp.desktop.ui.icons.DesktopIcons
import com.dexxp.desktop.ui.theme.StatusGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Mobile Continuity & Phone Hub Engine (Incoming Calls + Smart SMS OTP Copier).
 *
 * 1. **Floating Incoming Call Banner**:
 *    - Combines Android 12+ [TelephonyManager.registerTelephonyCallback] (`CallStateListener`) with
 *      live `Notification.CATEGORY_CALL` / Telecom notification inspection so caller names and
 *      Answer / Decline / Speaker controls work seamlessly across cellular & VoIP calls.
 * 2. **Smart SMS OTP Copier**:
 *    - Parses 4–8 digit verification codes from incoming SMS and messaging notifications and
 *      exposes a 1-click **"Copy Code"** chip above the Taskbar notification tray.
 */
class PhoneHubController(
    context: Context,
    private val appRepository: AppRepository
) {
    private val appContext = context.applicationContext
    private val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
    private val telecomManager = appContext.getSystemService(TelecomManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val _incomingCallState = MutableStateFlow<IncomingCallState?>(null)
    val incomingCallState: StateFlow<IncomingCallState?> = _incomingCallState.asStateFlow()

    private val _detectedOtp = MutableStateFlow<DetectedOtpCode?>(null)
    val detectedOtp: StateFlow<DetectedOtpCode?> = _detectedOtp.asStateFlow()

    @Volatile
    private var isTelephonyCallbackRegistered = false

    @Volatile
    private var lastTelephonyState: Int = TelephonyManager.CALL_STATE_IDLE

    @Volatile
    private var dismissedCallKey: String? = null

    private val dismissedOtpKeys = HashSet<String>()

    private val callStateCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            lastTelephonyState = state
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (_incomingCallState.value == null) {
                        _incomingCallState.value = IncomingCallState(
                            callerTitle = "Incoming Phone Call",
                            callerSubtitle = "Ringing on primary phone...",
                            phase = CallPhase.RINGING,
                            isSpeakerEnabled = isSpeakerphoneActive()
                        )
                    } else {
                        _incomingCallState.update { it?.copy(phase = CallPhase.RINGING) }
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    _incomingCallState.update { existing ->
                        (existing ?: IncomingCallState(
                            callerTitle = "Active Phone Call",
                            callerSubtitle = "Connected",
                            phase = CallPhase.ACTIVE
                        )).copy(
                            phase = CallPhase.ACTIVE,
                            callerSubtitle = "Call in progress",
                            isSpeakerEnabled = isSpeakerphoneActive()
                        )
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    dismissedCallKey = null
                    if (_incomingCallState.value?.notificationKey == null) {
                        _incomingCallState.value = null
                    }
                }
            }
        }
    }

    init {
        refreshTelephonyCallbackRegistration()
    }

    /**
     * Registers [TelephonyCallback] whenever `READ_PHONE_STATE` is granted; safe no-op otherwise.
     */
    fun refreshTelephonyCallbackRegistration() {
        if (isTelephonyCallbackRegistered) return
        val tm = telephonyManager ?: return
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        runCatching {
            tm.registerTelephonyCallback(appContext.mainExecutor, callStateCallback)
            isTelephonyCallbackRegistered = true
        }
    }

    /**
     * Invoked by `DesktopNotificationListenerService` whenever active phone notifications change.
     * Reconciles call notifications (`CATEGORY_CALL`) and extracts 4–8 digit SMS OTP codes.
     */
    fun onRawNotificationsSynced(rawList: Array<StatusBarNotification>) {
        val selfPkg = appContext.packageName
        var activeCallFromNotif: IncomingCallState? = null
        var newestOtp: DetectedOtpCode? = null

        for (sbn in rawList) {
            if (sbn.packageName == selfPkg) continue
            val notification = sbn.notification ?: continue
            val extras = notification.extras ?: Bundle.EMPTY

            val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE))
                ?.toString()?.trim().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()

            // 1. Call Notification Inspection (Cellular Dialer + WhatsApp/Telegram/Meet VoIP)
            val isCallCategory = notification.category == Notification.CATEGORY_CALL ||
                (isDialerOrVoipPackage(sbn.packageName) && notification.fullScreenIntent != null)
            if (isCallCategory && sbn.key != dismissedCallKey) {
                var answerIntent: PendingIntent? = null
                var declineIntent: PendingIntent? = null
                val actions = notification.actions
                if (actions != null) {
                    for (act in actions) {
                        val label = act.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
                        if (act.actionIntent == null) continue
                        when {
                            ANSWER_KEYWORDS.any { label.contains(it) } -> {
                                if (answerIntent == null) answerIntent = act.actionIntent
                            }
                            DECLINE_KEYWORDS.any { label.contains(it) } -> {
                                if (declineIntent == null) declineIntent = act.actionIntent
                            }
                        }
                    }
                }
                val isOngoingActive = lastTelephonyState == TelephonyManager.CALL_STATE_OFFHOOK ||
                    (answerIntent == null && declineIntent != null)
                activeCallFromNotif = IncomingCallState(
                    callerTitle = title.ifEmpty { "Incoming Call" },
                    callerSubtitle = body.ifEmpty {
                        if (isOngoingActive) "Call in progress" else "Incoming call"
                    },
                    phase = if (isOngoingActive) CallPhase.ACTIVE else CallPhase.RINGING,
                    isSpeakerEnabled = isSpeakerphoneActive(),
                    answerIntent = answerIntent,
                    declineIntent = declineIntent,
                    notificationKey = sbn.key
                )
            }

            // 2. Smart SMS / Messaging OTP Extraction
            if (sbn.key !in dismissedOtpKeys && (title.isNotEmpty() || body.isNotEmpty())) {
                val ageMs = System.currentTimeMillis() - sbn.postTime
                if (ageMs <= OTP_FRESHNESS_WINDOW_MS) {
                    val extracted = extractSmartOtpCode(title, body)
                    if (extracted != null && (newestOtp == null || sbn.postTime > newestOtp.detectedAtMs)) {
                        val appLabel = appRepository.findByPackage(sbn.packageName)?.label
                            ?: sbn.packageName.substringAfterLast('.')
                        newestOtp = DetectedOtpCode(
                            code = extracted,
                            senderTitle = title.ifEmpty { appLabel },
                            appName = appLabel,
                            detectedAtMs = sbn.postTime,
                            notificationKey = sbn.key
                        )
                    }
                }
            }
        }

        if (activeCallFromNotif != null) {
            _incomingCallState.value = activeCallFromNotif
        } else if (lastTelephonyState == TelephonyManager.CALL_STATE_IDLE) {
            _incomingCallState.value = null
        }

        _detectedOtp.value = newestOtp
    }

    @SuppressLint("MissingPermission")
    fun answerIncomingCall(): Boolean {
        val call = _incomingCallState.value ?: return false
        call.answerIntent?.let { pi ->
            if (firePendingIntent(pi)) {
                _incomingCallState.update { it?.copy(phase = CallPhase.ACTIVE, callerSubtitle = "Call in progress") }
                return true
            }
        }

        val canAnswerTelecom = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (canAnswerTelecom) {
            return runCatching {
                @Suppress("DEPRECATION")
                telecomManager?.acceptRingingCall()
                _incomingCallState.update { it?.copy(phase = CallPhase.ACTIVE, callerSubtitle = "Call in progress") }
                true
            }.getOrDefault(false)
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun declineOrHangupCall(): Boolean {
        val call = _incomingCallState.value ?: return false
        call.notificationKey?.let { dismissedCallKey = it }

        call.declineIntent?.let { pi ->
            if (firePendingIntent(pi)) {
                _incomingCallState.value = null
                return true
            }
        }

        val canEndTelecom = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (canEndTelecom) {
            val ended = runCatching {
                @Suppress("DEPRECATION")
                telecomManager?.endCall() == true
            }.getOrDefault(false)
            if (ended) {
                _incomingCallState.value = null
                return true
            }
        }

        _incomingCallState.value = null
        return false
    }

    fun toggleCallSpeaker(): Boolean {
        val am = audioManager ?: return false
        val nextSpeaker = !isSpeakerphoneActive()

        val applied = runCatching {
            if (nextSpeaker) {
                val speakerDev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speakerDev != null) {
                    am.setCommunicationDevice(speakerDev)
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    true
                }
            } else {
                am.clearCommunicationDevice()
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
                true
            }
        }.getOrDefault(false)

        _incomingCallState.update { it?.copy(isSpeakerEnabled = nextSpeaker) }
        return applied
    }

    fun copyAndInjectOtp(otp: DetectedOtpCode) {
        val cm = appContext.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("OTP Verification Code", otp.code))
        DesktopPointerAccessibilityService.instance?.pasteIntoFocusedExternalNode(otp.code)
        dismissedOtpKeys.add(otp.notificationKey)
    }

    fun dismissOtpBanner(otp: DetectedOtpCode) {
        dismissedOtpKeys.add(otp.notificationKey)
        if (_detectedOtp.value?.notificationKey == otp.notificationKey) {
            _detectedOtp.value = null
        }
    }

    private fun isSpeakerphoneActive(): Boolean {
        val am = audioManager ?: return false
        val commDev = runCatching { am.communicationDevice }.getOrNull()
        if (commDev != null) {
            return commDev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        @Suppress("DEPRECATION")
        return am.isSpeakerphoneOn
    }

    private fun firePendingIntent(pendingIntent: PendingIntent): Boolean {
        val opts = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        }.toBundle()
        return runCatching {
            pendingIntent.send(appContext, 0, null, null, null, null, opts)
            true
        }.getOrDefault(false)
    }

    private fun isDialerOrVoipPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.US)
        return lower.contains("dialer") ||
            lower.contains("incallui") ||
            lower.contains("telecom") ||
            lower.contains("whatsapp") ||
            lower.contains("telegram")
    }

    fun shutdown() {
        if (isTelephonyCallbackRegistered) {
            runCatching { telephonyManager?.unregisterTelephonyCallback(callStateCallback) }
            isTelephonyCallbackRegistered = false
        }
    }

    companion object {
        private const val OTP_FRESHNESS_WINDOW_MS = 10 * 60 * 1000L

        private val ANSWER_KEYWORDS = listOf(
            "answer", "accept", "pick up", "ענה", "קבל"
        )
        private val DECLINE_KEYWORDS = listOf(
            "decline", "reject", "hang up", "end", "dismiss", "דחה", "נתק", "סיום"
        )

        private val OTP_CONTEXT_KEYWORDS = listOf(
            "code", "otp", "verification", "verify", "passcode", "one-time",
            "one time", "security", "auth", "login", "pin", "אימות", "קוד", "סיסמה"
        )

        // Matches G-123456, 123-456, or 4–8 standalone digits
        private val PREFIXED_OTP_REGEX = Regex("""\b[A-Za-z]-(\d{4,8})\b""")
        private val HYPHENATED_SIX_REGEX = Regex("""\b(\d{3})-(\d{3})\b""")
        private val NUMERIC_OTP_REGEX = Regex("""\b(\d{4,8})\b""")

        /**
         * Extracts 4–8 digit verification codes from incoming SMS / messaging notifications while
         * rejecting non-OTP numbers (e.g., standalone calendar years or messages lacking OTP context).
         */
        fun extractSmartOtpCode(title: String, body: String): String? {
            if (body.isBlank()) return null

            PREFIXED_OTP_REGEX.find(body)?.groupValues?.getOrNull(1)?.let { return it }

            val combinedLower = "$title $body".lowercase(Locale.ROOT)
            val hasOtpContext = OTP_CONTEXT_KEYWORDS.any { combinedLower.contains(it) }
            if (!hasOtpContext) return null

            HYPHENATED_SIX_REGEX.find(body)?.let { match ->
                return match.groupValues[1] + match.groupValues[2]
            }

            val candidates = NUMERIC_OTP_REGEX.findAll(body).map { it.groupValues[1] }.toList()
            if (candidates.isEmpty()) return null

            // Prefer 6-digit codes first, then 5/7/8-digit, then 4-digit (excluding likely calendar years 2020..2035 unless sole hit after ':')
            candidates.firstOrNull { it.length == 6 }?.let { return it }
            candidates.firstOrNull { it.length in 5..8 }?.let { return it }
            return candidates.firstOrNull { token ->
                val asInt = token.toIntOrNull() ?: 0
                asInt !in 2020..2035 || candidates.size == 1 && body.contains(": $token")
            }
        }
    }
}

/**
 * Non-intrusive floating Material 3 Incoming / Active Call Banner anchored at top-end of Desktop.
 */
@Composable
fun IncomingCallFloatingBanner(
    callState: IncomingCallState,
    onAnswer: () -> Unit,
    onDeclineOrHangup: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(min = 340.dp, max = 430.dp)
            .border(
                width = 1.dp,
                color = if (callState.phase == CallPhase.RINGING) {
                    StatusGreen.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                },
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 12.dp,
        shadowElevation = 22.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(StatusGreen.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = DesktopIcons.PhoneCall,
                    contentDescription = null,
                    tint = StatusGreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callState.callerTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = callState.callerSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speaker Output Toggle
                Surface(
                    onClick = onToggleSpeaker,
                    shape = CircleShape,
                    color = if (callState.isSpeakerEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.VolumeUp,
                            contentDescription = "Speaker Output",
                            tint = if (callState.isSpeakerEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Answer Button (when ringing)
                if (callState.phase == CallPhase.RINGING) {
                    Surface(
                        onClick = onAnswer,
                        shape = CircleShape,
                        color = Color(0xFF1E8E3E),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = DesktopIcons.PhoneCall,
                                contentDescription = "Answer Call",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                // Decline / Hang Up Button
                Surface(
                    onClick = onDeclineOrHangup,
                    shape = CircleShape,
                    color = Color(0xFFD93025),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = DesktopIcons.PhoneHangup,
                            contentDescription = "Decline / End Call",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1-Click Smart SMS OTP Notification Preview Banner anchored above the bottom-end Taskbar tray.
 */
@Composable
fun SmartOtpTaskbarBanner(
    otp: DetectedOtpCode,
    onCopyCode: (DetectedOtpCode) -> Unit,
    onDismiss: (DetectedOtpCode) -> Unit,
    modifier: Modifier = Modifier
) {
    var copiedFeedback by remember(otp.notificationKey) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.70f),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        tonalElevation = 10.dp,
        shadowElevation = 20.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = DesktopIcons.KeyOtp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column {
                Text(
                    text = "Verification Code • ${otp.senderTitle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = if (copiedFeedback) "Copied & pasted to active field" else "From ${otp.appName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copiedFeedback) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                onClick = {
                    copiedFeedback = true
                    onCopyCode(otp)
                },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (copiedFeedback) DesktopIcons.CheckCircle else DesktopIcons.Copy,
                        contentDescription = "Copy Verification Code",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (copiedFeedback) "Copied ${otp.code}" else "Copy Code ${otp.code}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            IconButton(
                onClick = { onDismiss(otp) },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = DesktopIcons.Close,
                    contentDescription = "Dismiss OTP",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
