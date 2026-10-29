package org.chronoscompanion.app.ble

import java.util.UUID
import java.util.zip.CRC32

/**
 * Encoder for the binary protocol ChronosESP32 expects from the phone app, reverse
 * engineered from the firmware library's own receive-side parser (ChronosESP32.cpp,
 * onWrite/dataReceived). Nothing here is documented publicly - it was derived by
 * reading how the ESP32 side decodes incoming BLE writes.
 *
 * Packet layout (before chunking):
 *   byte 0     = 0xAB (start marker)
 *   byte 1-2   = big-endian uint16, (totalPacketLength - 3)
 *   byte 3     = 0xFE
 *   byte 4     = command
 *   byte 5.. = command-specific sub-command + payload
 *
 * BLE writes are capped at 20 bytes (matches the default ATT_MTU=23, 20 bytes of
 * usable payload). A packet that doesn't fit in 20 bytes is split:
 *   - chunk 0: the first 20 bytes of the logical packet, written as-is.
 *   - chunk N (N>=1): [N-1 as a single byte, then bytes 20+(N-1)*19 .. +19) of the
 *     logical packet] - i.e. a 1-byte chunk index (0-based, counting from the second
 *     chunk) followed by up to 19 payload bytes.
 */
object ChronosProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHARACTERISTIC_UUID_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val CHARACTERISTIC_UUID_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    private const val CMD_NAV_ICON = 0xEE
    private const val CMD_NAV_DATA = 0xEF
    private const val CMD_SET_TIME = 0x93
    private const val CMD_NOTIFICATION = 0x72

    private const val NAV_DATA_SUB_ACTIVE = 0x80

    // The firmware's incoming-packet buffer (ChronosData.data) is a FIXED 512-byte
    // array (DATA_SIZE in ChronosESP32.h) with no bounds checking against it - a
    // packet that doesn't fit overflows into adjacent memory and crashes the watch.
    // A long WhatsApp message (or one with several 2-byte UTF-8 accented characters)
    // can exceed that on its own, so text is capped well under the limit, leaving
    // headroom for the ~12-byte header/icon/CRC overhead and, for nav data, five other
    // fields sharing the same 512-byte packet.
    private const val MAX_NOTIFICATION_MESSAGE_BYTES = 400
    private const val MAX_NAV_FIELD_BYTES = 60

    /**
     * App icon codes the firmware recognizes (ChronosESP32.cpp's appName(int)) - only
     * the ones relevant to package name mapping below are listed, not the full table.
     */
    object AppIcon {
        const val SMS = 0x03
        const val MAIL = 0x04
        const val SKYPE = 0x08
        const val WECHAT = 0x09
        const val WHATSAPP = 0x0A
        const val GMAIL = 0x0B
        const val LINE = 0x0E
        const val TWITTER = 0x0F
        const val FACEBOOK = 0x10
        const val MESSENGER = 0x11
        const val INSTAGRAM = 0x12
        const val KAKAOTALK = 0x14
        const val VIBER = 0x16
        const val TELEGRAM = 0x18
        const val WHATSAPP_BUSINESS = 0x20
    }

    /**
     * Builds a general (non-navigation) notification packet - command 0x72. Firmware
     * layout: data[6]=icon, data[7]=state (0x02 = new notification), data[8..]=message,
     * which puts icon/state at the start of buildPacket's payload param (icon -> data[6]
     * matches payload[0] once the header (data[0-4]) + sub-command byte (data[5], unused
     * here) are accounted for). [message] should be "Sender: body" where possible - the
     * firmware's splitTitle() splits on the first ':' before index 30 if present,
     * otherwise falls back to the icon's own app name as the title and the whole string
     * as the body.
     */
    fun buildNotification(iconCode: Int, message: String): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.add(iconCode.toByte())
        payload.add(0x02) // state: new notification
        payload.addAll(truncateUtf8(sanitizeForWatch(message), MAX_NOTIFICATION_MESSAGE_BYTES).toByteArray(Charsets.UTF_8).toList())
        return buildPacket(CMD_NOTIFICATION, 0x00, payload.toByteArray())
    }

    /**
     * Builds the "incoming call" ringer-start packet - same command (0x72) as
     * [buildNotification] but icon=0x01, which the firmware's dataReceived() checks
     * before the regular new-notification path and routes to the call screen instead
     * (ChronosESP32.cpp's ringerAlertCallback). [callerName] is shown there.
     */
    fun buildRingerStart(callerName: String): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.add(0x01) // icon: ringer start (not a real app icon code)
        payload.add(0x00) // state: unused on this path
        payload.addAll(truncateUtf8(sanitizeForWatch(callerName), MAX_NOTIFICATION_MESSAGE_BYTES).toByteArray(Charsets.UTF_8).toList())
        return buildPacket(CMD_NOTIFICATION, 0x00, payload.toByteArray())
    }

    /** Stops the ringer (icon=0x02) - send when the call ends or is answered/declined
     * elsewhere (another device, or the phone itself), so the watch's call screen clears. */
    fun buildRingerStop(): ByteArray {
        return buildPacket(CMD_NOTIFICATION, 0x00, byteArrayOf(0x02, 0x00))
    }

    /**
     * Tells the watch whether media is currently playing, so its play button can show a
     * pause icon instead. Command 0xC3 is our own: the ChronosESP32 protocol carries no
     * playback state, and the firmware decodes this in its raw dataCallback rather than
     * through the library's parser, so no vendored-library changes are needed.
     */
    fun buildMusicState(playing: Boolean): ByteArray {
        return buildPacket(0xC3, 0x00, byteArrayOf(if (playing) 0x01 else 0x00))
    }

    // -------------------------------------------------------------------
    // Route mode (phone -> watch), our own command 0xC5, decoded in the firmware's
    // dataCallback like 0xC3 - no library change.
    //   sub 0x01 = frame: payload [n][x0 hi][x0 lo][y0 hi][y0 lo]...  n <= ROUTE_MAX_POINTS
    //              then [speed km/h, 255 = unknown][limit km/h, 0 = unknown]
    //   sub 0x00 = clear (leave route mode)
    // Coordinates are signed 16-bit WATCH PIXELS RELATIVE TO THE RIDER, y negative =
    // ahead. All geometry (projection, heading-up rotation, scale, simplification) is
    // done here on the phone; the watch only offsets by where it draws the rider and
    // strokes the line. Keeps the firmware small and lets the look be tuned by APK.
    // -------------------------------------------------------------------

    const val ROUTE_MAX_POINTS = 32
    private const val CMD_ROUTE = 0xC5

    /** Rider pose in the street map's frame, appended to route frames so the watch can
     * place the streets it holds: [x dm i32][y dm i32][heading 0.01 deg u16][px/m x100 u8]. */
    data class RiderPose(val xDm: Int, val yDm: Int, val headingCentiDeg: Int, val scale100: Int)

    fun buildRouteFrame(points: List<Pair<Int, Int>>, speedKmh: Int? = null, limitKmh: Int? = null,
                        pose: RiderPose? = null): ByteArray {
        val pts = points.take(ROUTE_MAX_POINTS)
        val base = 1 + pts.size * 4 + 2
        val payload = ByteArray(base + if (pose != null) 11 else 0)
        payload[base - 2] = (speedKmh?.coerceIn(0, 254) ?: 255).toByte()
        payload[base - 1] = (limitKmh?.coerceIn(1, 254) ?: 0).toByte()
        payload[0] = pts.size.toByte()
        pts.forEachIndexed { i, (x, y) ->
            val cx = x.coerceIn(-32768, 32767)
            val cy = y.coerceIn(-32768, 32767)
            val o = 1 + i * 4
            payload[o] = ((cx shr 8) and 0xFF).toByte()
            payload[o + 1] = (cx and 0xFF).toByte()
            payload[o + 2] = ((cy shr 8) and 0xFF).toByte()
            payload[o + 3] = (cy and 0xFF).toByte()
        }
        if (pose != null) {
            putInt32(payload, base, pose.xDm)
            putInt32(payload, base + 4, pose.yDm)
            payload[base + 8] = ((pose.headingCentiDeg shr 8) and 0xFF).toByte()
            payload[base + 9] = (pose.headingCentiDeg and 0xFF).toByte()
            payload[base + 10] = pose.scale100.coerceIn(1, 255).toByte()
        }
        return buildPacket(CMD_ROUTE, 0x01, payload)
    }

    private fun putInt32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    // -------------------------------------------------------------------
    // Street layer (phone -> watch), our own command 0xC7, decoded in dataCallback.
    //   sub 0x00 = forget every street held
    //   sub 0x01 = add: [anchorX m i32][anchorY m i32][count u8] then per street
    //              [class u8][n u8][dx m i16][dy m i16]*n, offsets from the anchor, in the
    //              street map's frame (see StreetMap). Class 0 minor .. 2 major.
    // The watch keeps them and draws them under the route line from the pose in each
    // route frame.
    // -------------------------------------------------------------------

    private const val CMD_STREETS = 0xC7
    /** Payload budget per packet: the library reassembles packets into a 512-byte buffer. */
    const val STREETS_MAX_PAYLOAD = 470

    fun streetBytes(points: Int): Int = 2 + points * 4

    fun buildStreetsClear(): ByteArray = buildPacket(CMD_STREETS, 0x00, ByteArray(0))

    /** Every street's offsets from the anchor must fit in 16 bits (checked by the caller). */
    fun buildStreets(anchorX: Int, anchorY: Int, streets: List<org.chronoscompanion.app.route.StreetMap.Street>): ByteArray {
        val size = 9 + streets.sumOf { streetBytes(it.xs.size) }
        val p = ByteArray(size)
        putInt32(p, 0, anchorX)
        putInt32(p, 4, anchorY)
        p[8] = streets.size.toByte()
        var o = 9
        for (s in streets) {
            p[o++] = s.cls.toByte()
            p[o++] = s.xs.size.toByte()
            for (i in s.xs.indices) {
                val dx = s.xs[i] - anchorX
                val dy = s.ys[i] - anchorY
                p[o++] = ((dx shr 8) and 0xFF).toByte()
                p[o++] = (dx and 0xFF).toByte()
                p[o++] = ((dy shr 8) and 0xFF).toByte()
                p[o++] = (dy and 0xFF).toByte()
            }
        }
        return buildPacket(CMD_STREETS, 0x01, p)
    }

    fun buildRouteClear(): ByteArray = buildPacket(CMD_ROUTE, 0x00, ByteArray(0))

    /** 0xC6: current speed + posted limit while navigating with Google Maps (route mode
     * carries them inside its frames instead). [speed km/h, 255 = unknown][limit, 0 = unknown]. */
    fun buildSpeedInfo(speedKmh: Int?, limitKmh: Int?): ByteArray = buildPacket(0xC6, 0x01, byteArrayOf(
        (speedKmh?.coerceIn(0, 254) ?: 255).toByte(),
        (limitKmh?.coerceIn(1, 254) ?: 0).toByte()))

    // -------------------------------------------------------------------
    // Contacts sync (phone -> watch). Three packet types, per the firmware library's
    // own parser (ChronosESP32.cpp, cases 0xA5 / 0xA2 / 0xA3):
    //   0xA5 = header: how many contacts follow, and which one is the SOS entry
    //   0xA2 = one contact's name,   at position `pos`
    //   0xA3 = one contact's number, at position `pos`
    // The watch fires its "contacts received" callback when it sees the number packet
    // for pos == count-1, so the numbers must be sent last / in order.
    // -------------------------------------------------------------------

    private const val CMD_CONTACT_NAME = 0xA2
    private const val CMD_CONTACT_NUMBER = 0xA3
    private const val CMD_CONTACT_HEADER = 0xA5

    /** [sosIndex] marks one entry as the SOS contact (drawn with a different icon on the
     * watch); pass an out-of-range value like 0xFF for "none". */
    fun buildContactsHeader(count: Int, sosIndex: Int = 0xFF): ByteArray {
        return buildPacket(CMD_CONTACT_HEADER, 0x00, byteArrayOf(sosIndex.toByte(), count.toByte()))
    }

    fun buildContactName(pos: Int, name: String): ByteArray {
        // The firmware appends these bytes straight into a String as raw chars, so keep
        // it to what its fonts can render (same sanitizer the notification path uses).
        val bytes = truncateUtf8(sanitizeForWatch(name), MAX_NAV_FIELD_BYTES).toByteArray(Charsets.UTF_8)
        return buildPacket(CMD_CONTACT_NAME, pos, bytes)
    }

    /**
     * Encodes the number the way the firmware decodes it: each byte holds two digits as
     * hex nibbles but *swapped* (it formats the byte as "%02X" then swaps the two
     * characters), and 'A' stands in for '+'. So the pair "A5" must be stored as 0x5A.
     * An odd digit count is padded with a trailing 'F' nibble, which the firmware drops
     * because it truncates the decoded string to the explicit length byte.
     */
    fun buildContactNumber(pos: Int, number: String): ByteArray {
        val digits = number.filter { it.isDigit() || it == '+' }.map {
            if (it == '+') 'A' else it
        }
        val payload = mutableListOf<Byte>()
        payload.add(digits.size.toByte()) // length, so the watch can drop any padding
        var i = 0
        while (i < digits.size) {
            val first = Character.digit(digits[i], 16)
            val second = if (i + 1 < digits.size) Character.digit(digits[i + 1], 16) else 0x0F
            payload.add(((second shl 4) or first).toByte())
            i += 2
        }
        return buildPacket(CMD_CONTACT_NUMBER, pos, payload.toByteArray())
    }

    /**
     * Builds the "set time" packet (command 0x93). Firmware does:
     *   setTime(sec=data[13], min=data[12], hour=data[11], day=data[10], month=data[9],
     *            year=data[7]*256+data[8])
     * i.e. after the 5-byte header + 1 sub-command byte (both unused/0 here), the
     * payload is [0, yearHi, yearLo, month(1-12), day, hour(0-23), minute, second].
     */
    fun buildSetTime(calendar: java.util.Calendar): ByteArray {
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1 // Calendar is 0-based
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val second = calendar.get(java.util.Calendar.SECOND)

        val payload = byteArrayOf(
            0,
            ((year shr 8) and 0xFF).toByte(),
            (year and 0xFF).toByte(),
            month.toByte(),
            day.toByte(),
            hour.toByte(),
            minute.toByte(),
            second.toByte()
        )
        return buildPacket(CMD_SET_TIME, 0x00, payload)
    }

    /** Builds the raw logical packet bytes for a "navigation active, full data" message. */
    fun buildNavigationData(
        title: String,
        duration: String,
        distance: String,
        eta: String,
        directions: String,
        speed: String,
        hasIcon: Boolean,
        isNavigation: Boolean,
        iconCrc: Long
    ): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.add(if (hasIcon) 1 else 0)
        payload.add(if (isNavigation) 1 else 0)
        payload.add(((iconCrc shr 24) and 0xFF).toByte())
        payload.add(((iconCrc shr 16) and 0xFF).toByte())
        payload.add(((iconCrc shr 8) and 0xFF).toByte())
        payload.add((iconCrc and 0xFF).toByte())
        for (s in listOf(title, duration, distance, eta, directions, speed)) {
            payload.addAll(truncateUtf8(sanitizeForWatch(s), MAX_NAV_FIELD_BYTES).toByteArray(Charsets.UTF_8).toList())
            payload.add(0)
        }
        return buildPacket(CMD_NAV_DATA, NAV_DATA_SUB_ACTIVE, payload.toByteArray())
    }

    /** Stops/clears navigation on the watch (matches the "0x00" sub-command path). */
    fun buildNavigationStop(): ByteArray {
        return buildPacket(CMD_NAV_DATA, 0x00, ByteArray(0))
    }

    /**
     * Builds one 107-byte logical packet per 96-byte chunk (3 chunks total for a
     * 48x48 1bpp = 288-byte icon). [chunkPos] is 0, 1 or 2. [crc] should be the same
     * value across all 3 chunks of one icon (identifies "this is the same icon").
     */
    fun buildNavigationIconChunk(chunkPos: Int, crc: Long, chunkBytes: ByteArray): ByteArray {
        require(chunkBytes.size == 96) { "icon chunk must be exactly 96 bytes" }
        val payload = mutableListOf<Byte>()
        payload.add(chunkPos.toByte())
        payload.add(((crc shr 24) and 0xFF).toByte())
        payload.add(((crc shr 16) and 0xFF).toByte())
        payload.add(((crc shr 8) and 0xFF).toByte())
        payload.add((crc and 0xFF).toByte())
        payload.addAll(chunkBytes.toList())
        // The icon path has no distinct sub-command byte before the payload (data[5] is
        // skipped over/unused by the firmware) - pass 0x00 as a filler byte to occupy it.
        return buildPacket(CMD_NAV_ICON, 0x00, payload.toByteArray())
    }

    /**
     * Packs a 48x48 ARGB [android.graphics.Bitmap]-derived grayscale threshold array
     * (48*48 booleans, row-major, true = lit pixel) into the 288-byte 1bpp buffer the
     * firmware expects: row-major, MSB-first per byte, 6 bytes/row (48/8).
     */
    fun packIconBits(litPixels: BooleanArray): ByteArray {
        require(litPixels.size == 48 * 48)
        val out = ByteArray(288)
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                if (litPixels[y * 48 + x]) {
                    val byteIndex = (y * 48 + x) / 8
                    val bitPos = 7 - (x % 8)
                    out[byteIndex] = (out[byteIndex].toInt() or (1 shl bitPos)).toByte()
                }
            }
        }
        return out
    }

    /**
     * The watch's font only covers ASCII + Latin-1 Supplement (0x20-0x7F, 0xA0-0xFF) -
     * see lv_conf.h/src/fonts_es on the firmware side. Google's notification text
     * commonly uses Unicode spacing/punctuation outside that range (e.g. U+2009 thin
     * space or U+202F narrow no-break space between a number and its unit, "190 m"),
     * which renders as a tofu box on the watch. Replace anything outside that range
     * with a plain space so it degrades gracefully instead of showing a box.
     */
    fun sanitizeForWatch(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val code = ch.code
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                // Force ANY whitespace variant (thin space, narrow NBSP, plain NBSP,
                // etc.) to a regular space - some of these are nominally inside the
                // "safe" Latin-1 range below but still rendered as a tofu box on the
                // watch, so don't rely on the range check alone for whitespace.
                sb.append(' ')
            } else if ((code in 0x21..0x7E) || (code in 0xA1..0xFF)) {
                sb.append(ch)
            } else {
                sb.append(' ')
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /**
     * Truncates [s] to at most [maxBytes] bytes once UTF-8 encoded, without splitting a
     * multi-byte character in half. Needed because the firmware's incoming-packet buffer
     * (ChronosData.data, DATA_SIZE=512) has no bounds checking - an overlong message
     * (especially one inflated by 2-byte accented characters) can overflow it and crash
     * the watch.
     */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var end = maxBytes
        // Back off while we're in the middle of a multi-byte UTF-8 sequence: continuation
        // bytes have the top two bits set to "10" (0x80..0xBF).
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /** Commands the watch's music/volume buttons send to the phone (Control enum in
     * ChronosESP32.h), plus CALL_ACCEPT/CALL_REJECT - our own addition (hi=0xC1), sent by
     * the call screen's new Accept/Reject buttons via a plain ChronosESP32::sendCommand()
     * call (see app_hal.cpp's sendCallAction()) rather than a library-defined Control
     * value, since 0xC1 doesn't collide with the library's reserved 0x9D/0x99 range. */
    enum class WatchCommand {
        MUSIC_PLAY, MUSIC_PAUSE, MUSIC_TOGGLE, MUSIC_PREVIOUS, MUSIC_NEXT,
        VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,
        CALL_ACCEPT, CALL_REJECT,
        /** hi=0xC4: the watch's "Salir" button asks the phone to end Maps navigation. */
        NAVIGATION_STOP
    }

    /** The phone's battery - the ChronosESP32 library's own command 0x91 (it parses
     * data[6] = charging, data[7] = level and fires CF_PBAT). Shown on the watch's
     * control panel next to the watch's own battery. */
    fun buildPhoneBattery(charging: Boolean, level: Int): ByteArray =
        buildPacket(0x91, 0x00, byteArrayOf(if (charging) 1 else 0, level.coerceIn(0, 100).toByte()))

    /** 0xC8, phone -> watch: set the screen brightness (1-255), or with null just ask the
     * watch to report it. */
    fun buildBrightness(level: Int?): ByteArray =
        if (level == null) buildPacket(0xC8, 0x00, ByteArray(0))
        else buildPacket(0xC8, 0x01, byteArrayOf(level.coerceIn(1, 255).toByte()))

    /** 0xC8, watch -> phone: its current brightness (1-255) in the low byte - sent after
     * every change (from the phone or its own settings slider) and when asked. */
    fun decodeBrightness(bytes: ByteArray): Int? {
        if (bytes.size != 7) return null
        if (bytes[0] != 0xAB.toByte() || bytes[3] != 0xFF.toByte() || bytes[5] != 0x80.toByte()) return null
        if ((bytes[4].toInt() and 0xFF) != 0xC8) return null
        return (bytes[6].toInt() and 0xFF).takeIf { it >= 1 }
    }

    // -------------------------------------------------------------------
    // Photo for the watch's "Foto" face (0xCA, ours)
    // -------------------------------------------------------------------

    const val PHOTO_OK = 1
    const val PHOTO_ERR_TRANSFER = 2
    const val PHOTO_ERR_DECODE = 3
    const val PHOTO_ERR_MEMORY = 4

    /** Bytes of a 0xCA data packet that are not JPEG: 6 header, 4 offset, 1 padding. */
    const val PHOTO_DATA_OVERHEAD = 11

    /** The firmware's packet buffer (DATA_SIZE): no packet may be longer. */
    const val MAX_PACKET = 512

    /** Start: [size u32][crc32 u32][where the time goes: 0 top, 1 centre, 2 bottom]. */
    fun buildPhotoStart(size: Int, crc: Long, timePos: Int): ByteArray {
        val p = ByteArray(9)
        putU32(p, 0, size.toLong())
        putU32(p, 4, crc)
        p[8] = timePos.coerceIn(0, 2).toByte()
        return buildPacket(0xCA, 0x00, p)
    }

    /** Data: [offset u32] then jpeg[offset, offset + length). Sent in order. */
    fun buildPhotoData(jpeg: ByteArray, offset: Int, length: Int): ByteArray {
        val p = ByteArray(4 + length)
        putU32(p, 0, offset.toLong())
        jpeg.copyInto(p, destinationOffset = 4, startIndex = offset, endIndex = offset + length)
        return buildPacket(0xCA, 0x01, p)
    }

    fun buildPhotoEnd(): ByteArray = buildPacket(0xCA, 0x02, ByteArray(0))

    /** Back to the face's "send a photo" placeholder. */
    fun buildPhotoRemove(): ByteArray = buildPacket(0xCA, 0x03, ByteArray(0))

    /** Watch -> phone {AB 00 04 FF CA 80 status}: one of the PHOTO_* codes. */
    fun decodePhotoStatus(bytes: ByteArray): Int? {
        if (bytes.size != 7) return null
        if (bytes[0] != 0xAB.toByte() || bytes[3] != 0xFF.toByte() || bytes[5] != 0x80.toByte()) return null
        if ((bytes[4].toInt() and 0xFF) != 0xCA) return null
        return bytes[6].toInt() and 0xFF
    }

    // -------------------------------------------------------------------
    // Firmware update over WiFi (0xCB, ours) - see firmware hal/esp32/navi_ota.cpp
    // -------------------------------------------------------------------

    const val OTA_READY = 1
    const val OTA_ERR_BATTERY = 2
    const val OTA_ERR_WIFI = 3
    const val OTA_ERR_TIMEOUT = 4
    const val OTA_ERR_FAILED = 5
    const val OTA_UPDATED = 6
    const val OTA_ROLLED_BACK = 7
    const val OTA_CANCELLED = 8

    /** Open the board's access point [ssid] with WPA2 [password] (8-63 ASCII). */
    fun buildOtaStart(ssid: String, password: String): ByteArray {
        val s = ssid.toByteArray(Charsets.US_ASCII)
        val p = password.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(2 + s.size + p.size)
        payload[0] = s.size.toByte()
        s.copyInto(payload, 1)
        payload[1 + s.size] = p.size.toByte()
        p.copyInto(payload, 2 + s.size)
        return buildPacket(0xCB, 0x00, payload)
    }

    fun buildOtaCancel(): ByteArray = buildPacket(0xCB, 0x01, ByteArray(0))

    /** Watch -> phone {AB 00 04 FF CB 80 status}: one of the OTA_* codes. */
    fun decodeOtaStatus(bytes: ByteArray): Int? = decodeOwnStatus(bytes, 0xCB)

    // -------------------------------------------------------------------
    // Crash detection (0xCC, ours) - see firmware hal/esp32/navi_crash.cpp
    // -------------------------------------------------------------------

    const val CRASH_CANCELLED = 0
    const val CRASH_SOS = 1
    const val CRASH_COUNTDOWN = 2
    const val CRASH_TEST_SOS = 3
    const val CRASH_TEST_COUNTDOWN = 4

    fun buildCrashConfig(enabled: Boolean): ByteArray =
        buildPacket(0xCC, 0x00, byteArrayOf(if (enabled) 1 else 0))

    /** The board runs a short test countdown and then asks for the test message. */
    fun buildCrashTest(): ByteArray = buildPacket(0xCC, 0x01, ByteArray(0))

    /** "Estoy bien" pressed on the phone: closes the board's countdown. */
    fun buildCrashCancel(): ByteArray = buildPacket(0xCC, 0x02, ByteArray(0))

    /** How many contacts got the SOS (0 = none), shown on the board. */
    fun buildCrashResult(sent: Int): ByteArray =
        buildPacket(0xCC, 0x03, byteArrayOf(sent.coerceIn(0, 255).toByte()))

    /** GPS speed for the crash detector: the watch only counts an impact if the bike was
     * moving (firmware navi_crash.cpp, CRASH_MOVING_KMH). */
    fun buildCrashSpeed(kmh: Int): ByteArray =
        buildPacket(0xCC, 0x04, byteArrayOf(kmh.coerceIn(0, 255).toByte()))

    /** Watch -> phone {AB 00 04 FF CC 80 event}: one of the CRASH_* codes. */
    fun decodeCrashEvent(bytes: ByteArray): Int? = decodeOwnStatus(bytes, 0xCC)

    // -------------------------------------------------------------------
    // Test navigation (0xCD, ours) - see firmware trackTrip() in app_hal.cpp
    // -------------------------------------------------------------------

    /** True before the route demo's first navigation packet, false after its stop: the
     * watch then leaves the demo out of its last-trip statistics (firmware 2.4+; older
     * firmware ignores it). */
    fun buildTestNavigation(test: Boolean): ByteArray =
        buildPacket(0xCD, 0x00, byteArrayOf(if (test) 1 else 0))

    private fun decodeOwnStatus(bytes: ByteArray, command: Int): Int? {
        if (bytes.size != 7) return null
        if (bytes[0] != 0xAB.toByte() || bytes[3] != 0xFF.toByte() || bytes[5] != 0x80.toByte()) return null
        if ((bytes[4].toInt() and 0xFF) != command) return null
        return bytes[6].toInt() and 0xFF
    }

    private fun putU32(p: ByteArray, o: Int, v: Long) {
        p[o] = ((v shr 24) and 0xFF).toByte()
        p[o + 1] = ((v shr 16) and 0xFF).toByte()
        p[o + 2] = ((v shr 8) and 0xFF).toByte()
        p[o + 3] = (v and 0xFF).toByte()
    }

    /** "Dial the contact at this index" - hi=0xC2, the index in the low byte. Separate
     * from [WatchCommand] because it carries a value rather than being a fixed action. */
    fun decodeDialContactIndex(bytes: ByteArray): Int? {
        if (bytes.size != 7) return null
        if (bytes[0] != 0xAB.toByte() || bytes[3] != 0xFF.toByte() || bytes[5] != 0x80.toByte()) return null
        if ((bytes[4].toInt() and 0xFF) != 0xC2) return null
        return bytes[6].toInt() and 0xFF
    }

    /**
     * Decodes a watch->phone control packet received as a TX characteristic notification.
     * Mirrors ChronosESP32::musicControl(), which sends exactly 7 raw bytes (no chunking,
     * no 0xFE app-header - that marker is phone->watch only):
     *   [0xAB, 0x00, 0x04, 0xFF, controlEnum >> 8, 0x80, controlEnum & 0xFF]
     */
    fun decodeWatchCommand(bytes: ByteArray): WatchCommand? {
        if (bytes.size != 7) return null
        if (bytes[0] != 0xAB.toByte() || bytes[3] != 0xFF.toByte() || bytes[5] != 0x80.toByte()) return null
        val hi = bytes[4].toInt() and 0xFF
        val lo = bytes[6].toInt() and 0xFF
        return when {
            hi == 0x9D && lo == 0x00 -> WatchCommand.MUSIC_PLAY
            hi == 0x9D && lo == 0x01 -> WatchCommand.MUSIC_PAUSE
            hi == 0x9D && lo == 0x02 -> WatchCommand.MUSIC_PREVIOUS
            hi == 0x9D && lo == 0x03 -> WatchCommand.MUSIC_NEXT
            hi == 0xC1 && lo == 0x01 -> WatchCommand.CALL_ACCEPT
            hi == 0xC1 && lo == 0x02 -> WatchCommand.CALL_REJECT
            hi == 0xC4 && lo == 0x01 -> WatchCommand.NAVIGATION_STOP
            hi == 0x99 && lo == 0x00 -> WatchCommand.MUSIC_TOGGLE
            hi == 0x99 && lo == 0xA1 -> WatchCommand.VOLUME_UP
            hi == 0x99 && lo == 0xA2 -> WatchCommand.VOLUME_DOWN
            hi == 0x99 && lo == 0xA3 -> WatchCommand.VOLUME_MUTE
            else -> null
        }
    }

    fun crc32Of(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /** Wraps [command]/[subCommand]/[payload] into the [header] marker (0xAB for regular
     * commands, 0xEA for the extended weather sub-commands) and splits into BLE-sized chunks. */
    private fun buildPacket(command: Int, subCommand: Int, payload: ByteArray, header: Int = 0xAB): ByteArray {
        val body = ByteArray(2 + payload.size)
        body[0] = command.toByte()
        body[1] = subCommand.toByte()
        payload.copyInto(body, destinationOffset = 2)

        val totalLen = 5 + body.size
        val lenField = totalLen - 3
        val full = ByteArray(totalLen)
        full[0] = header.toByte()
        full[1] = ((lenField shr 8) and 0xFF).toByte()
        full[2] = (lenField and 0xFF).toByte()
        full[3] = 0xFE.toByte()
        body.copyInto(full, destinationOffset = 4)
        return full
    }

    // -------------------------------------------------------------------
    // Weather
    // -------------------------------------------------------------------

    /** One day's icon (0-7, see [[decodeWatchCommand]] sibling note below) and temperature. */
    data class WeatherDay(val icon: Int, val temp: Int, val high: Int, val low: Int)

    /**
     * Daily icon+temp forecast, command 0x7E under the 0xAB header. Firmware parses pairs of
     * [(icon<<4)|signBit, abs(temp)] starting at data[6], one pair per day, and derives the
     * day-of-week itself from the current date - it does not need day sent explicitly.
     * Icon values 0-7 verified by decoding the actual watch icon images (SquareLine assets
     * dy-0..dy-7): 0=partly cloudy, 1=sunny, 2=snow, 3=rain, 4=cloudy, 5=thunderstorm, 6=fog,
     * 7=sunny with light rain.
     */
    fun buildWeatherDaily(days: List<WeatherDay>): ByteArray {
        val payload = mutableListOf<Byte>()
        for (d in days) {
            val signBit = if (d.temp < 0) 1 else 0
            payload.add((((d.icon and 0x0F) shl 4) or signBit).toByte())
            payload.add((kotlin.math.abs(d.temp) and 0xFF).toByte())
        }
        return buildPacket(0x7E, 0x00, payload.toByteArray())
    }

    /** High/low per day, command 0x88. Firmware: signH=bit7 of byte0, signL=bit7 of byte1. */
    fun buildWeatherHighLow(days: List<WeatherDay>): ByteArray {
        val payload = mutableListOf<Byte>()
        for (d in days) {
            val highByte = (if (d.high < 0) 0x80 else 0) or (kotlin.math.abs(d.high) and 0x7F)
            val lowByte = (if (d.low < 0) 0x80 else 0) or (kotlin.math.abs(d.low) and 0x7F)
            payload.add(highByte.toByte())
            payload.add(lowByte.toByte())
        }
        return buildPacket(0x88, 0x00, payload.toByteArray())
    }

    /** UV index + sea-level pressure (hPa) for today only, command 0x8A. */
    fun buildWeatherUvPressure(uvIndex: Int, pressureHpa: Int): ByteArray {
        val payload = byteArrayOf(
            (uvIndex and 0xFF).toByte(),
            ((pressureHpa shr 8) and 0xFF).toByte(),
            (pressureHpa and 0xFF).toByte()
        )
        return buildPacket(0x8A, 0x00, payload)
    }

    /** City name shown on the weather screen - uses the 0xEA header, command 0x7E, sub 0x01. */
    fun buildWeatherCity(city: String): ByteArray {
        val payload = mutableListOf<Byte>(0x00)
        payload.addAll(truncateUtf8(sanitizeForWatch(city), MAX_NAV_FIELD_BYTES).toByteArray(Charsets.UTF_8).toList())
        return buildPacket(0x7E, 0x01, payload.toByteArray(), header = 0xEA)
    }

    /**
     * 0xC9 (ours): weather the library's protocol doesn't carry, for the watch's Navi Moto
     * faces - [sunrise h][sunrise m][sunset h][sunset m] (255 = unknown) then 24 x [chance
     * of rain %, 255 = unknown] by hour of day.
     */
    fun buildWeatherExtra(sunriseMin: Int, sunsetMin: Int, rainByHour: IntArray): ByteArray {
        val p = ByteArray(4 + 24)
        fun hm(min: Int, o: Int) {
            p[o] = (if (min < 0) 255 else min / 60).toByte()
            p[o + 1] = (if (min < 0) 255 else min % 60).toByte()
        }
        hm(sunriseMin, 0)
        hm(sunsetMin, 2)
        for (h in 0 until 24) {
            val r = rainByHour.getOrElse(h) { -1 }
            p[4 + h] = (if (r in 0..100) r else 255).toByte()
        }
        return buildPacket(0xC9, 0x01, p)
    }

    /** One hour's forecast for [buildWeatherHourly]. [hour] is 0-23 (hour of day). */
    data class HourlyData(val hour: Int, val icon: Int, val temp: Int, val windKmh: Int, val humidity: Int, val uv: Int)

    /**
     * Hourly forecast - uses the 0xEA header, command 0x7E, sub 0x02. Firmware parses:
     * data[6]=count, data[7]=startHour, then 6 bytes per hour starting at data[8]:
     * [(icon<<4)|signBit, abs(temp), windHi, windLo, humidity, uv]. [hours] must be
     * sorted ascending by hour and (per the firmware's `hour+z` indexing) is assumed
     * contiguous starting at its first entry's hour.
     */
    fun buildWeatherHourly(hours: List<HourlyData>): ByteArray {
        if (hours.isEmpty()) return buildPacket(0x7E, 0x02, byteArrayOf(0, 0), header = 0xEA)
        val sorted = hours.sortedBy { it.hour }
        val payload = mutableListOf<Byte>()
        payload.add(sorted.size.toByte())
        payload.add(sorted.first().hour.toByte())
        for (h in sorted) {
            val signBit = if (h.temp < 0) 1 else 0
            payload.add((((h.icon and 0x0F) shl 4) or signBit).toByte())
            payload.add((kotlin.math.abs(h.temp) and 0xFF).toByte())
            payload.add(((h.windKmh shr 8) and 0xFF).toByte())
            payload.add((h.windKmh and 0xFF).toByte())
            payload.add((h.humidity and 0xFF).toByte())
            payload.add((h.uv and 0xFF).toByte())
        }
        return buildPacket(0x7E, 0x02, payload.toByteArray(), header = 0xEA)
    }

    /** Splits a logical packet (as produced by [buildPacket]) into the chunks to send over BLE, in order. */
    fun chunk(logicalPacket: ByteArray): List<ByteArray> {
        if (logicalPacket.size <= 20) {
            return listOf(logicalPacket)
        }
        val chunks = mutableListOf<ByteArray>()
        chunks.add(logicalPacket.copyOfRange(0, 20))
        var offset = 20
        var index = 0
        while (offset < logicalPacket.size) {
            val end = minOf(offset + 19, logicalPacket.size)
            val slice = logicalPacket.copyOfRange(offset, end)
            val out = ByteArray(1 + slice.size)
            out[0] = index.toByte()
            slice.copyInto(out, destinationOffset = 1)
            chunks.add(out)
            offset = end
            index++
        }
        return chunks
    }
}
