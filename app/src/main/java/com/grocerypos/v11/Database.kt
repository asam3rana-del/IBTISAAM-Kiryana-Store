package com.grocerypos.v11

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Product::class,
        User::class
    ], 
    version = 12, 
    exportSchema = false
)
abstract class PosDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun userDao(): UserDao

    companion object {
        @Volatile 
        private var INSTANCE: PosDatabase? = null
        
        fun get(context: Context): PosDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PosDatabase::class.java,
                    "pos_db_v11_ultra_final"
                )
                .fallbackToDestructiveMigration() // 👈 Crash fix + User table auto create
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
