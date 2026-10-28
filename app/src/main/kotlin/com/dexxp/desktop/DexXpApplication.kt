package com.dexxp.desktop

import android.app.Application
import com.dexxp.desktop.audio.AudioRouteManager
import com.dexxp.desktop.bridge.PointerFocusBridge
import com.dexxp.desktop.browser.DesktopBrowserLauncher
import com.dexxp.desktop.clipboard.DesktopClipboardManager
import com.dexxp.desktop.command.CommandPaletteController
import com.dexxp.desktop.continuity.PhoneHubController
import com.dexxp.desktop.files.DesktopFileManager
import com.dexxp.desktop.keyboard.DesktopKeyboardManager
import com.dexxp.desktop.launcher.AppLauncher
import com.dexxp.desktop.launcher.AppRepository
import com.dexxp.desktop.layout.WorkspaceLayoutPresetManager
import com.dexxp.desktop.overview.MissionControlOverviewController
import com.dexxp.desktop.power.PhoneScreenPowerController
import com.dexxp.desktop.state.DesktopSessionRepository
import com.dexxp.desktop.terminal.DesktopTerminalLauncher
import com.dexxp.desktop.windowing.WindowSnappingController
import com.dexxp.desktop.workspace.VirtualWorkspaceManager

/**
 * Lightweight, zero-reflection DI graph for the DexXP Desktop Environment.
 */
class DexXpApplication : Application() {

    lateinit var sessionRepository: DesktopSessionRepository
        private set

    lateinit var appRepository: AppRepository
        private set

    lateinit var snappingController: WindowSnappingController
        private set

    lateinit var pointerFocusBridge: PointerFocusBridge
        private set

    lateinit var appLauncher: AppLauncher
        private set

    lateinit var audioRouteManager: AudioRouteManager
        private set

    lateinit var desktopFileManager: DesktopFileManager
        private set

    lateinit var phoneScreenPowerController: PhoneScreenPowerController
        private set

    lateinit var clipboardManager: DesktopClipboardManager
        private set

    lateinit var virtualWorkspaceManager: VirtualWorkspaceManager
        private set

    lateinit var desktopBrowserLauncher: DesktopBrowserLauncher
        private set

    lateinit var presetManager: WorkspaceLayoutPresetManager
        private set

    lateinit var terminalLauncher: DesktopTerminalLauncher
        private set

    lateinit var commandPaletteController: CommandPaletteController
        private set

    lateinit var missionControlController: MissionControlOverviewController
        private set

    lateinit var phoneHubController: PhoneHubController
        private set

    lateinit var keyboardManager: DesktopKeyboardManager
        private set

    override fun onCreate() {
        super.onCreate()
        sessionRepository = DesktopSessionRepository(this)
        appRepository = AppRepository(this)
        snappingController = WindowSnappingController(sessionRepository)
        pointerFocusBridge = PointerFocusBridge(sessionRepository, snappingController)
        appLauncher = AppLauncher(
            context = this,
            sessionRepository = sessionRepository,
            appRepository = appRepository,
            snappingController = snappingController
        )
        pointerFocusBridge.appLauncherRef = appLauncher

        audioRouteManager = AudioRouteManager(this)
        desktopFileManager = DesktopFileManager(this, sessionRepository)
        phoneScreenPowerController = PhoneScreenPowerController(this, sessionRepository)
        clipboardManager = DesktopClipboardManager(this, sessionRepository, pointerFocusBridge)
        virtualWorkspaceManager = VirtualWorkspaceManager(this, sessionRepository, appLauncher)
        desktopBrowserLauncher = DesktopBrowserLauncher(
            context = this,
            sessionRepository = sessionRepository,
            appLauncher = appLauncher,
            snappingController = snappingController
        )
        presetManager = WorkspaceLayoutPresetManager(
            context = this,
            sessionRepository = sessionRepository,
            appRepository = appRepository,
            appLauncher = appLauncher
        )
        terminalLauncher = DesktopTerminalLauncher(
            context = this,
            sessionRepository = sessionRepository,
            appRepository = appRepository,
            appLauncher = appLauncher
        )
        commandPaletteController = CommandPaletteController(
            context = this,
            sessionRepository = sessionRepository,
            appRepository = appRepository,
            desktopFileManager = desktopFileManager,
            presetManager = presetManager,
            appLauncher = appLauncher,
            browserLauncher = desktopBrowserLauncher,
            terminalLauncher = terminalLauncher,
            phoneScreenPowerController = phoneScreenPowerController
        )
        missionControlController = MissionControlOverviewController(
            sessionRepository = sessionRepository,
            appLauncher = appLauncher,
            virtualWorkspaceManager = virtualWorkspaceManager
        )
        phoneHubController = PhoneHubController(
            context = this,
            appRepository = appRepository
        )
        keyboardManager = DesktopKeyboardManager(
            sessionRepository = sessionRepository,
            appLauncher = appLauncher,
            pointerFocusBridge = pointerFocusBridge,
            virtualWorkspaceManager = virtualWorkspaceManager,
            clipboardManager = clipboardManager,
            commandPaletteController = commandPaletteController,
            missionControlController = missionControlController,
            terminalLauncher = terminalLauncher
        )
    }

    override fun onTerminate() {
        presetManager.shutdown()
        terminalLauncher.shutdown()
        phoneHubController.shutdown()
        appRepository.shutdown()
        audioRouteManager.shutdown()
        desktopFileManager.shutdown()
        phoneScreenPowerController.shutdown()
        clipboardManager.shutdown()
        virtualWorkspaceManager.shutdown()
        sessionRepository.shutdown()
        super.onTerminate()
    }
}
