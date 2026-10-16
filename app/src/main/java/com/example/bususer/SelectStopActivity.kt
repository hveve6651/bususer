package com.example.bususer

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.example.bususer.BottomNavigation.dp
import com.example.bususer.BottomNavigation.showLongMessage
import com.example.bususer.BottomNavigation.showMessage
import com.example.bususer.TagoAPI.RouteStop
import java.util.concurrent.Executors

class SelectStopActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var radioGroup: RadioGroup
    private lateinit var progressStops: ProgressBar
    private lateinit var txtLoadStatus: TextView
    private lateinit var btnConfirmStop: Button

    private var selectedStop: RouteStop? = null
    private var routeId = ""
    private var routeNumber = ""
    private var boardingStopId = ""
    private var boardingStopName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_select_stop)

        routeId = intent.getStringExtra(RouteActivity.EXTRA_ROUTE_ID).orEmpty()
        routeNumber = intent.getStringExtra(RouteActivity.EXTRA_ROUTE_NUMBER)
            ?.trim()
            ?.removeSuffix("번")
            .orEmpty()
        boardingStopId =
            intent.getStringExtra(RouteActivity.EXTRA_BOARDING_STOP_ID).orEmpty()
        boardingStopName =
            intent.getStringExtra(RouteActivity.EXTRA_BOARDING_STOP_NAME)
                ?.trim()
                .orEmpty()

        // 이후 여러 함수에서 사용하는 화면 요소를 ID로 연결한다.
        radioGroup = findViewById(R.id.stopRadioGroup)
        progressStops = findViewById(R.id.progressStops)
        txtLoadStatus = findViewById(R.id.txtLoadStatus)
        btnConfirmStop = findViewById(R.id.btnConfirmStop)
        val boardingStopLabel = boardingStopName.ifBlank {
            getString(R.string.not_selected)
        }
        findViewById<TextView>(R.id.txtSelectedRoute).text =
            if (routeNumber.isBlank()) {
                getString(R.string.bus_not_selected)
            } else {
                getString(R.string.bus_number_format, routeNumber)
            }
        findViewById<TextView>(R.id.txtBoardingDescription).text =
            getString(
                R.string.boarding_description_format,
                boardingStopLabel
            )
        findViewById<TextView>(R.id.txtBoardingStopName).text = boardingStopLabel

        val btnViewMap = findViewById<Button>(R.id.btnViewMap)
        val btnHome = findViewById<TextView>(R.id.btnHome)

        // 하차 정류장을 고르기 전에는 완료 버튼을 누를 수 없게 한다.
        btnConfirmStop.isEnabled = false

        // 경로 화면에서 전달받은 노선과 승차 정류장이 있을 때만 조회한다.
        if (routeNumber.isNotBlank() &&
            (boardingStopId.isNotBlank() || boardingStopName.isNotBlank())
        ) {
            loadStops()
        } else {
            progressStops.visibility = View.GONE
            txtLoadStatus.setText(R.string.select_route_from_home)
        }

        // 선택된 정류장 정보를 결과 Intent에 담아 경로 화면으로 돌려준다.
        btnConfirmStop.setOnClickListener {
            val stop = selectedStop

            if (stop == null) {
                showMessage(R.string.select_alighting_prompt)
                return@setOnClickListener
            }

            // RouteActivity가 결과를 구분할 수 있도록 정류장 ID와 이름을 함께 전달한다.
            val resultIntent = Intent().apply {
                putExtra(RouteActivity.EXTRA_SELECTED_STOP_ID, stop.id)
                putExtra(RouteActivity.EXTRA_SELECTED_STOP_NAME, stop.name)
            }

            setResult(RESULT_OK, resultIntent)
            finish()
        }

        // 선택하지 않고 지도 보기 버튼을 누르면 현재 화면만 닫는다.
        btnViewMap.setOnClickListener {
            finish()
        }

        btnHome.setOnClickListener {
            BottomNavigation.navigateHome(this)
            finish()
        }

        BottomNavigation.setup(
            this,
            R.id.navHome,
            R.id.navRoute,
            R.id.navBooking,
            R.id.navProfile
        )
    }

    // 노선의 전체 정류장을 조회한 뒤 승차 정류장 이후 항목만 남긴다.
    private fun loadStops() {

        progressStops.visibility = View.VISIBLE
        txtLoadStatus.setText(R.string.stops_loading)

        executor.execute {
            try {
                val resolvedRouteId = routeId.ifBlank {
                    TagoAPI.getRouteId(routeNumber)
                }

                // 노선 ID가 전달되지 않았다면 버스 번호로 ID를 먼저 찾는다.
                val allStops = TagoAPI.getRouteStops(resolvedRouteId)

                val startIndexById = if (boardingStopId.isNotBlank()) {
                    allStops.indexOfFirst { it.id == boardingStopId }
                } else {
                    -1
                }
                val startIndex = if (startIndexById >= 0) {
                    startIndexById
                } else {
                    allStops.indexOfFirst {
                        it.name.trim() == boardingStopName.trim()
                    }
                }

                if (startIndex == -1) {
                    throw Exception(
                        getString(
                            R.string.boarding_stop_missing_in_route_format,
                            routeNumber,
                            boardingStopName
                        )
                    )
                }

                // 승차 정류장 자체와 이전 정류장은 하차 후보에서 제외한다.
                val stops = allStops.drop(startIndex + 1)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressStops.visibility = View.GONE
                    showStops(stops)
                }

            } catch (e: Exception) {

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressStops.visibility = View.GONE
                    txtLoadStatus.text = getString(
                        R.string.stop_load_failed_format,
                        e.message.orEmpty()
                    )

                    showLongMessage(getString(R.string.tago_lookup_failed))
                }
            }
        }
    }

    // 하차 후보마다 하나의 RadioButton을 만들어 세로 목록에 추가한다.
    private fun showStops(stops: List<RouteStop>) {

        radioGroup.removeAllViews()
        selectedStop = null
        btnConfirmStop.isEnabled = false

        if (stops.isEmpty()) {
            txtLoadStatus.setText(R.string.no_selectable_alighting_stops)
            return
        }

        txtLoadStatus.text = ""

        stops.forEach { stop ->

            val radioButton = RadioButton(this).apply {
                id = View.generateViewId()
                text = stop.name
                textSize = 16f
                setTextColor(Color.rgb(32, 41, 54))

                buttonTintList = ColorStateList.valueOf(
                    Color.rgb(33, 99, 197)
                )

                val padding = dp(12)

                setPadding(
                    padding,
                    padding,
                    padding,
                    padding
                )

                minHeight = dp(56)

                tag = stop
            }

            radioGroup.addView(radioButton)
        }

        // 항목을 선택하면 결과를 저장하고 완료 버튼을 활성화한다.
        radioGroup.setOnCheckedChangeListener { group, checkedId ->

            val selectedButton =
                group.findViewById<RadioButton>(checkedId)

            selectedStop = selectedButton?.tag as? RouteStop

            btnConfirmStop.isEnabled =
                selectedStop != null
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
