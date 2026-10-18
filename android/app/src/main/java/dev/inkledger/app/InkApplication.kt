package dev.inkledger.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dev.inkledger.core.RoomLedgerRepository
import dev.inkledger.app.capture.AlipayCaptureManager

class InkApplication : Application() {
    val repository by lazy { RoomLedgerRepository.createLive(this) }
    val captureManager by lazy { AlipayCaptureManager(this, repository) }
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
    }
}
