package com.mogun.weatherapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.mogun.weatherapp.databinding.ActivityMainBinding
import com.mogun.weatherapp.databinding.ItemForecastBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale

class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                updateLocation()
            }
            else -> {
                Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                Intent(ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    startActivity(this)
                    finish()
                }
            }
        }
    }

    private lateinit var binding: ActivityMainBinding

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 위치 권한 요청
        locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun transformRainType(forecast: ForecastEntity): String {
        return when (forecast.forecastValue.toInt()) {
            0 -> "없음"
            1 -> "비"
            2 -> "비/눈"
            3 -> "눈"
            4 -> "소나기"
            else -> ""
        }
    }

    private fun transformSky(forecast: ForecastEntity): String {
        return when (forecast.forecastValue.toInt()) {
            1 -> "맑음"
            3 -> "구름 많음"
            4 -> "흐림"
            else -> ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateLocation() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)   // 위치 서비스 클라이언트 만들기
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 위치 권한 요청
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener {
            Thread {
                try {
                    val addressList = Geocoder(this, Locale.KOREA).getFromLocation(
                        it.latitude,
                        it.longitude,
                        1,
                    )

                    runOnUiThread {
                        binding.locationTextView.text = addressList?.get(0)?.thoroughfare.orEmpty()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()

            val retrofit = Retrofit.Builder()
                .baseUrl("http://apis.data.go.kr/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service = retrofit.create(WeatherService::class.java)

            val baseDateTime = BaseDateTime.getBaseDateTime()
            val converter = GeoPointConverter()
            val point = converter.convert(lat = it.latitude, lon = it.longitude)

            service.getVilageForecast(
                serviceKey = getString(R.string.service_key),
                baseDate = baseDateTime.baseDate,
                baseTime = baseDateTime.baseTime,
                nx = point.nx,
                ny = point.ny,
            ).enqueue(object : Callback<WeatherEntity> {
                override fun onResponse(call: Call<WeatherEntity>, response: Response<WeatherEntity>) {

                    val forecastDateTimeMap = mutableMapOf<String, Forecast>()
                    val forecastList =
                        response.body()?.response?.body?.items?.forevaseEntities.orEmpty()

                    for (forecast in forecastList) {
                        if (forecastDateTimeMap["${forecast.forecastDate}/${forecast.forecastTime}"] == null) {
                            forecastDateTimeMap["${forecast.forecastDate}/${forecast.forecastTime}"] =
                                Forecast(
                                    forecastDate = forecast.forecastDate,
                                    forecastTime = forecast.forecastTime,
                                )
                        }

                        forecastDateTimeMap["${forecast.forecastDate}/${forecast.forecastTime}"]?.apply {
                            when (forecast.category) {
                                Category.POP -> precipitation = forecast.forecastValue.toInt()
                                Category.PTY -> precipitationType = transformRainType(forecast)
                                Category.SKY -> sky = transformSky(forecast)
                                Category.TMP -> temperature = forecast.forecastValue.toDouble()
                                else -> {}
                            }
                        }
                    }

                    val list = forecastDateTimeMap.values.toMutableList()
                    list.sortWith { f1, f2 ->
                        val f1DateTime = "${f1.forecastDate}${f1.forecastTime}"
                        val f2DateTime = "${f2.forecastDate}${f2.forecastTime}"

                        return@sortWith f1DateTime.compareTo(f2DateTime)
                    }

                    val currentForecast = list.first()
                    binding.temperatureTextView.text = getString(R.string.temperature_text, currentForecast.temperature)
                    binding.skyTextView.text = currentForecast.weather
                    binding.precipitationTextView.text = getString(R.string.precipitation_text, currentForecast.precipitation)

                    binding.childForecastLayout.apply {
                        list.forEachIndexed { index, forecast ->
                            if (index == 0) {
                                return@forEachIndexed
                            }

                            val itemView = ItemForecastBinding.inflate(layoutInflater)

                            itemView.timeTextView.text = forecast.forecastTime
                            itemView.weatherTextView.text = forecast.weather
                            itemView.temperatureTextView.text = getString(R.string.temperature_text, forecast.temperature)

                            addView(itemView.root)
                        }
                    }

                    Log.e("Forecast", forecastDateTimeMap.toString())
                }

                override fun onFailure(call: Call<WeatherEntity>, t: Throwable) {}
            })
        }
    }
}