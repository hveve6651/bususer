package com.example.bususer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import com.example.bususer.BottomNavigation.showMessage
import com.example.bususer.TagoAPI.NearbyBusStop
import com.skt.Tmap.TMapMarkerItem
import com.skt.Tmap.TMapPoint
import com.skt.Tmap.TMapView
import com.skt.Tmap.poi_item.TMapPOIItem
import java.util.concurrent.Executors

class NearbyMapActivity : Activity() {

    private lateinit var tmapView: TMapView

    private val executor = Executors.newSingleThreadExecutor()

    private val markerIds = mutableListOf<String>()
    private val stopsByMarkerId = mutableMapOf<String, NearbyBusStop>()

    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    private var lastClickedMarkerId: String? = null
    private var lastMarkerClickTime = 0L

    companion object {
        private const val LOCATION_REQUEST = 100
        private const val MAP_ZOOM_LEVEL = 17
        private const val MY_LOCATION_MARKER_ID = "my_location"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearbymap)

        val mapContainer = findViewById<FrameLayout>(R.id.mapContainer)

        // XML의 빈 FrameLayout에 TMapView를 생성해 넣는다.
        tmapView = TMapView(this).apply {
            setSKTMapApiKey(TmapAPI.TMAP_API_KEY)
            setCenterPoint(
                TmapAPI.TEST_LONGITUDE,
                TmapAPI.TEST_LATITUDE,
                true
            )
            setZoomLevel(MAP_ZOOM_LEVEL)
            setMapType(TMapView.MAPTYPE_STANDARD)
        }

        mapContainer.addView(tmapView)
        setupMarkerClickListener()

        // 상단 홈 버튼은 기존 홈 화면을 재사용하고 현재 지도 화면을 닫는다.
        findViewById<TextView>(R.id.btnHome)
            .setOnClickListener {
                BottomNavigation.navigateHome(this)
                finish()
            }

        BottomNavigation.setup(
            this,
            R.id.navHome,
            R.id.navRoute,
            R.id.navReservation,
            R.id.navProfile
        )

        // 위치 버튼을 누르면 좌표와 주변 정류장을 다시 조회한다.
        findViewById<TextView>(R.id.btnMyLocation)
            .setOnClickListener {
                loadCurrentLocation()
            }

