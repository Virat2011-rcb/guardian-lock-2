package com.morningsearch.guard

object GuardConfig {
    const val COMMITMENT_DAYS = 45L
    const val COMMITMENT_DURATION_MS = COMMITMENT_DAYS * 24L * 60L * 60L * 1000L
    const val URGE_DELAY_MS = 60_000L
    const val ESCALATION_WINDOW_MS = 24L * 60L * 60L * 1000L
    const val ACCESSIBILITY_GRACE_MS = 15_000L
    const val TAMPER_LOCK_THRESHOLD = 100
    const val TAMPER_LOCK_DURATION_MS = 12L * 60L * 60L * 1000L

    val browserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
        "com.mi.globalbrowser",
        "com.google.android.googlequicksearchbox",
        "org.torproject.torbrowser",
        "org.mozilla.focus",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser"
    )

    // Only apps explicitly selected by the guardian are added to this set at runtime.
    // Banking, school, work, health, Settings, dialer and emergency apps are never inferred.
    val suggestedEntertainmentPackages = setOf(
        "com.netflix.mediaclient",
        "com.disney.hotstar",
        "com.amazon.avod.thirdpartyclient",
        "com.zhiliaoapp.musically",
        "com.instagram.android",
        "com.snapchat.android",
        "com.reddit.frontpage"
    )
}
