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
import android.os.Build
import android.os.Bundle
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

data class CellTowerInfo(
    val cid: Long,
    val lac: Int,
    val tac: Int,
    val pci: Int,
    val rsrp: Int,
    val rsrq: Int,
    val rssi: Int,
    val frequency: Int,
    val band: Int,
    val technology: String,
    val latitude: Double,
    val longitude: Double
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "NetAnalyzer"
        private const val LOCATION_PERMISSION_REQUEST = 101
        private const val PHONE_STATE_PERMISSION_REQUEST = 102
        private const val COMPASS_SMOOTHING_WINDOW = 8
        private const val UNKNOWN_VALUE = Int.MAX_VALUE
    }

    private lateinit var mapView: MapView
    private lateinit var mapContainer: FrameLayout
    private lateinit var btnToggleMap: Button
    private lateinit var btnMyLocation: Button
    private lateinit var btnScan: Button
    private lateinit var btnDiagnostics: Button
    private lateinit var compassView: ImageView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var infoPanel: ScrollView
    private lateinit var infoPanelText: TextView

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var telephonyManager: TelephonyManager

    private var isSatellite = false
    private var currentZoom = 15.0
    private var currentCenter = GeoPoint(54.5293, 36.2754)
    private var myLocation: GeoPoint? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var isLocationPermissionGranted = false
    private var isPhoneStatePermissionGranted = false

    private var currentAzimuth = 0f
    private val azimuthBuffer = FloatArray(COMPASS_SMOOTHING_WINDOW)
    private var azimuthBufferIndex = 0
    private var azimuthBufferCount = 0
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var accelerometerValues: FloatArray? = null
    private var magnetometerValues: FloatArray? = null
    private var hasAccelerometer = false
    private var hasMagnetometer = false

    private val heatmapOverlay = CoverageHeatmapOverlay()
    private val locationListener = LocationUpdateListener()
    private val cellMarkers = mutableListOf<Marker>()
    private var detectedTowers = mutableListOf<CellTowerInfo>()
    private var pendingScanAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeOSMDroid()
        initializeUI()
        initializeServices()
        setupMap()
        checkExistingPermissions()
        requestLocationPermission()
    }

    private fun checkExistingPermissions() {
        isLocationPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        isPhoneStatePermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeOSMDroid() {
        val prefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().userAgentValue = "NetAnalyzer/1.0"
        val osmdroidBaseDir = File(getExternalFilesDir(null), "osmdroid")
        val osmdroidTileDir = File(osmdroidBaseDir, "tiles")
        if (!osmdroidTileDir.exists()) osmdroidTileDir.mkdirs()
        Configuration.getInstance().osmdroidBasePath = osmdroidBaseDir
        Configuration.getInstance().osmdroidTileCache = osmdroidTileDir
        Configuration.getInstance().load(this, prefs)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeUI() {
        mapContainer = findViewById(R.id.mapContainer)

        mapView = MapView(this)
        mapContainer.addView(mapView, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        loadingIndicator = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        mapContainer.addView(loadingIndicator, FrameLayout.LayoutParams(120, 120, Gravity.CENTER))

        statusText = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(12, 6, 12, 6)
        }
        mapContainer.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            setMargins(0, 90, 0, 0)
        })

        infoPanelText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(12, 8, 12, 8)
        }
        val infoPanelHeight = (resources.displayMetrics.density * 200).toInt()
        infoPanel = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(230, 20, 20, 20))
            addView(infoPanelText, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        mapContainer.addView(infoPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, infoPanelHeight).apply {
            gravity = Gravity.BOTTOM
            setMargins(16, 16, 16, 180)
        })

        btnDiagnostics = createButton("Диагностика", Color.parseColor("#FF5722")) { showDiagnostics() }
        mapContainer.addView(btnDiagnostics, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16, 16, 16, 16)
        })

        compassView = ImageView(this).apply {
            setImageResource(R.drawable.compass)
            elevation = 8f
        }
        mapContainer.addView(compassView, FrameLayout.LayoutParams(160, 160).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16, 80, 16, 16)
        })

        btnToggleMap = createButton("Спутник", Color.parseColor("#2196F3")) { toggleMapType() }
        mapContainer.addView(btnToggleMap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(16, 16, 16, 16)
        })

        btnScan = createButton("Сканировать", Color.parseColor("#9C27B0")) { scanCellTowers() }
        mapContainer.addView(btnScan, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(16, 16, 16, 16)
        })

        btnMyLocation = createButton("Место", Color.parseColor("#4CAF50")) { goToMyLocation() }
        mapContainer.addView(btnMyLocation, FrameLayout.LayoutParams(120, 120).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(16, 16, 16, 16)
        })
    }

    private fun createButton(text: String, backgroundColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setBackgroundColor(backgroundColor)
            setTextColor(Color.WHITE)
            elevation = 8f
            setPadding(20, 10, 20, 10)
            textSize = 13f
            setOnClickListener { onClick() }
        }
    }

    private fun initializeServices() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        hasAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        hasMagnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        if (!hasAccelerometer || !hasMagnetometer) compassView.visibility = View.GONE
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isFlingEnabled = true
        mapView.controller.setZoom(currentZoom)
        mapView.controller.setCenter(currentCenter)
        mapView.overlays.add(heatmapOverlay)
        if (isLocationPermissionGranted) {
            enableMyLocationOverlay()
            startLocationUpdates()
        }
    }

    private fun enableMyLocationOverlay() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            myLocationOverlay = MyLocationNewOverlay(mapView)
            myLocationOverlay?.enableMyLocation()
            myLocationOverlay?.enableFollowLocation()
            mapView.overlays.add(myLocationOverlay)
        }
    }

    private fun requestLocationPermission() {
        if (!isLocationPermissionGranted) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST)
        }
    }

    private fun scanCellTowers() {
        if (!isLocationPermissionGranted) {
            Toast.makeText(this, "Нужно разрешение на геолокацию", Toast.LENGTH_LONG).show()
            requestLocationPermission()
            pendingScanAfterPermission = true
            return
        }
        if (!isPhoneStatePermissionGranted) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_PHONE_STATE), PHONE_STATE_PERMISSION_REQUEST)
            pendingScanAfterPermission = true
            return
        }
        performScan()
    }

    private fun performScan() {
        showLoading("Сканирование сетей...")
        detectedTowers.clear()
        clearCellMarkers()
        heatmapOverlay.clearTowers()

        try {
            val cellInfos: List<CellInfo>? = telephonyManager.allCellInfo
            if (cellInfos == null || cellInfos.isEmpty()) {
                hideLoading()
                Toast.makeText(this, "Не удалось получить данные о вышках", Toast.LENGTH_LONG).show()
                return
            }

            for (cellInfo in cellInfos) {
                if (!cellInfo.isRegistered) continue
                val tower = when (cellInfo) {
                    is CellInfoLte -> parseLteCell(cellInfo)
                    is CellInfoGsm -> parseGsmCell(cellInfo)
                    is CellInfoWcdma -> parseWcdmaCell(cellInfo)
                    is CellInfoNr -> parseNrCell(cellInfo)
                    else -> null
                }
                if (tower != null) {
                    detectedTowers.add(tower)
                    addTowerMarker(tower)
                    heatmapOverlay.addTower(tower)
                }
            }

            hideLoading()
            mapView.invalidate()

            if (detectedTowers.isEmpty()) {
                Toast.makeText(this, "Вышки не найдены", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Найдено ${detectedTowers.size} вышек", Toast.LENGTH_LONG).show()
                showTowerInfo(detectedTowers.first())
            }
        } catch (e: SecurityException) {
            hideLoading()
            Toast.makeText(this, "Нет доступа к данным о вышках", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            hideLoading()
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ПРОСТОЙ парсер LTE - без latitude/longitude из CellIdentity
    @SuppressLint("NewApi")
    private fun parseLteCell(cellInfo: CellInfoLte): CellTowerInfo? {
        val ci = cellInfo.cellIdentity.ci.toLong()
        val lac = cellInfo.cellIdentity.lac
        val tac = cellInfo.cellIdentity.tac
        val pci = cellInfo.cellIdentity.pci
        val earfcn = cellInfo.cellIdentity.earfcn
        val rsrp = cellInfo.cellSignalStrength.rsrp
        val rsrq = cellInfo.cellSignalStrength.rsrq
        val rssi = cellInfo.cellSignalStrength.rssi

        val lat = myLocation?.latitude ?: return null
        val lon = myLocation?.longitude ?: return null

        return CellTowerInfo(ci, lac, tac, pci, rsrp, rsrq, rssi, earfcn, 0, "LTE", lat, lon)
    }

    @SuppressLint("NewApi")
    private fun parseGsmCell(cellInfo: CellInfoGsm): CellTowerInfo? {
        val cid = cellInfo.cellIdentity.cid.toLong()
        val lac = cellInfo.cellIdentity.lac
        val psc = cellInfo.cellIdentity.psc
        val arfcn = cellInfo.cellIdentity.arfcn
        val rssi = cellInfo.cellSignalStrength.rssi

        val lat = myLocation?.latitude ?: return null
        val lon = myLocation?.longitude ?: return null

        return CellTowerInfo(cid, lac, 0, psc, 0, 0, rssi, arfcn, 0, "GSM", lat, lon)
    }

    @SuppressLint("NewApi")
    private fun parseWcdmaCell(cellInfo: CellInfoWcdma): CellTowerInfo? {
        val cid = cellInfo.cellIdentity.cid.toLong()
        val lac = cellInfo.cellIdentity.lac
        val psc = cellInfo.cellIdentity.psc
        val uarfcn = cellInfo.cellIdentity.uarfcn
        val rssi = cellInfo.cellSignalStrength.rssi

        val lat = myLocation?.latitude ?: return null
        val lon = myLocation?.longitude ?: return null

        return CellTowerInfo(cid, lac, 0, psc, 0, 0, rssi, uarfcn, 0, "WCDMA", lat, lon)
    }

    @SuppressLint("NewApi")
    private fun parseNrCell(cellInfo: CellInfoNr): CellTowerInfo? {
        val nci = cellInfo.cellIdentity.nci
        val tac = cellInfo.cellIdentity.tac
        val pci = cellInfo.cellIdentity.pci
        val nrarfcn = cellInfo.cellIdentity.nrarfcn
        val rsrp = cellInfo.cellSignalStrength.rsrp
        val rsrq = cellInfo.cellSignalStrength.rsrq
        val rssi = cellInfo.cellSignalStrength.rssi

        val lat = myLocation?.latitude ?: return null
        val lon = myLocation?.longitude ?: return null

        return CellTowerInfo(nci, 0, tac, pci, rsrp, rsrq, rssi, nrarfcn, 0, "5G NR", lat, lon)
    }

    private fun addTowerMarker(tower: CellTowerInfo) {
        val geoPoint = GeoPoint(tower.latitude, tower.longitude)
        val marker = Marker(mapView).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "${tower.technology} CID:${tower.cid}"
            snippet = "RSRP: ${tower.rsrp} dBm\nPCI: ${tower.pci}"
            setOnMarkerClickListener { _, _ ->
                showTowerInfo(tower)
                true
            }
        }
        mapView.overlays.add(marker)
        cellMarkers.add(marker)
    }

    private fun clearCellMarkers() {
        for (marker in cellMarkers) mapView.overlays.remove(marker)
        cellMarkers.clear()
    }

    private fun showTowerInfo(tower: CellTowerInfo) {
        val info = buildString {
            appendLine(" ${tower.technology} Вышка")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("CID: ${tower.cid}")
            appendLine("LAC/TAC: ${tower.lac}/${tower.tac}")
            appendLine("PCI: ${tower.pci}")
            appendLine("RSRP: ${tower.rsrp} dBm")
            appendLine("RSRQ: ${tower.rsrq} dB")
            appendLine("RSSI: ${tower.rssi} dBm")
            appendLine("Частота: ${tower.frequency}")
            appendLine("Диапазон (Band): ${tower.band}")
            appendLine("Координаты: ${String.format("%.5f", tower.latitude)}, ${String.format("%.5f", tower.longitude)}")
        }
        infoPanel.visibility = View.VISIBLE
        infoPanelText.text = info
    }

    private fun showDiagnostics() {
        val sb = StringBuilder()
        sb.appendLine("ДИАГНОСТИКА СЕТИ")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("Оператор: ${telephonyManager.networkOperatorName.ifEmpty { "Неизвестно" }}")
        sb.appendLine("MCC/MNC: ${telephonyManager.networkOperator.ifEmpty { "—" }}")
        sb.appendLine("Тип сети: ${getNetworkTypeName(telephonyManager.networkType)}")
        sb.appendLine("SIM: ${getSimStateName(telephonyManager.simState)}")
        sb.appendLine()
        sb.appendLine("Местоположение:")
        if (myLocation != null) {
            sb.appendLine("  Широта: ${String.format("%.5f", myLocation!!.latitude)}")
            sb.appendLine("  Долгота: ${String.format("%.5f", myLocation!!.longitude)}")
        } else {
            sb.appendLine("  Не определено")
        }
        sb.appendLine()
        sb.appendLine("Обнаружено вышек: ${detectedTowers.size}")
        for (tower in detectedTowers) {
            sb.appendLine("  - ${tower.technology} CID:${tower.cid} RSRP:${tower.rsrp}dBm")
        }
        sb.appendLine()
        sb.appendLine("Кэш карт: ${if (isKalugaRegionCached()) "ЕСТЬ" else "НЕТ"}")
        infoPanel.visibility = View.VISIBLE
        infoPanelText.text = sb.toString()
    }

    private fun getNetworkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA (3G)"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
        else -> "Unknown ($type)"
    }

    private fun getSimStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "Готов"
        TelephonyManager.SIM_STATE_ABSENT -> "Нет SIM"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "Нужен PIN"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Нужен PUK"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Сеть заблокирована"
        else -> "Неизвестно ($state)"
    }

    private fun isKalugaRegionCached(): Boolean {
        val tileCache = Configuration.getInstance().osmdroidTileCache
        val kalugaCacheDir = File(tileCache, "Mapnik")
        return kalugaCacheDir.exists() && kalugaCacheDir.listFiles()?.isNotEmpty() == true
    }

    private fun toggleMapType() {
        isSatellite = !isSatellite
        if (isSatellite) {
            Toast.makeText(this, "Спутник недоступен в РФ", Toast.LENGTH_LONG).show()
            isSatellite = false
        }
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        btnToggleMap.text = "Схема"
        mapView.invalidate()
    }

    private fun startLocationUpdates() {
        if (!isLocationPermissionGranted) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, locationListener)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 50f, locationListener)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Ошибка доступа к геолокации", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLocationUpdates() {
        try { locationManager.removeUpdates(locationListener) } catch (e: SecurityException) {}
    }

    private inner class LocationUpdateListener : LocationListener {
        override fun onLocationChanged(location: Location) {
            myLocation = GeoPoint(location.latitude, location.longitude)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun goToMyLocation() {
        val loc = myLocation
        if (loc != null) {
            currentCenter = loc
            mapView.controller.animateTo(loc)
            mapView.controller.setZoom(16.0)
            Toast.makeText(this, "Центрировано", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Местоположение не определено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    isLocationPermissionGranted = true
                    enableMyLocationOverlay()
                    startLocationUpdates()
                    if (pendingScanAfterPermission) { pendingScanAfterPermission = false; scanCellTowers() }
                } else {
                    isLocationPermissionGranted = false
                    pendingScanAfterPermission = false
                }
            }
            PHONE_STATE_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    isPhoneStatePermissionGranted = true
                    if (pendingScanAfterPermission) { pendingScanAfterPermission = false; performScan() }
                } else {
                    isPhoneStatePermissionGranted = false
                    pendingScanAfterPermission = false
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size >= 3) {
                    accelerometerValues = floatArrayOf(event.values[0], event.values[1], event.values[2])
                    updateCompass()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (event.values.size >= 3) {
                    magnetometerValues = floatArrayOf(event.values[0], event.values[1], event.values[2])
                    updateCompass()
                }
            }
        }
    }

    private fun updateCompass() {
        if (accelerometerValues != null && magnetometerValues != null) {
            val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                azimuthBuffer[azimuthBufferIndex] = azimuth
                azimuthBufferIndex = (azimuthBufferIndex + 1) % COMPASS_SMOOTHING_WINDOW
                if (azimuthBufferCount < COMPASS_SMOOTHING_WINDOW) azimuthBufferCount++
                var sum = 0f
                for (i in 0 until azimuthBufferCount) sum += azimuthBuffer[i]
                val smoothedAzimuth = if (azimuthBufferCount > 0) sum / azimuthBufferCount else azimuth
                rotateCompass(smoothedAzimuth)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun rotateCompass(azimuth: Float) {
        val rotateAnimation = RotateAnimation(currentAzimuth, -azimuth,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 100; fillAfter = true
        }
        compassView.startAnimation(rotateAnimation)
        currentAzimuth = -azimuth
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (hasAccelerometer && hasMagnetometer) {
            val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (acc != null && mag != null) {
                sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        clearCellMarkers()
        detectedTowers.clear()
    }

    private fun showLoading(message: String) {
        loadingIndicator.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun hideLoading() {
        loadingIndicator.visibility = View.GONE
        statusText.visibility = View.GONE
    }

    private class CoverageHeatmapOverlay : Overlay() {
        private val towers = mutableListOf<CellTowerInfo>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun addTower(tower: CellTowerInfo) { towers.add(tower) }
        fun clearTowers() { towers.clear() }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            for (tower in towers) {
                val point = mapView.projection.toPixels(GeoPoint(tower.latitude, tower.longitude), null)
                val x = point.x.toFloat()
                val y = point.y.toFloat()
                val radius = 150f
                val signalStrength = when (tower.technology) {
                    "LTE", "5G NR" -> tower.rsrp
                    "GSM" -> if (tower.rssi == 0) 0 else tower.rssi - 20
                    "WCDMA" -> tower.rssi
                    else -> tower.rsrp
                }
                val color = when {
                    signalStrength >= -85 -> Color.argb(102, 0, 255, 0)
                    signalStrength >= -100 -> Color.argb(102, 136, 255, 0)
                    signalStrength >= -110 -> Color.argb(102, 255, 255, 0)
                    else -> Color.argb(102, 255, 0, 0)
                }
                val gradient = RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                paint.shader = gradient
                canvas.drawCircle(x, y, radius, paint)
            }
        }
    }
}
