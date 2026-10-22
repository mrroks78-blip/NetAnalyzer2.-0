package com.netanalyzer.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var btnToggleMap: Button
    private lateinit var btnMyLocation: Button
    private lateinit var compassView: ImageView
    private lateinit var mapContainer: FrameLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusText: TextView
    
    private var isSatellite = false
    private var currentSatelliteIndex = 0
    private val heatmapOverlay = CoverageHeatmapOverlay()
    private var currentZoom = 15.0
    private var currentCenter = GeoPoint(55.751244, 37.618423)
    private var currentAzimuth = 0f
    private var myLocation: GeoPoint? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    
    private var smoothedAzimuth = 0f
    private val azimuthHistory = mutableListOf<Float>()
    private val AZIMUTH_SMOOTHING_WINDOW = 5
    
    private val tileLoadHandler = Handler(Looper.getMainLooper())
    private var tileLoadTimeout: Runnable? = null

    // Источники карт: Яндекс (приоритет для РФ) + другие
    private val satelliteSources = listOf(
        XYTileSource(
            "Yandex Satellite",
            1, 19, 256, ".jpg",
            arrayOf(
                "https://sat01.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}",
                "https://sat02.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}",
                "https://sat03.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}"
            ),
            "© Яндекс"
        ),
        XYTileSource(
            "Yandex Map",
            1, 19, 256, ".png",
            arrayOf(
                "https://vec01.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}",
                "https://vec02.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}"
            ),
            "© Яндекс"
        ),
        XYTileSource(
            "ESRI World Imagery",
            1, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
            "© Esri"
        ),
        XYTileSource(
            "Google Satellite",
            1, 20, 256, "",
            arrayOf(
                "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                "https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                "https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"
            ),
            "© Google"
        ),
        XYTileSource(
            "OpenTopoMap",
            1, 17, 256, ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
            ),
            "© OpenTopoMap"
        )
    )

    data class CellTower(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val operator: String,
        val networkType: String,
        val signalStrengthDbm: Int
    )

    private val mockTowers = listOf(
        CellTower("1", 55.751244, 37.618423, "МТС", "4G", -65),
        CellTower("2", 55.755244, 37.622423, "Билайн", "5G", -80),
        CellTower("3", 55.749244, 37.610423, "Мегафон", "LTE", -95)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().userAgentValue = "NetAnalyzer/1.0"
        Configuration.getInstance().osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        Configuration.getInstance().load(this, prefs)

        mapContainer = findViewById(R.id.mapContainer)

        mapView = MapView(this)
        val mapParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        mapContainer.addView(mapView, 0, mapParams)

        createUIElements()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setupMap()
        loadCoverageData()
        setupLocationTracking()
    }

    private fun createUIElements() {
        loadingIndicator = ProgressBar(this).apply {
            visibility = View.GONE
        }
        val loadingParams = FrameLayout.LayoutParams(120, 120, Gravity.CENTER)
        mapContainer.addView(loadingIndicator, loadingParams)

        statusText = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(12, 6, 12, 6)
        }
        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            setMargins(0, 80, 0, 0)
        }
        mapContainer.addView(statusText, statusParams)

        btnToggleMap = Button(this).apply {
            text = "🛰 Спутник"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            elevation = 8f
            setPadding(20, 12, 20, 12)
            textSize = 14f
            setOnClickListener { toggleMapType() }
        }
        val btnToggleParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(12, 12, 12, 12)
        }
        mapContainer.addView(btnToggleMap, btnToggleParams)

        btnMyLocation = Button(this).apply {
            text = "📍"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            elevation = 8f
            textSize = 20f
            setOnClickListener { goToMyLocation() }
        }
        val btnLocParams = FrameLayout.LayoutParams(100, 100).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(12, 12, 12, 12)
        }
        mapContainer.addView(btnMyLocation, btnLocParams)

        compassView = ImageView(this)
        compassView.setImageResource(R.drawable.compass)
        compassView.elevation = 8f
        val compassParams = FrameLayout.LayoutParams(100, 100).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(12, 12, 12, 12)
        }
        mapContainer.addView(compassView, compassParams)
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isFlingEnabled = true
        mapView.controller.setZoom(currentZoom)
        mapView.controller.setCenter(currentCenter)
        mapView.overlays.add(heatmapOverlay)

        myLocationOverlay = MyLocationNewOverlay(mapView)
        myLocationOverlay?.enableMyLocation()
        mapView.overlays.add(myLocationOverlay)
    }

    private fun toggleMapType() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет интернета", Toast.LENGTH_SHORT).show()
            return
        }

        isSatellite = !isSatellite

        if (isSatellite) {
            currentSatelliteIndex = 0
            loadSatelliteSourceWithFallback()
            btnToggleMap.text = "🗺 Схема"
        } else {
            cancelTileLoadTimeout()
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            btnToggleMap.text = " Спутник"
            hideLoading()
        }

        mapView.invalidate()
    }

    private fun loadSatelliteSourceWithFallback() {
        if (currentSatelliteIndex >= satelliteSources.size) {
            Toast.makeText(this, "Все источники недоступны", Toast.LENGTH_LONG).show()
            isSatellite = false
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            btnToggleMap.text = "🛰 Спутник"
            hideLoading()
            return
        }

        showLoading("Загрузка: ${satelliteSources[currentSatelliteIndex].name()}")
        mapView.setTileSource(satelliteSources[currentSatelliteIndex])

        tileLoadTimeout = Runnable {
            if (isSatellite) {
                currentSatelliteIndex++
                loadSatelliteSourceWithFallback()
            }
        }
        tileLoadHandler.postDelayed(tileLoadTimeout!!, 8000)
    }

    private fun cancelTileLoadTimeout() {
        tileLoadTimeout?.let { tileLoadHandler.removeCallbacks(it) }
        tileLoadTimeout = null
    }

    private fun showLoading(message: String) {
        loadingIndicator.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun hideLoading() {
        loadingIndicator.visibility = View.GONE
        statusText.visibility = View.GONE
        cancelTileLoadTimeout()
    }

    private fun goToMyLocation() {
        val loc = myLocation
        if (loc != null) {
            currentCenter = loc
            mapView.controller.setCenter(loc)
            mapView.controller.setZoom(16.0)
            Toast.makeText(this, "Центрировано", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Местоположение не определено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCoverageData() {
        for (tower in mockTowers) {
            val geoPoint = GeoPoint(tower.latitude, tower.longitude)
            
            val marker = Marker(mapView)
            marker.position = geoPoint
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "${tower.operator} (${tower.networkType})"
            marker.setOnMarkerClickListener { _, _ ->
                showTowerDetails(tower)
                true
            }
            mapView.overlays.add(marker)
            
            heatmapOverlay.addTower(tower)
        }
        
        mapView.controller.setCenter(currentCenter)
    }

    private fun showTowerDetails(tower: CellTower) {
        val explanation = getExplanation(tower.signalStrengthDbm)
        
        android.app.AlertDialog.Builder(this)
            .setTitle("${tower.operator} (${tower.networkType})")
            .setMessage("Уровень сигнала: ${tower.signalStrengthDbm} dBm\n\nЧто это значит:\n$explanation")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getExplanation(dbm: Int): String {
        return when {
            dbm >= -70 -> "Отличный сигнал. Идеально для стриминга 4K."
            dbm >= -85 -> "Хороший сигнал. Стабильный интернет."
            dbm >= -100 -> "Средний сигнал. Возможны задержки."
            dbm >= -110 -> "Слабый сигнал. Только текст."
            else -> "Очень слабый сигнал."
        }
    }

    private fun setupLocationTracking() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), 101)
            return
        }
        
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 3000L, 10f, locationListener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 5000L, 50f, locationListener
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Нет доступа к геолокации", Toast.LENGTH_LONG).show()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            myLocation = GeoPoint(location.latitude, location.longitude)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupLocationTracking()
            } else {
                Toast.makeText(this, "Геолокация отключена", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var accelerometerValues: FloatArray? = null
    private var magnetometerValues: FloatArray? = null

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerValues = event.values.clone()
                updateCompass()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetometerValues = event.values.clone()
                updateCompass()
            }
        }
    }

    private fun updateCompass() {
        if (accelerometerValues != null && magnetometerValues != null) {
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            
            azimuthHistory.add(azimuth)
            if (azimuthHistory.size > AZIMUTH_SMOOTHING_WINDOW) {
                azimuthHistory.removeAt(0)
            }
            smoothedAzimuth = azimuthHistory.average().toFloat()
            
            rotateCompass(smoothedAzimuth)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun rotateCompass(azimuth: Float) {
        val rotateAnimation = RotateAnimation(
            currentAzimuth,
            -azimuth,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotateAnimation.duration = 100
        rotateAnimation.fillAfter = true
        compassView.startAnimation(rotateAnimation)
        currentAzimuth = -azimuth
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        if (accelerometer != null && magnetometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        } else {
            Toast.makeText(this, "Компас недоступен", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(this)
        cancelTileLoadTimeout()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        sensorManager.unregisterListener(this)
        cancelTileLoadTimeout()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private class CoverageHeatmapOverlay : Overlay() {
        private val towers = mutableListOf<CellTower>()
        private val paint = Paint()

        fun addTower(tower: CellTower) {
            towers.add(tower)
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            for (tower in towers) {
                val point = mapView.projection.toPixels(GeoPoint(tower.latitude, tower.longitude), null)
                val x = point.x.toFloat()
                val y = point.y.toFloat()
                val radius = 150f
                val color = when {
                    tower.signalStrengthDbm >= -70 -> Color.argb(102, 0, 255, 0)
                    tower.signalStrengthDbm >= -85 -> Color.argb(102, 136, 255, 0)
                    tower.signalStrengthDbm >= -100 -> Color.argb(102, 255, 255, 0)
                    else -> Color.argb(102, 255, 0, 0)
                }
                val gradient = RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                paint.shader = gradient
                canvas.drawCircle(x, y, radius, paint)
            }
        }
    }
}
