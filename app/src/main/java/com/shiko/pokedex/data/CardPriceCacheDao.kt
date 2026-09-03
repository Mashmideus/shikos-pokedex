package com.shiko.pokedex.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CardPriceCacheDao {

    @Query("SELECT * FROM card_price_cache WHERE cardKey = :cardKey LIMIT 1")
    suspend fun get(cardKey: String): CardPriceCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CardPriceCacheEntity)
}
