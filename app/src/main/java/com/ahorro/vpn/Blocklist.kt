package com.ahorro.vpn

/**
 * Lista de dominios de anuncios, rastreadores y analítica.
 * Solo dominios que son PURO anuncio/rastreo: nunca CDNs de video
 * (googlevideo, fbcdn, tiktokcdn...), ni APIs que usan las apps
 * (graph.facebook.com NO está aquí: bloquearlo rompería Facebook
 * e Instagram). La comparación es por sufijo: "doubleclick.net"
 * bloquea también "www.doubleclick.net" y "stats.doubleclick.net".
 */
object Blocklist {
    private val DOMAINS = setOf(
        // Google ads / analítica
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "googletagservices.com",
        "admob.com",
        "adsense.com",
        "adsensecustomsearchads.com",
        // Facebook (solo rastreo, no la API de la app)
        "connect.facebook.net",
        // Amazon ads
        "amazon-adsystem.com",
        // Yahoo / AOL ads
        "ads.yahoo.com",
        "analytics.yahoo.com",
        // Redes de anuncios
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "criteo.net",
        "outbrain.com",
        "taboola.com",
        "scorecardresearch.com",
        "quantserve.com",
        "moatads.com",
        "adsafeprotected.com",
        "doubleverify.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "openx.com",
        "appnexus.com",
        "lijit.com",
        "mathtag.com",
        "media.net",
        "revcontent.com",
        "mgid.com",
        "adroll.com",
        "perfectaudience.com",
        "adbutler.com",
        "servedbyadbutler.com",
        "zergnet.com",
        "advertising.com",
        "tribalfusion.com",
        "casalemedia.com",
        "adtech.de",
        "adtechus.com",
        "smartclip.net",
        "spotxchange.com",
        "spotx.tv",
        "tremorhub.com",
        "tremorvideo.com",
        "smartadserver.com",
        "adform.net",
        "adform.com",
        "flashtalking.com",
        "sizmek.com",
        "eyeota.net",
        "liveramp.com",
        "rlcdn.com",
        "exelator.com",
        "bluekai.com",
        "demdex.net",
        "krux.com",
        "agkn.com",
        "rfihub.com",
        "addthis.com",
        "sharethis.com",
        // Analítica y seguimiento
        "hotjar.com",
        "fullstory.com",
        "mixpanel.com",
        "amplitude.com",
        "segment.com",
        "segment.io",
        "heap.io",
        "crazyegg.com",
        "mouseflow.com",
        "inspectlet.com",
        "luckyorange.com",
        "clicky.com",
        "statcounter.com",
        "newrelic.com",
        "nr-data.net",
        // Redes sociales (solo analítica de anuncios)
        "ads-twitter.com",
        "analytics.twitter.com",
        "ads.linkedin.com",
        "px.ads.linkedin.com",
        "snap.licdn.com",
        "ads.pinterest.com",
        "log.pinterest.com",
        "analytics.pinterest.com",
        "analytics.tiktok.com",
        // Anuncios en apps
        "vungle.com",
        "applovin.com",
        "unityads.unity3d.com",
        "ironsrc.com",
        "chartboost.com",
        "mopub.com",
        "inmobi.com",
        "smaato.com",
        "fyber.com",
        "tapjoy.com",
        "adcolony.com",
        "aerserv.com",
        "startapp.com",
        "airpush.com",
        "leadbolt.com",
        "millennialmedia.com",
        "jumptap.com",
        "greystripe.com",
        "inner-active.com",
        "mobfox.com",
        "mdotm.com",
        // Popups de sitios de streaming (AnimeFLV y similares)
        "popads.net",
        "popcash.net",
        "adcash.com",
        "propellerads.com",
        "adsterra.com",
        "clickadu.com",
        "hilltopads.com",
        "exoclick.com",
        "juicyads.com",
        "plugrush.com",
        "trafficjunky.com",
        "trafficfactory.com",
        "ero-advertising.com"
    )

    fun isBlocked(host: String): Boolean {
        val h = host.lowercase().trim().trimEnd('.')
        if (h.isEmpty()) return false
        for (d in DOMAINS) {
            if (h == d || h.endsWith(".$d")) return true
        }
        for (d in extra) {
            if (h == d || h.endsWith(".$d")) return true
        }
        return false
    }

    /** Dominios extra que trae la config local del usuario. */
    fun setExtra(domains: Set<String>) {
        extra.clear()
        extra.addAll(domains.map { it.lowercase().trim().trimEnd('.') }.filter { it.isNotBlank() })
    }

    private val extra = mutableSetOf<String>()
}
