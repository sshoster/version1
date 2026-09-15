package com.bridge.debate.shared.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Android App Links verification: Google fetches /.well-known/assetlinks.json over HTTPS and,
 * when the app's signing certificate matches, https links to this host open the native app
 * instead of the browser. Served by a controller (not a static file) because the SPA fallback
 * would otherwise swallow the path. The debug fingerprint is the default; add the release
 * fingerprint via APP_ANDROID_CERT_FINGERPRINTS when a signed build exists.
 */
@RestController
class WellKnownController(
    @param:Value(
        "\${app.android-cert-fingerprints:" +
            "E5:DE:74:AF:4E:71:B3:2C:23:A6:D1:D2:E5:D0:B3:1B:F9:2F:3A:9E:5E:EF:91:1A:A0:96:5C:BD:5F:2A:DD:BF}",
    )
    private val certFingerprints: List<String>,
) {

    @GetMapping("/.well-known/assetlinks.json")
    fun assetLinks(): List<Map<String, Any>> = listOf(
        mapOf(
            "relation" to listOf("delegate_permission/common.handle_all_urls"),
            "target" to mapOf(
                "namespace" to "android_app",
                "package_name" to "com.bridge.app",
                "sha256_cert_fingerprints" to certFingerprints,
            ),
        ),
    )
}
