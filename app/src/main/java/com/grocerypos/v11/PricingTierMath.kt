package com.grocerypos.v11.pricing

import com.grocerypos.v11.Product

/**
 * Represents one unit "tier" for a product — e.g. pcs / dozen / carton —
 * along with the factor to convert an amount in that unit into the
 * product's main (smallest recorded) unit.
 */
data class UnitTier(val unit: String, val factor: Double)

/**
 * Builds the list of unit tiers for a product based on its primary,
 * secondary, and tertiary unit/quantity fields.
 *
 * Example: if unit="pcs", secondaryUnit="dozen", secondaryUnitQty=12,
 * tertiaryUnit="carton", tertiaryUnitQty=10 →
 *   [ ("pcs", 1.0), ("dozen", 12.0), ("carton", 120.0) ]
 */
fun Product.toUnitTiers(): List<UnitTier> {
    val tiers = mutableListOf(UnitTier(unit, 1.0))
    if (secondaryUnit.isNotBlank() && secondaryUnitQty > 0) {
        tiers.add(UnitTier(secondaryUnit, secondaryUnitQty))
        if (tertiaryUnit.isNotBlank() && tertiaryUnitQty > 0) {
            tiers.add(UnitTier(tertiaryUnit, secondaryUnitQty * tertiaryUnitQty))
        }
    }
    return tiers
}

/**
 * Shared math for converting a price/rate entered in some chosen unit
 * to and from the product's main-unit basis, using its unit tiers.
 */
object PricingTierMath {

    /** Converts a price entered in [chosenUnit] to the product's main-unit price. */
    fun toMainUnit(entered: Double, tiers: List<UnitTier>, chosenUnit: String): Double {
        val factor = tiers.find { it.unit == chosenUnit }?.factor ?: 1.0
        return entered * factor
    }

    /** Converts a main-unit price back down to a price in [chosenUnit]. */
    fun fromMainUnit(mainPrice: Double, tiers: List<UnitTier>, chosenUnit: String): Double {
        val factor = tiers.find { it.unit == chosenUnit }?.factor ?: 1.0
        return if (factor > 0) mainPrice / factor else mainPrice
    }
}
