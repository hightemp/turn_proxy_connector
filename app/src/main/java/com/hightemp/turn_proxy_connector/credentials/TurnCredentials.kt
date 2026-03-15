package com.hightemp.turn_proxy_connector.credentials

/**
 * Holds TURN server credentials obtained from VK or Yandex.
 */
data class TurnCredentials(
    val username: String,
    val password: String,
    val serverAddress: String // host:port
) {
    val host: String
        get() {
            val idx = serverAddress.lastIndexOf(':')
            return if (idx > 0) serverAddress.substring(0, idx) else serverAddress
        }

    val port: Int
        get() {
            val idx = serverAddress.lastIndexOf(':')
            return if (idx > 0) {
                serverAddress.substring(idx + 1).toIntOrNull() ?: 3478
            } else {
                3478
            }
        }
}

/**
 * Interface for fetching TURN credentials from a service.
 */
interface CredentialFetcher {
    /**
     * Fetch TURN credentials. This is a blocking network call.
     * @return TurnCredentials or null on failure
     */
    fun fetch(): TurnCredentials?
}

/**
 * Factory to create the appropriate CredentialFetcher based on a link URL.
 */
object CredentialFetcherFactory {
    fun create(link: String): CredentialFetcher? {
        return when {
            link.contains("vk.com/call") || link.contains("vk.ru/call") ->
                VkCredentialFetcher(link)
            link.contains("telemost.yandex") ->
                YandexCredentialFetcher(link)
            else -> null
        }
    }
}
