package com.example.bususer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.example.bususer.BottomNavigation.dp
import com.example.bususer.BottomNavigation.showMessage
import com.example.bususer.TagoAPI.BusArrivalInfo
import com.example.bususer.TagoAPI.NearbyBusStop
import java.util.concurrent.Executors
import kotlin.math.ceil

class BusStopActivity : Activity() {

    companion object {
        private const val EXTRA_STOP_ID = "stopId"
        private const val EXTRA_STOP_NAME = "stopName"
        private const val EXTRA_DISTANCE = "distance"

        fun createIntent(
            context: Context,
            stop: NearbyBusStop,
            distanceMeters: Float
        ): Intent = createIntent(context, stop.id, stop.name, distanceMeters)

        fun createIntent(
            context: Context,
            stopId: String,
            stopName: String,
            distanceMeters: Float? = null
        ): Intent = Intent(context, BusStopActivity::class.java).apply {
            putExtra(EXTRA_STOP_ID, stopId)
            putExtra(EXTRA_STOP_NAME, stopName)
            distanceMeters?.let { putExtra(EXTRA_DISTANCE, it.toInt()) }
        }
    }

    private lateinit var tvStopName: TextView
    private lateinit var tvStopDistance: TextView
    private lateinit var busArrivalContainer: LinearLayout

    private val executor = Executors.newSingleThreadExecutor()
    private var stopId = ""

