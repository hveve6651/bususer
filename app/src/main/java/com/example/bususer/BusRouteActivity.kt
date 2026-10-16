package com.example.bususer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.example.bususer.BottomNavigation.dp
import com.example.bususer.TagoAPI.RouteBusLocation
import com.example.bususer.TagoAPI.RouteStop
import java.util.concurrent.Executors

class BusRouteActivity : Activity() {

    companion object {
        private const val EXTRA_ROUTE_ID = "routeId"
        private const val EXTRA_ROUTE_NUMBER = "routeNumber"
        private const val EXTRA_STOP_ID = "stopId"
        private const val LOCATION_REFRESH_MS = 60_000L

        fun createIntent(
            context: Context,
            routeId: String,
            routeNumber: String,
            selectedStopId: String? = null
        ): Intent = Intent(context, BusRouteActivity::class.java).apply {
            putExtra(EXTRA_ROUTE_ID, routeId)
            putExtra(EXTRA_ROUTE_NUMBER, routeNumber)
            selectedStopId?.let { putExtra(EXTRA_STOP_ID, it) }
        }
    }

    private lateinit var tvRouteNumber: TextView
    private lateinit var tvRouteInfo: TextView
    private lateinit var tvRouteEndpoints: TextView
    private lateinit var tvRouteStatus: TextView
    private lateinit var routeStopContainer: LinearLayout

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var routeId = ""
    private var routeNumber = ""
    private var selectedStopId = ""

    private var routeStops: List<RouteStop> = emptyList()
    private var busLocations: List<RouteBusLocation> = emptyList()

    private var isLoadingLocations = false

    private val blue = "#2678DB".toColorInt()
    private val dark = "#202936".toColorInt()
    private val secondary = "#697586".toColorInt()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (routeStops.isNotEmpty()) {
                loadBusLocations()
            }
            handler.postDelayed(this, LOCATION_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_busroute)

        tvRouteNumber = findViewById(R.id.tvRouteNumber)
        tvRouteInfo = findViewById(R.id.tvRouteInfo)
        tvRouteEndpoints = findViewById(R.id.tvRouteEndpoints)
        tvRouteStatus = findViewById(R.id.tvRouteStatus)
        routeStopContainer = findViewById(R.id.routeStopContainer)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty()
        routeNumber = intent.getStringExtra(EXTRA_ROUTE_NUMBER).orEmpty()
        selectedStopId = intent.getStringExtra(EXTRA_STOP_ID).orEmpty()

        tvRouteNumber.text =
            if (routeNumber.endsWith("번")) {
                routeNumber
            } else {
                getString(R.string.bus_number_format, routeNumber)
            }

        tvRouteInfo.setText(R.string.jinju_city_bus)

        if (routeId.isBlank()) {
            tvRouteStatus.setText(R.string.route_id_missing)
            return
        }

