package com.netanalyzer.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var btnToggleMap: Button
    private var isSatellite = false
    private val heatmapOverlay = CoverageHeatmapOverlay()
    private var currentZoom = 15.0
    private var currentCenter = GeoPoint(55.751244, 37.618423)

    private val satelliteTileSource = XYTileSource(
        "ESRI World Imagery",
        1, 19,
        256,
        ".jpg",
        arrayOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        ),
        "© Esri — World Imagery"
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
        Configuration.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        Configuration.getInstance().load(this, prefs)

        mapView = MapView(this)
        findViewById<FrameLayout>(R.id.mapContainer).addView(mapView)

        btnToggleMap = findViewById(R.id.btnToggleMap)
        btnToggleMap.setOnClickListener {
            toggleMapType()
        }

        setupMap()
        loadCoverageData()
        setupLocationTracking()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isFlingEnabled = true
        mapView.controller.setZoom(currentZoom)
        mapView.controller.setCenter(currentCenter)
        mapView.overlays.add(heatmapOverlay)
    }

    private fun toggleMapType() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет интернета — снимки не загрузятся", Toast.LENGTH_LONG).show()
            return
        }

        isSatellite = !isSatellite

        // Сохраняем текущее состояние через координаты
        currentZoom = mapView.zoomLevelDouble
        val center = mapView.mapCenter
        currentCenter = GeoPoint(center.latitude, center.longitude)

        if (isSatellite) {
            clearTileCache()
            mapView.setTileSource(satelliteTileSource)
            btnToggleMap.text = "🗺 Схема"
            Toast.makeText(this, "Спутник загружается...", Toast.LENGTH_SHORT).show()
        } else {
            clearTileCache()
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            btnToggleMap.text = "🛰 Спутник"
        }

        mapView.controller.setZoom(currentZoom)
        mapView.controller.setCenter(currentCenter)
        mapView.invalidate()
        mapView.postInvalidate()
    }

    private fun clearTileCache() {
        try {
            val cacheDir = File(cacheDir, "osmdroid/tiles")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }
        
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 50f, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentCenter = GeoPoint(location.latitude, location.longitude)
                mapView.controller.setCenter(currentCenter)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupLocationTracking()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
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
