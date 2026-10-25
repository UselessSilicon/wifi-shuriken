package com.wifishuriken.companion.core

enum class SecurityClass { OPEN_OR_OWE, WEP, WPA, WPA2, WPA2_WPA3, WPA3, ENTERPRISE }

/**
 * Security class from the firmware's AuthMode string (design §8.3). The firmware also logs
 * OWE (Enhanced Open) as [OPEN], hence OPEN_OR_OWE.
 */
fun classifySecurity(authMode: String): SecurityClass {
  val a = authMode.uppercase()
  val wpa2 = "[WPA2" in a
  val rsn = "[RSN" in a
  return when {
    "EAP" in a -> SecurityClass.ENTERPRISE
    wpa2 && rsn -> SecurityClass.WPA2_WPA3
    rsn -> SecurityClass.WPA3
    wpa2 -> SecurityClass.WPA2
    "[WPA" in a -> SecurityClass.WPA
    "[WEP" in a -> SecurityClass.WEP
    else -> SecurityClass.OPEN_OR_OWE
  }
}
