package com.bikeeyes.gps

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), LocationListener {
    private val serviceId = UUID.fromString("7b2f0001-6c9d-4a1a-9b8e-1c3d5e7a9000")
    private val charId = UUID.fromString("7b2f0002-6c9d-4a1a-9b8e-1c3d5e7a9000")
    private lateinit var status: TextView
    private lateinit var locationManager: LocationManager
    private var gatt: BluetoothGatt? = null
    private var gpsChar: BluetoothGattCharacteristic? = null
    private var lastLocation: Location? = null
    private var lastSentAt = 0L
    private lateinit var preview: TextureView
    private var mediaPlayer: MediaPlayer? = null
    private var videoStreaming = false
    private val videoHandler = Handler(Looper.getMainLooper())
    private val videoExecutor = Executors.newSingleThreadExecutor()
    private val frameInFlight = AtomicBoolean(false)
    private val videoHost = "http://192.168.4.1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }
        status = TextView(this).apply { textSize = 18f; text = "BIKE EYES GPS\nNot connected"; gravity = Gravity.CENTER }
        val connect = Button(this).apply { text = "CONNECT GPS OVER BLUETOOTH"; setOnClickListener { requestNeededPermissions() } }
        preview = TextureView(this)
        preview.layoutParams = LinearLayout.LayoutParams(240, 240)
        layout.addView(status)
        layout.addView(connect)
        layout.addView(preview)
        layout.addView(Button(this).apply { text = "CHOOSE PHONE VIDEO"; setOnClickListener { chooseVideo() } })
        layout.addView(Button(this).apply { text = "START VIDEO ON BOTH DISPLAYS"; setOnClickListener { startVideoStream() } })
        layout.addView(Button(this).apply { text = "STOP VIDEO"; setOnClickListener { stopVideoStream() } })
        setContentView(layout)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) { }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) { }
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) { }
        }
    }

    private fun neededPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        return p.toTypedArray()
    }

    private fun requestNeededPermissions() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
        else startGpsAndBle()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startGpsAndBle()
        else status.text = "Permissions are required for GPS and Bluetooth."
    }

    private fun startGpsAndBle() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            status.text = "Allow precise location, then try again."; return
        }
        startBleScan()
        status.text = "GPS active. Searching for BIKE_EYES_GPS..."
    }

    private fun startBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: run { status.text = "Bluetooth is unavailable"; return }
        if (!adapter.isEnabled) { status.text = "Turn on Bluetooth and tap CONNECT again"; return }
        adapter.bluetoothLeScanner.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == "BIKE_EYES_GPS") {
                val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter.bluetoothLeScanner.stopScan(this)
                status.text = "Found BIKE_EYES_GPS. Connecting..."
                gatt = device.connectGatt(this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) { status.text = "Bluetooth scan failed: $errorCode" }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { status.text = "Bluetooth connected. Finding GPS service..." }
                if (Build.VERSION.SDK_INT >= 21) g.requestMtu(64) else g.discoverServices()
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                gpsChar = null
                runOnUiThread { status.text = "Disconnected. Tap CONNECT to retry." }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) { g.discoverServices() }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val service: BluetoothGattService? = g.getService(serviceId)
            gpsChar = service?.getCharacteristic(charId)
            runOnUiThread { status.text = if (gpsChar != null) "Connected! Waiting for GPS fix..." else "GPS service not found. Check ESP32 firmware." }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) runOnUiThread { status.text = "GPS data write failed" }
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < 800) return
        lastSentAt = now
        val previous = lastLocation
        val heading = if (location.hasBearing()) location.bearing else if (previous != null) previous.bearingTo(location) else 0f
        val speed = if (location.hasSpeed()) location.speed * 3.6f else 0f
        lastLocation = location
        val packet = String.format(Locale.US, "%.5f,%.5f,%.1f,%.0f", location.latitude, location.longitude, speed, ((heading % 360f) + 360f) % 360f)
        sendPacket(packet)
        status.text = "Connected: ${String.format(Locale.US, "%.1f km/h", speed)}\n${location.latitude}, ${location.longitude}\nHeading ${heading.toInt()}°"
    }

    @Suppress("DEPRECATION")
    private fun sendPacket(packet: String) {
        val g = gatt ?: return
        val c = gpsChar ?: return
        val bytes = packet.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            g.writeCharacteristic(c)
        }
    }


    private fun chooseVideo() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, 41)
    }

    @Deprecated("Activity result kept compatible with the existing simple Activity project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            playPhoneVideo(uri)
        }
    }

    private fun playPhoneVideo(uri: Uri) {
        try {
            mediaPlayer?.release()
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                if (preview.isAvailable) {
                    it.setSurface(Surface(preview.surfaceTexture))
                    it.isLooping = true
                    it.start()
                    status.text = "Phone video playing. Connect to BIKE_EYES Wi-Fi, then start streaming."
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                status.text = "Video playback error: $what / $extra"
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            status.text = "Cannot open video: ${e.message}"
        }
    }

    private fun startVideoStream() {
        if (!preview.isAvailable) {
            status.text = "Video preview is not ready. Choose a video first."
            return
        }
        videoExecutor.execute {
            try {
                val conn = (URL("$videoHost/videoStart").openConnection() as HttpURLConnection)
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) {
                    videoStreaming = true
                    runOnUiThread { status.text = "Video streaming to both BIKE EYES displays" }
                    videoHandler.post(videoFrameTask)
                } else runOnUiThread { status.text = "ESP32 rejected video start: HTTP $code" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Connect phone to BIKE_EYES Wi-Fi first. ${e.message}" }
            }
        }
    }

    private val videoFrameTask = object : Runnable {
        override fun run() {
            if (!videoStreaming) return
            val frame = try { preview.getBitmap(240, 240) } catch (_: Exception) { null }
            if (frame != null) {
                if (frameInFlight.compareAndSet(false, true)) {
                    videoExecutor.execute {
                        try { postJpegFrame(frame) }
                        finally { frame.recycle(); frameInFlight.set(false) }
                    }
                } else frame.recycle()
            }
            videoHandler.postDelayed(this, 100L) // target about 10 frames/second initially
        }
    }

    private fun postJpegFrame(bitmap: Bitmap) {
        val boundary = "BikeEyesFrameBoundary"
        var connection: HttpURLConnection? = null
        try {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out)
                out.toByteArray()
            }
            connection = (URL("$videoHost/videoFrame").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(connection.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"frame\"; filename=\"frame.jpg\"\r\n")
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                out.write(bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            connection.responseCode
        } catch (e: Exception) {
            runOnUiThread { status.text = "Video stream connection lost: ${e.message}" }
        } finally {
            connection?.disconnect()
        }
    }

    private fun stopVideoStream() {
        videoStreaming = false
        videoHandler.removeCallbacks(videoFrameTask)
        videoExecutor.execute {
            try {
                val c = URL("$videoHost/videoStop").openConnection() as HttpURLConnection
                c.connectTimeout = 2000
                c.responseCode
                c.disconnect()
            } catch (_: Exception) { }
        }
        mediaPlayer?.pause()
        status.text = "Video stopped"
    }

    override fun onProviderDisabled(provider: String) { status.text = "Turn on phone Location/GPS" }
    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        videoStreaming = false
        videoHandler.removeCallbacks(videoFrameTask)
        try { mediaPlayer?.release() } catch (_: Exception) {}
        videoExecutor.shutdownNow()
    }
}