        // 화면을 처음 열었을 때도 위치와 정류장 마커를 바로 표시한다.
        loadCurrentLocation()
    }

    // 설정에 따라 테스트 좌표를 사용하거나 기기의 최근 GPS 위치를 가져온다.
    private fun loadCurrentLocation() {

        // 테스트 모드에서는 항상 가좌동 652-4 좌표를 사용한다.
        if (TmapAPI.USE_TEST_LOCATION) {
            showCurrentLocation(
                TmapAPI.TEST_LATITUDE,
                TmapAPI.TEST_LONGITUDE
            )
            return
        }

        // 실제 GPS 모드에서는 먼저 위치 권한이 있는지 확인한다.
        if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_REQUEST
            )
            return
        }

        val manager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        try {
            val location = manager.getProviders(true)
                .mapNotNull { provider ->
                    manager.getLastKnownLocation(provider)
                }
                .maxByOrNull { it.time }

            if (location == null) {
                showMessage(R.string.location_unavailable)
                return
            }

            showCurrentLocation(location.latitude, location.longitude)

        } catch (e: SecurityException) {
            showMessage(R.string.location_permission_required)
        }
    }

    // 위치 종류와 관계없이 지도 이동, 내 위치 마커, 정류장 조회를 같은 순서로 처리한다.
    private fun showCurrentLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
        tmapView.setCenterPoint(longitude, latitude, true)
        tmapView.setZoomLevel(MAP_ZOOM_LEVEL)
        addMyLocationMarker(latitude, longitude)
        loadNearbyStops(latitude, longitude)
    }

    // 기존 내 위치 마커를 지우고 최신 좌표에 새 마커를 표시한다.
    private fun addMyLocationMarker(
        lat: Double,
        lon: Double
    ) {
        tmapView.removeMarkerItem(MY_LOCATION_MARKER_ID)

        val marker = TMapMarkerItem().apply {
            tMapPoint = TMapPoint(lat, lon)
            name = getString(R.string.my_location)
            visible = TMapMarkerItem.VISIBLE
            canShowCallout = true
            calloutTitle = getString(R.string.my_location)
            autoCalloutVisible = true
        }

        tmapView.addMarkerItem(MY_LOCATION_MARKER_ID, marker)
    }

    // 현재 좌표 주변의 정류장을 조회하고 가까운 순서로 정렬한다.
    private fun loadNearbyStops(
        lat: Double,
        lon: Double
    ) {
        showMessage(R.string.nearby_stops_searching)

        executor.execute {
            try {
                val stops = TagoAPI.getNearbyStops(lat, lon)

                // API 결과의 각 정류장까지 실제 직선거리를 계산해 가까운 순서로 정렬한다.
                val sortedStops = stops.sortedBy { stop ->

                    TagoAPI.distanceMeters(
                        lat,
                        lon,
                        stop.latitude,
                        stop.longitude
                    )
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    showBusStopMarkers(sortedStops)

                    if (sortedStops.isEmpty()) {
                        showMessage(R.string.no_nearby_stops)
                    } else {
                        showMessage(
                            getString(
                                R.string.nearby_stops_found_format,
                                sortedStops.size
                            )
                        )
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showMessage(
                            getString(
                                R.string.stop_query_failed_format,
                                e.message.orEmpty()
                            )
                        )
                    }
                }
            }
        }
    }

    // 조회된 정류장마다 지도 마커와 상세 이동에 사용할 데이터를 연결한다.
    private fun showBusStopMarkers(
        stops: List<NearbyBusStop>
    ) {
        // 새 조회 결과와 섞이지 않도록 이전 정류장 마커와 저장 데이터를 먼저 비운다.
        markerIds.forEach { id ->
            tmapView.removeMarkerItem(id)
        }

        markerIds.clear()
        stopsByMarkerId.clear()
        lastClickedMarkerId = null
        lastMarkerClickTime = 0L

        stops.forEachIndexed { index, stop ->

            val markerId = "bus_stop_$index"

            val marker = TMapMarkerItem().apply {
                id = markerId
                tMapPoint = TMapPoint(
                    stop.latitude,
                    stop.longitude
                )

                name = stop.name
                visible = TMapMarkerItem.VISIBLE

                canShowCallout = true
                calloutTitle = stop.name
                calloutSubTitle = if (stop.number.isBlank()) {
                    getString(R.string.stop_id_format, stop.id)
                } else {
                    getString(R.string.stop_number_format, stop.number)
                }

                autoCalloutVisible = false
            }

            tmapView.addMarkerItem(markerId, marker)
            markerIds.add(markerId)
            stopsByMarkerId[markerId] = stop
        }
    }

    private fun setupMarkerClickListener() {
        // 지도 SDK가 전달한 항목 중 실제 정류장 마커를 찾아 클릭을 처리한다.
        tmapView.setOnClickListenerCallBack(
            object : TMapView.OnClickListenerCallback {
                override fun onPressEvent(
                    markerItems: ArrayList<TMapMarkerItem>,
                    poiItems: ArrayList<TMapPOIItem>,
                    point: TMapPoint,
                    screenPoint: PointF
                ): Boolean = false

                override fun onPressUpEvent(
                    markerItems: ArrayList<TMapMarkerItem>,
                    poiItems: ArrayList<TMapPOIItem>,
                    point: TMapPoint,
                    screenPoint: PointF
                ): Boolean {
                    val markerId = markerItems
                        .firstOrNull { stopsByMarkerId.containsKey(it.id) }
                        ?.id
                        ?: return false

                    handleMarkerClick(markerId)
                    return true
                }
            }
        )
    }

    private fun handleMarkerClick(markerId: String) {
        val now = SystemClock.elapsedRealtime()
        // 같은 마커를 정해진 시간 안에 다시 누르면 더블클릭으로 판단한다.
        val isDoubleClick = markerId == lastClickedMarkerId &&
                now - lastMarkerClickTime <=
                ViewConfiguration.getDoubleTapTimeout()

        if (isDoubleClick) {
            lastClickedMarkerId = null
            lastMarkerClickTime = 0L
            openBusStop(markerId)
            return
        }

        lastClickedMarkerId = markerId
        lastMarkerClickTime = now
        tmapView.showCallOutViewWithMarkerItemID(markerId)
    }

    private fun openBusStop(markerId: String) {
        val stop = stopsByMarkerId[markerId] ?: return
        val latitude = currentLatitude
        val longitude = currentLongitude

        val intent = if (latitude != null && longitude != null) {
            BusStopActivity.createIntent(
                this,
                stop,
                TagoAPI.distanceMeters(
                    latitude,
                    longitude,
                    stop.latitude,
                    stop.longitude
                )
            )
        } else {
            BusStopActivity.createIntent(this, stop.id, stop.name)
        }

        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == LOCATION_REQUEST) {
            if (grantResults.any {
                    it == PackageManager.PERMISSION_GRANTED
                }) {
                loadCurrentLocation()
            } else {
                showMessage(R.string.location_permission_denied)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
