package ge.momento.booth

/** Pure-Kotlin MPEG-TS framing helper; accepts typical 188-byte TS packets. */
object MpegTsPackets {
    data class Payload(val offset: Int, val length: Int)
    fun findPayload(bytes: ByteArray, count: Int): Payload? {
        if (count < 188 || count > bytes.size) return null
        for (i in 0..minOf(16, count - 188)) {
            if ((bytes[i].toInt() and 0xff) != 0x47) continue
            val length = ((count - i) / 188) * 188
            if (length < 188) continue
            // Check a second sync byte when there are multiple TS packets.
            if (length >= 376 && (bytes[i + 188].toInt() and 0xff) != 0x47) continue
            return Payload(i, length)
        }
        return null
    }
}
