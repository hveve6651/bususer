package com.example.bususer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.example.bususer.BottomNavigation.dp
import com.example.bususer.TagoAPI.NearbyBusStop
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val LOCATION_REQUEST = 100
    }

    private lateinit var locationManager: LocationManager
    private lateinit var nearbyStopContainer: LinearLayout
    private lateinit var tvStopStatus: TextView
    private lateinit var tvLocation: TextView
    private lateinit var etSearch: EditText

    private var allStops: List<NearbyBusStop> = emptyList()

    // GPS를 얻기 전에는 테스트 좌표를 사용하고, 위치가 확인되면 실제 좌표로 바꾼다.
    private var currentLat = TmapAPI.TEST_LATITUDE
    private var currentLon = TmapAPI.TEST_LONGITUDE

    // 화면이 멈추지 않도록 API를 백그라운드에서 실행하되, 동시 요청은 최대 3개로 제한한다.
    private val apiExecutor = Executors.newFixedThreadPool(3)

    // 같은 정류장의 방면을 중복 조회하지 않도록 완료 결과와 조회 중인 ID를 각각 기억한다.
    private val directionCache = mutableMapOf<String, String>()
    private val directionLoading = mutableSetOf<String>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            loadNearbyStops(location.latitude, location.longitude)
            locationManager.removeUpdates(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        nearbyStopContainer = findViewById(R.id.nearbyStopContainer)
        tvStopStatus = findViewById(R.id.tvStopStatus)
        tvLocation = findViewById(R.id.tvLocation)
        etSearch = findViewById(R.id.etSearch)

        val btnNearbyMap = findViewById<LinearLayout>(R.id.btnNearbyMap)
        val btnRouteSearch = findViewById<LinearLayout>(R.id.btnRouteSearch)
        val btnRefreshLocation = findViewById<TextView>(R.id.btnRefreshLocation)
        val navHome = findViewById<TextView>(R.id.navHome)

        btnNearbyMap.setOnClickListener { openMap() }
        btnRefreshLocation.setOnClickListener { requestLocation(forceRefresh = true) }

        etSearch.apply {
            isFocusable = false
            isClickable = true

            setOnClickListener {
                val intent = Intent(
                    this@MainActivity,
                    SearchActivity::class.java
                ).apply {
                    putExtra(SearchActivity.EXTRA_CURRENT_LATITUDE, currentLat)
                    putExtra(SearchActivity.EXTRA_CURRENT_LONGITUDE, currentLon)
                }

                startActivity(intent)
            }
        }

        btnRouteSearch.setOnClickListener {
            startActivity(Intent(this, RouteActivity::class.java))
        }
        BottomNavigation.setup(
            this,
            R.id.navHome,
            R.id.navRoute,
            R.id.navReservation,
            R.id.navMyInfo
        )
        navHome.setOnClickListener {
            etSearch.setText("")
            renderStops()
        }
        if (TmapAPI.USE_TEST_LOCATION) {
            loadNearbyStops(TmapAPI.TEST_LATITUDE, TmapAPI.TEST_LONGITUDE)
        } else {
            requestLocation()
        }
    }

    private fun requestLocation(forceRefresh: Boolean = false) {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_REQUEST
            )
            return
        }
        startLocation(forceRefresh)
    }

    private fun startLocation(forceRefresh: Boolean = false) {
        try {
            locationManager.removeUpdates(locationListener)

            if (!forceRefresh) {
                val providers = locationManager.getProviders(true)
                val lastLocation = providers.mapNotNull {
                    locationManager.getLastKnownLocation(it)
                }.maxByOrNull { it.time }

                if (lastLocation != null) {
                    loadNearbyStops(lastLocation.latitude, lastLocation.longitude)
                    return
                }
            }

            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER

                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER

                else -> {
                    tvStopStatus.setText(R.string.location_service_required)
                    return
                }
            }

            tvLocation.setText(R.string.checking_location)
            tvStopStatus.setText(R.string.refreshing_current_location)
            locationManager.requestLocationUpdates(provider, 1000L, 0f, locationListener)
        } catch (e: SecurityException) {
            tvStopStatus.setText(R.string.location_permission_required)
        }
    }


    private fun loadNearbyStops(latitude: Double, longitude: Double) {

        // 주소와 정류장 조회가 끝나기 전까지 사용자에게 로딩 상태를 보여준다.
        tvLocation.setText(R.string.checking_location)
        tvStopStatus.setText(R.string.nearby_stops_loading)

        // 첫 번째 작업: Tmap API로 위·경도를 동 단위 주소로 변환한다.
        apiExecutor.execute {
            try {
                val address = TmapAPI.getDongAddress(
                    latitude,
                    longitude
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    tvLocation.text = if (TmapAPI.USE_TEST_LOCATION) {
                        getString(R.string.test_location_format, address)
                    } else {
                        address
                    }
                }

            } catch (e: Exception) {
                Log.e(
                    "TMAP_ADDRESS",
                    "주소 조회 실패",
                    e
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    tvLocation.text = if (TmapAPI.USE_TEST_LOCATION) {
                        getString(R.string.test_location_address_failed)
                    } else {
                        getString(R.string.address_lookup_failed)
                    }
                }
            }
        }

        // 두 번째 작업: TAGO API에서 현재 좌표 기준 300m 이내 정류장을 가져온다.
        apiExecutor.execute {
            try {
                val stops = TagoAPI.getNearbyStops(
                    latitude,
                    longitude
                )
                    .map { stop ->
                        stop to TagoAPI.distanceMeters(
                            latitude, longitude,
                            stop.latitude, stop.longitude
                        )
                    }
                    .filter { (_, distance) -> distance <= 300f }
                    .sortedBy { (_, distance) -> distance }
                    .map { (stop, _) -> stop }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    allStops = stops
                    currentLat = latitude
                    currentLon = longitude

                    renderStops()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        tvStopStatus.text = getString(
                            R.string.stop_query_failed_format,
                            e.message.orEmpty()
                        )
                    }
                }
            }
        }
    }

    private fun renderStops() {
        nearbyStopContainer.removeAllViews()

        val stops = allStops

        if (stops.isEmpty()) {
            tvStopStatus.setText(R.string.no_nearby_stops)
            return
        }
        tvStopStatus.text = getString(
            R.string.nearby_stop_count_format,
            stops.size
        )

        for (stop in stops) {
            val distance = TagoAPI.distanceMeters(
                currentLat, currentLon, stop.latitude, stop.longitude
            )

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setBackgroundResource(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
                tag = stop.id
                isClickable = true
                isFocusable = true
            }

            val nameView = TextView(this).apply {
                text = stop.name
                textSize = 18f
                setTextColor("#202936".toColorInt())
                setTypeface(null, Typeface.BOLD)
            }

            val numberView = TextView(this).apply {
                text = if (stop.number.isNotBlank()) {
                    getString(R.string.stop_number_format, stop.number)
                } else {
                    getString(R.string.stop_id_format, stop.id)
                }
                textSize = 13f
                setTextColor("#667085".toColorInt())
                setPadding(0, dp(6), 0, 0)
            }

            val directionView = TextView(this).apply {
                text = directionCache[stop.id]
                    ?: getString(R.string.direction_loading)
                textSize = 14f
                setTextColor("#1565C0".toColorInt())
                setPadding(0, dp(10), 0, dp(6))
            }

            val distanceView = TextView(this).apply {
                text = getString(
                    R.string.distance_meters_format,
                    distance.toInt()
                )
                textSize = 14f
                setTextColor("#667085".toColorInt())
                setPadding(0, dp(8), 0, 0)
            }

            card.addView(nameView)
            card.addView(numberView)
            card.addView(directionView)
            card.addView(distanceView)
            card.setOnClickListener { openBusStopActivity(stop, distance) }
            nearbyStopContainer.addView(card)

            loadStopDirection(stop.id)
        }
    }

    private fun loadStopDirection(nodeId: String) {
        if (directionCache.containsKey(nodeId) || !directionLoading.add(nodeId)) {
            return
        }

        apiExecutor.execute {
            try {
                // 여러 노선의 다음 정류장을 비교해 이 정류장의 대표 방면을 구한다.
                val direction = TagoAPI.getStopDirection(nodeId)
                runOnUiThread {
                    directionLoading.remove(nodeId)
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    directionCache[nodeId] = direction
                    updateDirectionViews(nodeId, direction)
                }
            } catch (e: Exception) {
                Log.e("BUSUSER_DIRECTION", "방면 조회 실패: $nodeId", e)
                runOnUiThread {
                    directionLoading.remove(nodeId)
                    if (!isFinishing && !isDestroyed) {
                        updateDirectionViews(
                            nodeId,
                            getString(R.string.direction_lookup_failed)
                        )
                    }
                }
            }
        }
    }

    // 목록을 다시 그린 경우에도 정류장 ID가 같은 카드에 방면 조회 결과를 적용한다.
    private fun updateDirectionViews(nodeId: String, direction: String) {
        for (i in 0 until nearbyStopContainer.childCount) {
            val card = nearbyStopContainer.getChildAt(i) as? LinearLayout ?: continue
            if (card.tag != nodeId) continue
            val directionView = card.getChildAt(2) as? TextView ?: continue
            directionView.text = direction
        }
    }

    private fun openBusStopActivity(
        stop: NearbyBusStop,
        distance: Float
    ) {
        startActivity(BusStopActivity.createIntent(this, stop, distance))
    }

    private fun openMap() {
        startActivity(Intent(this, NearbyMapActivity::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startLocation(forceRefresh = true)
            } else {
                tvStopStatus.setText(R.string.location_permission_denied)
            }
        }
    }

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        apiExecutor.shutdownNow()
        super.onDestroy()
    }
}
