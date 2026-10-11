package com.nizam.app.feature.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream

/**
 * A blocker for Atlas's browser: known ad and tracker networks never load, pop-ups and script redirects to other sites are stopped,
 * and leftover ad slots are hidden. It can be turned off for a single site when a page needs one of these services to work.
 */
object AdBlock {
    private fun p(c: Context) = c.getSharedPreferences("adblock", 0)
    fun on(c: Context) = p(c).getBoolean("on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun siteOff(c: Context, host: String) = host.isNotBlank() && (p(c).getStringSet("off", emptySet()) ?: emptySet()).contains(host.removePrefix("www."))
    fun setSiteOff(c: Context, host: String, off: Boolean) {
        val cur = (p(c).getStringSet("off", emptySet()) ?: emptySet()).toMutableSet()
        if (off) cur.add(host.removePrefix("www.")) else cur.remove(host.removePrefix("www."))
        p(c).edit().putStringSet("off", cur).apply()
    }

    /** How many requests were stopped since Atlas started. */
    var blocked by mutableIntStateOf(0)

    private val HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adnxs.com", "adsrvr.org", "advertising.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "casalemedia.com", "indexww.com", "amazon-adsystem.com", "scorecardresearch.com", "quantserve.com", "hotjar.com", "moatads.com", "adform.net", "smartadserver.com",
        "33across.com", "sharethrough.com", "teads.tv", "adsafeprotected.com", "zemanta.com", "serving-sys.com", "tapad.com",
        "popads.net", "popcash.net", "propellerads.com", "onclickads.com", "onclkds.com", "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.com", "trafficfactory.biz",
        "adsterra.com", "hilltopads.net", "clickadu.com", "mgid.com", "revcontent.com", "adcash.com", "ad-maven.com", "admaven.com", "bidvertiser.com", "zedo.com", "yllix.com",
        "a-ads.com", "richads.com", "adspyglass.com", "plugrush.com", "clickaine.com", "tsyndicate.com", "realsrv.com", "magsrv.com", "ero-advertising.com", "popunder.net", "trafficstars.com"
    )
    val PATHS = Regex("(?i)(/ads?/|/adserver|/advert|popunder|pop-?under|/banners?/|adsbygoogle|/prebid|/doubleclick|[?&]ad(?:s|unit|id)=)")

    fun isAdHost(host: String): Boolean { val h = host.lowercase(); return HOSTS.any { h == it || h.endsWith(".$it") } }

    const val HIDE_CSS = ".adsbygoogle,ins.adsbygoogle,[id^=\"google_ads\"],[class*=\"popunder\"],[id*=\"popunder\"],iframe[src*=\"doubleclick\"],iframe[src*=\"googlesyndication\"],a[href*=\"ad.doubleclick\"]{display:none!important}"
}

class AdBlockClient(private val ctx: Context) : WebViewClient() {
    private fun pageHost(view: WebView?): String = runCatching { Uri.parse(view?.url).host }.getOrNull().orEmpty()

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        if (!AdBlock.on(ctx)) return null
        val page = pageHost(view)
        if (AdBlock.siteOff(ctx, page)) return null
        val host = request.url.host.orEmpty()
        val thirdParty = host.removePrefix("www.") != page.removePrefix("www.")
        if (AdBlock.isAdHost(host) || (thirdParty && AdBlock.PATHS.containsMatchIn(request.url.toString()))) {
            AdBlock.blocked++
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        request ?: return false
        if (!AdBlock.on(ctx) || !request.isForMainFrame) return false
        val page = pageHost(view)
        if (AdBlock.siteOff(ctx, page)) return false
        val host = request.url.host.orEmpty()
        // a script sending you to another site without a tap is how pop-unders and redirect ads work
        if (AdBlock.isAdHost(host) || (!request.hasGesture() && !request.isRedirect && page.isNotBlank() && host.removePrefix("www.") != page.removePrefix("www."))) { AdBlock.blocked++; return true }
        return false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!AdBlock.on(ctx) || AdBlock.siteOff(ctx, pageHost(view))) return
        view?.evaluateJavascript("(function(){try{var s=document.createElement('style');s.textContent='" + AdBlock.HIDE_CSS.replace("'", "\\'") + "';document.head.appendChild(s);}catch(e){}})()", null)
    }
}
