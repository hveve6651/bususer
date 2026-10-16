package com.example.bususer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.widget.doAfterTextChanged
import com.example.bususer.BottomNavigation.dp
import com.example.bususer.BottomNavigation.showMessage
import com.example.bususer.TagoAPI.BusRouteSearchResult
import com.example.bususer.TagoAPI.NearbyBusStop
import java.util.concurrent.Executors

class SearchActivity : Activity() {

    companion object {
        const val EXTRA_CURRENT_LATITUDE = "currentLat"
        const val EXTRA_CURRENT_LONGITUDE = "currentLon"
    }

    private lateinit var etSearch: EditText
    private lateinit var searchFilter: RadioGroup
    private lateinit var resultContainer: LinearLayout
    private lateinit var tvStatus: TextView

    private var currentLat = TmapAPI.TEST_LATITUDE
    private var currentLon = TmapAPI.TEST_LONGITUDE

    // 검색 중 화면이 멈추지 않도록 TAGO 요청은 별도 스레드에서 실행한다.
    private val searchExecutor =
        Executors.newSingleThreadExecutor()

    // 사용자가 계속 입력하는 동안 예약된 검색을 취소할 수 있도록 Handler를 사용한다.
    private val searchHandler =
        Handler(Looper.getMainLooper())

    private val searchRunnable = Runnable {
        searchFromApi()
    }

