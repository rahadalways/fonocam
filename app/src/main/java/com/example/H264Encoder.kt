package com.example

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import androidx.camera.core.ImageProxy

/**
 * Hardware H.264 encoder fed with camera frames.
 * Emits Annex-B NAL units via [onEncoded]; config data (SPS/PPS) is flagged
 * so the server can replay it to every new client.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    bitrate: Int,
    fps: Int,
    private val onEncoded: (data: ByteArray, isConfig: Boolean) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufInfo = MediaCodec.BufferInfo()
    @Volatile
    private var running = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        running = true
    }

    /** Ask for an IDR frame so a newly connected client can start decoding. */
    fun requestKeyFrame() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun encode(proxy: ImageProxy) {
        if (!running) return
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val image = codec.getInputImage(inIndex)
                if (image != null) {
                    copyYuv(proxy, image)
                    codec.queueInputBuffer(
                        inIndex, 0, width * height * 3 / 2,
                        System.nanoTime() / 1000, 0
                    )
                } else {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                }
            }
            drain()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyYuv(src: ImageProxy, dst: android.media.Image) {
        // plane 0 = Y (full res), 1 = U, 2 = V (half res)
        for (p in 0..2) {
            val s = src.planes[p]
            val d = dst.planes[p]
            val w = if (p == 0) width else width / 2
            val h = if (p == 0) height else height / 2
            val sBuf = s.buffer
            val dBuf = d.buffer
            if (s.pixelStride == 1 && d.pixelStride == 1) {
                // fast path: bulk row copies
                val row = ByteArray(w)
                for (y in 0 until h) {
                    sBuf.position(y * s.rowStride)
                    sBuf.get(row, 0, w)
                    dBuf.position(y * d.rowStride)
                    dBuf.put(row, 0, w)
                }
            } else {
                for (y in 0 until h) {
                    val sRow = y * s.rowStride
                    val dRow = y * d.rowStride
                    for (x in 0 until w) {
                        dBuf.put(dRow + x * d.pixelStride, sBuf.get(sRow + x * s.pixelStride))
                    }
                }
            }
        }
    }

    private fun drain() {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufInfo, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (outIndex < 0) continue
            val buf = codec.getOutputBuffer(outIndex)
            if (buf != null && bufInfo.size > 0) {
                val data = ByteArray(bufInfo.size)
                buf.position(bufInfo.offset)
                buf.get(data)
                val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                onEncoded(data, isConfig)
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    fun release() {
        running = false
        try { codec.stop() } catch (e: Exception) { e.printStackTrace() }
        try { codec.release() } catch (e: Exception) { e.printStackTrace() }
    }
}
