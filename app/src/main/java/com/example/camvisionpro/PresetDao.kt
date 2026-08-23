package com.example.camvisionpro

import androidx.room.*

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets")
    suspend fun getAll(): List<Preset>

    @Insert
    suspend fun insert(preset: Preset): Long

    @Update
    suspend fun update(preset: Preset)

    @Delete
    suspend fun delete(preset: Preset)
}