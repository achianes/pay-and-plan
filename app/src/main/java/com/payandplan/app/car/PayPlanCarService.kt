package com.payandplan.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Pay & Plan on the car screen. Nothing is typed while driving: the day is read out in rows,
 * an appointment hands its place to the navigation app, and the shopping list is ticked off
 * with one tap each, which is all a car screen allows anyway.
 */
class PayPlanCarService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // a debug build rides in whatever head unit or desktop head unit is testing it
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext)
    }
}
