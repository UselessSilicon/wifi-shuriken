package com.wifishuriken.companion.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.core.TrackPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Dot colours by security class (design §6). */
val SECURITY_COLORS = mapOf(
  SecurityClass.WPA2 to "#2EA043",
  SecurityClass.WPA3 to "#5A46C8",
  SecurityClass.WPA2_WPA3 to "#1E82C8",
  SecurityClass.ENTERPRISE to "#787878",
  SecurityClass.WPA to "#E3B008",
  SecurityClass.WEP to "#FF8C00",
  SecurityClass.OPEN_OR_OWE to "#E63946",
)

private object MapLibreInit {
  @Volatile private var done = false

  /** MapLibre is initialised on first use, offline, before any MapView exists. */
  fun ensure(context: Context) {
    if (done) return
    synchronized(this) {
      if (done) return
      MapLibre.getInstance(context.applicationContext)
      MapLibre.setConnected(false)
      done = true
    }
  }
}

/** Loads the bundled style for the theme with the local map file, or a plain background without one. */
fun styleJson(context: Context, mapFile: File?, dark: Boolean): String {
  if (mapFile == null || !mapFile.isFile) {
    val bg = if (dark) "#14181D" else "#EEF1F4"
    return """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$bg"}}]}"""
  }
  val name = if (dark) "map/style-dark.json" else "map/style-light.json"
  val json = context.assets.open(name).bufferedReader().use { it.readText() }
  return json.replace("pmtiles://MAP_FILE", "pmtiles://file://" + mapFile.absolutePath)
}

/**
 * Offline MapLibre map: the owner's PMTiles basemap (if imported), the trip track, network dots
 * (clustered, coloured by security, violet ring for new this trip) and the phone's position.
 * Never centres on a hard-coded place: the camera follows the user or fits the data.
 */
@Composable
fun OfflineMap(
  modifier: Modifier,
  mapFile: File?,
  dark: Boolean,
  followUser: Boolean,
  headingUp: Boolean,
  track: List<TrackPoint>,
  networks: List<MapNetwork>,
  userLocation: TrackPoint?,
  bearingDeg: Float?,
  onNetworkClick: (String) -> Unit,
  /** More tracks drawn as separate lines (e.g. every drive on "All on map"). */
  extraTracks: List<List<TrackPoint>> = emptyList(),
  /** Estimated position with its uncertainty circle (network detail). */
  estimate: MapEstimate? = null,
) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var map by remember { mutableStateOf<MapLibreMap?>(null) }
  var style by remember { mutableStateOf<Style?>(null) }
  val mapView = remember {
    MapLibreInit.ensure(context)
    MapView(context).apply { onCreate(null) }
  }

  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> mapView.onStart()
        Lifecycle.Event.ON_RESUME -> mapView.onResume()
        Lifecycle.Event.ON_PAUSE -> mapView.onPause()
        Lifecycle.Event.ON_STOP -> mapView.onStop()
        Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(mapFile, dark) {
    mapView.getMapAsync { m ->
      map = m
      m.uiSettings.isAttributionEnabled = true
      m.setStyle(Style.Builder().fromJson(styleJson(context, mapFile, dark))) { s ->
        addOverlay(s)
        style = s
        m.addOnMapClickListener { point ->
          val screen = m.projection.toScreenLocation(point)
          val hit = m.queryRenderedFeatures(screen, "networks-dots").firstOrNull()
          hit?.getStringProperty("mac")?.let { onNetworkClick(it); true } ?: false
        }
      }
    }
  }

  LaunchedEffect(style, extraTracks) {
    val s = style ?: return@LaunchedEffect
    val lines = extraTracks.filter { it.size >= 2 }.map { t -> t.map { Point.fromLngLat(it.lon, it.lat) } }
    (s.getSource("tracks-all") as? GeoJsonSource)?.setGeoJson(
      if (lines.isEmpty()) FeatureCollection.fromFeatures(emptyList())
      else FeatureCollection.fromFeature(Feature.fromGeometry(MultiLineString.fromLngLats(lines))),
    )
  }

  LaunchedEffect(style, estimate) {
    val s = style ?: return@LaunchedEffect
    (s.getSource("estimate") as? GeoJsonSource)?.setGeoJson(
      estimate?.let { FeatureCollection.fromFeature(Feature.fromGeometry(circlePolygon(it))) } ?: FeatureCollection.fromFeatures(emptyList()),
    )
  }

  // Browsing maps fit the data once it is known; nothing is ever centred on a fixed place.
  LaunchedEffect(map, followUser, track, extraTracks, networks, estimate) {
    val m = map ?: return@LaunchedEffect
    if (followUser) return@LaunchedEffect
    val pts = track.map { it.lat to it.lon } + extraTracks.flatten().map { it.lat to it.lon } + networks.map { it.lat to it.lon } +
      (estimate?.let { e -> circlePolygon(e).coordinates().first().map { it.latitude() to it.longitude() } } ?: emptyList())
    if (pts.isEmpty()) return@LaunchedEffect
    if (pts.size == 1 || pts.all { it == pts[0] }) {
      m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].first, pts[0].second), 15.0))
    } else {
      val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.first, it.second)) } }.build()
      m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 60))
    }
  }

  LaunchedEffect(style, track, networks, userLocation) {
    val s = style ?: return@LaunchedEffect
    (s.getSource("track") as? GeoJsonSource)?.setGeoJson(
      if (track.size >= 2) FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(track.map { Point.fromLngLat(it.lon, it.lat) })))
      else FeatureCollection.fromFeatures(emptyList()),
    )
    (s.getSource("networks") as? GeoJsonSource)?.setGeoJson(
      FeatureCollection.fromFeatures(
        networks.map { n ->
          Feature.fromGeometry(Point.fromLngLat(n.lon, n.lat)).apply {
            addStringProperty("mac", n.mac)
            addStringProperty("color", SECURITY_COLORS[n.security] ?: "#A0A0A0")
            addBooleanProperty("new", n.newThisTrip)
          }
        },
      ),
    )
    (s.getSource("user") as? GeoJsonSource)?.setGeoJson(
      userLocation?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }
        ?: FeatureCollection.fromFeatures(emptyList()),
    )
  }

  LaunchedEffect(map, userLocation, bearingDeg, followUser, headingUp) {
    val m = map ?: return@LaunchedEffect
    val here = userLocation ?: return@LaunchedEffect
    if (!followUser) return@LaunchedEffect
    val position = CameraPosition.Builder()
      .target(LatLng(here.lat, here.lon))
      .zoom(maxOf(m.cameraPosition.zoom, 15.0))
      .bearing(if (headingUp) (bearingDeg ?: 0f).toDouble() else 0.0)
      .build()
    m.animateCamera(CameraUpdateFactory.newCameraPosition(position), 800)
  }

  AndroidView(factory = { mapView }, modifier = modifier)
}

