package com.shiko.pokedex.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "card_price_cache")
data class CardPriceCacheEntity(
    @PrimaryKey val cardKey: String, // e.g. "name|set|number" lowercase, no spaces
    val name: String,
    val setName: String,
    val cardNumber: String,
    val imageUrl: String?,
    val rawPrice: Double?,
    val psa9Price: Double?,
    val psa10Price: Double?,
    val fetchedAtMillis: Long
)
