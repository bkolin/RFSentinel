package com.rfsentinel.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.rfsentinel.app.MainActivity
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Affiliation
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.BuildIdentity
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets

/**
 * The Tactical view: the whole scan on one dark, monospaced screen of three cards - RADAR, WI-FI and BLE - with
 * every device sorted and coloured by who it most likely belongs to ([Affiliation]): Priority, Threat, yours,
 * Fleet, Commercial, Residential. Opens over the main screen when Prefs.tacticalView is on (the default); "Classic
 * view" goes back to the main screen for the rest of the session.
 *
 * It only shows what the scanner already hears: starting and stopping the scan and the Bluetooth half of it are the
 * same switches as on the main screen and in Settings.
 */
class TacticalActivity : AppCompatActivity() {

    companion object {
        /** Classic view was chosen this session: the main screen stops sending you back here until the app restarts. */
        @Volatile var dismissed = false

        private const val REFRESH_MS = 1_000L
        private const val WIFI_SHORT = 5
        /** BLE devices listed under "Show BLE details", most important first. */
        private const val BLE_DETAILS = 25
        private const val LONG = 60

        // A fixed palette, whatever the app's theme: the Tactical view always looks the same.
        private const val BG = 0xFF05090B.toInt()
        private const val CARD = 0xFF0A1316.toInt()
        private const val CYAN = 0xFF2BD4E0.toInt()
        private const val INK = 0xFFD8F3F5.toInt()
        private const val DIM = 0xFF7FA3A8.toInt()
        private const val MAGENTA = 0xFFFF4FA3.toInt()
        private const val BUTTON = 0xFF5BC8D3.toInt()
        private const val BUTTON_INK = 0xFF04262A.toInt()

        fun open(context: Context) {
            dismissed = false
            context.startActivity(Intent(context, TacticalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, REFRESH_MS) }
    }
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var status: TextView
    private lateinit var scanButton: TextView
    private lateinit var alertStrip: TextView
    private lateinit var radar: RadarView
    private lateinit var legend: TextView
    private lateinit var modeChip: TextView
    private lateinit var radarFooter: TextView
    private lateinit var wifiList: LinearLayout
    private lateinit var wifiShowing: TextView
    private lateinit var wifiMore: TextView
    private lateinit var bleStatus: TextView
    private lateinit var bleList: LinearLayout
    private lateinit var bleShowing: TextView
    private lateinit var bleDetailsButton: TextView
    private var wifiExpanded = false
    private var bleDetails = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        wifiExpanded = savedInstanceState?.getBoolean("wifiExpanded") ?: false
        bleDetails = savedInstanceState?.getBoolean("bleDetails") ?: false

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(column)
        }
        setContentView(scroll)
        scroll.applySystemBarInsets()

