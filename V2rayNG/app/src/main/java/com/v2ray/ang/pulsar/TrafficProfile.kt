package com.v2ray.ang.pulsar

/**
 * Модели для имитации ритма трафика
 */
data class BurstModel(
    val pauseMsRange: IntRange,
    val triggerThresholdBytes: Long
)

sealed class TrafficProfile(
    val profileName: String,
    val bucketSizeBytes: IntRange,
    val iatMeanMs: Long,
    val iatStdDevMs: Long,
    val sessionDurationSecs: IntRange,
    val interSessionGapSecs: IntRange,
    val burstModel: BurstModel
) {
    object OzonScroll : TrafficProfile(
        profileName = "ozon_scroll",
        bucketSizeBytes = 8192..14336,
        iatMeanMs = 45L,
        iatStdDevMs = 20L,
        sessionDurationSecs = 30..90,
        interSessionGapSecs = 3..12,
        burstModel = BurstModel(pauseMsRange = 200..800, triggerThresholdBytes = 204800L)
    )

    object VkFeed : TrafficProfile(
        profileName = "vk_feed",
        bucketSizeBytes = 4096..10240,
        iatMeanMs = 30L,
        iatStdDevMs = 15L,
        sessionDurationSecs = 45..120,
        interSessionGapSecs = 2..8,
        burstModel = BurstModel(pauseMsRange = 100..500, triggerThresholdBytes = 102400L)
    )

    object YouTube4K : TrafficProfile(
        profileName = "youtube_4k",
        bucketSizeBytes = 49152..65536,
        iatMeanMs = 8L,
        iatStdDevMs = 3L,
        sessionDurationSecs = 300..1800,
        interSessionGapSecs = 1..3,
        burstModel = BurstModel(pauseMsRange = 1500..4000, triggerThresholdBytes = 5242880L)
    )
}

enum class NetworkType {
    WIFI, MOBILE, UNKNOWN
}
