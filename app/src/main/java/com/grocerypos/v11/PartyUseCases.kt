package com.grocerypos.v11.domain

import com.grocerypos.v11.Customer
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.Sale
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.data.PartyRepository
import kotlinx.coroutines.flow.Flow

/**
 * UseCases for the Customers & Suppliers screen (PartyActivity / PartyViewModel).
 *
 * Each one is a single, named operation rather than exposing PartyRepository's
 * raw CRUD to the ViewModel — the validation that belongs to "adding a party"
 * (e.g. name is required), not to "writing a row to the database", lives here.
 * This is also the layer a unit test would target: no Android framework
 * classes are involved above PartyRepository.
 */

/** Result of validating + saving/updating a customer or supplier. */
sealed class SavePartyResult {
    object Success : SavePartyResult()
    object NameRequired : SavePartyResult()
}

class ObserveCustomersUseCase(private val repository: PartyRepository) {
    operator fun invoke(): Flow<List<Customer>> = repository.observeCustomers()
}

class ObserveSuppliersUseCase(private val repository: PartyRepository) {
    operator fun invoke(): Flow<List<Supplier>> = repository.observeSuppliers()
}

class SaveCustomerUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(
        name: String,
        phone: String,
        creditLimit: Double,
        openingBalance: Double
    ): SavePartyResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return SavePartyResult.NameRequired
        repository.saveCustomer(
            Customer(
                name = trimmedName,
                phone = phone.trim(),
                creditLimit = creditLimit,
                openingBalance = openingBalance,
                balance = 0.0
            )
        )
        return SavePartyResult.Success
    }
}

class SaveSupplierUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(
        name: String,
        phone: String,
        openingBalance: Double
    ): SavePartyResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return SavePartyResult.NameRequired
        repository.saveSupplier(
            Supplier(
                name = trimmedName,
                phone = phone.trim(),
                openingBalance = openingBalance,
                balance = 0.0
            )
        )
        return SavePartyResult.Success
    }
}

class UpdateCustomerUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(
        existing: Customer,
        name: String,
        phone: String,
        creditLimit: Double,
        openingBalance: Double
    ): SavePartyResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return SavePartyResult.NameRequired
        repository.updateCustomer(
            existing.copy(
                name = trimmedName,
                phone = phone.trim(),
                creditLimit = creditLimit,
                openingBalance = openingBalance
            )
        )
        return SavePartyResult.Success
    }
}

class UpdateSupplierUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(
        existing: Supplier,
        name: String,
        phone: String,
        openingBalance: Double
    ): SavePartyResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return SavePartyResult.NameRequired
        repository.updateSupplier(
            existing.copy(
                name = trimmedName,
                phone = phone.trim(),
                openingBalance = openingBalance
            )
        )
        return SavePartyResult.Success
    }
}

class DeleteCustomerUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(customer: Customer) = repository.deleteCustomer(customer)
}

class DeleteSupplierUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(supplier: Supplier) = repository.deleteSupplier(supplier)
}

class GetCustomerHistoryUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(customer: Customer): List<Sale> =
        repository.salesByCustomer(customer.id)
}

class GetSupplierHistoryUseCase(private val repository: PartyRepository) {
    suspend operator fun invoke(supplier: Supplier): List<Purchase> =
        repository.purchasesBySupplier(supplier.id)
}
