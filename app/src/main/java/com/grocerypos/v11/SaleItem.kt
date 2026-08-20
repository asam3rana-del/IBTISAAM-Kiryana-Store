package com.grocerypos.v11

data class SaleItem(
    val barcode: String,
    val name: String,
    val qty: Double,
    val price: Double,
    val total: Double,
    val qtyType: String = "dabbi",
    val priceType: String = "Retail"
)
