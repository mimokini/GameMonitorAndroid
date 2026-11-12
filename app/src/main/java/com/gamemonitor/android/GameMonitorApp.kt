package com.gamemonitor.android

import android.app.Application
import com.gamemonitor.android.data.GameRepository
import com.gamemonitor.android.data.PinManager
import com.gamemonitor.android.data.SecurityRepository
import com.gamemonitor.android.service.EnforcementManager
import com.gamemonitor.android.service.LauncherIconController
import com.gamemonitor.android.service.Notifier
import com.gamemonitor.android.service.OverlayController

class GameMonitorApp : Application() {

    val repository: GameRepository by lazy { GameRepository(this) }
    val enforcement: EnforcementManager by lazy { EnforcementManager(this) }
    val securityRepository: SecurityRepository by lazy { SecurityRepository(this) }
    val pinManager: PinManager by lazy { PinManager(securityRepository) }
    val launcherIcon: LauncherIconController by lazy { LauncherIconController(this) }

    override fun onCreate() {
        super.onCreate()
        runCatching { Notifier(this).ensureChannels() }
    }
}
