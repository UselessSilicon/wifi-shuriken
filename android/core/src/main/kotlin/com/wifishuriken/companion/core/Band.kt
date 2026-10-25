package com.wifishuriken.companion.core

enum class Band(val label: String) {
  GHZ_2_4("2.4 GHz"),
  GHZ_5("5 GHz");

  companion object {
    /** Channels 1-14 are 2.4 GHz and 32-177 are 5 GHz (design §8.3); anything else is unknown. */
    fun fromChannel(channel: Int?): Band? = when (channel) {
      null -> null
      in 1..14 -> GHZ_2_4
      in 32..177 -> GHZ_5
      else -> null
    }
  }
}
