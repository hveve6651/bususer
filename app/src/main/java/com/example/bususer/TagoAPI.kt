package com.example.bususer

import android.location.Location
import androidx.core.net.toUri
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object TagoAPI {

    // 두 위·경도 사이의 직선거리를 미터 단위로 계산한다.
    fun distanceMeters(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            startLatitude,
            startLongitude,
            endLatitude,
            endLongitude,
            results
        )
        return results[0]
    }

    data class NearbyBusStop(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val number: String = ""
    )

    data class BusArrivalInfo(
        val routeId: String,
        val busNumber: String,
        val arrivalSeconds: Int,
        val remainingStops: Int,
        val busType: String
    )

    data class BusRouteSearchResult(
        val id: String,
        val number: String,
        val type: String,
        val startStop: String,
        val endStop: String
    )

    data class RouteStop(
        val id: String,
        val name: String,
        val order: Int,
        val latitude: Double?,
        val longitude: Double?
    )

    data class RouteBusLocation(
        val vehicleId: String,
        val nodeId: String,
        val nodeOrder: Int,
        val vehicleNumber: String,
        val lowPlate: Boolean
    )

    // 실시간 정보는 짧게, 거의 변하지 않는 노선 정보는 오래 저장해 API 호출을 줄인다.
    private const val NEARBY_CACHE_MS = 10 * 60 * 1000L
    private const val SEARCH_CACHE_MS = 30 * 60 * 1000L
    private const val ROUTE_CACHE_MS = 24 * 60 * 60 * 1000L
    private const val REALTIME_CACHE_MS = 30 * 1000L
    private const val MAX_RESPONSE_CACHE_ENTRIES = 128
    private const val MAX_DIRECTION_CACHE_ENTRIES = 256

    // 공공데이터포털 일반 인증키 (Decoding)
    private const val API_KEY = ""

    // 진주시 도시코드
    private const val CITY_CODE = "38030"

    private const val BASE_URL =
        "https://apis.data.go.kr/1613000/"

    private const val STOP_SERVICE =
        "BusSttnInfoInqireService/"

    private const val ROUTE_SERVICE =
        "BusRouteInfoInqireService/"

    private data class BusRoute(
        val id: String,
        val number: String
    )

    // 한 번 계산한 정류장 대표 방면은 다시 계산하지 않도록 따로 보관한다.
    private val directionCache =
        object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > MAX_DIRECTION_CACHE_ENTRIES
        }

    private data class CacheEntry(
        val value: Any,
        val expiresAt: Long
    )

    // 캐시가 128개를 넘으면 가장 오래 사용하지 않은 응답부터 자동으로 제거한다.
    private val responseCache =
        object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry>?
            ): Boolean = size > MAX_RESPONSE_CACHE_ENTRIES
        }
    private val requestLocks = Array(16) { Any() }

    // 유효 시간이 남은 응답만 반환하며, 시간이 지난 응답은 삭제하고 다시 조회한다.
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cachedValue(key: String): T? =
        synchronized(responseCache) {
            val entry = responseCache[key] ?: return@synchronized null

            if (entry.expiresAt <= System.currentTimeMillis()) {
                responseCache.remove(key)
                null
            } else {
                entry.value as? T
            }
        }

    private fun <T : Any> cached(
        key: String,
        ttlMs: Long,
        loader: () -> T
    ): T {
        if (ttlMs <= 0L) return loader()

        cachedValue<T>(key)?.let { return it }

        // 같은 내용의 요청이 동시에 들어오면 첫 요청만 실행하고 나머지는 그 결과를 공유한다.
        val requestLock = requestLocks[
            (key.hashCode() and Int.MAX_VALUE) % requestLocks.size
        ]

        return synchronized(requestLock) {
            cachedValue<T>(key) ?: loader().also { value ->
                synchronized(responseCache) {
                    responseCache[key] = CacheEntry(
                        value = value,
                        expiresAt = System.currentTimeMillis() + ttlMs
                    )
                }
            }
        }
    }

    // 모든 TAGO 기능이 공통으로 사용하는 요청 함수로, 주소 생성과 캐시 처리를 한곳에서 담당한다.
    private fun requestItems(
        endpoint: String,
        params: Map<String, String>,
        maxPages: Int = 5,
        cacheTtlMs: Long = SEARCH_CACHE_MS
    ): List<JSONObject> {
        val cacheKey = buildString {
            append("items:")
            append(endpoint)
            append(':')
            append(maxPages)
            params.toSortedMap().forEach { (key, value) ->
                append(':')
                append(key)
                append('=')
                append(value)
            }
        }

        return cached(cacheKey, cacheTtlMs) {
            requestItemsFromNetwork(endpoint, params, maxPages)
        }
    }

    private fun requestItemsFromNetwork(
        endpoint: String,
        params: Map<String, String>,
        maxPages: Int
    ): List<JSONObject> {

        val results = mutableListOf<JSONObject>()

        val pageSize = 100
        var page = 1

        // 한 번에 최대 100개씩 읽고, 마지막 페이지에 도착하면 추가 요청을 중단한다.
        while (page <= maxPages) {

            val builder = (BASE_URL + endpoint)
                .toUri()
                .buildUpon()
                .appendQueryParameter(
                    "serviceKey",
                    API_KEY
                )
                .appendQueryParameter(
                    "_type",
                    "json"
                )
                .appendQueryParameter(
                    "pageNo",
                    page.toString()
                )
                .appendQueryParameter(
                    "numOfRows",
                    pageSize.toString()
                )

            params.forEach { (key, value) ->
                builder.appendQueryParameter(key, value)
            }

            val connection = URL(
                builder.build().toString()
            ).openConnection() as HttpURLConnection

            var totalCount = 0
            var receivedCount = 0

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val status = connection.responseCode

                if (status != 200) {
                    throw Exception(
                        "TAGO HTTP 오류: $status"
                    )
                }

                val responseText =
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                val response = JSONObject(
                    responseText
                ).getJSONObject("response")

                val header = response
                    .getJSONObject("header")

                val resultCode = header
                    .optString("resultCode")

                if (resultCode != "00" &&
                    resultCode != "0"
                ) {

                    throw Exception(
                        header.optString(
                            "resultMsg",
                            "TAGO API 오류"
                        )
                    )
                }

                val body = response
                    .getJSONObject("body")

                totalCount = body.optInt(
                    "totalCount",
                    0
                )

                val rawItem = body
                    .optJSONObject("items")
                    ?.opt("item")

                when (rawItem) {

                    is JSONArray -> {
                        receivedCount = rawItem.length()

                        for (i in 0 until rawItem.length()) {
                            results.add(
                                rawItem.getJSONObject(i)
                            )
                        }
                    }

                    is JSONObject -> {
                        receivedCount = 1
                        results.add(rawItem)
                    }
                }

            } finally {
                connection.disconnect()
            }

            if (receivedCount == 0) break

            if (results.size >= totalCount) break

            page++
        }

        return results
    }

    // 현재 좌표를 기준으로 지정한 반경 안의 정류장을 조회한다.
    fun getNearbyStops(
        latitude: Double,
        longitude: Double
    ): List<NearbyBusStop> {

        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            return emptyList()
        }

        // GPS가 조금 흔들려도 같은 위치로 판단하도록 좌표를 약 11m 단위로 맞춘다.
        val normalizedLatitude = String.format(Locale.US, "%.4f", latitude)
        val normalizedLongitude = String.format(Locale.US, "%.4f", longitude)

        val items = requestItems(
            endpoint = STOP_SERVICE +
                    "getCrdntPrxmtSttnList",
            params = mapOf(
                "gpsLati" to normalizedLatitude,
                "gpsLong" to normalizedLongitude
            ),
            maxPages = 1,
            cacheTtlMs = NEARBY_CACHE_MS
        )

        return items.mapNotNull { item ->

            val lat = item
                .optString("gpslati")
                .toDoubleOrNull()
                ?: return@mapNotNull null

            val lon = item
                .optString("gpslong")
                .toDoubleOrNull()
                ?: return@mapNotNull null

            val id = item.optString("nodeid")
            val name = item.optString("nodenm")

            if (id.isBlank() || name.isBlank()) {
                return@mapNotNull null
            }

            NearbyBusStop(
                id = id,
                name = name,
                latitude = lat,
                longitude = lon,
                number = item.optString("nodeno")
            )

        }.distinctBy { it.id }
    }

    // 한 정류장을 지나가는 모든 버스 노선을 조회한다.
    private fun getRoutesByStop(
        nodeId: String
    ): List<BusRoute> {

        val items = requestItems(
            endpoint = STOP_SERVICE +
                    "getSttnThrghRouteList",
            params = mapOf(
                "cityCode" to CITY_CODE,
                "nodeId" to nodeId
            ),
            maxPages = 1,
            cacheTtlMs = ROUTE_CACHE_MS
        )

        return items.mapNotNull { item ->

            val routeId = item.optString("routeid")
            val routeNo = item.optString("routeno")

            if (routeId.isBlank() ||
                routeNo.isBlank()
            ) {

                return@mapNotNull null
            }

            BusRoute(
                id = routeId,
                number = routeNo
            )

        }.distinctBy { it.id }
    }

    fun getRouteBusLocations(
        routeId: String
    ): List<RouteBusLocation> {

        if (routeId.isBlank()) return emptyList()

        val items = requestItems(
            endpoint = "BusLcInfoInqireService/getRouteAcctoBusLcList",
            params = mapOf(
                "cityCode" to CITY_CODE,
                "routeId" to routeId
            ),
            maxPages = 1,
            cacheTtlMs = REALTIME_CACHE_MS
        )

        return items.mapIndexedNotNull { index, item ->

            val order = item.optInt("nodeord", -1)
            val nodeId = item.optString("nodeid")
            val vehicleId = item.optString("vehicleno")
                .ifBlank {
                    item.optString("vehicleno1")
                }

            if (order <= 0 && nodeId.isBlank()) {
                null
            } else {
                RouteBusLocation(
                    vehicleId = vehicleId.ifBlank {
                        "bus_$index"
                    },
                    nodeId = nodeId,
                    nodeOrder = order,
                    vehicleNumber = vehicleId,
                    lowPlate = item.optString("lowplate") == "1"
                )
            }
        }
    }

    // 한 버스 노선이 지나가는 정류장을 운행 순서대로 조회한다.
    fun getRouteStops(
        routeId: String
    ): List<RouteStop> {

        if (routeId.isBlank()) {
            return emptyList()
        }

        // 노선 순서는 자주 바뀌지 않으므로 하루 동안 저장된 결과를 재사용한다.
        val items = requestItems(
            endpoint = ROUTE_SERVICE +
                    "getRouteAcctoThrghSttnList",
            params = mapOf(
                "cityCode" to CITY_CODE,
                "routeId" to routeId
            ),
            maxPages = 2,
            cacheTtlMs = ROUTE_CACHE_MS
        )

        return items.mapNotNull { item ->

            val id = item.optString("nodeid")
            val name = item.optString("nodenm")

            val order = item
                .optString("nodeord")
                .toIntOrNull()

            val latitude = item
                .optString("gpslati")
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() }

            val longitude = item
                .optString("gpslong")
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() }

            if (id.isBlank() ||
                name.isBlank() ||
                order == null
            ) {
                return@mapNotNull null
            }

            RouteStop(
                id = id,
                name = name,
                order = order,
                latitude = latitude,
                longitude = longitude
            )

        }.sortedBy { it.order }

    }

    // 사용자가 선택한 버스 번호를 TAGO 요청에 필요한 노선 ID로 변환한다.
    fun getRouteId(
        routeNumber: String = "150"
    ): String {

        val normalizedRouteNumber = routeNumber.trim()

        require(normalizedRouteNumber.isNotEmpty()) {
            "버스 번호를 입력하세요."
        }

        val route = searchRoutes(normalizedRouteNumber)
            .firstOrNull {
                it.number.trim() == normalizedRouteNumber
            }
            ?: throw Exception(
                "${normalizedRouteNumber}번 버스 노선을 찾지 못했습니다."
            )

        return route.id.ifBlank {
            throw Exception(
                "${normalizedRouteNumber}번 노선 ID가 비어 있습니다."
            )
        }
    }

    // 정류장을 지나는 노선들의 다음 정류장을 비교해 대표 방면을 계산한다.
    fun getStopDirection(
        nodeId: String
    ): String {

        val normalizedNodeId = nodeId.trim()
        if (normalizedNodeId.isEmpty()) return "방면 정보 없음"

        synchronized(directionCache) {
            directionCache[normalizedNodeId]?.let {
                return it
            }
        }

        val routes = getRoutesByStop(normalizedNodeId)

        if (routes.isEmpty()) {
            return cacheDirection(normalizedNodeId, "방면 정보 없음")
        }

        // 같은 노선이 중복 집계되지 않도록 다음 정류장별로 노선 ID를 묶는다.
        val nextStopRoutes =
            mutableMapOf<String, MutableSet<String>>()

        var failedRoutes = 0

        for (route in routes) {

            try {
                val stops = getRouteStops(route.id)

                for (index in stops.indices) {

                    if (stops[index].id != normalizedNodeId) {
                        continue
                    }

                    val nextStop =
                        stops.getOrNull(index + 1)
                            ?: continue

                    if (nextStop.name.isBlank()) {
                        continue
                    }

                    nextStopRoutes
                        .getOrPut(nextStop.name) {
                            mutableSetOf()
                        }
                        .add(route.id)
                }

            } catch (e: Exception) {

                failedRoutes++

                android.util.Log.e(
                    "BUS_STOP_API",
                    "노선 조회 실패: ${route.id}",
                    e
                )
            }
        }

        if (nextStopRoutes.isEmpty()) {
            return if (failedRoutes > 0) {
                "방면 조회 실패"
            } else {
                cacheDirection(normalizedNodeId, "방면 정보 없음")
            }
        }

        // 일부 노선 조회가 실패했다면 잘못된 방면을 확정하지 않고 확인 필요로 표시한다.
        if (failedRoutes > 0) {
            return "방면 확인 필요"
        }

        val maxCount = nextStopRoutes.values
            .maxOf { it.size }

        val candidates = nextStopRoutes
            .filterValues {
                it.size == maxCount
            }
            .keys
            .sorted()

        val direction = if (candidates.size == 1) {

            "${candidates.first()} 방면"

        } else {

            "방면 확인 필요"
        }

        return cacheDirection(normalizedNodeId, direction)
    }

    private fun cacheDirection(nodeId: String, direction: String): String =
        direction.also {
            synchronized(directionCache) {
                directionCache[nodeId] = it
            }
        }

    fun getBusArrivals(
        nodeId: String,
        cityCode: String = CITY_CODE
    ): List<BusArrivalInfo> {
        val normalizedNodeId = nodeId.trim()
        if (normalizedNodeId.isEmpty()) return emptyList()

        return cached(
            key = "arrivals:$cityCode:$normalizedNodeId",
            ttlMs = REALTIME_CACHE_MS
        ) {
            requestBusArrivals(normalizedNodeId, cityCode)
        }
    }

    private fun requestBusArrivals(
        nodeId: String,
        cityCode: String
    ): List<BusArrivalInfo> {

        val url = (BASE_URL +
                "ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList").toUri()
            .buildUpon()
            .appendQueryParameter("serviceKey", API_KEY)
            .appendQueryParameter("cityCode", cityCode)
            .appendQueryParameter("nodeId", nodeId)
            .appendQueryParameter("numOfRows", "100")
            .appendQueryParameter("pageNo", "1")
            .appendQueryParameter("_type", "json")
            .build()
            .toString()

        val connection = URL(url)
            .openConnection() as HttpURLConnection

        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"

        try {
            if (connection.responseCode != 200) {
                throw Exception("HTTP ${connection.responseCode}")
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val root = JSONObject(response)
            val result = root.getJSONObject("response")
            val header = result.getJSONObject("header")

            if (header.optString("resultCode") != "00" &&
                header.optString("resultCode") != "0"
            ) {
                throw Exception(
                    header.optString("resultMsg", "API 오류")
                )
            }

            val body = result.getJSONObject("body")
            val items = body.optJSONObject("items")
                ?: return emptyList()

            val rawItems = items.opt("item")
            val array = when (rawItems) {
                is JSONArray -> rawItems
                is JSONObject -> JSONArray().put(rawItems)
                else -> JSONArray()
            }

            val arrivals = mutableListOf<BusArrivalInfo>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                arrivals.add(
                    BusArrivalInfo(
                        routeId = item.optString("routeid"),
                        busNumber = item.optString("routeno"),
                        arrivalSeconds = item.optInt("arrtime", -1),
                        remainingStops = item.optInt(
                            "arrprevstationcnt", -1
                        ),
                        busType = item.optString("routetp")
                    )
                )
            }

            return arrivals
                .filter { it.busNumber.isNotBlank() }
                .distinctBy { it.routeId to it.arrivalSeconds }
                .sortedWith(
                    compareBy {
                        if (it.arrivalSeconds < 0)
                            Int.MAX_VALUE
                        else it.arrivalSeconds
                    }
                )

        } finally {
            connection.disconnect()
        }
    }

    // 진주시 정류장을 이름 또는 정류장 번호로 검색한다.
    fun searchStops(keyword: String): List<NearbyBusStop> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()

        val searchParam = if (query.all { it.isDigit() }) {
            "nodeNo" to query
        } else {
            "nodeNm" to query
        }

        val items = requestItems(
            endpoint = STOP_SERVICE + "getSttnNoList",
            params = mapOf(
                "cityCode" to CITY_CODE,
                searchParam
            ),
            maxPages = 3,
            cacheTtlMs = SEARCH_CACHE_MS
        )

        return items.mapNotNull { item ->
            val id = item.optString("nodeid")
            val name = item.optString("nodenm")
            val latitude = item.optString("gpslati").toDoubleOrNull()
            val longitude = item.optString("gpslong").toDoubleOrNull()

            if (id.isBlank() || name.isBlank() ||
                latitude == null || longitude == null ||
                !latitude.isFinite() || !longitude.isFinite()
            ) {
                null
            } else {
                NearbyBusStop(
                    id = id,
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    number = item.optString("nodeno")
                )
            }
        }.distinctBy { it.id }
    }

    // 진주시 버스 번호를 검색해 노선 ID와 기점·종점 정보를 반환한다.
    fun searchRoutes(keyword: String): List<BusRouteSearchResult> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()

        val items = requestItems(
            endpoint = ROUTE_SERVICE + "getRouteNoList",
            params = mapOf(
                "cityCode" to CITY_CODE,
                "routeNo" to query
            ),
            maxPages = 2,
            cacheTtlMs = ROUTE_CACHE_MS
        )

        return items.mapNotNull { item ->
            val id = item.optString("routeid")
            val number = item.optString("routeno")
            if (id.isBlank() || number.isBlank()) {
                null
            } else {
                BusRouteSearchResult(
                    id = id,
                    number = number,
                    type = item.optString("routetp"),
                    startStop = item.optString("startnodenm"),
                    endStop = item.optString("endnodenm")
                )
            }
        }.distinctBy { it.id }
    }
}