        loadRouteStops()
    }

    override fun onStart() {
        super.onStart()
        // 화면이 다시 보이면 60초 간격의 실시간 버스 위치 갱신을 시작한다.
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, LOCATION_REFRESH_MS)
    }

    override fun onStop() {
        // 화면이 보이지 않을 때는 예약된 갱신을 취소해 API 호출을 낭비하지 않는다.
        handler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    private fun loadRouteStops() {
        tvRouteStatus.visibility = View.VISIBLE
        tvRouteStatus.setText(R.string.route_stops_loading)

        // 먼저 고정된 노선 순서를 불러온 뒤, 그 위에 실시간 버스 위치를 표시한다.
        executor.execute {
            try {
                val stops = TagoAPI.getRouteStops(routeId)

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    routeStops = stops

                    if (stops.isEmpty()) {
                        tvRouteStatus.setText(R.string.route_stops_empty)
                        return@runOnUiThread
                    }

                    tvRouteEndpoints.text = getString(
                        R.string.route_endpoints_format,
                        stops.first().name,
                        stops.last().name
                    )

                    tvRouteInfo.text = getString(
                        R.string.jinju_bus_stop_count_format,
                        stops.size
                    )

                    tvRouteStatus.setText(R.string.bus_locations_loading)

                    showRouteStops()
                    loadBusLocations()
                }

            } catch (e: Exception) {
                Log.e("BUS_ROUTE", "전체 노선 조회 실패", e)

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        tvRouteStatus.text = getString(
                            R.string.route_load_failed_format,
                            e.message.orEmpty()
                        )
                    }
                }
            }
        }
    }

    private fun loadBusLocations() {
        // 이전 실시간 위치 요청이 진행 중이면 다음 요청을 건너뛴다.
        if (isLoadingLocations || routeStops.isEmpty()) return

        isLoadingLocations = true

        executor.execute {
            try {
                val locations =
                    TagoAPI.getRouteBusLocations(routeId)

                runOnUiThread {
                    isLoadingLocations = false

                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    busLocations = locations

                    tvRouteStatus.visibility = View.VISIBLE
                    tvRouteStatus.text = getString(
                        R.string.operating_buses_refresh_format,
                        locations.size
                    )

                    showRouteStops()
                }

            } catch (e: Exception) {
                Log.e("BUS_ROUTE", "버스 위치 조회 실패", e)

                runOnUiThread {
                    isLoadingLocations = false

                    if (!isFinishing && !isDestroyed) {
                        tvRouteStatus.visibility = View.VISIBLE
                        tvRouteStatus.text = getString(
                            R.string.bus_location_failed_format,
                            e.message.orEmpty()
                        )
                    }
                }
            }
        }
    }

    private fun showRouteStops() {
        routeStopContainer.removeAllViews()

        routeStops.forEachIndexed { index, stop ->

            val isFirst = index == 0
            val isLast = index == routeStops.lastIndex
            val isSelected = stop.id == selectedStopId &&
                    selectedStopId.isNotBlank()

            val busesHere = busLocations.filter { bus ->
                if (bus.nodeId.isNotBlank()) {
                    bus.nodeId == stop.id
                } else {
                    bus.nodeOrder == stop.order
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(76)
                isClickable = true
            }

            val markerColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            markerColumn.addView(
                lineView(!isFirst),
                LinearLayout.LayoutParams(dp(3), dp(28))
            )

            val marker = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isSelected) blue else Color.WHITE)
                    setStroke(dp(3), blue)
                }
            }

            markerColumn.addView(
                marker,
                LinearLayout.LayoutParams(
                    if (isSelected) dp(20) else dp(14),
                    dp(20)
                )
            )

            markerColumn.addView(
                lineView(!isLast),
                LinearLayout.LayoutParams(dp(3), dp(28))
            )

            row.addView(
                markerColumn,
                LinearLayout.LayoutParams(dp(44), dp(76))
            )

            val infoColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            val nameText = TextView(this).apply {
                text = stop.name
                textSize = 16f
                setTextColor(if (isSelected) blue else dark)

                if (isSelected) {
                    setTypeface(null, Typeface.BOLD)
                }
            }

            val description = TextView(this).apply {
                text = when {
                    isFirst -> getString(R.string.route_origin)
                    isLast -> getString(R.string.route_destination)
                    isSelected -> getString(R.string.currently_selected_stop)
                    else -> getString(R.string.stop_order_format, stop.order)
                }
                textSize = 12f
                setTextColor(secondary)
            }

            infoColumn.addView(nameText)
            infoColumn.addView(description)

            if (busesHere.isNotEmpty()) {
                val busText = TextView(this).apply {
                    text = getString(
                        R.string.operating_buses_count_format,
                        busesHere.size
                    )
                    textSize = 13f
                    setTextColor(blue)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, dp(5), 0, 0)
                }

                infoColumn.addView(busText)
            }

            row.addView(
                infoColumn,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            row.setOnClickListener {
                startActivity(BusStopActivity.createIntent(this, stop.id, stop.name))
            }

            routeStopContainer.addView(row)
        }
    }

    private fun lineView(visible: Boolean): View {
        return View(this).apply {
            setBackgroundColor(
                if (visible) blue else Color.TRANSPARENT
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        executor.shutdownNow()
        super.onDestroy()
    }
}
