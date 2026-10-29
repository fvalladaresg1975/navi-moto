package org.chronoscompanion.app.ota

/**
 * Checks a firmware .bin before it is sent to the watch over WiFi.
 *
 * Accepted only if it is an ESP32-S3 app image (ESP-IDF image header: magic 0xE9, chip id
 * 9) that fits the watch's update slot, and carries the Navi Moto build stamp with "ota1"
 * (firmware hal/esp32/navi_build.c, support/navi_build_stamp.py). A Navi Moto firmware from
 * before WiFi updates can't receive the next one, so sending it would leave the USB cable
 * as the only way back - that is what the stamp check prevents.
 */
class FirmwareImage private constructor(val bytes: ByteArray, val buildDate: String) {

    enum class Problem { NOT_FIRMWARE, WRONG_CHIP, TOO_BIG, NO_WIFI_UPDATE }

    class Invalid(val problem: Problem) : Exception(problem.name)

    companion object {
        /** The watch's app slot (partitions_16M.csv: app0/app1, 0x480000 each). */
        const val MAX_SIZE = 0x480000
        private const val ESP32_S3_CHIP_ID = 9
        private const val STAMP = "NAVIMOTO-FW "

        fun parse(bytes: ByteArray): FirmwareImage {
            if (bytes.size < 64 || bytes[0] != 0xE9.toByte()) throw Invalid(Problem.NOT_FIRMWARE)
            val chip = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)
            if (chip != ESP32_S3_CHIP_ID) throw Invalid(Problem.WRONG_CHIP)
            if (bytes.size > MAX_SIZE) throw Invalid(Problem.TOO_BIG)
            val stamp = findStamp(bytes) ?: throw Invalid(Problem.NO_WIFI_UPDATE)
            // "NAVIMOTO-FW ota1 2026-10-28 20:42": features, then the build date.
            val parts = stamp.removePrefix(STAMP).split(' ', limit = 2)
            if (parts.isEmpty() || parts[0] != "ota1") throw Invalid(Problem.NO_WIFI_UPDATE)
            return FirmwareImage(bytes, parts.getOrElse(1) { "" })
        }

        private fun findStamp(b: ByteArray): String? {
            val key = STAMP.toByteArray(Charsets.US_ASCII)
            outer@ for (i in 0..b.size - key.size) {
                for (k in key.indices) if (b[i + k] != key[k]) continue@outer
                var end = i
                while (end < b.size && end - i < 64 && b[end] != 0.toByte()) end++
                return String(b, i, end - i, Charsets.US_ASCII)
            }
            return null
        }
    }
}
