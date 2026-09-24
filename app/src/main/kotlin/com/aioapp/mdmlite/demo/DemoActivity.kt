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
 * Test host for MDM Lite: a full-screen sample menu board in a WebView wired the way
 * android-menu-board's MainScreen is, with MDM Lite's live status and test buttons in a
 * Debug panel (bottom-right chip). The buttons do what the adb triggers do:
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
        debugChip.requestFocus()
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

    private lateinit var debugPanel: View
    private lateinit var debugChip: TextView

    /**
     * The menu board fills the screen, like the real app. Everything MDM-lite knows (state,
     * identity, versions, test buttons) lives in a Debug panel behind a small chip in the
     * bottom-right corner, so the board looks like a board.
     */
    private fun buildScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        webHost = FrameLayout(this).apply { setBackgroundColor(BG) }
        root.addView(webHost, matchParent())

        // Overlay layer: kept clear of the status/navigation bars (Android 15+ is edge to edge).
        val overlay = FrameLayout(this).apply {
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        root.addView(overlay, matchParent())

        debugPanel = buildDebugPanel().apply { visibility = View.GONE }
        overlay.addView(debugPanel, FrameLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(62)) })

        debugChip = text("Debug", 12f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isFocusable = true
            isClickable = true
            alpha = 0.85f
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pill(CHIP_OVERLAY, FOCUS))
                addState(intArrayOf(), pill(CHIP_OVERLAY, null))
            }
            setOnClickListener { toggleDebug() }
        }
        overlay.addView(debugChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
        return root
    }

    private fun toggleDebug() {
        val open = debugPanel.visibility != View.VISIBLE
        debugPanel.visibility = if (open) View.VISIBLE else View.GONE
        debugChip.text = if (open) "Close" else "Debug"
        if (open) debugPanel.findFocus() ?: debugPanel.focusSearch(View.FOCUS_DOWN)?.requestFocus()
    }

    // The remote's Menu / Info key opens the panel from anywhere.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU || keyCode == android.view.KeyEvent.KEYCODE_INFO) {
            toggleDebug(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::debugPanel.isInitialized && debugPanel.visibility == View.VISIBLE) toggleDebug()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun buildDebugPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(PANEL); setStroke(dp(1), PANEL_EDGE) }
            elevation = dp(8).toFloat()
        }
        panel.addView(text("MDM Lite · debug", 12f, MUTED, bold = true).apply { letterSpacing = 0.08f; isAllCaps = true })

        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(8))
        }
        stateDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AMBER) }
        }
        stateRow.addView(stateDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        stateText = text("Starting…", 17f, TEXT, bold = true)
        stateRow.addView(stateText)
        panel.addView(stateRow)

        listOf("Serial", "Server", "Last check-in", "App", "Library", "Queued events", "In front").forEach { panel.addView(row(it)) }

        // Test buttons, two rows so they fit the panel's width.
        val b1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(6)) }
        b1.addView(button("Check in now", primary = true) { AioMdm.checkinNow() })
        b1.addView(button("Test crash") { handleTrigger("crash") })
        b1.addView(button("Freeze 30 s") { handleTrigger("anr") })
        val b2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b2.addView(button("JS error") { handleTrigger("jserror") })
        b2.addView(button("Bad URL") { handleTrigger("badurl") })
        b2.addView(button("Blank") { handleTrigger("blank") })
        b2.addView(button("Menu") { handleTrigger("home") })
        panel.addView(b1)
        panel.addView(b2)
        return panel
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row(label: String): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), dp(12), dp(2))
        }
        r.addView(text(label, 12.5f, MUTED), LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = text("—", 12.5f, TEXT).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        rows[label] = v
        r.addView(v)
        return r
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) =
        text(label, 12.5f, if (primary) Color.WHITE else TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
            ).apply { marginEnd = dp(6) }
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
        // The board takes no remote input: leave D-pad focus to the Debug chip.
        isFocusable = false
        isFocusableInTouchMode = false
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
        else w.loadUrl(SAMPLE_MENU)
    }

    private companion object {
        // Stands in for the menu board: varied enough that blank-screen detection does
        // not fire on it, and nothing to load from the network (fonts ship beside it).
        const val SAMPLE_MENU = "file:///android_asset/menu.html"

        val BG = Color.parseColor("#F6F0E6") // the board's paper, so a reload never flashes dark
        val TEXT = Color.parseColor("#F2F4F7")
        val MUTED = Color.parseColor("#9AA3AD")
        val CHIP = Color.parseColor("#262A31")
        val CHIP_DOWN = Color.parseColor("#343A43")
        val ACCENT = Color.parseColor("#F9674E")
        val ACCENT_DARK = Color.parseColor("#E0533B")
        val FOCUS = Color.parseColor("#FFFFFF")
        val PANEL = Color.parseColor("#FF181B21")
        val PANEL_EDGE = Color.parseColor("#33FFFFFF")
        val CHIP_OVERLAY = Color.parseColor("#CC181B21")
        val GREEN = Color.parseColor("#34C759")
        val AMBER = Color.parseColor("#F5A524")
        val RED = Color.parseColor("#F04438")
    }
}
