package com.shiko.pokedex.repository

import android.content.Context
import android.graphics.Bitmap
import com.shiko.pokedex.camera.CardTextParser
import com.shiko.pokedex.camera.TextExtractor
import com.shiko.pokedex.data.AppDatabase
import com.shiko.pokedex.data.CardPriceCacheEntity
import com.shiko.pokedex.data.LocalCardIndex
import com.shiko.pokedex.network.PokemonTcgPrices
import com.shiko.pokedex.network.RetrofitClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.TimeUnit

/** A price that's either known, or unavailable for a specific, shown reason. */
sealed class PriceValue {
    data class Available(val amount: Double) : PriceValue()
    data class Unavailable(val reason: String) : PriceValue()
}

data class ScannedCard(
    val name: String,
    val setName: String,
    val cardNumber: String,
    val imageUrl: String?,
    val rawPrice: PriceValue,
    /** True if the OCR name matched more than one card and nothing (number/HP) disambiguated it. */
    val isApproximateMatch: Boolean
)

sealed class ScanResult {
    data class Success(val card: ScannedCard) : ScanResult()
    object NoCardRecognized : ScanResult()
    data class Error(val message: String) : ScanResult()
}

class CardRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).cardPriceCacheDao()
    private val cacheTtlMillis = TimeUnit.HOURS.toMillis(24)

    fun identifyAndPrice(cardBitmap: Bitmap): Flow<ScanResult> = flow {
        val identified = try {
            identify(cardBitmap)
        } catch (e: Exception) {
            emit(ScanResult.Error("Identification failed: ${e.message}"))
            return@flow
        }
        if (identified == null) {
            emit(ScanResult.NoCardRecognized)
            return@flow
        }

        val cacheKey = buildKey(identified.name, identified.setName, identified.cardNumber)
        val cached = dao.get(cacheKey)
        val fresh = cached != null && (System.currentTimeMillis() - cached.fetchedAtMillis) < cacheTtlMillis

        val rawPrice = if (fresh && cached != null) {
            cached.rawPrice?.let { PriceValue.Available(it) } ?: identified.rawPrice
        } else {
            dao.upsert(
                CardPriceCacheEntity(
                    cardKey = cacheKey,
                    name = identified.name,
                    setName = identified.setName,
                    cardNumber = identified.cardNumber,
                    imageUrl = identified.imageUrl,
                    rawPrice = (identified.rawPrice as? PriceValue.Available)?.amount,
                    psa9Price = null,
                    psa10Price = null,
                    fetchedAtMillis = System.currentTimeMillis()
                )
            )
            identified.rawPrice
        }

        emit(
            ScanResult.Success(
                ScannedCard(
                    name = identified.name,
                    setName = identified.setName,
                    cardNumber = identified.cardNumber,
                    imageUrl = identified.imageUrl,
                    rawPrice = rawPrice,
                    isApproximateMatch = identified.isApproximateMatch
                )
            )
        )
    }

    private data class IdentifiedCard(
        val name: String,
        val setName: String,
        val cardNumber: String,
        val imageUrl: String?,
        val rawPrice: PriceValue,
        val isApproximateMatch: Boolean
    )

    /**
     * No API key, no network required to identify: OCR reads the card, then
     * matches against the bundled offline index (20k cards) using name +
     * collector number + HP to disambiguate cards that share a name.
     */
    private suspend fun identify(bitmap: Bitmap): IdentifiedCard? {
        LocalCardIndex.ensureLoaded(appContext)

        val lines = TextExtractor.extractLines(bitmap)
        val parsed = CardTextParser.parse(lines) ?: return null

        val match = LocalCardIndex.findBest(parsed.nameCandidates, parsed.collectorNumber, parsed.hp)
            ?: return null
        val entry = match.entry

        val liveCard = retrying { RetrofitClient.pokemonTcgIoApi.getCard(entry.id).data }

        if (liveCard == null) {
            return IdentifiedCard(
                name = entry.name,
                setName = entry.setName,
                cardNumber = entry.number,
                imageUrl = "https://images.pokemontcg.io/${entry.setId}/${entry.number}.png",
                rawPrice = PriceValue.Unavailable("couldn't reach price service"),
                isApproximateMatch = !match.confident
            )
        }

        val rawPrice = bestMarketPrice(liveCard.tcgplayer?.prices)

        return IdentifiedCard(
            name = liveCard.name ?: entry.name,
            setName = liveCard.set?.name ?: entry.setName,
            cardNumber = liveCard.number ?: entry.number,
            imageUrl = liveCard.images?.large ?: liveCard.images?.small,
            rawPrice = rawPrice?.let { PriceValue.Available(it) } ?: PriceValue.Unavailable("not listed on TCGPlayer"),
            isApproximateMatch = !match.confident
        )
    }

    /** One retry after a short delay — smooths over transient network blips (weak wifi, DNS hiccups). */
    private suspend fun <T> retrying(attempts: Int = 2, delayMs: Long = 400, block: suspend () -> T): T? {
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (attempt == attempts - 1) return null
                kotlinx.coroutines.delay(delayMs)
            }
        }
        return null
    }

    private fun bestMarketPrice(prices: PokemonTcgPrices?): Double? {
        if (prices == null) return null
        return prices.normal?.market
            ?: prices.holofoil?.market
            ?: prices.reverseHolofoil?.market
            ?: prices.firstEditionHolofoil?.market
            ?: prices.firstEditionNormal?.market
    }

    private fun buildKey(name: String, setName: String, cardNumber: String): String =
        "$name|$setName|$cardNumber".lowercase().replace(" ", "")
}
