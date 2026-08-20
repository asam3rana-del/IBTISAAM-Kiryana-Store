package com.grocerypos.v11

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val username: String = "admin",
    val password: String = "admin123",
    val displayName: String = "Admin",
    val role: String = "Admin" // Admin, Cashier, Manager
)
