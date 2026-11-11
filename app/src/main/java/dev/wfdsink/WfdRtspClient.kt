package dev.wfdsink

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Miracast (WFD) sink-side RTSP client.
 * The SOURCE (phone/tablet) runs the RTSP server on port 7236 at the P2P group owner IP;
 * the sink connects out to it.
 *
 * Flow: source M1 (OPTIONS) -> sink M2 (OPTIONS) -> source M3 (GET_PARAMETER)
 *       -> source M4 (SET_PARAMETER) -> source M5 (trigger SETUP)
 *       -> sink M6 (SETUP) -> sink M7 (PLAY) -> RTP/MPEG-TS arrives on UDP [rtpPort].
 *
 * Capability strings below are a conservative starting point and are UNTESTED.
 */
class WfdRtspClient(
    private val sourceIp: String,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000,
    private val onPlaying: (rtpPort: Int) -> Unit,
    private val onClosed: () -> Unit = {}
) : Thread("wfd-rtsp") {

    private class Msg(val startLine: String, val headers: Map<String, String>, val body: String) {
        val isResponse get() = startLine.startsWith("RTSP/")
        val cseq get() = headers["cseq"] ?: "0"
        val method get() = startLine.substringBefore(' ')
    }

    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream
    private var nextCseq = 100
    private var pending = ""
    private var presentationUrl = "rtsp://$sourceIp/wfd1.0/streamid=0"
    private var session = ""
    @Volatile private var socket: Socket? = null

    // Parameters we can answer for M3. Tune the video mask per device.
    // wfd_video_formats: native-res, preferred-display-mode, profile(01=CBP), level(01=3.1),
    // CEA mask (0x60 = 720p30 + 720p60), VESA, HH, latency, min-slice, slice-enc, frame-skip, none none
    private val caps = mapOf(
        "wfd_video_formats" to "00 00 01 01 00000060 00000000 00000000 00 0000 0000 00 none none",
        "wfd_audio_codecs" to "LPCM 00000002 00",
        "wfd_client_rtp_ports" to "RTP/AVP/UDP;unicast $rtpPort 0 mode=play",
        "wfd_content_protection" to "none"
    )

    fun close() { try { socket?.close() } catch (_: Exception) {} }

    override fun run() {
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(sourceIp, port), 10_000)
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
            while (true) {
                val m = readMsg() ?: break
                if (m.isResponse) handleResponse(m) else if (!handleRequest(m)) break
            }
        } catch (e: Exception) {
            android.util.Log.w("WFD", "rtsp error", e)
        } finally {
            close(); onClosed()
        }
    }

    private fun readMsg(): Msg? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
        }
        val lines = sb.toString().trim().split("\r\n")
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i > 0) l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim() else null
        }.toMap()
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        var body = ""
        if (len > 0) {
            val buf = ByteArray(len)
            var r = 0
            while (r < len) {
                val n = input.read(buf, r, len - r)
                if (n < 0) return null
                r += n
            }
            body = String(buf, Charsets.UTF_8)
        }
        return Msg(lines[0], headers, body)
    }

    private fun send(text: String) {
        android.util.Log.d("WFD", ">>> " + text.trim())
        output.write(text.toByteArray(Charsets.UTF_8)); output.flush()
    }

    private fun reply(cseq: String, extra: String = "", body: String = "") {
        val b = if (body.isEmpty()) "" else "Content-Type: text/parameters\r\nContent-Length: ${body.toByteArray().size}\r\n"
        send("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extra$b\r\n$body")
    }

    private fun request(method: String, uri: String, extra: String = "") {
        send("$method $uri RTSP/1.0\r\nCSeq: ${nextCseq++}\r\n$extra\r\n")
    }

    /** Returns false when the session should end. */
    private fun handleRequest(m: Msg): Boolean {
        android.util.Log.d("WFD", "<<< " + m.startLine + " | " + m.body.trim())
        when (m.method) {
            "OPTIONS" -> { // M1
                reply(m.cseq, "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n")
                pending = "M2"
                request("OPTIONS", "*", "Require: org.wfa.wfd1.0\r\n") // M2
            }
            "GET_PARAMETER" -> { // M3, or M16 keepalive when body is empty
                if (m.body.isBlank()) { reply(m.cseq); return true }
                val wanted = m.body.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val out = StringBuilder()
                for (k in wanted) caps[k]?.let { out.append("$k: $it\r\n") }
                reply(m.cseq, body = out.toString())
            }
            "SET_PARAMETER" -> { // M4 / M5 / later control
                Regex("wfd_presentation_URL:\\s*(\\S+)").find(m.body)?.let { presentationUrl = it.groupValues[1] }
                reply(m.cseq)
                if (m.body.contains("wfd_trigger_method: SETUP")) { // M5
                    pending = "M6"
                    request(
                        "SETUP", presentationUrl,
                        "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort\r\n"
                    )
                } else if (m.body.contains("wfd_trigger_method: TEARDOWN")) return false
            }
            "TEARDOWN" -> { reply(m.cseq); return false }
            else -> reply(m.cseq)
        }
        return true
    }

    private fun handleResponse(m: Msg) {
        android.util.Log.d("WFD", "<<< " + m.startLine)
        when (pending) {
            "M6" -> {
                session = (m.headers["session"] ?: "").substringBefore(';')
                pending = "M7"
                request("PLAY", presentationUrl, "Session: $session\r\n")
            }
            "M7" -> { pending = ""; onPlaying(rtpPort) }
        }
    }
}
