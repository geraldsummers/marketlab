package dev.marketlab.social

import dev.marketlab.contracts.InstrumentId
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class AssetAlias(
    val symbol: String,
    val names: Set<String>,
    val ambiguousBareSymbol: Boolean = false,
)

class CryptoEntityResolver(
    initialAliases: List<AssetAlias> = DEFAULT_ALIASES,
) {
    private val aliases = AtomicReference(normalize(initialAliases))

    fun replaceAliases(values: List<AssetAlias>) {
        require(values.isNotEmpty()) { "entity alias set cannot be empty" }
        aliases.set(normalize(values))
    }

    fun resolve(text: String): List<InstrumentId> {
        val normalizedText = text.lowercase(Locale.ROOT)
        val tokens =
            TOKEN.findAll(normalizedText)
                .map { it.value }
                .toSet()
        return aliases
            .get()
            .asSequence()
            .filter { alias ->
                val symbol = alias.symbol.lowercase(Locale.ROOT)
                val cashtag = "\$$symbol"
                cashtag in tokens ||
                    (!alias.ambiguousBareSymbol && symbol in tokens) ||
                    alias.names.any { name -> containsPhrase(normalizedText, name) }
            }
            .map { InstrumentId("hyperliquid:perpetual:${it.symbol}") }
            .distinct()
            .sortedBy(InstrumentId::value)
            .toList()
    }

    private fun normalize(values: List<AssetAlias>): List<AssetAlias> =
        values
            .map {
                require(SYMBOL.matches(it.symbol)) { "invalid asset alias symbol" }
                AssetAlias(
                    symbol = it.symbol.uppercase(Locale.ROOT),
                    names =
                        it.names
                            .map { name -> name.trim().lowercase(Locale.ROOT) }
                            .filter(String::isNotEmpty)
                            .toSet(),
                    ambiguousBareSymbol = it.ambiguousBareSymbol,
                )
            }
            .distinctBy(AssetAlias::symbol)
            .sortedBy(AssetAlias::symbol)

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val match = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])")
        return match.containsMatchIn(text)
    }

    companion object {
        private val SYMBOL = Regex("[A-Z0-9][A-Z0-9._:-]{0,63}")
        private val TOKEN = Regex("\\$?[\\p{L}\\p{N}._:-]+")
        val DEFAULT_ALIASES =
            listOf(
                AssetAlias("BTC", setOf("bitcoin")),
                AssetAlias("ETH", setOf("ethereum", "ether")),
                AssetAlias("SOL", setOf("solana")),
                AssetAlias("HYPE", setOf("hyperliquid"), ambiguousBareSymbol = true),
                AssetAlias("XRP", setOf("xrp", "ripple")),
                AssetAlias("DOGE", setOf("dogecoin")),
                AssetAlias("BNB", setOf("bnb", "binance coin")),
                AssetAlias("ADA", setOf("cardano"), ambiguousBareSymbol = true),
                AssetAlias("AVAX", setOf("avalanche")),
                AssetAlias("LINK", setOf("chainlink"), ambiguousBareSymbol = true),
            )
    }
}