/** A 64-point polygon approximating a circle of [MapEstimate.radiusM] metres around the estimate. */
private fun circlePolygon(e: MapEstimate): Polygon {
  val dLat = e.radiusM / 111_320.0
  val dLon = dLat / maxOf(cos(Math.toRadians(e.lat)), 0.01)
  val ring = (0..64).map { i ->
    val a = 2 * PI * (i % 64) / 64
    Point.fromLngLat(e.lon + dLon * sin(a), e.lat + dLat * cos(a))
  }
  return Polygon.fromLngLats(listOf(ring))
}

private fun addOverlay(s: Style) {
  val empty = FeatureCollection.fromFeatures(emptyList())
  s.addSource(GeoJsonSource("track", empty))
  s.addSource(GeoJsonSource("tracks-all", empty))
  s.addSource(GeoJsonSource("networks", empty, GeoJsonOptions().withCluster(true).withClusterMaxZoom(15).withClusterRadius(40)))
  s.addSource(GeoJsonSource("user", empty))
  s.addSource(GeoJsonSource("estimate", empty))
  s.addLayer(
    FillLayer("estimate-fill", "estimate").withProperties(
      PropertyFactory.fillColor("#2F7DF6"), PropertyFactory.fillOpacity(0.15f), PropertyFactory.fillOutlineColor("#2F7DF6"),
    ),
  )
  s.addLayer(
    LineLayer("tracks-all-line", "tracks-all").withProperties(
      PropertyFactory.lineColor("#FF7A00"), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineOpacity(0.75f),
    ),
  )
  s.addLayer(
    LineLayer("track-line", "track").withProperties(
      PropertyFactory.lineColor("#2F7DF6"), PropertyFactory.lineWidth(4f), PropertyFactory.lineOpacity(0.9f),
    ),
  )
  s.addLayer(
    CircleLayer("networks-clusters", "networks").withFilter(Expression.has("point_count")).withProperties(
      PropertyFactory.circleColor("#3B82F6"), PropertyFactory.circleOpacity(0.7f),
      PropertyFactory.circleRadius(Expression.step(Expression.get("point_count"), Expression.literal(12), Expression.stop(50, 16), Expression.stop(500, 22))),
    ),
  )
  s.addLayer(
    CircleLayer("networks-new", "networks").withFilter(Expression.all(Expression.not(Expression.has("point_count")), Expression.eq(Expression.get("new"), true)))
      .withProperties(
        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#00000000"),
        PropertyFactory.circleStrokeColor("#A855F7"), PropertyFactory.circleStrokeWidth(2f),
      ),
  )
  s.addLayer(
    CircleLayer("networks-dots", "networks").withFilter(Expression.not(Expression.has("point_count"))).withProperties(
      PropertyFactory.circleRadius(4.5f), PropertyFactory.circleColor(Expression.get("color")),
      PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1f),
    ),
  )
  s.addLayer(
    CircleLayer("user-halo", "user").withProperties(PropertyFactory.circleRadius(16f), PropertyFactory.circleColor("#2F7DF6"), PropertyFactory.circleOpacity(0.2f)),
  )
  s.addLayer(
    CircleLayer("user-dot", "user").withProperties(
      PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#2F7DF6"),
      PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
    ),
  )
}
