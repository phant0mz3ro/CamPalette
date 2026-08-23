package com.example.camvisionpro

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class Preset(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    var curveStrength: Double,
    var saturationMultiplier: Double,
    var contrastValue: Int,
    var warmthValue: Int
)