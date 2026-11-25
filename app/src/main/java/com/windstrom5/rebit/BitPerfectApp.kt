package com.windstrom5.rebit

import android.app.Application
import com.windstrom5.rebit.di.allModules
import com.windstrom5.rebit.usb.UsbDacManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class BitPerfectApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@BitPerfectApp)
            modules(allModules)
        }
        // Start listening for USB attach/detach immediately so a DAC that's
        // already plugged in at launch is picked up without user action.
        org.koin.java.KoinJavaComponent.get<UsbDacManager>(UsbDacManager::class.java).start()
        // When a USB DAC is detected: ask permission, read its capabilities, and switch to USB Direct.
        org.koin.java.KoinJavaComponent.get<com.windstrom5.rebit.data.usb.UsbAutoSetup>(
            com.windstrom5.rebit.data.usb.UsbAutoSetup::class.java
        ).start()
        // Record listening history (Home rankings) regardless of which screen is open.
        org.koin.java.KoinJavaComponent.get<com.windstrom5.rebit.data.history.PlayTracker>(
            com.windstrom5.rebit.data.history.PlayTracker::class.java
        ).start()
        // Check for new releases / news from library artists in the background (~every 12 h).
        com.windstrom5.rebit.data.news.NewsScheduler.schedule(this)
        // Pick up newly copied music while idle (incremental, so cheap when nothing changed).
        com.windstrom5.rebit.data.scanner.LibraryScanScheduler.schedulePeriodic(this)
    }
}