    // 각 검색에 번호를 붙여 오래 걸린 이전 결과가 최신 화면을 덮어쓰지 못하게 한다.
    private var searchVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_search)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        etSearch = findViewById(R.id.etSearch)
        searchFilter = findViewById(R.id.searchFilter)
        resultContainer =
            findViewById(R.id.searchResultContainer)
        tvStatus = findViewById(R.id.tvSearchStatus)

        // 홈 화면에서 전달한 좌표는 검색된 정류장까지의 거리 계산에 사용한다.
        currentLat = intent.getDoubleExtra(
            EXTRA_CURRENT_LATITUDE,
            currentLat
        )

        currentLon = intent.getDoubleExtra(
            EXTRA_CURRENT_LONGITUDE,
            currentLon
        )

        // 글자가 바뀔 때마다 즉시 호출하지 않고 지연 검색을 새로 예약한다.
        etSearch.doAfterTextChanged { scheduleSearch() }

        // 필터를 바꾸면 같은 검색어로 버스 또는 정류장 결과만 다시 조회한다.
        searchFilter.setOnCheckedChangeListener { _, _ ->
            scheduleSearch()
        }

        tvStatus.setText(R.string.search_instruction)
    }

    // 마지막 입력 후 700ms 동안 추가 입력이 없을 때만 API를 호출한다.
    private fun scheduleSearch() {

        searchHandler.removeCallbacks(searchRunnable)

        // 검색 번호를 증가시켜 이미 실행 중인 이전 요청 결과를 무효로 만든다.
        searchVersion++

        val keyword = etSearch.text.toString().trim()

        if (keyword.isBlank()) {
            resultContainer.removeAllViews()
            tvStatus.setText(R.string.enter_search_term)
            return
        }

        tvStatus.setText(R.string.search_preparing)

        searchHandler.postDelayed(
            searchRunnable,
            700L
        )
    }

    // 선택한 필터에 따라 정류장 검색과 버스 노선 검색을 실행한다.
    private fun searchFromApi() {

        val keyword = etSearch.text.toString().trim()
        val filter = searchFilter.checkedRadioButtonId

        if (keyword.isBlank()) {
            return
        }

        val version = searchVersion

        tvStatus.setText(R.string.searching)
        resultContainer.removeAllViews()

        searchExecutor.execute {

            try {

                // 전체 또는 정류장 필터일 때 정류장 결과를 요청한다.
                val stopResults =
                    if (filter != R.id.radioBus) {
                        TagoAPI.searchStops(keyword)
                    } else {
                        emptyList()
                    }

                // 전체 또는 버스 필터일 때 버스 노선 결과를 요청한다.
                val routeResults =
                    if (filter != R.id.radioStop) {
                        TagoAPI.searchRoutes(keyword)
                    } else {
                        emptyList()
                    }

                runOnUiThread {

                    if (
                        isFinishing ||
                        isDestroyed ||
                        version != searchVersion
                    ) {
                        return@runOnUiThread
                    }

                    resultContainer.removeAllViews()

                    // 정류장 결과를 거리순으로 정렬해 카드로 표시한다.
                    if (stopResults.isNotEmpty()) {
                        addSectionTitle(getString(R.string.stop_section_title))

                        stopResults.forEach { stop ->
                            addStopCard(stop)
                        }
                    }

                    // 버스 결과는 같은 노선 ID가 중복되지 않도록 정리해 표시한다.
                    if (routeResults.isNotEmpty()) {
                        addSectionTitle(
                            getString(R.string.bus_route_section_title)
                        )

                        routeResults.forEach { route ->
                            addRouteCard(route)
                        }
                    }

                    val totalCount =
                        stopResults.size + routeResults.size

                    tvStatus.text = getString(
                        R.string.search_results_count_format,
                        totalCount
                    )

                    if (totalCount == 0) {
                        showEmptyMessage()
                    }
                }

            } catch (e: Exception) {

                android.util.Log.e(
                    "BUSUSER_SEARCH",
                    "TAGO 검색 실패",
                    e
                )

                runOnUiThread {

                    if (
                        !isFinishing &&
                        !isDestroyed &&
                        version == searchVersion
                    ) {
                        tvStatus.text = getString(
                            R.string.search_failed_format,
                            e.message.orEmpty()
                        )

                        resultContainer.removeAllViews()
                    }
                }
            }
        }
    }

    // 정류장과 버스 결과 사이를 구분하는 제목을 코드로 만든다.
    private fun addSectionTitle(title: String) {

        val titleView = TextView(this).apply {

            text = title
            textSize = 16f

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                "#202936".toColorInt()
            )


            setPadding(
                0,
                dp(16),
                0,
                dp(12)
            )
        }

        resultContainer.addView(titleView)
    }

    // 정류장 이름·번호·현재 위치와의 거리를 보여주는 카드를 만든다.
    private fun addStopCard(stop: NearbyBusStop) {

        val distance = TagoAPI.distanceMeters(
            currentLat,
            currentLon,
            stop.latitude,
            stop.longitude
        )

        val card = createCard()

        val nameView = TextView(this).apply {

            text = stop.name
            textSize = 18f

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                "#202936".toColorInt()
            )
        }

        val numberView = TextView(this).apply {

            text = if (stop.number.isNotBlank()) {
                getString(R.string.stop_number_format, stop.number)
            } else {
                getString(R.string.stop_id_format, stop.id)
            }

            textSize = 13f

            setTextColor(
                "#667085".toColorInt()
            )

            setPadding(
                0,
                dp(6),
                0,
                0
            )
        }

        val distanceView = TextView(this).apply {

            text = getString(
                R.string.distance_meters_format,
                distance.toInt()
            )
            textSize = 14f

            setTextColor(
                "#667085".toColorInt()
            )

            setPadding(
                0,
                dp(8),
                0,
                0
            )
        }

        card.addView(nameView)
        card.addView(numberView)
        card.addView(distanceView)

        card.setOnClickListener {
            openBusStopActivity(stop, distance)
        }

        resultContainer.addView(card)
    }

    // 버스 번호·유형·기점·종점을 보여주는 노선 카드를 만든다.
    private fun addRouteCard(
        route: BusRouteSearchResult
    ) {

        val card = createCard()

        val numberView = TextView(this).apply {

            text = getString(R.string.bus_number_format, route.number)
            textSize = 18f

            setTypeface(
                null,
                Typeface.BOLD
            )

            setTextColor(
                "#1565C0".toColorInt()
            )

        }

        val typeView = TextView(this).apply {

            text = if (route.type.isNotBlank()) {
                getString(R.string.route_type_format, route.type)
            } else {
                getString(R.string.bus_route_label)
            }

            textSize = 13f

            setTextColor(
                "#667085".toColorInt()
            )

            setPadding(
                0,
                dp(6),
                0,
                0
            )
        }

        val directionView = TextView(this).apply {

            text = if (
                route.startStop.isNotBlank() ||
                route.endStop.isNotBlank()
            ) {
                getString(
                    R.string.route_endpoints_format,
                    route.startStop,
                    route.endStop
                )
            } else {
                getString(R.string.route_id_format, route.id)
            }

            textSize = 14f

            setTextColor(
                "#667085".toColorInt()
            )

            setPadding(
                0,
                dp(8),
                0,
                0
            )
        }

        card.addView(numberView)
        card.addView(typeView)
        card.addView(directionView)

        card.setOnClickListener {
            if (route.id.isBlank()) {
                showMessage(R.string.route_id_missing)
                return@setOnClickListener
            }

            startActivity(
                BusRouteActivity.createIntent(
                    this@SearchActivity,
                    route.id,
                    route.number
                )
            )
        }

        resultContainer.addView(card)
    }

    // 정류장 카드와 버스 카드가 함께 사용하는 여백과 배경을 설정한다.
    private fun createCard(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16)
            )

            setBackgroundResource(
                R.drawable.bg_card
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }

            isClickable = true
            isFocusable = true
        }
    }

    // 정류장 카드를 누르면 선택한 정류장 ID와 거리를 상세 화면에 전달한다.
    private fun openBusStopActivity(
        stop: NearbyBusStop,
        distance: Float
    ) {
        startActivity(BusStopActivity.createIntent(this, stop, distance))
    }

    // 조건에 맞는 결과가 없을 때 안내 문구를 표시한다.
    private fun showEmptyMessage() {

        val messageView = TextView(this).apply {

            text = getString(R.string.no_search_results)
            textSize = 15f
            gravity = Gravity.CENTER

            setTextColor(Color.GRAY)

            setPadding(
                dp(16),
                dp(48),
                dp(16),
                dp(16)
            )
        }

        resultContainer.addView(messageView)
    }

    override fun onDestroy() {

        searchVersion++

        searchHandler.removeCallbacks(
            searchRunnable
        )

        searchExecutor.shutdownNow()

        super.onDestroy()
    }
}
