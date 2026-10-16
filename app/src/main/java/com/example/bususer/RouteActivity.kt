package com.example.bususer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import com.example.bususer.BottomNavigation.showLongMessage
import com.example.bususer.BottomNavigation.showMessage
import com.example.bususer.TagoAPI.RouteStop
import com.skt.Tmap.TMapMarkerItem
import com.skt.Tmap.TMapPoint
import com.skt.Tmap.TMapPolyLine
import com.skt.Tmap.TMapView
import java.util.concurrent.Executors

class RouteActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var tMapView: TMapView

    private lateinit var mapContainer: FrameLayout
    private lateinit var txtStart: TextView
    private lateinit var txtEnd: TextView
    private lateinit var btnSelectStop: Button

    private lateinit var txtRouteSummary: TextView

    private val boardingMarkerIcon by lazy {
        createStopMarkerIcon(StopMarkerType.BOARDING)
    }
    private val intermediateMarkerIcon by lazy {
        createStopMarkerIcon(StopMarkerType.INTERMEDIATE)
    }
    private val alightingMarkerIcon by lazy {
        createStopMarkerIcon(StopMarkerType.ALIGHTING)
    }

    // 홈 화면의 정류장 상세에서 전달받는 승차 정류장 정보이다.
    private var boardingStopId: String? = null
    private var boardingStopName: String? = null

    private var routeId: String? = null
    private var routeNumber: String? = null

    // 하차 정류장 선택 화면에서 돌려받는 선택 결과이다.
    private var selectedStopId: String? = null
    private var selectedStopName: String? = null

    // 지도에 선택 구간을 그릴 때 사용하는 노선의 전체 정류장 목록이다.
    private var busStops: List<RouteStop> = emptyList()

    private enum class StopMarkerType {
        BOARDING,
        INTERMEDIATE,
        ALIGHTING
    }

    companion object {

        const val EXTRA_ROUTE_ID = "routeId"
        const val EXTRA_ROUTE_NUMBER = "routeNumber"
        const val EXTRA_BOARDING_STOP_ID = "boardingStopId"
        const val EXTRA_BOARDING_STOP_NAME = "boardingStopName"
        const val EXTRA_SELECTED_STOP_ID = "stopId"
        const val EXTRA_SELECTED_STOP_NAME = "stopName"

        private const val MAP_LONGITUDE = 128.0845
        private const val MAP_LATITUDE = 35.1800

        private const val REQUEST_SELECT_STOP = 1001

        private const val ROUTE_LINE_ID = "selected_bus_route"

        private const val ROUTE_PREFS = "route_selection"
        private const val PREF_ROUTE_ID = "route_id"
        private const val PREF_ROUTE_NUMBER = "route_number"
        private const val PREF_BOARDING_STOP_ID = "boarding_stop_id"
        private const val PREF_BOARDING_STOP_NAME = "boarding_stop_name"
        private const val PREF_ALIGHTING_STOP_ID = "alighting_stop_id"
        private const val PREF_ALIGHTING_STOP_NAME = "alighting_stop_name"

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadIntentData()
        setContentView(R.layout.activity_route)

        initViews()
        updateStopInfo()
        setupListeners()

        if (hasRouteSelection()) {
            initTMap()
        } else {
            btnSelectStop.isEnabled = false
            showMessage(getString(R.string.select_route_from_home))
        }
    }

    private fun loadIntentData() {
        // 새 Intent 값이 있으면 사용하고, 없으면 이전에 저장한 선택값을 복원한다.
        val preferences = getSharedPreferences(ROUTE_PREFS, MODE_PRIVATE)

        val savedRouteId = preferences.getString(PREF_ROUTE_ID, null)
        val savedRouteNumber = preferences.getString(PREF_ROUTE_NUMBER, null)
        val savedBoardingStopId =
            preferences.getString(PREF_BOARDING_STOP_ID, null)
        val savedBoardingStopName =
            preferences.getString(PREF_BOARDING_STOP_NAME, null)

        val incomingRouteId = normalizedExtra(EXTRA_ROUTE_ID)
        val incomingRouteNumber = normalizedExtra(EXTRA_ROUTE_NUMBER)
            ?.removeSuffix("번")
        val incomingBoardingStopId = normalizedExtra(EXTRA_BOARDING_STOP_ID)
        val incomingBoardingStopName = normalizedExtra(EXTRA_BOARDING_STOP_NAME)
        val hasIncomingRoute = incomingRouteNumber != null ||
                incomingBoardingStopId != null ||
                incomingBoardingStopName != null

        if (hasIncomingRoute) {
            routeNumber = incomingRouteNumber
            boardingStopId = incomingBoardingStopId
            boardingStopName = incomingBoardingStopName

            val isSameSavedRoute =
                routeNumber == savedRouteNumber &&
                        boardingStopId == savedBoardingStopId &&
                        boardingStopName == savedBoardingStopName &&
                        (incomingRouteId == null || savedRouteId == null ||
                                incomingRouteId == savedRouteId)

            routeId = incomingRouteId ?: savedRouteId.takeIf {
                isSameSavedRoute
            }

            if (isSameSavedRoute) {
                selectedStopId =
                    preferences.getString(PREF_ALIGHTING_STOP_ID, null)
                selectedStopName =
                    preferences.getString(PREF_ALIGHTING_STOP_NAME, null)
            }
        } else {
            routeId = savedRouteId
            routeNumber = savedRouteNumber
            boardingStopId = savedBoardingStopId
            boardingStopName = savedBoardingStopName
            selectedStopId = preferences.getString(PREF_ALIGHTING_STOP_ID, null)
            selectedStopName =
                preferences.getString(PREF_ALIGHTING_STOP_NAME, null)
        }

        saveRouteSelection()
    }

    private fun normalizedExtra(key: String): String? =
        intent.getStringExtra(key)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun saveRouteSelection() {
        if (!hasRouteSelection()) return

        // 앱을 다시 열거나 화면을 이동해도 선택한 경로를 유지한다.
        getSharedPreferences(ROUTE_PREFS, MODE_PRIVATE).edit {
            putString(PREF_ROUTE_ID, routeId)
            putString(PREF_ROUTE_NUMBER, routeNumber)
            putString(PREF_BOARDING_STOP_ID, boardingStopId)
            putString(PREF_BOARDING_STOP_NAME, boardingStopName)
            putString(PREF_ALIGHTING_STOP_ID, selectedStopId)
            putString(PREF_ALIGHTING_STOP_NAME, selectedStopName)
        }
    }

    private fun hasRouteSelection(): Boolean =
        !routeNumber.isNullOrBlank() &&
                (!boardingStopId.isNullOrBlank() ||
                        !boardingStopName.isNullOrBlank())

    private fun initViews() {
        mapContainer = findViewById(R.id.mapContainer)
        txtStart = findViewById(R.id.txtStart)
        txtEnd = findViewById(R.id.txtEnd)
        btnSelectStop = findViewById(R.id.btnSelectStop)
        txtRouteSummary = findViewById(R.id.txtRouteSummary)
        findViewById<TextView>(R.id.txtRouteTitle).text =
            routeNumber?.let {
                getString(R.string.route_title_format, it)
            } ?: getString(R.string.bus_not_selected)
    }

    private fun initTMap() {

        try {
            tMapView = TMapView(this)

            tMapView.setSKTMapApiKey(TmapAPI.TMAP_API_KEY)

            tMapView.setCenterPoint(
                MAP_LONGITUDE,
                MAP_LATITUDE
            )

            tMapView.setZoomLevel(14)

            mapContainer.removeAllViews()

            mapContainer.addView(
                tMapView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            // 지도가 준비된 뒤에만 노선 정류장을 조회하고 표시한다.
            loadBusStops()

        } catch (e: Exception) {
            showLongMessage(
                getString(
                    R.string.tmap_init_failed_format,
                    e.message.orEmpty()
                )
            )
        }
    }

    // 노선 ID를 확인한 후 해당 버스가 지나가는 모든 정류장을 가져온다.
    private fun loadBusStops() {

        executor.execute {

            try {
                val selectedRouteNumber = routeNumber
                    ?: throw Exception(
                        getString(R.string.select_route_from_home)
                    )
                val resolvedRouteId = routeId.orEmpty().ifBlank {
                    TagoAPI.getRouteId(selectedRouteNumber)
                }
                val stops = TagoAPI.getRouteStops(resolvedRouteId)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    routeId = resolvedRouteId
                    busStops = stops

                    if (selectedStopId.isNullOrBlank() &&
                        selectedStopName.isNullOrBlank()
                    ) {
                        val startIndex = findBoardingStopIndex(stops)
                        stops.lastOrNull()
                            ?.takeIf { startIndex >= 0 && it.order > stops[startIndex].order }
                            ?.let {
                                selectedStopId = it.id
                                selectedStopName = it.name
                            }
                    }

                    updateStopInfo()
                    saveRouteSelection()

                    // 하차 정류장까지 선택된 경우 승차~하차 구간만 지도에 표시한다.
                    showSelectedRoute()
                }

            } catch (e: Exception) {

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showLongMessage(
                        getString(
                            R.string.stop_query_failed_format,
                            e.message.orEmpty()
                        )
                    )
                }
            }
        }
    }

    private fun hasValidCoordinates(
        stop: RouteStop
    ): Boolean {

        val lat = stop.latitude
        val lon = stop.longitude

        return lat != null &&
                lon != null &&
                lat.isFinite() &&
                lon.isFinite() &&
                lat in -90.0..90.0 &&
                lon in -180.0..180.0
    }

    private fun findBoardingStopIndex(
        stops: List<RouteStop>
    ): Int {
        if (!boardingStopId.isNullOrBlank()) {
            val indexById = stops.indexOfFirst { it.id == boardingStopId }
            if (indexById >= 0) return indexById
        }

        val selectedBoardingStopName = boardingStopName ?: return -1
        return stops.indexOfFirst {
            it.name.trim() == selectedBoardingStopName.trim()
        }
    }

    // 전체 노선에서 승차 정류장부터 선택한 하차 정류장까지의 구간을 찾아 그린다.
    private fun showSelectedRoute() {

        // 이전 선택 결과가 남지 않도록 기존 경로선과 정류장 마커를 먼저 삭제한다.
        tMapView.removeTMapPolyLine(ROUTE_LINE_ID)
        tMapView.removeAllMarkerItem()

        if (busStops.isEmpty()) {
            return
        }

        // ID를 우선 사용하고, ID가 없으면 정류장 이름으로 승차 위치를 찾는다.
        val startIndex = findBoardingStopIndex(busStops)

        // 사용자가 선택한 하차 정류장이 전체 노선의 몇 번째인지 찾는다.
        val endIndex = if (!selectedStopId.isNullOrBlank()) {
            busStops.indexOfFirst {
                it.id == selectedStopId
            }
        } else {
            val selectedDestinationName = selectedStopName
                ?: return showMessage(
                    getString(R.string.select_alighting_guide)
                )
            busStops.indexOfFirst {
                it.name.trim() == selectedDestinationName.trim()
            }
        }

        if (startIndex == -1) {
            showMessage(getString(R.string.boarding_stop_not_found))
            return
        }

        if (endIndex == -1) {
            showMessage(getString(R.string.alighting_stop_not_found))
            return
        }

        if (endIndex <= startIndex) {
            showMessage(
                getString(R.string.select_alighting_after_boarding)
            )
            return
        }

        // 전체 목록에서 실제 탑승 구간에 포함되는 정류장만 잘라낸다.
        val selectedStops = busStops.subList(
            startIndex,
            endIndex + 1
        )

        val validStops = selectedStops.filter {
            hasValidCoordinates(it)
        }

        if (validStops.size < 2) {
            showMessage(
                getString(R.string.route_coordinates_insufficient)
            )
            return
        }

        // 승차·중간·하차 정류장을 서로 다른 점 모양으로 표시한다.
        showSelectedMarkers(validStops)

        // 선택 구간의 정류장 좌표를 순서대로 연결해 파란 경로선을 그린다.
        drawSelectedRouteLine(validStops)

        // 선택 구간 전체가 보이도록 지도의 중심과 확대 수준을 조정한다.
        moveCameraToRoute(validStops)
    }

    // 첫 정류장은 승차, 마지막은 하차, 나머지는 중간 정류장 마커를 사용한다.
    private fun showSelectedMarkers(
        stops: List<RouteStop>
    ) {

        stops.forEachIndexed { index, stop ->

            val latitude = stop.latitude ?: return@forEachIndexed
            val longitude = stop.longitude ?: return@forEachIndexed

            val marker = TMapMarkerItem().apply {

                setTMapPoint(
                    TMapPoint(latitude, longitude)
                )

                setName(stop.name)
                setPosition(0.5f, 0.5f)
                setIcon(
                    when (index) {
                        0 -> boardingMarkerIcon
                        stops.lastIndex -> alightingMarkerIcon
                        else -> intermediateMarkerIcon
                    }
                )

                setVisible(TMapMarkerItem.VISIBLE)

                setCanShowCallout(true)
                setCalloutTitle(stop.name)
            }

            tMapView.addMarkerItem(
                "selected_stop_${index}_${stop.id}",
                marker
            )
        }
    }

    private fun createStopMarkerIcon(type: StopMarkerType): Bitmap {
        val size = (18 * resources.displayMetrics.density).toInt()
            .coerceAtLeast(18)
        val center = size / 2f
        val radius = when (type) {
            StopMarkerType.INTERMEDIATE -> size * 0.27f
            else -> size * 0.36f
        }
        val strokeWidth = (2 * resources.displayMetrics.density)
            .coerceAtLeast(2f)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = when (type) {
            StopMarkerType.BOARDING -> Color.rgb(37, 99, 235)
            StopMarkerType.INTERMEDIATE -> Color.WHITE
            StopMarkerType.ALIGHTING -> Color.rgb(210, 76, 60)
        }
        canvas.drawCircle(center, center, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = if (type == StopMarkerType.INTERMEDIATE) {
            Color.rgb(37, 99, 235)
        } else {
            Color.WHITE
        }
        canvas.drawCircle(center, center, radius, paint)

        return bitmap
    }

    // 좌표가 있는 정류장만 순서대로 연결해 파란 경로선을 만든다.
    private fun drawSelectedRouteLine(
        stops: List<RouteStop>
    ) {

        val polyLine = TMapPolyLine().apply {

            setLineColor(
                Color.rgb(37, 99, 235)
            )

            setLineWidth(5.0f)
        }

        stops.forEach { stop ->

            val latitude = stop.latitude ?: return@forEach
            val longitude = stop.longitude ?: return@forEach

            polyLine.addLinePoint(
                TMapPoint(latitude, longitude)
            )
        }

        tMapView.addTMapPolyLine(
            ROUTE_LINE_ID,
            polyLine
        )
    }

    // 모든 정류장 좌표의 범위를 계산해 선택 구간이 한 화면에 들어오게 한다.
    private fun moveCameraToRoute(
        stops: List<RouteStop>
    ) {

        val latitudes = stops.mapNotNull {
            it.latitude
        }

        val longitudes = stops.mapNotNull {
            it.longitude
        }

        if (latitudes.isEmpty() || longitudes.isEmpty()) {
            return
        }

        val minLat = latitudes.minOrNull() ?: return
        val maxLat = latitudes.maxOrNull() ?: return

        val minLon = longitudes.minOrNull() ?: return
        val maxLon = longitudes.maxOrNull() ?: return

        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0

        tMapView.setCenterPoint(
            centerLon,
            centerLat
        )

        val latRange = maxLat - minLat
        val lonRange = maxLon - minLon

        val zoomLevel = when {

            latRange > 0.20 || lonRange > 0.20 -> 10
            latRange > 0.10 || lonRange > 0.10 -> 11
            latRange > 0.05 || lonRange > 0.05 -> 12
            latRange > 0.02 || lonRange > 0.02 -> 13

            else -> 14
        }

        tMapView.setZoomLevel(zoomLevel)
    }

    private fun updateStopInfo() {

        val startName = boardingStopName
        val endName = selectedStopName

        if (routeNumber == null || startName == null) {
            txtStart.text = getString(
                R.string.boarding_value_format,
                getString(R.string.not_selected)
            )
            txtEnd.text = getString(
                R.string.alighting_value_format,
                getString(R.string.not_selected)
            )
            txtRouteSummary.setText(R.string.select_route_from_home)
            return
        }

        txtStart.text = getString(
            R.string.boarding_value_format,
            startName
        )

        txtEnd.text = getString(
            R.string.alighting_value_format,
            endName ?: getString(R.string.not_selected)
        )

        txtRouteSummary.text = if (endName == null) {
            getString(R.string.route_summary_prompt_format, startName)
        } else {
            getString(R.string.route_summary_format, startName, endName)
        }
    }

    private fun setupListeners() {

        findViewById<TextView>(R.id.btnHome)
            .setOnClickListener { BottomNavigation.navigateHome(this) }

        btnSelectStop.setOnClickListener {
            openSelectStopScreen()
        }

        BottomNavigation.setup(
            this,
            R.id.navHome,
            R.id.navRoute,
            R.id.navBooking,
            R.id.navProfile
        )
    }

    private fun openSelectStopScreen() {

        if (!hasRouteSelection()) {
            showMessage(getString(R.string.select_route_from_home))
            return
        }

        val intent = Intent(
            this,
            SelectStopActivity::class.java
        ).apply {
            putExtra(EXTRA_ROUTE_ID, routeId)
            putExtra(EXTRA_ROUTE_NUMBER, routeNumber)
            putExtra(EXTRA_BOARDING_STOP_ID, boardingStopId)
            putExtra(EXTRA_BOARDING_STOP_NAME, boardingStopName)
        }

        @Suppress("DEPRECATION")
        startActivityForResult(
            intent,
            REQUEST_SELECT_STOP
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode == REQUEST_SELECT_STOP &&
            resultCode == RESULT_OK
        ) {

            selectedStopId =
                data?.getStringExtra(EXTRA_SELECTED_STOP_ID)

            selectedStopName =
                data?.getStringExtra(EXTRA_SELECTED_STOP_NAME)

            updateStopInfo()
            saveRouteSelection()

            // 새 하차 정류장을 저장한 뒤 변경된 구간으로 지도와 안내 문구를 갱신한다.
            showSelectedRoute()

            showMessage(
                getString(
                    R.string.selected_alighting_stop_format,
                    selectedStopName.orEmpty()
                )
            )
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

}