        // Header: name, build, and what the scanner is doing.
        column.addView(text("RF SENTINEL // TACTICAL", 18f, CYAN, bold = true))
        column.addView(text("PASSIVE · RX ONLY · ${BuildIdentity.label}", 11f, DIM))
        status = text("", 12f, INK).also { column.addView(it, margins(top = 6)) }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            scanButton = button("") { toggleScan() }
            addView(scanButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(button("CLASSIC VIEW", outline = true) { toClassic() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, margins(top = 8))
        alertStrip = text("", 13f, Color.WHITE, bold = true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            setOnClickListener { (tag as? String)?.let { DeviceActions.openDetails(this@TacticalActivity, it) } }
        }
        column.addView(alertStrip, margins(top = 10))

        // RADAR
        val radarCard = card(column, "RADAR")
        radarCard.addView(text("Passive RSSI visualization — not location", 12f, INK))
        legend = text("", 12f, INK).also { radarCard.addView(it, margins(top = 2)) }
        modeChip = text("", 11f, CYAN).apply {
            background = GradientDrawable().apply { setStroke(dp(1), CYAN); cornerRadius = dp(12).toFloat() }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setOnClickListener {
                radar.angleLayout = if (radar.angleLayout == RadarModel.Layout.SPREAD) RadarModel.Layout.APPROACH else RadarModel.Layout.SPREAD
                Prefs.setRadarApproach(this@TacticalActivity, radar.angleLayout == RadarModel.Layout.APPROACH)
                render()
            }
        }
        radarCard.addView(modeChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END; topMargin = dp(4)
        })
        radar = RadarView(this).apply {
            compact = true
            setScopeColors(CYAN, DIM)
            angleLayout = if (Prefs.radarApproach(this@TacticalActivity)) RadarModel.Layout.APPROACH else RadarModel.Layout.SPREAD
            onBlipClick = { DeviceActions.openDetails(this@TacticalActivity, it) }
        }
        radarCard.addView(radar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        radarFooter = text("", 12f, INK).apply { gravity = Gravity.CENTER_HORIZONTAL }
        radarCard.addView(radarFooter, margins(top = 4))

        // WI-FI
        val wifiCard = card(column, "WI-FI")
        wifiList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wifiCard.addView(wifiList)
        wifiShowing = text("", 12f, 0xFF7BD88F.toInt()).also { wifiCard.addView(it, margins(top = 4)) }
        wifiMore = button("", outline = true) { wifiExpanded = !wifiExpanded; render() }
        wifiCard.addView(wifiMore, margins(top = 6))

        // BLE
        val bleCard = card(column, "BLE")
        bleCard.addView(button("START BLE") { setBle(true) }, margins(top = 2))
        bleCard.addView(button("STOP BLE") { setBle(false) }, margins(top = 6))
        bleDetailsButton = button("") { bleDetails = !bleDetails; render() }
        bleCard.addView(bleDetailsButton, margins(top = 6))
        bleStatus = text("", 12f, INK).also { bleCard.addView(it, margins(top = 8)) }
        bleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bleCard.addView(bleList, margins(top = 4))
        bleShowing = text("", 12f, 0xFF7BD88F.toInt()).also { bleCard.addView(it, margins(top = 4)) }

        // Back leaves the app as from the main screen, instead of dropping into the classic view underneath.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("wifiExpanded", wifiExpanded)
        outState.putBoolean("bleDetails", bleDetails)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun toClassic() {
        dismissed = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    private fun toggleScan() {
        if (ScanForegroundService.isRunning) ScanForegroundService.stop(this)
        else if (Permissions.required().all { Permissions.granted(this, it) }) ScanForegroundService.start(this)
        else toClassic() // the main screen asks for the permissions
        handler.postDelayed({ render() }, 400)
    }

    /** The Bluetooth half of the scan on or off: the same switch as Settings > Scanning, applied live. */
    private fun setBle(on: Boolean) {
        Prefs.setBleEnabled(this, on)
        if (ScanForegroundService.isRunning) ScanForegroundService.start(this)
        handler.postDelayed({ render() }, 400)
    }

    private fun device(s: DeviceRegistry.Snapshot) = TacticalModel.Device(
        mac = s.mac, wifi = s.source == Advert.Source.WIFI, name = s.name, vendor = s.vendor, deviceType = s.deviceType,
        best = s.best, rssi = s.rssi, frequencyMhz = null,
        firstSeen = s.firstSeen, lastSeen = s.lastSeen,
    )

    private fun render() {
        val now = System.currentTimeMillis()
        val running = ScanForegroundService.isRunning
        val bleMs = Prefs.liveBleSec(this) * 1000L
        val wifiMs = Prefs.liveWifiSec(this) * 1000L
        val externalMs = Prefs.liveExternalSec(this) * 1000L
        val all = DeviceRegistry.snapshot(now).filter { LiveWindow.keep(it, now, bleMs, wifiMs, externalMs) }
        val (wifiSnaps, bleSnaps) = all.partition { it.source == Advert.Source.WIFI }
        val devices = TacticalModel.withNearbyEms(all.map { device(it) }).associateBy { it.mac }

        status.text = buildString {
            append(if (running) "SCAN: ACTIVE" else "SCAN: STOPPED")
            append(" · WIFI ").append(if (running && Prefs.wifiEnabled(this@TacticalActivity)) "ON" else "OFF")
            append(" · BLE ").append(if (running && Prefs.bleEnabled(this@TacticalActivity)) "ON" else "OFF")
        }
        scanButton.text = if (running) "STOP SCAN" else "START SCAN"

        // The most important flagged device, in its colour, across the top.
        val top = all.filter { LiveWindow.alerting(it, now) }.map { devices.getValue(it.mac) }
            .let { TacticalModel.sorted(it) }.firstOrNull()
        if (top == null) alertStrip.visibility = View.GONE else {
            alertStrip.visibility = View.VISIBLE
            alertStrip.tag = top.mac
            alertStrip.text = "⚠ ${top.affiliation.title.uppercase()} · ${top.best?.label ?: top.name ?: top.mac} · ${top.rssi} dBm · ${TacticalModel.ago(now, top.lastSeen)}"
            alertStrip.background = GradientDrawable().apply { setColor(darken(top.affiliation.colorArgb)); cornerRadius = dp(6).toFloat() }
        }

        // RADAR: every device in its affiliation's colour; flagged ones pulse with their label.
        radar.setBlips(all.sortedByDescending { it.rssi }.take(250).map { s ->
            val d = devices.getValue(s.mac)
            val alert = LiveWindow.alerting(s, now)
            RadarView.Blip(
                s.mac, s.rssi, if (alert || d.affiliation.rank <= Affiliation.FLEET.rank) d.affiliation.colorArgb else fade(d.affiliation.colorArgb),
                alert, if (alert) s.best?.label else null,
                kind = if (d.wifi) RadarModel.Kind.WIFI else RadarModel.Kind.BLE,
                trail = RadarModel.trail(s.history, now),
                motion = RadarModel.motion(RadarModel.slopeDbPerMin(s.history, now)),
            )
        })
        legend.text = legendText(TacticalModel.counts(devices.values.toList()))
        modeChip.text = if (radar.angleLayout == RadarModel.Layout.SPREAD) "ANGLE: SPREAD" else "ANGLE: APPROACH (top = closing)"
        radarFooter.text = TacticalModel.radarFooter(wifiSnaps.size, bleSnaps.size, (REFRESH_MS / 1000).toInt())

        // WI-FI: the most important networks first.
        val wifiSorted = TacticalModel.sorted(wifiSnaps.map { devices.getValue(it.mac) })
        val wifiShown = wifiSorted.take(if (wifiExpanded) LONG else WIFI_SHORT)
        fill(wifiList, wifiShown.map { d -> d.copy(frequencyMhz = DeviceRegistry.get(d.mac)?.advert?.wifi?.frequencyMhz) }, now,
            empty = if (running) "Listening... no WiFi networks heard yet." else "Scan stopped.")
        wifiShowing.text = TacticalModel.showing(wifiShown.size, wifiSorted.size)
        wifiMore.text = if (wifiExpanded) "SHOW LESS" else "SHOW ALL WI-FI"
        wifiMore.visibility = if (wifiSorted.size > WIFI_SHORT) View.VISIBLE else View.GONE

        // BLE: the switch, a summary, and the devices when asked for.
        val bleOn = running && Prefs.bleEnabled(this)
        val bleSorted = TacticalModel.sorted(bleSnaps.map { devices.getValue(it.mac) })
        bleStatus.text = SpannableStringBuilder("BLE status: ").apply {
            val on = if (bleOn) "ON" else "OFF"
            append(on, ForegroundColorSpan(if (bleOn) 0xFF7BD88F.toInt() else MAGENTA), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\nBLE devices: ${bleSorted.size}")
            if (!bleOn) append("\nBLE is optional. Start it when you want.")
        }
        bleDetailsButton.text = if (bleDetails) "HIDE BLE DETAILS" else "SHOW BLE DETAILS"
        if (bleDetails) {
            val shown = bleSorted.take(BLE_DETAILS)
            fill(bleList, shown, now, empty = "No Bluetooth devices heard.")
            bleShowing.text = TacticalModel.showing(shown.size, bleSorted.size)
            bleShowing.visibility = View.VISIBLE
        } else {
            bleList.removeAllViews()
            bleShowing.visibility = View.GONE
        }
    }

    /** Replaces [list]'s rows with one bordered box per device. */
    private fun fill(list: LinearLayout, devices: List<TacticalModel.Device>, now: Long, empty: String) {
        list.removeAllViews()
        if (devices.isEmpty()) { list.addView(text(empty, 12f, DIM), margins(top = 4)); return }
        for (d in devices) {
            val e = TacticalModel.entry(d, now)
            val c = e.affiliation.colorArgb
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor((c and 0x00FFFFFF) or 0x14000000)
                    setStroke(dp(1), (c and 0x00FFFFFF) or 0xAA000000.toInt())
                    cornerRadius = dp(4).toFloat()
                }
                setOnClickListener { DeviceActions.openDetails(this@TacticalActivity, e.mac) }
            }
            box.addView(text(e.title, 13f, if (e.hidden) MAGENTA else INK, bold = true))
            e.lines.forEach { line -> box.addView(text(line, 12f, if (line.startsWith("Category:")) c else INK)) }
            list.addView(box, margins(top = 6))
        }
    }

    private fun legendText(counts: Map<Affiliation, Int>) = SpannableStringBuilder("Legend: Wi-Fi ● | BLE ■").apply {
        for (a in Affiliation.entries) {
            append(" | ")
            append("${a.title} ${counts[a] ?: 0}", ForegroundColorSpan(a.colorArgb), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Ordinary devices sit back on the radar: their colour at half strength. */
    private fun fade(c: Int) = (c and 0x00FFFFFF) or 0x80000000.toInt()
    private fun darken(c: Int): Int {
        val r = (Color.red(c) * 0.55f).toInt(); val g = (Color.green(c) * 0.55f).toInt(); val b = (Color.blue(c) * 0.55f).toInt()
        return Color.rgb(r, g, b)
    }

    /** A bordered card with its [title] in the corner; returns the column to fill. */
    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(CARD); setStroke(dp(1), (CYAN and 0x00FFFFFF) or 0x99000000.toInt()); cornerRadius = dp(6).toFloat() }
        }
        body.addView(text(title, 15f, CYAN, bold = true), margins(bottom = 4))
        parent.addView(body, margins(top = 12))
        return body
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun button(label: String, outline: Boolean = false, onClick: () -> Unit) = text(label, 13f, if (outline) CYAN else BUTTON_INK, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = GradientDrawable().apply {
            if (outline) setStroke(dp(1), CYAN) else setColor(BUTTON)
            cornerRadius = dp(4).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }
}
