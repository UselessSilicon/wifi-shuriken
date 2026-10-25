package com.wifishuriken.companion.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.wifishuriken.companion.Diag
import com.wifishuriken.companion.R
import com.wifishuriken.companion.core.CardFile
import com.wifishuriken.companion.core.CsvRecord
import com.wifishuriken.companion.core.LogTailer
import com.wifishuriken.companion.core.Nmea
import com.wifishuriken.companion.core.OuiTable
import com.wifishuriken.companion.core.PollOutcome
import com.wifishuriken.companion.core.SecurityClass
import com.wifishuriken.companion.core.TrackPoint
import com.wifishuriken.companion.core.TripCounters
import com.wifishuriken.companion.core.WigleCsv
import com.wifishuriken.companion.core.WigleRecord
import com.wifishuriken.companion.core.parseModuleFix
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.Ingestor
import com.wifishuriken.companion.data.NetworkFilter
import com.wifishuriken.companion.data.NetworkSort
import com.wifishuriken.companion.data.Settings
import com.wifishuriken.companion.data.TrackPointEntity
import com.wifishuriken.companion.data.loadOuiTable
import com.wifishuriken.companion.map.MapNetwork
import com.wifishuriken.companion.usb.MtpCardSource
import com.wifishuriken.companion.usb.MtpStalledException
import com.wifishuriken.companion.usb.ShurikenConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Runs a live session in the foreground (screen off included): archive sync of finished logs,
 * live tailing of the active log into the database and trip counters, the phone's track, and the
 * phone GPS feed to the Shuriken's NMEA port (design §7, §8, §10). Started only from MainActivity.
 */
