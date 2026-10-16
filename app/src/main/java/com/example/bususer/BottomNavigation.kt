package com.example.bususer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes

object BottomNavigation {

    // 각 화면의 하단 메뉴에 홈, 경로, 예약, 내 정보 동작을 연결한다.
    fun setup(
        activity: Activity,
        homeViewId: Int,
        routeViewId: Int,
        reservationViewId: Int,
        profileViewId: Int
    ) = with(activity) {
        findViewById<View>(homeViewId).setOnClickListener {
            navigateTo(MainActivity::class.java)
        }
        findViewById<View>(routeViewId).setOnClickListener {
            navigateTo(RouteActivity::class.java)
        }
        findViewById<View>(reservationViewId).setOnClickListener {
            navigateTo(ReservationActivity::class.java)
        }
        findViewById<View>(profileViewId).setOnClickListener {
            showMessage(R.string.profile_not_connected)
        }
    }

    fun navigateHome(activity: Activity) = with(activity) {
        navigateTo(MainActivity::class.java)
    }

    private fun Activity.navigateTo(destination: Class<out Activity>) {
        if (destination.isInstance(this)) return

        startActivity(
            Intent(this, destination).apply {
                // 이미 열려 있는 화면이 있으면 재사용해 화면이 계속 쌓이는 것을 막는다.
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }

    // 코드에서 만든 View의 크기를 기기 화면 밀도에 맞는 픽셀 값으로 변환한다.
    internal fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    internal fun Context.showMessage(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    internal fun Context.showMessage(@StringRes messageRes: Int) =
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()

    internal fun Context.showLongMessage(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
