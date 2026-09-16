
package com.standee.kioskweb

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var overlaySettings: View
    private lateinit var etUrl: EditText
    private lateinit var etPass: EditText
    private lateinit var etNewPass: EditText
    private lateinit var progress: ProgressBar

    // CẤU HÌNH MẶC ĐỊNH - ĐỔI Ở ĐÂY
    private val DEFAULT_URL = "https://namchauhoiquan.com/hc1"
    private val DEFAULT_PASS = "@dmin123"
    private val REFRESH_INTERVAL = 5 * 60 * 1000L // 5 phút

    private val handler = Handler(Looper.getMainLooper())
    private var isRefreshing = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            softRefresh()
            handler.postDelayed(this, REFRESH_INTERVAL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        
        webView = findViewById(R.id.webview)
        overlaySettings = findViewById(R.id.overlay_settings)
        etUrl = findViewById(R.id.et_url)
        etPass = findViewById(R.id.et_pass)
        etNewPass = findViewById(R.id.et_new_pass)
        progress = findViewById(R.id.progress)
        val btnSave = findViewById<Button>(R.id.btn_save)
        val btnClose = findViewById<Button>(R.id.btn_close)
        val btnExitKiosk = findViewById<Button>(R.id.btn_exit)

        setupFullscreen()
        setupWebView()

        val savedUrl = prefs.getString("url", DEFAULT_URL)!!
        etUrl.setText(savedUrl)
        
        // Load lần đầu
        webView.loadUrl(savedUrl)

        // Bắt đầu vòng lặp tự cập nhật 5p
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL)

        // Nhấn giữ 5 giây ở góc trên-trái để mở khung pass (chống chạm nhầm)
        val secretTrigger = findViewById<View>(R.id.secret_trigger)
        secretTrigger.setOnLongClickListener {
            overlaySettings.visibility = View.VISIBLE
            true
        }

        btnSave.setOnClickListener {
            val pass = prefs.getString("pass", DEFAULT_PASS)
            if (etPass.text.toString() == pass) {
                val newUrl = etUrl.text.toString().trim()
                val newPass = etNewPass.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    prefs.edit().putString("url", newUrl).apply()
                    webView.loadUrl(newUrl)
                }
                if (newPass.isNotEmpty()) {
                    prefs.edit().putString("pass", newPass).apply()
                }
                Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show()
                overlaySettings.visibility = View.GONE
                etPass.text.clear()
                etNewPass.text.clear()
            } else {
                Toast.makeText(this, "Sai mật khẩu!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            overlaySettings.visibility = View.GONE
        }

        btnExitKiosk.setOnClickListener {
            val pass = prefs.getString("pass", DEFAULT_PASS)
            if (etPass.text.toString() == pass) {
                // Thoát kiosk mode để vào settings
                stopLockTask()
                finish()
            } else {
                Toast.makeText(this, "Nhập đúng pass để thoát kiosk", Toast.LENGTH_SHORT).show()
            }
        }

        // Tự động vào lock task (kiosk) nếu được set làm device owner hoặc whitelisted
        try {
            startLockTask()
        } catch (e: Exception) {}
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // Khóa dọc 1080x1920
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            // Tối ưu không nháy
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }
        
        // Chống nháy trắng khi reload: giữ background
        webView.setBackgroundColor(0xFF000000.toInt())
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!isRefreshing) progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                isRefreshing = false
            }
            // Chặn mở link ngoài - chỉ chạy 1 website duy nhất trong domain
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val currentDomain = getDomain(prefs.getString("url", DEFAULT_URL)!!)
                val newDomain = getDomain(request?.url.toString())
                // Nếu muốn khóa cứng 100% 1 URL thì luôn return false và chỉ load URL gốc
                // Ở đây cho phép di chuyển trong cùng domain
                return if (newDomain == currentDomain) false else {
                    view?.loadUrl(prefs.getString("url", DEFAULT_URL)!!)
                    true
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    // Refresh mềm không nháy trắng: dùng JS reload nếu có thể
    private fun softRefresh() {
        if (overlaySettings.visibility == View.VISIBLE) return // không refresh khi đang nhập pass
        isRefreshing = true
        try {
            webView.evaluateJavascript("window.location.reload();", null)
        } catch (e: Exception) {
            webView.reload()
        }
    }

    private fun getDomain(url: String): String {
        return try { android.net.Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
    }

    override fun onBackPressed() {
        // Chặn nút back trong kiosk
        // super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }
}
