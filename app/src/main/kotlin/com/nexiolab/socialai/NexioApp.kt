package com.nexiolab.socialai

import android.app.Application
import com.nexiolab.socialai.data.backup.BackupService
import com.nexiolab.socialai.work.Jobs

class NexioApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        BackupService.recover(this) // Must finish before anything opens the database.
        Jobs.dailyMaintenance(this)
    }
}
