package com.aioapp.mdmlite.demo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.aioapp.mdmlite.AioMdm
import com.aioapp.mdmlite.MdmStatus

/**
 * Test host for MDM-lite: a live status panel, test buttons, and a WebView wired the way
 * android-menu-board's MainScreen is. The buttons do what the adb triggers do:
 *   adb shell am start --activity-single-top -n com.aioapp.mdmlite.demo/.DemoActivity \
 *       --es trigger crash|anr|jserror|badurl|blank
 * Views are built in code (no layout XML, no AndroidX) and every button takes D-pad focus,
 * so it works the same on a phone and on a TV box with a remote.
 */
class DemoActivity : Activity() {
    private var web: WebView? = null
    private lateinit var webHost: FrameLayout
    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private val rows = linkedMapOf<String, TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render(AioMdm.status())
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        web = newWebView().also { webHost.addView(it, matchParent()) }
        handleTrigger(intent?.getStringExtra("trigger"))
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTrigger(intent.getStringExtra("trigger"))
    }

    private fun handleTrigger(t: String?) {
        when (t) {
            "crash" -> throw IllegalStateException("mdm-lite demo: test crash")
            "anr" -> web?.postDelayed({ Thread.sleep(30_000) }, 500) // main thread blocked; input times out
            "jserror" -> web?.evaluateJavascript("setTimeout(function(){ undefinedFn() }, 0)", null)
            "badurl" -> web?.loadUrl("https://does-not-exist.invalid/")
            "blank" -> web?.loadUrl("about:blank") // uniform white: blank-screen detection
            "home" -> web?.let(::loadHome)
        }
    }

    // ── Status panel ─────────────────────────────────────────────────────────────

    private fun render(s: MdmStatus?) {
        if (s == null) {
            setState(AMBER, "MDM-lite is off", "this build has no enrollment token")
            return
        }
        val age = if (s.lastCheckinAtMs > 0) System.currentTimeMillis() - s.lastCheckinAtMs else -1L
        when {
            !s.enrolled && s.lastCheckinResult.isEmpty() -> setState(AMBER, "Enrolling…", "")
            !s.enrolled -> setState(RED, "Not enrolled", s.lastCheckinResult)
            s.lastCheckinResult == "ok" && age in 0..150_000 -> setState(GREEN, "Reporting", "")
            s.lastCheckinResult == "" -> setState(AMBER, "Starting…", "")
            else -> setState(RED, "Not reporting", s.lastCheckinResult)
        }
        rows.getValue("Serial").text = s.serial
        rows.getValue("Server").text = s.serverUrl.removePrefix("https://").removePrefix("http://")
        rows.getValue("Last check-in").text = if (age < 0) "—" else
            "${DateUtils.getRelativeTimeSpanString(s.lastCheckinAtMs, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)} · ${s.lastCheckinResult}"
        rows.getValue("Queued events").text = s.pendingEvents.toString()
        rows.getValue("Library").text = s.libraryVersion
        rows.getValue("App").text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        rows.getValue("In front").text = if (s.appInForeground) "yes" else "no"
    }

    private fun setState(color: Int, text: String, detail: String) {
        (stateDot.background as GradientDrawable).setColor(color)
        stateText.text = if (detail.isEmpty()) text else "$text · $detail"
    }

    // ── Layout ───────────────────────────────────────────────────────────────────

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            // Android 15+ draws apps edge to edge: keep the panel clear of the status
            // bar and the page clear of the navigation bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        panel.addView(text("MDM-lite demo", 13f, MUTED, bold = true).apply { letterSpacing = 0.08f; isAllCaps = true })

        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        stateDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AMBER) }
        }
        stateRow.addView(stateDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        stateText = text("Starting…", 22f, TEXT, bold = true)
        stateRow.addView(stateText)
        panel.addView(stateRow)

        // Key/value rows: two columns on a wide screen (TV) to keep the panel short, one
        // column on a phone so values are not cut off.
        val wide = resources.configuration.screenWidthDp >= 600
        val left = column()
        val right = if (wide) column() else left
        listOf("Serial", "Server", "Last check-in").forEach { left.addView(row(it)) }
        listOf("App", "Library", "Queued events", "In front").forEach { right.addView(row(it)) }
        if (wide) {
            val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
            grid.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(grid)
        } else {
            panel.addView(left)
        }
        root.addView(panel)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        buttons.addView(button("Check in now", primary = true) { AioMdm.checkinNow() })
        buttons.addView(button("Test crash") { handleTrigger("crash") })
        buttons.addView(button("Freeze 30 s") { handleTrigger("anr") })
        buttons.addView(button("JS error") { handleTrigger("jserror") })
        buttons.addView(button("Bad URL") { handleTrigger("badurl") })
        buttons.addView(button("Blank page") { handleTrigger("blank") })
        buttons.addView(button("Home page") { handleTrigger("home") })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        })

        webHost = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(webHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row(label: String): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), dp(12), dp(2))
        }
        r.addView(text(label, 13f, MUTED), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = text("—", 13f, TEXT).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        rows[label] = v
        r.addView(v)
        return r
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) =
        text(label, 14f, if (primary) Color.WHITE else TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                // Focus ring for D-pad navigation on TV; pressed state for touch.
                addState(intArrayOf(android.R.attr.state_focused), pill(if (primary) ACCENT else CHIP, FOCUS))
                addState(intArrayOf(android.R.attr.state_pressed), pill(if (primary) ACCENT_DARK else CHIP_DOWN, null))
                addState(intArrayOf(), pill(if (primary) ACCENT else CHIP, null))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }

    private fun pill(fill: Int, stroke: Int?) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── WebView (the host content) ───────────────────────────────────────────────

    private fun newWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                AioMdm.reportPageError(request.url.toString(), error.errorCode, error.description.toString(), request.isForMainFrame)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                AioMdm.reportPageError(request.url.toString(), response.statusCode, "HTTP ${response.statusCode}", request.isForMainFrame)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AioMdm.reportRendererGone(detail, view.url)
                webHost.removeView(view)
                view.destroy()
                web = newWebView().also { webHost.addView(it, matchParent()) }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    AioMdm.reportJsError(m.message(), m.sourceId(), m.lineNumber())
                }
                return true
            }
        }
        AioMdm.attachWebView(this)
        loadHome(this)
    }

    /** The host content: a real page when built with -PdemoUrl, else the sample menu. */
    private fun loadHome(w: WebView) {
        if (BuildConfig.DEMO_URL.isNotBlank()) w.loadUrl(BuildConfig.DEMO_URL)
        else w.loadDataWithBaseURL("https://demo.mdm-lite.local/", SAMPLE_MENU, "text/html", "utf-8", null)
    }

    private companion object {
        // Stands in for the menu board: varied enough that blank-screen detection
        // does not fire on it, and nothing to load from the network.
        const val SAMPLE_MENU = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  body{margin:0;font-family:sans-serif;background:#1d1f24;color:#f2f4f7;padding:22px}
  h1{margin:0 0 4px;font-size:26px;color:#f9674e}
  p.sub{margin:0 0 18px;color:#9aa3ad;font-size:14px}
  .item{display:flex;justify-content:space-between;padding:12px 14px;margin:0 0 8px;
        border-radius:10px;background:#2a2e36;font-size:17px}
  .item b{color:#ffd166}
  .note{margin-top:18px;color:#9aa3ad;font-size:13px;line-height:1.4}
</style></head><body>
<h1>Sample menu board</h1>
<p class="sub">Host content for the MDM-lite demo</p>
<div class="item"><span>Chicken burger</span><b>Rs 850</b></div>
<div class="item"><span>Loaded fries</span><b>Rs 450</b></div>
<div class="item"><span>Mint margarita</span><b>Rs 390</b></div>
<div class="item"><span>Chocolate shake</span><b>Rs 520</b></div>
<p class="note">MDM-lite watches this page: it reports load errors, JavaScript
errors and a blank screen, and the MDM can reload it, clear its cache or take a
screenshot of it.</p>
</body></html>"""

        val BG = Color.parseColor("#14161A")
        val TEXT = Color.parseColor("#F2F4F7")
        val MUTED = Color.parseColor("#9AA3AD")
        val CHIP = Color.parseColor("#262A31")
        val CHIP_DOWN = Color.parseColor("#343A43")
        val ACCENT = Color.parseColor("#F9674E")
        val ACCENT_DARK = Color.parseColor("#E0533B")
        val FOCUS = Color.parseColor("#FFFFFF")
        val GREEN = Color.parseColor("#34C759")
        val AMBER = Color.parseColor("#F5A524")
        val RED = Color.parseColor("#F04438")
    }
}
