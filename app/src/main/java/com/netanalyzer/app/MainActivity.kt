package com.netanalyzer.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager

    data class CellTower(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val operator: String,
        val networkType: String,
        val signalStrengthDbm: Int
    )

    // Тестовые данные вышек (в реальном приложении здесь был бы API)
    private val mockTowers = listOf(
        CellTower("1", 55.751244, 37.618423, "МТС", "4G", -65),
        CellTower("2", 55.755244, 37.622423, "Билайн", "5G", -80),
        CellTower("3", 55.749244, 37.610423, "Мегафон", "LTE", -95)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация OSMDroid
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        // Создаём карту
        mapView = MapView(this)
        (findViewById<View>(R.id.mapContainer) as android.widget.FrameLayout)
            .addView(mapView)

        setupMap()
        loadCoverageData()
        setupLocationTracking()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true) // Свободное масштабирование
        mapView.controller.setZoom(15.0)

        // Добавляем оверлей для рисования зон покрытия
        mapView.overlays.add(CoverageHeatmapOverlay())
    }

    private fun loadCoverageData() {
        val heatmap = mapView.overlays
            .filterIsInstance<CoverageHeatmapOverlay>()
            .firstOrNull()

        for (tower in mockTowers) {
            val geoPoint = GeoPoint(tower.latitude, tower.longitude)

            // Создаём кликабельный маркер
            val marker = Marker(mapView)
            marker.position = geoPoint
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "${tower.operator} (${tower.networkType})"
            marker.setOnMarkerClickListener { _, _ ->
                showTowerDetails(tower)
                true
            }
            mapView.overlays.add(marker)

            // Добавляем в оверлей для рисования heatmap
            heatmap?.towers?.add(tower)
        }

        // Центрируем карту на первой вышке
        mapView.controller.setCenter(
            GeoPoint(mockTowers[0].latitude, mockTowers[0].longitude)
        )
        mapView.invalidate()
    }

    private fun showTowerDetails(tower: CellTower) {
        val explanation = getExplanation(tower.signalStrengthDbm)
        val color = getSignalColor(tower.signalStrengthDbm)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("${tower.operator} (${tower.networkType})")
            .setMessage(
                "Уровень сигнала: ${tower.signalStrengthDbm} dBm\n\n" +
                "Что это значит:\n$explanation"
            )
            .setPositiveButton("OK", null)
            .create()
        dialog.show()
    }

    private fun getExplanation(dbm: Int): String {
        return when {
            dbm >= -70 -> "Отличный сигнал. Идеально для стриминга 4K и видеозвонков."
            dbm >= -85 -> "Хороший сигнал. Стабильный интернет, видео загружается быстро."
            dbm >= -100 -> "Средний сигнал. Веб-серфинг и мессенджеры работают, видео может буферизоваться."
            dbm >= -110 -> "Слабый сигнал. Возможны обрывы связи, только текст и аудио."
            else -> "Очень слабый сигнал. Высокая вероятность потери сети."
        }
    }

    private fun getSignalColor(dbm: Int): Int {
        return when {
            dbm >= -70 -> 0xFF00FF00.toInt()
            dbm >= -85 -> 0xFF88FF00.toInt()
            dbm >= -100 -> 0xFFFFFF00.toInt()
            dbm >= -110 -> 0xFFFF8800.toInt()
            else -> 0xFFFF0000.toInt()
        }
    }

    private fun setupLocationTracking() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                101
            )
            return
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            5000L, // Обновление каждые 5 секунд
            50f,   // Или при сдвиге на 50 метров
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    mapView.controller.setCenter(
                        GeoPoint(location.latitude, location.longitude)
                    )
                }
                override fun onStatusChanged(
                    provider: String?, status: Int, extras: Bundle?
                ) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
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

    // Внутренний класс для рисования зон покрытия (heatmap)
    inner class CoverageHeatmapOverlay : Overlay() {
        val towers = mutableListOf<CellTower>()
        private val paint = Paint()

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return

            for (tower in towers) {
                val point = mapView.projection.toPixels(
                    GeoPoint(tower.latitude, tower.longitude), null
                )
                val radius = 150f // Радиус зоны в пикселях

                val gradient = RadialGradient(
                    point.x, point.y, radius,
                    getSignalColor(tower.signalStrengthDbm),
                    0x00000000, // Прозрачный край
                    Shader.TileMode.CLAMP
                )
                paint.shader = gradient
                canvas.drawCircle(point.x, point.y, radius, paint)
            }
        }
    }
}
