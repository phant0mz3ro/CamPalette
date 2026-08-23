package com.example.camvisionpro

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Preset::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "camvisionpro_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}