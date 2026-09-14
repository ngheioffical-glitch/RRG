package com.sectorrrg.app

import android.app.Application
import com.sectorrrg.app.work.RefreshScheduler
import com.sectorrrg.data.RrgRepository

class RrgApp : Application() {

    lateinit var repo: RrgRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = RrgRepository(this)
        RefreshScheduler.schedule(this)
    }
}
