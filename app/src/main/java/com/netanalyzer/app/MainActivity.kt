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
import androidx.annotation.RequiresApi
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeOSMDroid()
        initializeUI()
        initializeServices()
        setupMap()
        checkExistingPermissions()
        requestLocationPermission()

        Log.d(TAG, "Activity created")
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

        if (!osmdroidTileDir.exists()) {
            osmdroidTileDir.mkdirs()
        }

        Configuration.getInstance().osmdroidBasePath = osmdroidBaseDir
        Configuration.getInstance().osmdroidTileCache = osmdroidTileDir
        Configuration.getInstance().load(this, prefs)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeUI() {
        mapContainer = findViewById(R.id.mapContainer)

        mapView = MapView(this)
        mapContainer.addView(mapView, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

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
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val infoPanelParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            infoPanelHeight
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(16, 16, 16, 180)
        }
        mapContainer.addView(infoPanel, infoPanelParams)

        btnDiagnostics = createButton("Диагностика", Color.parseColor("#FF5722")) { showDiagnostics() }
        mapContainer.addView(btnDiagnostics, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
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
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(16, 16, 16, 16)
        })

        btnScan = createButton("Сканировать", Color.parseColor("#9C27B0")) { scanCellTowers() }
        mapContainer.addView(btnScan, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
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

        if (!hasAccelerometer || !hasMagnetometer) {
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
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
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
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                PHONE_STATE_PERMISSION_REQUEST
            )
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

            Log.d(TAG, "Найдено ${cellInfos.size} ячеек")

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
                Toast.makeText(this, "Вышки не найдены. Попробуй ещё раз.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Найдено ${detectedTowers.size} вышек", Toast.LENGTH_LONG).show()
                showTowerInfo(detectedTowers.first())
            }

        } catch (e: SecurityException) {
            hideLoading()
            Toast.makeText(this, "Нет доступа к данным о вышках", Toast.LENGTH_LONG).show()
            Log.e(TAG, "SecurityException", e)
        } catch (e: Exception) {
            hideLoading()
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error scanning", e)
        }
    }

    @SuppressLint("NewApi")
    private fun parseLteCell(cellInfo: CellInfoLte): CellTowerInfo? {
        val cellIdentity = cellInfo.cellIdentity
        val signal = cellInfo.cellSignalStrength

        // Координаты доступны только с API 29
        val latitude: Double
        val longitude: Double
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val latValue = cellIdentity.latitude
            val lonValue = cellIdentity.longitude
            if (latValue != null && lonValue != null) {
                val lat = latValue / 1e7
                val lon = lonValue / 1e7
                if (lat != 0.0 && lon != 0.0) {
                    latitude = lat
                    longitude = lon
                } else {
                    val loc = myLocation ?: return null
                    latitude = loc.latitude
                    longitude = loc.longitude
                }
            } else {
                val loc = myLocation ?: return null
                latitude = loc.latitude
                longitude = loc.longitude
            }
        } else {
            val loc = myLocation ?: return null
            latitude = loc.latitude
            longitude = loc.longitude
        }

        val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (cellIdentity.bands.size > 0) cellIdentity.bands[0] else 0
            } catch (e: Exception) { 0 }
        } else 0

        val lac = if (cellIdentity.lac != UNKNOWN_VALUE) cellIdentity.lac else 0
        val tac = if (cellIdentity.tac != UNKNOWN_VALUE) cellIdentity.tac else 0
        val pci = if (cellIdentity.pci != UNKNOWN_VALUE) cellIdentity.pci else 0
        val earfcn = if (cellIdentity.earfcn != UNKNOWN_VALUE) cellIdentity.earfcn else 0
        val rsrp = if (signal.rsrp != UNKNOWN_VALUE) signal.rsrp else 0
        val rsrq = if (signal.rsrq != UNKNOWN_VALUE) signal.rsrq else 0
        val rssi = if (signal.rssi != UNKNOWN_VALUE) signal.rssi else 0

        return CellTowerInfo(
            cid = cellIdentity.ci.toLong(),
            lac = lac,
            tac = tac,
            pci = pci,
            rsrp = rsrp,
            rsrq = rsrq,
            rssi = rssi,
            frequency = earfcn,
            band = band,
            technology = "LTE",
            latitude = latitude,
            longitude = longitude
        )
    }

    @SuppressLint("NewApi")
    private fun parseGsmCell(cellInfo: CellInfoGsm): CellTowerInfo? {
        val cellIdentity = cellInfo.cellIdentity
        val signal = cellInfo.cellSignalStrength

        val latitude: Double
        val longitude: Double
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val latValue = cellIdentity.latitude
            val lonValue = cellIdentity.longitude
            if (latValue != null && lonValue != null) {
                val lat = latValue / 1e7
                val lon = lonValue / 1e7
                if (lat != 0.0 && lon != 0.0) {
                    latitude = lat
                    longitude = lon
                } else {
                    val loc = myLocation ?: return null
                    latitude = loc.latitude
                    longitude = loc.longitude
                }
            } else {
                val loc = myLocation ?: return null
                latitude = loc.latitude
                longitude = loc.longitude
            }
        } else {
            val loc = myLocation ?: return null
            latitude = loc.latitude
            longitude = loc.longitude
        }

        val lac = if (cellIdentity.lac != UNKNOWN_VALUE) cellIdentity.lac else 0
        val cid = if
