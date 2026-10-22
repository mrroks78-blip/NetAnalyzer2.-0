package com.netanalyzer.app

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Log
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
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "NetAnalyzer"
        private const val LOCATION_PERMISSION_REQUEST = 101
        private const val TILE_LOAD_TIMEOUT_MS = 10000L // 10 секунд на загрузку
        private const val COMPASS_SMOOTHING_WINDOW = 8
        private const val LOCATION_UPDATE_INTERVAL_MS = 3000L
        private const val LOCATION_MIN_DISTANCE_M = 10f
    }

    // === UI Components ===
    private lateinit var mapView: MapView
    private lateinit var mapContainer: FrameLayout
    private lateinit var btnToggleMap: Button
    private lateinit var btnMyLocation: Button
    private lateinit var compassView: ImageView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusText: TextView

    // === Services ===
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    // === State ===
    private var isSatellite = false
    private var currentSatelliteIndex = 0
    private var currentZoom = 15.0
    private var currentCenter = GeoPoint(55.751244, 37.618423)
    private var myLocation: GeoPoint? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var isLocationPermissionGranted = false

    // === Compass State ===
    private var currentAzimuth = 0f
    private var smoothedAzimuth = 0f
    private val azimuthBuffer = FloatArray(COMPASS_SMOOTHING_WINDOW)
    private var azimuthBufferIndex = 0
    private var azimuthBufferCount = 0
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var accelerometerValues: FloatArray? = null
    private var magnetometerValues: FloatArray? = null
    private var hasAccelerometer = false
    private var hasMagnetometer = false

    // === Fallback State ===
    private val tileLoadHandler = Handler(Looper.getMainLooper())
    private var tileLoadTimeout: Runnable? = null
    private var isTileLoading = false

    // === Overlays ===
    private val heatmapOverlay = CoverageHeatmapOverlay()
    private val locationListener = LocationUpdateListener()

    // === Tile Sources (только РЕАЛЬНО работающие) ===
    private val satelliteSources = listOf(
        // 1. ESRI World Imagery — работает глобально, включая РФ
        XYTileSource(
            "ESRI World Imagery",
            1, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
            "© Esri"
        ),
        // 2. ESRI Satellite (альтернативный endpoint)
        XYTileSource(
            "ESRI Satellite",
            1, 19, 256, ".jpg",
            arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
            "© Esri Services"
        ),
        // 3. Google Satellite — работает неофициально
        XYTileSource(
            "Google Satellite",
            1, 20, 256, ".png",
            arrayOf(
                "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                "https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                "https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"
            ),
            "© Google"
        ),
        // 4. OpenTopoMap — топографическая карта
        XYTileSource(
            "OpenTopoMap",
            1, 17, 256, ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
            ),
            "© OpenTopoMap"
        ),
        // 5. CartoDB Voyager — стильная карта
        XYTileSource(
            "CartoDB Voyager",
            1, 19, 256, ".png",
            arrayOf("https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"),
            "© CartoDB"
        )
    )

    // === Data Models ===
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

    // === Lifecycle ===

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeOSMDroid()
        initializeUI()
        initializeServices()
        setupMap()
        loadCoverageData()
        requestLocationPermission()
    }

    private fun initializeOSMDroid() {
        val prefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().userAgentValue = "NetAnalyzer/1.0"
        Configuration.getInstance().osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        Configuration.getInstance().load(this, prefs)
        Log.d(TAG, "OSMDroid initialized, cache: ${Configuration.getInstance().osmdroidTileCache}")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeUI() {
        mapContainer = findViewById(R.id.mapContainer)

        // Карта добавляется ПЕРВОЙ (индекс 0) — будет внизу
        mapView = MapView(this)
        val mapParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        mapContainer.addView(mapView, 0, mapParams)

        // Индикатор загрузки
        loadingIndicator = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        mapContainer.addView(loadingIndicator, FrameLayout.LayoutParams(120, 120, Gravity.CENTER))

        // Текст статуса
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

        // Кнопка переключения карты
        btnToggleMap = createButton(
            text = "Спутник",
            backgroundColor = Color.parseColor("#2196F3"),
            onClick = { toggleMapType() }
        )
        mapContainer.addView(btnToggleMap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(12, 12, 12, 12)
        })

        // Кнопка местоположения
        btnMyLocation = createButton(
            text = "📍",
            backgroundColor = Color.parseColor("#4CAF50"),
            onClick = { goToMyLocation() }
        )
        mapContainer.addView(btnMyLocation, FrameLayout.LayoutParams(100, 100).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(12, 12, 12, 12)
        })

        // Компас
        compassView = ImageView(this).apply {
            setImageResource(R.drawable.compass)
            elevation = 8f
        }
        mapContainer.addView(compassView, FrameLayout.LayoutParams(100, 100).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(12, 12, 12, 12)
        })
    }

    private fun createButton(text: String, backgroundColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setBackgroundColor(backgroundColor)
            setTextColor(Color.WHITE)
            elevation = 8f
            setPadding(20, 12, 20, 12)
            textSize = 14f
            setOnClickListener { onClick() }
        }
    }

    private fun initializeServices() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Проверяем наличие датчиков
        hasAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        hasMagnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

        if (!hasAccelerometer || !hasMagnetometer) {
            Log.w(TAG, "Компас недоступен: accelerometer=$hasAccelerometer, magnetometer=$hasMagnetometer")
            compassView.visibility = View.GONE
        }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isFlingEnabled = true
        mapView.controller.setZoom(currentZoom)
        mapView.controller.setCenter(currentCenter)
        mapView.overlays.add(heatmapOverlay)

        // Оверлей местоположения (только если есть разрешение)
        if (isLocationPermissionGranted) {
            enableMyLocationOverlay()
        }
    }

    private fun enableMyLocationOverlay() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            myLocationOverlay = MyLocationNewOverlay(mapView)
            myLocationOverlay?.enableMyLocation()
            myLocationOverlay?.enableFollowLocation()
            mapView.overlays.add(myLocationOverlay)
            Log.d(TAG, "MyLocation overlay enabled")
        }
    }

    private fun loadCoverageData() {
        for (tower in mockTowers) {
            val geoPoint = GeoPoint(tower.latitude, tower.longitude)
            
            val marker = Marker(mapView).apply {
                position = geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "${tower.operator} (${tower.networkType})"
                setOnMarkerClickListener { _, _ ->
                    showTowerDetails(tower)
                    true
                }
            }
            mapView.overlays.add(marker)
            heatmapOverlay.addTower(tower)
        }
        mapView.controller.setCenter(currentCenter)
        Log.d(TAG, "Loaded ${mockTowers.size} cell towers")
    }

    // === Map Type Toggle ===

    private fun toggleMapType() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет интернета — карты не загрузятся", Toast.LENGTH_LONG).show()
            return
        }

        isSatellite = !isSatellite
        cancelTileLoadTimeout()

        if (isSatellite) {
            currentSatelliteIndex = 0
            tryLoadSatelliteSource()
            btnToggleMap.text = "Схема"
        } else {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            btnToggleMap.text = "Спутник"
            hideLoading()
            mapView.invalidate()
        }
    }

    private fun tryLoadSatelliteSource() {
        if (currentSatelliteIndex >= satelliteSources.size) {
            Log.e(TAG, "Все ${satelliteSources.size} источников недоступны")
            Toast.makeText(this, "Не удалось загрузить спутниковую карту", Toast.LENGTH_LONG).show()
            isSatellite = false
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            btnToggleMap.text = "Спутник"
            hideLoading()
            return
        }

        val source = satelliteSources[currentSatelliteIndex]
        Log.d(TAG, "Пробуем источник #$currentSatelliteIndex: ${source.name()}")
        
        showLoading("Загрузка: ${source.name()}")
        mapView.setTileSource(source)
        isTileLoading = true

        // Таймаут для автоматического fallback
        tileLoadTimeout = Runnable {
            if (isSatellite && isTileLoading) {
                Log.w(TAG, "Таймаут для ${source.name()}, пробуем следующий")
                isTileLoading = false
                currentSatelliteIndex++
                tryLoadSatelliteSource()
            }
        }
        tileLoadHandler.postDelayed(tileLoadTimeout!!, TILE_LOAD_TIMEOUT_MS)
    }

    private fun cancelTileLoadTimeout() {
        tileLoadTimeout?.let { 
            tileLoadHandler.removeCallbacks(it)
            Log.d(TAG, "Tile load timeout cancelled")
        }
        tileLoadTimeout = null
        isTileLoading = false
    }

    private fun showLoading(message: String) {
        loadingIndicator.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        Log.d(TAG, "Loading: $message")
    }

    private fun hideLoading() {
        loadingIndicator.visibility = View.GONE
        statusText.visibility = View.GONE
        cancelTileLoadTimeout()
    }

    // === Location ===

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED -> {
                isLocationPermissionGranted = true
                enableMyLocationOverlay()
                startLocationUpdates()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Toast.makeText(this, "Разрешите геолокацию для работы приложения", Toast.LENGTH_LONG).show()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST
                )
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST
                )
            }
        }
    }

    private fun startLocationUpdates() {
        if (!isLocationPermissionGranted) {
            Log.w(TAG, "Location permission not granted, skipping updates")
            return
        }

        try {
            // GPS — точный, но работает только на улице
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    LOCATION_MIN_DISTANCE_M,
                    locationListener
                )
                Log.d(TAG, "GPS location updates started")
            } else {
                Log.w(TAG, "GPS provider disabled")
            }

            // Network — менее точный, но работает в помещении
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS * 2,
                    LOCATION_MIN_DISTANCE_M * 5,
                    locationListener
                )
                Log.d(TAG, "Network location updates started")
            } else {
                Log.w(TAG, "Network provider disabled")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location updates", e)
            Toast.makeText(this, "Ошибка доступа к геолокации", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
            Log.d(TAG, "Location updates stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping location updates", e)
        }
    }

    private inner class LocationUpdateListener : LocationListener {
        override fun onLocationChanged(location: Location) {
            myLocation = GeoPoint(location.latitude, location.longitude)
            Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude}, accuracy=${location.accuracy}m")
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            Log.d(TAG, "Location status changed: $provider -> $status")
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "Provider disabled: $provider")
        }
    }

    private fun goToMyLocation() {
        val loc = myLocation
        if (loc != null) {
            currentCenter = loc
            mapView.controller.animateTo(loc)
            mapView.controller.setZoom(16.0)
            Toast.makeText(this, "Центрировано на вашем местоположении", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Centered on user location: $loc")
        } else {
            Toast.makeText(this, "Местоположение ещё не определено. Подождите...", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "User location not available yet")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                isLocationPermissionGranted = true
                enableMyLocationOverlay()
                startLocationUpdates()
                Log.d(TAG, "Location permission granted")
            } else {
                isLocationPermissionGranted = false
                Toast.makeText(this, "Геолокация отключена — некоторые функции недоступны", Toast.LENGTH_LONG).show()
                Log.w(TAG, "Location permission denied")
            }
        }
    }

    // === Compass ===

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
            val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                
                // Циклический буфер для сглаживания
                azimuthBuffer[azimuthBufferIndex] = azimuth
                azimuthBufferIndex = (azimuthBufferIndex + 1) % COMPASS_SMOOTHING_WINDOW
                if (azimuthBufferCount < COMPASS_SMOOTHING_WINDOW) {
                    azimuthBufferCount++
                }
                
                // Вычисляем среднее
                var sum = 0f
                for (i in 0 until azimuthBufferCount) {
                    sum += azimuthBuffer[i]
                }
                smoothedAzimuth = sum / azimuthBufferCount
                
                rotateCompass(smoothedAzimuth)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(TAG, "Magnetometer unreliable — compass may be inaccurate")
        }
    }

    private fun rotateCompass(azimuth: Float) {
        val rotateAnimation = RotateAnimation(
            currentAzimuth,
            -azimuth,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 100
            fillAfter = true
        }
        compassView.startAnimation(rotateAnimation)
        currentAzimuth = -azimuth
    }

    // === Lifecycle Management ===

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        
        if (hasAccelerometer && hasMagnetometer) {
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            
            if (accelerometer != null && magnetometer != null) {
                // SENSOR_DELAY_GAME = 20ms для плавности
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
                Log.d(TAG, "Compass sensors registered with SENSOR_DELAY_GAME")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(this)
        cancelTileLoadTimeout()
        Log.d(TAG, "Sensors unregistered, tile timeout cancelled")
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        cancelTileLoadTimeout()
        Log.d(TAG, "Activity destroyed, resources cleaned up")
    }

    // === Utilities ===

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Log.d(TAG, "Network available: $hasInternet")
        return hasInternet
    }

    private fun showTowerDetails(tower: CellTower) {
        val explanation = getExplanation(tower.signalStrengthDbm)
        android.app.AlertDialog.Builder(this)
            .setTitle("${tower.operator} (${tower.networkType})")
            .setMessage("Уровень сигнала: ${tower.signalStrengthDbm} dBm\n\nЧто это значит:\n$explanation")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getExplanation(dbm: Int): String = when {
        dbm >= -70 -> "Отличный сигнал. Идеально для стриминга 4K и видеозвонков."
        dbm >= -85 -> "Хороший сигнал. Стабильный интернет, видео загружается быстро."
        dbm >= -100 -> "Средний сигнал. Веб-серфинг работает, видео может буферизоваться."
        dbm >= -110 -> "Слабый сигнал. Возможны обрывы связи, только текст и аудио."
        else -> "Очень слабый сигнал. Высокая вероятность потери сети."
    }

    // === Heatmap Overlay ===

    private class CoverageHeatmapOverlay : Overlay() {
        private val towers = mutableListOf<CellTower>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        fun addTower(tower: CellTower) {
            towers.add(tower)
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            
            for (tower in towers) {
                val point = mapView.projection.toPixels(
                    GeoPoint(tower.latitude, tower.longitude), null
                )
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
