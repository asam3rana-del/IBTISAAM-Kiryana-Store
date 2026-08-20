package com.grocerypos.v11

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users")
    fun all(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getByUsername(username: String): User?

    @Upsert
    suspend fun upsert(user: User)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteById(id: Int)
}
