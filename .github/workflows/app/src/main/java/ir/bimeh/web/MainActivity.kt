package ir.bimeh.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
import java.net.InetSocketAddress
import java.net.Socket
import androidx.appcompat.app.AlertDialog
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private val defHost = "86.104.95.7"
    private val defPort = "3128"
    // پیش‌فرض تست؛ در رابط نمایش داده نمی‌شود
    private val defUser = String(charArrayOf('3', '1', '6', '3'))
    private val defPass = String(charArrayOf('3', '1', '6', '3'))

    private val sites = listOf(
        "صفحه اصلی" to "https://agents.iraninsurance.ir/",
        "اتوماسیون" to "https://eoffice.iraninsurance.ir",
        "صدور ثالث" to "http://sales.iic.local/",
        "صدور بدنه" to "http://hulldmg.iic.local",
        "صدور آتش‌سوزی" to "http://fire.iic.local/",
        "صدور مسئولیت" to "http://insres.iic.local/"
    )

    private lateinit var web: WebView
    private lateinit var login: View
    private lateinit var browser: View
    private lateinit var progress: ProgressBar
    private var user = ""
    private var pass = ""
    private var authTries = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        login = findViewById(R.id.loginPanel)
        browser = findViewById(R.id.browserPanel)
        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)

        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        val etHost = findViewById<EditText>(R.id.etHost)
        val etPort = findViewById<EditText>(R.id.etPort)
        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val cbSave = findViewById<CheckBox>(R.id.cbSave)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val box = findViewById<View>(R.id.settingsBox)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        etHost.setText(sp.getString("host", defHost))
        etPort.setText(sp.getString("port", defPort))
        etUser.setText(sp.getString("user", ""))
        etPass.setText(sp.getString("pass", ""))
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            box.visibility = if (box.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnLogin.setOnClickListener {
            val h = etHost.text.toString().trim().ifEmpty { defHost }
            val p = etPort.text.toString().trim().ifEmpty { defPort }
            val u = etUser.text.toString().trim()
            val pw = etPass.text.toString()
            user = u.ifEmpty { defUser }
            pass = if (u.isEmpty() && pw.isEmpty()) defPass else pw
            val e = sp.edit().putString("host", h).putString("port", p)
            if (cbSave.isChecked) e.putString("user", u).putString("pass", pw)
            else e.remove("user").remove("pass")
            e.apply()
            if (!setProxy("$h:$p")) return@setOnClickListener
            btnLogin.isEnabled = false
            tvStatus.setTextColor(0xFF555555.toInt()); tvStatus.text = "در حال اتصال..."
            val u2 = user; val p2 = pass
            Thread {
                val res = testProxy(h, p.toIntOrNull() ?: 0, u2, p2)
                runOnUiThread {
                    btnLogin.isEnabled = true
                    if (res == null) {
                        tvStatus.setTextColor(0xFF2E7D32.toInt()); tvStatus.text = "اتصال برقرار شد ✓"
                        Toast.makeText(this, "اتصال برقرار شد", Toast.LENGTH_SHORT).show()
                        login.visibility = View.GONE
                        browser.visibility = View.VISIBLE
                        authTries = 0
                        web.loadUrl(sites[0].second)
                    } else {
                        tvStatus.setTextColor(0xFFC62828.toInt()); tvStatus.text = res
                    }
                }
            }.start()
        }
        val etUrl = findViewById<EditText>(R.id.etUrl)
        findViewById<Button>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<Button>(R.id.btnFwd).setOnClickListener { if (web.canGoForward()) web.goForward() }
        etUrl.setOnEditorActionListener { v, _, _ ->
            var u = v.text.toString().trim()
            if (u.isNotEmpty()) {
                if (!u.contains("://")) u = "http://$u"
                authTries = 0; web.loadUrl(u)
            }
            true
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener { authTries = 0; web.reload() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }

        val bar = findViewById<LinearLayout>(R.id.siteBar)
        for ((name, url) in sites) {
            bar.addView(Button(this).apply {
                text = name; background = null
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_normal))
                setOnClickListener { authTries = 0; web.loadUrl(url) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }

        bar.addView(Button(this).apply {
            text = "همه سایت‌ها"; background = null
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_normal))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("همه سایت‌ها")
                    .setItems(ALL_SITES.map { it.first }.toTypedArray()) { _, k -> authTries = 0; web.loadUrl(ALL_SITES[k].second) }
                    .show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
        }
        web.webViewClient = object : WebViewClient() {
            // همه لینک‌ها داخل همین برنامه باز می‌شوند (فعلاً محدودیتی نیست)
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false

            override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) {
                if (url != null && url != "about:blank" && !etUrl.hasFocus()) etUrl.setText(url)
            }

            override fun onReceivedHttpAuthRequest(v: WebView, h: HttpAuthHandler, host: String, realm: String) {
                if (authTries++ < 3) h.proceed(user, pass) else {
                    h.cancel()
                    Toast.makeText(this@MainActivity, "نام کاربری یا رمز اشتباه است", Toast.LENGTH_LONG).show()
                }
            }
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                h.proceed() // هیچ آدرسی بسته نیست
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) v.loadData(
                    "<html dir='rtl'><body style='font-family:sans-serif;text-align:center;margin-top:20%'>" +
                    "<h3>اتصال برقرار نشد</h3><p>${e.description}</p></body></html>", "text/html", "utf-8")
            }
        }
    }

    private fun testProxy(h: String, port: Int, u: String, p: String): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(h, port), 8000)
                s.soTimeout = 8000
                val auth = Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
                s.getOutputStream().write(("CONNECT agents.iraninsurance.ir:443 HTTP/1.1\r\nHost: agents.iraninsurance.ir:443\r\nProxy-Authorization: Basic $auth\r\n\r\n").toByteArray())
                val line = s.getInputStream().bufferedReader().readLine() ?: return "پاسخی از سرور نیامد"
                when (line.split(" ").getOrNull(1)) {
                    "200" -> null
                    "407" -> "نام کاربری یا رمز اشتباه است"
                    "403" -> "دسترسی مجاز نیست (۴۰۳)"
                    else -> "پاسخ سرور: $line"
                }
            }
        } catch (e: Exception) { "اتصال به سرور برقرار نشد: ${e.message}" }
    }

    private fun setProxy(hostPort: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Toast.makeText(this, "این نسخه WebView از پروکسی پشتیبانی نمی‌کند؛ WebView را به‌روز کنید", Toast.LENGTH_LONG).show()
            return false
        }
        val cfg = ProxyConfig.Builder().addProxyRule(hostPort).build()
        ProxyController.getInstance().setProxyOverride(cfg, ContextCompat.getMainExecutor(this)) {}
        return true
    }

    private fun logout() {
        pass = ""
        web.loadUrl("about:blank")
        web.clearCache(true); web.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        browser.visibility = View.GONE
        login.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvStatus).text = ""
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (browser.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
