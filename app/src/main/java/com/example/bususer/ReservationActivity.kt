package com.example.bususer

import android.app.Activity
import android.os.Bundle
import android.view.View

class ReservationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservation)

        BottomNavigation.setup(
            this,
            R.id.navHome,
            R.id.navRoute,
            R.id.navReservation,
            R.id.navProfile
        )

        findViewById<View>(R.id.btnHome).setOnClickListener {
            BottomNavigation.navigateHome(this)
        }
    }
}
