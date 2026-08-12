package com.kitsuneandroid

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

internal enum class PlaybackSupport {
    HARDWARE,
    SOFTWARE,
    UNSUPPORTED,
    UNKNOWN
}

internal data class PlaybackCapabilities(
    val h264: PlaybackSupport,
    val hevc: PlaybackSupport,
    val hevcTenBit: PlaybackSupport,
    val av1: PlaybackSupport,
    val av1TenBit: PlaybackSupport
) {
    fun supportFor(release: ParsedRelease): PlaybackSupport {
        return when (release.codec) {
            "H264" -> if (release.tenBit) PlaybackSupport.UNSUPPORTED else h264
            "HEVC" -> if (release.tenBit) hevcTenBit else hevc
            "AV1" -> if (release.tenBit) av1TenBit else av1
            else -> PlaybackSupport.UNKNOWN
        }
    }

    companion object {
        fun commonAndroid(): PlaybackCapabilities {
            return PlaybackCapabilities(
                h264 = PlaybackSupport.HARDWARE,
                hevc = PlaybackSupport.UNKNOWN,
                hevcTenBit = PlaybackSupport.UNKNOWN,
                av1 = PlaybackSupport.UNKNOWN,
                av1TenBit = PlaybackSupport.UNKNOWN
            )
        }

        @SuppressLint("InlinedApi")
        fun detect(): PlaybackCapabilities {
            val codecs = try {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .filterNot(MediaCodecInfo::isEncoder)
            } catch (_: Exception) {
                return commonAndroid()
            }

            return PlaybackCapabilities(
                h264 = detectCodec(codecs, "video/avc"),
                hevc = detectCodec(codecs, "video/hevc"),
                hevcTenBit = detectCodec(
                    codecs,
                    "video/hevc",
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                ),
                av1 = detectCodec(codecs, "video/av01"),
                av1TenBit = detectCodec(
                    codecs,
                    "video/av01",
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                )
            )
        }
    }
}

private fun detectCodec(
    codecs: List<MediaCodecInfo>,
    mimeType: String,
    requiredProfile: Int? = null
): PlaybackSupport {
    val matchingCodecs = codecs.filter { codec ->
        codec.supportedTypes.any { type -> type.equals(mimeType, ignoreCase = true) }
    }

    if (matchingCodecs.isEmpty()) {
        return PlaybackSupport.UNSUPPORTED
    }

    val compatibleCodecs = if (requiredProfile == null) {
        matchingCodecs
    } else {
        matchingCodecs.filter { codec ->
            try {
                codec.getCapabilitiesForType(mimeType)
                    .profileLevels
                    .any { profile -> profile.profile == requiredProfile }
            } catch (_: Exception) {
                false
            }
        }
    }

    if (compatibleCodecs.isEmpty()) {
        return PlaybackSupport.UNSUPPORTED
    }

    val hasHardwareDecoder = compatibleCodecs.any { codec ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || codec.isHardwareAccelerated
    }

    return if (hasHardwareDecoder) {
        PlaybackSupport.HARDWARE
    } else {
        PlaybackSupport.SOFTWARE
    }
}

internal data class CompatibilityScore(
    val points: Int,
    val reason: String
)

internal fun codecCompatibilityScore(
    release: ParsedRelease,
    capabilities: PlaybackCapabilities
): CompatibilityScore {
    return when (capabilities.supportFor(release)) {
        PlaybackSupport.HARDWARE -> CompatibilityScore(
            points = 10,
            reason = "Decodificação por hardware disponível"
        )

        PlaybackSupport.SOFTWARE -> CompatibilityScore(
            points = 2,
            reason = "Compatível por software; pode consumir mais bateria"
        )

        PlaybackSupport.UNSUPPORTED -> CompatibilityScore(
            points = -50,
            reason = "Codec ou perfil incompatível com este aparelho"
        )

        PlaybackSupport.UNKNOWN -> CompatibilityScore(
            points = if (release.tenBit) -12 else 0,
            reason = if (release.tenBit) {
                "Perfil 10-bit sem compatibilidade confirmada"
            } else {
                "Compatibilidade do codec não confirmada"
            }
        )
    }
}
