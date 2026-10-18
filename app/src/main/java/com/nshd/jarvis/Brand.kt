package com.nshd.jarvis

/**
 * Single place for owner branding and contact values.
 * Leave a value blank to hide its row in the app. Never put secrets here.
 */
object Brand {
    const val APP_NAME = "Zara AI"
    const val COMPANION_NAME = "Zara"
    const val CREATOR = "N S H D"
    const val CREATOR_SHORT = "NSHD"
    const val TAGLINE = "Zara AI — by NSHD"

    const val INSTAGRAM = "__nshd.__"   // handle without @
    const val TELEGRAM = "nshd_0"       // handle without @
    const val WHATSAPP_NUMBER = ""      // digits only incl. country code; blank = hidden
    const val EMAIL = ""                // blank = hidden
    const val WEBSITE = ""              // https URL; blank = hidden

    const val DEFAULT_THEME = "sunset"  // sunset | amoled | dark | light | system
}
