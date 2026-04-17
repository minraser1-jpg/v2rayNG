package com.v2ray.ang.pulsar

/**
 * Интеллектуальный выбор профиля на основе метаданных трафика
 */
class ProfileAutoSelector {

    fun selectProfile(
        destinationHost: String,
        avgChunkSize: Long,
        networkType: NetworkType
    ): TrafficProfile {
        return when {
            // Если чанки тяжелые (>500 КБ) — режим стриминга
            avgChunkSize > 500_000 -> TrafficProfile.YouTube4K
            
            // Маскировка под российские маркетплейсы
            destinationHost.endsWith(".ru") || 
            destinationHost.contains("wildberries") -> TrafficProfile.OzonScroll
            
            // Экономичный режим для мобильных сетей
            networkType == NetworkType.MOBILE -> TrafficProfile.VkFeed
            
            else -> TrafficProfile.OzonScroll
        }
    }
}
