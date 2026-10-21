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
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RelativeLayout
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

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var btnToggleMap: Button
    private var isSatellite = false

    // Спутниковые тайлы от ESRI (ArcGIS World Imagery)
    private val satelliteTileSource = XYTileSource(
        "ESRI",
        0, 19,
        256,
        ".png",
        arrayOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
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

        // ВАЖНО: Устанавливаем User-Agent (обязательно для OSMDroid)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

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
        mapView.controller.setZoom(15.0)
        mapView.overlays.add(CoverageHeatmapOverlay())
    }

    private fun toggleMapType() {
        isSatellite = !isSatellite
        if (isSatellite) {
            mapView.setTileSource(satelliteTileSource)
            btnToggleMap.text = "🗺 Схема"
            Toast.makeText(this, "Загрузка спутниковых снимков...", Toast.LENGTH_SHORT).show()
        } else {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            btnToggleMap.text = "🛰 Спутник"
        }
        // Очищаем кэш тайлов и перерисовываем
        mapView.tileProvider.detach()
        mapView.invalidate()
    }

    private fun loadCoverageData() {
        val heatmap = mapView.overlays.filterIsInstance<CoverageHeatmapOverlay>().firstOrNull()
        
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
            
            heatmap?.towers?.add(tower)
        }
        
        mapView.controller.setCenter(GeoPoint(mockTowers[0].latitude, mockTowers[0].longitude))
        mapView.invalidate()
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
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
