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
        else w.loadDataWithBaseURL("https://demo.mdm-lite.local/", SAMPLE_MENU, "text/html", "utf-8", null)
    }

    private companion object {
        // Stands in for the menu board: varied enough that blank-screen detection
        // does not fire on it, and nothing to load from the network.
        const val SAMPLE_MENU = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  *{box-sizing:border-box}
  html,body{margin:0;height:100%}
  body{font-family:system-ui,sans-serif;background:#0f1115;color:#f4f1ec;display:flex;flex-direction:column;padding:3.2vh 3vw 2.4vh;gap:2.4vh;overflow:hidden}
  header{display:flex;align-items:baseline;gap:1.4vw}
  .brand{font-size:5.2vh;font-weight:800;letter-spacing:-.02em}
  .brand span{background:linear-gradient(90deg,#f9674e,#7a80f6);-webkit-background-clip:text;color:transparent}
  .tag{color:#a8a39b;font-size:2.2vh}
  .clock{margin-left:auto;font-size:3vh;font-weight:700;color:#e9e4dc;font-variant-numeric:tabular-nums}
  main{flex:1;display:grid;grid-template-columns:1.05fr 1fr 1fr;gap:2vw;min-height:0}
  .promo{border-radius:2.4vh;padding:3.4vh 2.2vw;background:linear-gradient(145deg,#f9674e 0%,#c45a8f 55%,#7a80f6 100%);display:flex;flex-direction:column;justify-content:space-between;box-shadow:0 1.6vh 4vh rgba(0,0,0,.35)}
  .promo .kick{font-size:2vh;font-weight:700;letter-spacing:.14em;text-transform:uppercase;opacity:.9}
  .promo h2{margin:1.4vh 0 0;font-size:6.4vh;line-height:1.02;font-weight:800;letter-spacing:-.02em}
  .promo p{margin:1.4vh 0 0;font-size:2.4vh;opacity:.92;max-width:26ch}
  .promo .price{font-size:7vh;font-weight:800}
  .promo .price small{display:block;font-size:2.4vh;font-weight:600;opacity:.85;margin-top:.4vh}
  .col{display:flex;flex-direction:column;gap:2.4vh;min-height:0}
  .cat{background:#181b21;border:1px solid #262a31;border-radius:2vh;padding:2.2vh 1.6vw}
  .cat h3{margin:0 0 1.2vh;font-size:2.1vh;letter-spacing:.14em;text-transform:uppercase;color:#f9674e}
  .it{display:grid;grid-template-columns:1fr auto;gap:.2vh 1vw;padding:1vh 0;border-top:1px solid #23272e}
  .it:first-of-type{border-top:0}
  .it b{font-size:2.6vh;font-weight:700}
  .it i{font-style:normal;font-size:2.6vh;font-weight:700;color:#ffd166;font-variant-numeric:tabular-nums}
  .it em{grid-column:1/-1;font-style:normal;font-size:1.85vh;color:#a8a39b}
  .new{font-size:1.5vh;font-weight:800;color:#0f1115;background:#ffd166;border-radius:99px;padding:.2vh .6vw;margin-left:.6vw;vertical-align:.3vh}
  footer{display:flex;justify-content:space-between;color:#7d786f;font-size:1.9vh;padding-right:7vw}
  @media (max-aspect-ratio:1/1){main{grid-template-columns:1fr;overflow:auto}body{overflow:auto}.brand{font-size:4vh}}
</style></head><body>
<header><div class="brand"><span>AIO</span> Grill</div><div class="tag">Fresh · Fast · Flame-grilled</div><div class="clock" id="clock"></div></header>
<main>
  <section class="promo">
    <div><div class="kick">Today's special</div><h2>Double Smash Stack</h2><p>Two smashed patties, aged cheddar, pickles and house sauce on a toasted brioche bun.</p></div>
    <div class="price">Rs 1,190<small>with fries &amp; a drink</small></div>
  </section>
  <div class="col">
    <div class="cat"><h3>Burgers</h3>
      <div class="it"><b>Classic Beef</b><i>Rs 790</i><em>Beef patty, lettuce, tomato, onion</em></div>
      <div class="it"><b>Crispy Chicken<span class="new">NEW</span></b><i>Rs 850</i><em>Buttermilk fried chicken, slaw, spicy mayo</em></div>
      <div class="it"><b>Mushroom Swiss</b><i>Rs 890</i><em>Sautéed mushrooms, Swiss cheese</em></div>
      <div class="it"><b>Garden Veggie</b><i>Rs 690</i><em>Grilled halloumi, peppers, pesto</em></div>
    </div>
    <div class="cat"><h3>Sides</h3>
      <div class="it"><b>Loaded Fries</b><i>Rs 450</i><em>Cheese sauce, jalapeños, crispy onion</em></div>
      <div class="it"><b>Onion Rings</b><i>Rs 380</i><em>Beer-battered, smoky dip</em></div>
    </div>
  </div>
  <div class="col">
    <div class="cat"><h3>Drinks</h3>
      <div class="it"><b>Mint Margarita</b><i>Rs 390</i><em>Fresh mint, lime, soda</em></div>
      <div class="it"><b>Iced Latte</b><i>Rs 420</i><em>Double shot, whole milk</em></div>
      <div class="it"><b>Soft Drinks</b><i>Rs 180</i><em>Cola, lemon-lime, orange</em></div>
    </div>
    <div class="cat"><h3>Desserts</h3>
      <div class="it"><b>Chocolate Shake</b><i>Rs 520</i><em>Belgian chocolate, whipped cream</em></div>
      <div class="it"><b>Molten Lava Cake</b><i>Rs 560</i><em>Warm, with vanilla ice cream</em></div>
    </div>
  </div>
</main>
<footer><span>Prices include tax · Ask us about allergens</span><span>Order at the counter or scan the QR on your table</span></footer>
<script>
  function tick(){var d=new Date();document.getElementById('clock').textContent=d.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});}
  tick(); setInterval(tick, 15000);
</script>
</body></html>"""

        val BG = Color.parseColor("#0F1115")
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
