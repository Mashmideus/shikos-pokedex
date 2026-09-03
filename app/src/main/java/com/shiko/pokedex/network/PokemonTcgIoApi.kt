package com.shiko.pokedex.network

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * pokemontcg.io — used only to fetch live pricing/images for a card ID already
 * resolved by the bundled offline index (assets/cards_index.json). No key needed
 * for personal-use volumes (1000 req/day unauthenticated); a free key from
 * dev.pokemontcg.io raises that to 20,000/day if ever needed.
 * Docs: https://docs.pokemontcg.io/api-reference/cards/card-object
 */
interface PokemonTcgIoApi {
    @GET("/v2/cards/{id}")
    suspend fun getCard(@Path("id") id: String): PokemonTcgCardResponse
}

data class PokemonTcgCardResponse(val data: PokemonTcgCard?)

data class PokemonTcgCard(
    val id: String,
    val name: String?,
    val number: String?,
    val images: PokemonTcgImages?,
    val set: PokemonTcgSet?,
    val tcgplayer: PokemonTcgPlayerBlock?
)

data class PokemonTcgImages(
    val small: String? = null,
    val large: String? = null
)

data class PokemonTcgSet(
    val id: String? = null,
    val name: String? = null
)

data class PokemonTcgPlayerBlock(
    val url: String? = null,
    val prices: PokemonTcgPrices? = null
)

data class PokemonTcgPrices(
    val normal: PokemonTcgPriceVariant? = null,
    val holofoil: PokemonTcgPriceVariant? = null,
    val reverseHolofoil: PokemonTcgPriceVariant? = null,
    val firstEditionHolofoil: PokemonTcgPriceVariant? = null,
    val firstEditionNormal: PokemonTcgPriceVariant? = null
)

data class PokemonTcgPriceVariant(
    val low: Double? = null,
    val mid: Double? = null,
    val high: Double? = null,
    val market: Double? = null,
    val directLow: Double? = null
)
