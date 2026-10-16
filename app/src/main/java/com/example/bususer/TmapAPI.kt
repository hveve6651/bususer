package com.example.bususer

import androidx.core.net.toUri
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object TmapAPI {

    // true이면 발표·테스트용 고정 좌표를, false이면 기기의 실제 GPS를 사용한다.
    internal const val USE_TEST_LOCATION = true
    internal const val TEST_LATITUDE = 35.162356
    internal const val TEST_LONGITUDE = 128.107108

    // 주변 지도와 운행 경로 지도가 함께 사용하는 Tmap 인증키이다.
    internal const val TMAP_API_KEY = ""

    fun getDongAddress(
        latitude: Double,
        longitude: Double
    ): String {
        val urlString =
            "https://apis.openapi.sk.com/tmap/geo/reversegeocoding"
                .toUri()
                .buildUpon()
                .appendQueryParameter("version", "1")
                .appendQueryParameter("lat", latitude.toString())
                .appendQueryParameter("lon", longitude.toString())
                .appendQueryParameter("coordType", "WGS84GEO")
                .appendQueryParameter("addressType", "A10")
                .build()
                .toString()

        // 역지오코딩은 위·경도 좌표를 사람이 읽을 수 있는 주소로 바꾸는 기능이다.
        val connection = URL(urlString).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("appKey", TMAP_API_KEY)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode != 200) {
                throw Exception("TMAP 주소 조회 오류: ${connection.responseCode}")
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val info = JSONObject(response)
                .getJSONObject("addressInfo")

            val district = info.optString("gu_gun")
            val town = info.optString("eup_myun")
            val dong = info.optString("legalDong")
                .ifBlank { info.optString("adminDong") }

            return listOf(district, town, dong)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
                .ifBlank { "주소 정보 없음" }
        } finally {
            connection.disconnect()
        }
    }
}