class LiveSessionService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val sendExecutor = Executors.newSingleThreadExecutor()
  private var job: Job? = null
  private var location: PhoneLocation? = null
  @Volatile private var conn: ShurikenConnection? = null
  @Volatile private var deviceName: String? = null
  @Volatile private var lastFeedOkMs = 0L
  private val trackBuffer = Collections.synchronizedList(ArrayList<TrackPoint>())
  private lateinit var settings: Settings

  private val detachReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
      if (device != null && device.deviceName == deviceName) stopSelf()
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    settings = Settings(this)
    ContextCompat.registerReceiver(
      this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    val device = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DEVICE, UsbDevice::class.java) }
    if (device == null) {
      stopSelf()
      return START_NOT_STICKY
    }
    ServiceCompat.startForeground(
      this, NOTIFICATION_ID, buildNotification(this),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
    )
    if (job?.isActive == true) return START_NOT_STICKY
    deviceName = device.deviceName
    startPhoneLocation()
    job = scope.launch { runSession(device) }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    location?.stop()
    shutdownSession(sendExecutor, job, SHUTDOWN_WAIT_MS)
    scope.cancel()
    unregisterReceiver(detachReceiver)
    LiveSessionRepository.update { it.copy(connected = false, feeding = false, message = "Session ended") }
    super.onDestroy()
  }

  private fun startPhoneLocation() {
    val loc = PhoneLocation(this)
    loc.start(onLocation = ::onLocation, onNmea = ::onPhoneNmea)
    location = loc
  }

  private fun onLocation(l: Location) {
    val p = TrackPoint(l.time, l.latitude, l.longitude)
    trackBuffer.add(p)
    LiveSessionRepository.update {
      it.copy(
        userLocation = p,
        bearingDeg = if (l.hasBearing()) l.bearing else it.bearingDeg,
        speedKmh = if (l.hasSpeed()) l.speed * 3.6f else null,
        track = (it.track + p).takeLast(MAX_TRACK_IN_STATE),
      )
    }
  }

  /** Phone NMEA arrives on the main thread; USB writes happen on a background executor. */
  private fun onPhoneNmea(sentence: String) {
    if (!settings.feedPhoneGps) return
    val port = conn?.nmea ?: return
    sendExecutor.execute {
      if (!Nmea.isForwardable(sentence)) return@execute // only GGA/RMC go to the Shuriken
      val ok = port.send(sentence)
      if (ok) {
        if (lastFeedOkMs == 0L) Diag.i("NMEA feed: first sentence written")
        lastFeedOkMs = System.currentTimeMillis()
      } else {
        nmeaFailed++
        if (nmeaFailed <= 3 || nmeaFailed % 100 == 0) Diag.w("NMEA feed: write failed (result ${port.lastWriteResult}, failures $nmeaFailed)")
      }
    }
  }

  @Volatile private var nmeaFailed = 0

  private suspend fun runSession(device: UsbDevice) {
    val db = CompanionDb.get(this)
    val ingestor = Ingestor(db, loadOui())
    val syncer = ArchiveSyncer(ArchiveStore(File(filesDir, "archive")), ingestor, db)
    LiveSessionRepository.reset()
    LiveSessionRepository.update { it.copy(syncProgress = "Connecting") }
    var reopened = false
    while (currentCoroutineContext().isActive) {
      try {
        ShurikenConnection.open(this, device).use { c ->
          conn = c
          Diag.i("session: connected, nmeaPort=${c.nmea != null}")
          LiveSessionRepository.update { it.copy(connected = true, message = null, syncProgress = "Checking archive") }
          val source = MtpCardSource(c)
          val report = syncer.syncOnce(source, activeLog = null)
          Diag.i("session: archive sync archived=${report.archived} failed=${report.failed}")
          LiveSessionRepository.update { it.copy(syncProgress = syncSummary(report.archived, report.failed, report.importFailed)) }
          c.nmea?.start { line -> parseModuleFix(line)?.let { f -> LiveSessionRepository.update { it.copy(moduleFix = f) } } }
          coroutineScope {
            // Archived logs without a trip are imported beside the live tail, never before it,
            // so a slow or failing file cannot hold up recording.
            launch {
              val r = syncer.reingestOrphans()
              if (r.failed > 0 || r.quarantined > 0) Diag.w("session: orphan logs failed=${r.failed} setAside=${r.quarantined}")
              if (r.quarantined > 0) {
                LiveSessionRepository.update { it.copy(syncProgress = "${r.quarantined} archived log(s) could not be imported") }
              }
            }
            tail(source, ingestor, syncer, db)
          }
        }
        Diag.i("session: ended normally")
        break
      } catch (e: MtpStalledException) {
        Diag.w("session: MTP stalled", e)
        if (reopened) {
          LiveSessionRepository.update { it.copy(connected = false, message = "The Shuriken stopped answering; unplug and replug") }
          break
        }
        reopened = true
        delay(1_000)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Diag.w("session: failed", e)
        LiveSessionRepository.update { it.copy(connected = false, message = "USB error: ${e.message}") }
        break
      } finally {
        conn = null // the connection is closed by use {}; NMEA sends must not reach it
      }
    }
    withContext(Dispatchers.Main) { stopSelf() }
  }

  private suspend fun tail(source: MtpCardSource, ingestor: Ingestor, syncer: ArchiveSyncer, db: CompanionDb) {
    val dao = db.dao()
    val tracker = ActiveLogTracker()
    val pending = ArrayList<Pair<String, CsvRecord>>()
    val tailer = LogTailer(source, onRecord = { name, record -> pending += name to record })
    var live: String? = null
    var counters: TripCounters? = null
    var tripId: Long? = null
    var lastRefresh = 0L
    while (currentCoroutineContext().isActive) {
      val outcome = tailer.poll()
      val newest = (outcome as? PollOutcome.Tailing)?.let { CardFile(it.fileName, it.fileSize, 0) }
      val nowLive = tracker.observe(newest)
      if (nowLive != null && nowLive != live) {
        live = nowLive
        val start = Ingestor.startUtcOf(nowLive)
        val known = HashMap<String, Boolean>()
        counters = TripCounters(knownBefore = { mac -> known.getOrPut(mac) { runBlocking { dao.isKnownBefore(mac, start) } } })
        tripId = null
        syncer.syncOnce(source, activeLog = nowLive) // the previous newest file is finished now
        LiveSessionRepository.update {
          it.copy(activeLog = nowLive, tripStartMs = epochMs(start), counters = EMPTY_COUNTERS, networks = emptyList())
        }
      }
      if (pending.isNotEmpty()) {
        val batch = pending.toList()
        pending.clear()
        val liveName = live
        val mine = batch.groupBy({ it.first }, { it.second }).flatMap { (name, records) -> tracker.accept(name, records) }
        if (liveName != null && mine.isNotEmpty()) {
          ingestor.ingestRecords(liveName, mine)
          val now = System.currentTimeMillis()
          for (r in mine) (WigleCsv.parseRecord(r.text) as? WigleRecord.Wifi)?.let { counters?.add(it.sighting, now) }
          if (tripId == null) tripId = dao.tripByLogName(liveName)?.id
          counters?.let { c -> LiveSessionRepository.update { it.copy(counters = c.snapshot()) } }
        }
      }
      tripId?.let { flushTrack(db, it) }
      val now = System.currentTimeMillis()
      if (now - lastRefresh > REFRESH_MS) {
        lastRefresh = now
        val id = tripId
        val networks = if (id == null) emptyList() else dao.networksPage(NetworkFilter(tripId = id), NetworkSort.NEWEST, MAX_MAP_NETWORKS, 0)
          .filter { it.estLat != null && it.estLon != null }
          .map { MapNetwork(it.mac, it.estLat!!, it.estLon!!, SecurityClass.valueOf(it.security), it.firstTripId == id) }
        LiveSessionRepository.update {
          it.copy(networks = networks, phoneBatteryPct = location?.batteryPercent(), feeding = now - lastFeedOkMs < FEED_FRESH_MS, feedEnabled = settings.feedPhoneGps)
        }
      }
      val catchingUp = outcome is PollOutcome.Tailing && !outcome.caughtUp
      delay(if (catchingUp) CATCH_UP_MS else POLL_MS)
    }
  }

  private suspend fun flushTrack(db: CompanionDb, tripId: Long) {
    val points = synchronized(trackBuffer) { trackBuffer.toList().also { trackBuffer.clear() } }
    if (points.isNotEmpty()) db.dao().insertTrackPoints(points.map { TrackPointEntity(tripId = tripId, tMs = it.tMs, lat = it.lat, lon = it.lon) })
  }

  private fun loadOui(): OuiTable? = loadOuiTable(this)

  companion object {
    const val EXTRA_DEVICE = "device"
    const val ACTION_STOP = "com.wifishuriken.companion.action.STOP_SESSION"

    /** Intent that ends the running session (notification action and the Live screen's Stop). */
    fun stopIntent(context: Context): Intent = Intent(context, LiveSessionService::class.java).setAction(ACTION_STOP)

    fun buildNotification(context: Context): Notification {
      val manager = context.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Live session", NotificationManager.IMPORTANCE_LOW))
      val stop = PendingIntent.getService(context, 0, stopIntent(context), PendingIntent.FLAG_IMMUTABLE)
      return NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("Shuriken live session")
        .setContentText("Recording the trip and importing finished logs")
        .setSmallIcon(R.drawable.ic_stat_session)
        .setOngoing(true)
        .addAction(0, "Stop", stop)
        .build()
    }

    private const val CHANNEL_ID = "live_session"
    private const val NOTIFICATION_ID = 1
    private const val POLL_MS = 2_000L
    private const val CATCH_UP_MS = 50L
    private const val REFRESH_MS = 5_000L
    private const val FEED_FRESH_MS = 5_000L
    private const val MAX_TRACK_IN_STATE = 5_000
    private const val MAX_MAP_NETWORKS = 3_000
    private const val SHUTDOWN_WAIT_MS = 1_500L

    private val UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** One line for Live about the card sync. */
    fun syncSummary(archived: Int, failed: Int, importFailed: Int): String =
      listOfNotNull(
        "Imported $archived new log(s)",
        failed.takeIf { it > 0 }?.let { "$it to retry" },
        importFailed.takeIf { it > 0 }?.let { "$it could not be read" },
      ).joinToString(", ")

    fun epochMs(utc: String): Long? =
      runCatching { LocalDateTime.parse(utc, UTC).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
  }
}