    private var selectedBusId: String? = null
    private var selectedBus: BusArrivalInfo? = null
    private var selectedRadioButton: RadioButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_busstop)
        tvStopName = findViewById(R.id.tvStopName)
        tvStopDistance = findViewById(R.id.tvStopDistance)
        busArrivalContainer = findViewById(R.id.busArrivalContainer)
        loadStopData()
        setupListeners()
        loadBusArrivals()
    }

    private fun loadStopData() {
        stopId = intent.getStringExtra(EXTRA_STOP_ID).orEmpty()
        tvStopName.text = intent.getStringExtra(EXTRA_STOP_NAME)
            ?: getString(R.string.stop_info_title)
        val distance = intent.getIntExtra(EXTRA_DISTANCE, -1)
        tvStopDistance.text = if (distance >= 0) {
            val minutes = ceil(distance / 67.0).toInt()
            getString(R.string.walking_distance_format, minutes, distance)
        } else getString(R.string.distance_unavailable)
    }

    private fun loadBusArrivals() {
        if (stopId.isBlank()) {
            showStatus(getString(R.string.stop_id_missing))
            return
        }
        showStatus(getString(R.string.bus_arrivals_loading))
        // 네트워크 작업은 화면이 멈추지 않도록 별도 스레드에서 실행한다.
        executor.execute {
            try {
                val arrivals = TagoAPI.getBusArrivals(stopId)
                // View 변경은 메인(UI) 스레드에서만 수행한다.
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) displayBusArrivals(arrivals)
                }
            } catch (e: Exception) {
                Log.e("BusStopActivity", "도착정보 조회 실패", e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showStatus(getString(R.string.arrival_lookup_failed))
                    }
                }
            }
        }
    }

    private fun displayBusArrivals(arrivals: List<BusArrivalInfo>) {
        busArrivalContainer.removeAllViews()
        selectedBusId = null
        selectedBus = null
        selectedRadioButton = null
        if (arrivals.isEmpty()) {
            showStatus(getString(R.string.no_upcoming_buses))
            return
        }
        val sorted = arrivals.sortedBy {
            if (it.arrivalSeconds >= 0) it.arrivalSeconds else Int.MAX_VALUE
        }
        sorted.forEach { bus ->
            busArrivalContainer.addView(createBusCard(bus))
        }
    }

    private fun createBusCard(bus: BusArrivalInfo): View {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_busstop_arrival)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // API가 제공한 노선 ID가 없을 때는 버스 번호를 선택 구분값으로 사용한다.
        val busKey = bus.routeId.ifBlank {
            bus.busNumber
        }

        // 버스 한 대를 선택할 수 있는 라디오 버튼을 코드로 만든다.
        val radioButton = RadioButton(this).apply {
            buttonTintList =
                android.content.res.ColorStateList.valueOf(
                    "#075BC4".toColorInt()
                )

            isChecked = selectedBusId == busKey
            contentDescription = getString(
                R.string.select_bus_description_format,
                bus.busNumber
            )
        }

        if (radioButton.isChecked) {
            selectedRadioButton = radioButton
        }

        // 새 버스를 고르면 이전 라디오 버튼을 해제해 하나만 선택되도록 한다.
        radioButton.setOnCheckedChangeListener { _, checked ->

            if (checked) {
                val previous = selectedRadioButton

                selectedBusId = busKey
                selectedBus = bus
                selectedRadioButton = radioButton

                if (previous !== radioButton) {
                    previous?.isChecked = false
                }

            } else if (selectedRadioButton === radioButton) {
                selectedBusId = null
                selectedBus = null
                selectedRadioButton = null
            }
        }

        // 카드 왼쪽에는 버스 번호를 표시한다.
        val number = TextView(this).apply {
            text = getString(R.string.bus_number_format, bus.busNumber)
            textSize = 20f
            setTextColor("#182235".toColorInt())
            setTypeface(null, Typeface.BOLD)
        }

        // 카드 오른쪽에는 초 단위 도착 정보를 분 단위 문구로 바꿔 표시한다.
        val time = TextView(this).apply {
            text = formatArrivalTime(bus.arrivalSeconds)
            textSize = 20f
            setTextColor("#075BC4".toColorInt())
            setTypeface(null, Typeface.BOLD)
        }

        // 한 줄에 선택 버튼, 버스 번호, 도착 시간을 차례대로 배치한다.
        top.addView(radioButton)

        top.addView(
            number,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        top.addView(time)

        card.addView(top)

        // 두 번째 줄에는 버스가 몇 정류장 뒤에 도착하는지 표시한다.
        val detail = TextView(this).apply {
            text = if (bus.remainingStops >= 0) {
                getString(R.string.remaining_stops_format, bus.remainingStops)
            } else {
                getString(R.string.remaining_stops_unavailable)
            }

            textSize = 13f
            setTextColor("#344054".toColorInt())
            setPadding(0, dp(8), 0, 0)
        }

        card.addView(detail)

        // 작은 라디오 버튼 대신 카드 전체를 눌러도 해당 버스가 선택된다.
        card.setOnClickListener {
            radioButton.isChecked = true
        }

        return card
    }

    private fun formatArrivalTime(seconds: Int): String = when {
        seconds < 0 -> getString(R.string.information_unavailable)
        seconds == 0 -> getString(R.string.arriving_soon)
        else -> getString(
            R.string.minutes_later_format,
            ceil(seconds / 60.0).toInt()
        )
    }

    private fun showStatus(message: String) {
        busArrivalContainer.removeAllViews()
        busArrivalContainer.addView(TextView(this).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor("#737D8C".toColorInt())
            setPadding(dp(16), dp(24), dp(16), dp(24))
        })
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnHome).setOnClickListener {
            BottomNavigation.navigateHome(this)
            finish()
        }
        findViewById<View>(R.id.btnRouteDetail).setOnClickListener {
            val bus = selectedBus
            if (bus == null) {
                showMessage(R.string.select_bus_for_route)
                return@setOnClickListener
            }
            if (bus.routeId.isBlank()) {
                showMessage(R.string.route_id_missing)
                return@setOnClickListener
            }
            startActivity(
                BusRouteActivity.createIntent(
                    this,
                    bus.routeId,
                    bus.busNumber,
                    stopId
                )
            )
        }
        findViewById<View>(R.id.btnFindRoute).setOnClickListener {
            val bus = selectedBus

            if (bus == null) {
                showMessage(R.string.select_bus_for_route)
                return@setOnClickListener
            }

            startActivity(
                Intent(this, RouteActivity::class.java).apply {
                    putExtra(RouteActivity.EXTRA_ROUTE_ID, bus.routeId)
                    putExtra(RouteActivity.EXTRA_ROUTE_NUMBER, bus.busNumber)
                    putExtra(RouteActivity.EXTRA_BOARDING_STOP_ID, stopId)
                    putExtra(
                        RouteActivity.EXTRA_BOARDING_STOP_NAME,
                        tvStopName.text.toString()
                    )
                }
            )
        }
        BottomNavigation.setup(
            this,
            R.id.navHome,
            R.id.navRoute,
            R.id.navBookmark,
            R.id.navProfile
        )
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
