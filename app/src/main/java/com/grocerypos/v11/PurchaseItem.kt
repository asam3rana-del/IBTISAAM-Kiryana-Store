package com.grocerypos.v11

data class PurchaseItem(
    val barcode: String,
    val name: String,
    val qty: Double,
    val cost: Double,
    val total: Double,
    val qtyType: String = "dabbi",
    val wholesalePrice: Double = 0.0,
    val retailPrice: Double = 0.0
)
