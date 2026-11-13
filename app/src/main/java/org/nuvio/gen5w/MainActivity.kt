package org.nuvio.gen5w

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.nuvio.gen5w.gate.VehicleVideoGate
import org.nuvio.gen5w.overlay.NativeVideoOverlay
import org.nuvio.gen5w.player.NativePlaybackBridge
import org.nuvio.gen5w.player.NativePlaybackService
import org.nuvio.gen5w.player.PlayerEventListener

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NuvioMainActivity"
        private const val DEFAULT_WEB_URL = "file:///android_asset/www/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var videoOverlay: NativeVideoOverlay
    private lateinit var vehicleGate: VehicleVideoGate
    private lateinit var playbackBridge: NativePlaybackBridge

    private var playbackService: NativePlaybackService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NativePlaybackService.LocalBinder
            val svc = binder?.service
            playbackService = svc
            isServiceBound = true
            Log.i(TAG, "NativePlaybackService bound successfully")

            svc?.let { playerSvc ->
                playbackBridge.setPlaybackService(playerSvc)

                playerSvc.setEventListener(object : PlayerEventListener {
                    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                        val stateJson = JSONObject()
                        stateJson.put("playWhenReady", playWhenReady)
                        stateJson.put("playbackState", playbackState)
                        playbackBridge.dispatchWebEvent("stateChanged", stateJson)
                    }

                    override fun onPositionUpdate(currentPositionMs: Long, durationMs: Long) {
                        val posJson = JSONObject()
                        posJson.put("position", currentPositionMs)
                        posJson.put("duration", durationMs)
                        playbackBridge.dispatchWebEvent("timeupdate", posJson)
                    }

                    override fun onVideoSizeChanged(width: Int, height: Int) {
                        videoOverlay.setVideoSize(width, height)
                    }

                    override fun onError(errorMessage: String) {
                        playbackBridge.dispatchWebError(errorMessage)
                    }
                })
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
            Log.w(TAG, "NativePlaybackService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAutomotiveWindowFlags()
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        videoOverlay = findViewById(R.id.videoOverlay)

        initVehicleSafetyGate()
        initPlaybackBridge()
        configureKitKatWebView()

        startAndBindPlaybackService()

        webView.loadUrl(DEFAULT_WEB_URL)
    }

    private fun applyAutomotiveWindowFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        hideSystemUI()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun initVehicleSafetyGate() {
        vehicleGate = VehicleVideoGate(this)
        vehicleGate.setListener { isParked, reason ->
            Log.d(TAG, "Vehicle gate state: parked=$isParked, reason=$reason")
            videoOverlay.setVehicleParked(isParked)

            val parkJson = JSONObject()
            parkJson.put("isParked", isParked)
            parkJson.put("reason", reason)
            playbackBridge.dispatchWebEvent("parkStateChanged", parkJson)
        }
        vehicleGate.start()
    }

    private fun initPlaybackBridge() {
        playbackBridge = NativePlaybackBridge(webView, videoOverlay, vehicleGate)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureKitKatWebView() {
        val settings = webView.settings

        // Enable core JavaScript & DOM capabilities
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // Optimize caching and storage paths for KitKat
        val databasePath = applicationContext.getDir("databases", Context.MODE_PRIVATE).path
        settings.databasePath = databasePath

        val cachePath = applicationContext.cacheDir.absolutePath
        settings.setAppCacheEnabled(true)
        settings.setAppCachePath(cachePath)
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // File and asset access
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
        }

        // Auto-play media without user gesture requirement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.mediaPlaybackRequiresUserGesture = false
        }

        // Viewport and responsiveness
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Hardware layer acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Register the NativePlayer JavaScript bridge
        webView.addJavascriptInterface(playbackBridge, "NativePlayer")

        // Console logger & Chrome client
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d("NuvioWebJS", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (line ${consoleMessage.lineNumber()})")
                }
                return true
            }
        }

        // Navigation client
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false // load inside the WebView
            }
        }
    }

    private fun startAndBindPlaybackService() {
        val serviceIntent = Intent(this, NativePlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onBackPressed() {
        // If native video overlay is visible, hide it first
        if (videoOverlay.visibility == View.VISIBLE) {
            playbackBridge.stop()
            return
        }

        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        vehicleGate.start()
    }

    override fun onPause() {
        vehicleGate.stop()
        super.onPause()
    }

    override fun onDestroy() {
        vehicleGate.stop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}

