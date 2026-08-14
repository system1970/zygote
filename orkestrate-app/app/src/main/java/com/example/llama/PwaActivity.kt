package com.example.llama

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Opens the Zygote PWA (DeepSeek-Harness-style UI) served by [ZygoteServer]
 * on 127.0.0.1:8787. The PWA is the surface; all inference stays native.
 *
 * The WebView lives inside a padded FrameLayout: padding is applied to the
 * PARENT (not the WebView), because with viewport-fit=cover the WebView fills
 * its whole box and ignores its own padding — only the parent padding reliably
 * shifts the page below the status bar / above the gesture nav.
 */
class PwaActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ZygoteServer.start(applicationContext)

        val webView = WebView(this)
        val frame = FrameLayout(this)
        frame.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(frame)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // Pad the PARENT frame by the real system-bar insets.
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        frame.requestApplyInsets()

        webView.loadUrl("http://127.0.0.1:8787/")
    }

    override fun onBackPressed() {
        val wv = findViewById<WebView>(android.R.id.content)?.let { it as? WebView }
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }
}
