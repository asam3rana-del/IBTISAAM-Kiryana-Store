package com.grocerypos.v11

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class Product(
    @PrimaryKey
    val barcode: String,
    val name: String,
    val category: String = "General",
    val unit: String = "ctn",
    val secondaryUnit: String = "outer",
    val tertiaryUnit: String = "dabbi",
    val secondaryUnitQty: Double = 50.0,
    val tertiaryUnitQty: Double = 10.0,
    val stock: Int = 0,
    val cost: Double = 0.0,
    val wholesalePrice: Double = 0.0,
    val salePrice: Double = 0.0
)
