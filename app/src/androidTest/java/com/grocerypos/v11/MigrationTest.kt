package com.grocerypos.v11

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3 (Improvement Pack) — Room migration coverage, oldest supported version -> 32.
 *
 * WHY THIS SHAPE, NOT MigrationTestHelper.createDatabase(name, version):
 * `exportSchema` has always been `false` on `@Database` (Database.kt), so this
 * project has never written the schemas/<version>.json files that
 * MigrationTestHelper normally reads to materialize an "old" database. Rather than
 * turn on schema export now (which only captures the CURRENT version going
 * forward, and can't retroactively produce the 19 historical json files this
 * would need), this test hand-builds the oldest supported schema (version 13 —
 * the lowest version any MIGRATION_x_y in Database.kt starts from; anything
 * older has no migration path and is out of support) with the same DDL Room
 * itself generated for that version, straight from a plain
 * SupportSQLiteOpenHelper. From there it calls every real MIGRATION_x_y.migrate()
 * object from Database.kt in order — the exact same Migration instances
 * PosDatabase.get() registers via addMigrations(...) — so this test is
 * exercising the shipping migration code, not a copy of it.
 *
 * Two layers of verification:
 *  1. STEP_MIGRATIONS_PRESERVE_DATA — seeds v13-shaped rows, walks every
 *     migration in order, and checks specific columns/values survive and that
 *     newly-added columns land on the defaults each migration promises
 *     (status='active', dueDate=0, conversionFactor=0.0, and — the important
 *     one — that MIGRATION_31_32 actually backfills a non-blank saleUid/
 *     purchaseUid/lineUid onto every pre-existing row instead of leaving them
 *     blank forever).
 *  2. FULL_CHAIN_MATCHES_ROOMS_OWN_SCHEMA — re-opens the now-v32 file through
 *     Room.databaseBuilder(...).build() (the same call PosDatabase.get() makes)
 *     and forces onOpen(). Room validates the on-disk schema's identity hash
 *     against what it compiled from the @Entity classes on EVERY open,
 *     regardless of exportSchema — so if this hand-rolled v13 baseline or any
 *     migration in the chain is wrong in a way that leaves the final table
 *     shape even one column/type/default off from what Database.kt's entities
 *     declare, Room throws IllegalStateException here. This is the same check
 *     class that caused the real "Migration didn't properly handle:
 *     stock_movements" crash documented above MIGRATION_27_28 — this test
 *     exists specifically so that class of bug fails a test run instead of a
 *     user's device.
 *
 * Assumption worth flagging (see ROOM_MIGRATION_TEST_PLAN.md "Known
 * assumptions"): the v13 DDL below was reconstructed by reading every
 * migration from MIGRATION_13_14 onward and subtracting its changes back out,
 * not from an old APK or a preserved schema file. If a real pre-v14 device
 * backup or APK ever turns up, replace `createV13Schema()` with DDL taken
 * from it and re-run — everything downstream of that function is unaffected.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDbName = "migration-test-${System.currentTimeMillis()}.db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Every real migration PosDatabase.get() registers, in order. Kept as one
    // list so both tests below walk exactly what production does.
    private val allMigrations = arrayOf(
        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
        MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
        MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
        MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32
    )

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    // ================= Layer 1: step through the real chain, checking data =================

    @Test
    fun stepMigrationsPreserveDataAndDefaults() {
        val db = openV13Database()
        seedV13Rows(db)

        for (migration in allMigrations) {
            migration.migrate(db)
        }
        db.version = 32

        // --- pre-existing rows survived the whole chain ---
        db.query("SELECT name, salePrice FROM products WHERE barcode='BC-1'").use { c ->
            assertTrue("seeded product should still exist after full chain", c.moveToFirst())
            assertEquals("Test Soap", c.getString(0))
            assertEquals(25.0, c.getDouble(1), 0.0001)
        }

        // --- columns added along the way landed on the promised default ---
        db.query("SELECT status, dueDate, saleUid FROM sales WHERE invoice='INV-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("active", c.getString(0)) // MIGRATION_15_16 default
            assertEquals(0L, c.getLong(1)) // MIGRATION_29_30 default
            // MIGRATION_31_32 must have backfilled a real UUID onto this
            // pre-existing row, not left it at the ALTER TABLE's blank default.
            assertTrue("saleUid should be backfilled, not blank", c.getString(2).isNotBlank())
        }

        db.query("SELECT conversionFactor, lineUid, unit FROM sale_items WHERE invoice='INV-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.0, c.getDouble(0), 0.0001) // MIGRATION_25_26 default = "not captured"
            assertTrue("lineUid should be backfilled", c.getString(1).isNotBlank())
            assertEquals("", c.getString(2)) // MIGRATION_19_20 default
        }

        db.query("SELECT purchaseUid FROM purchases WHERE billNo='BILL-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("purchaseUid should be backfilled", c.getString(0).isNotBlank())
        }

        // stock_movements is brand new at MIGRATION_26_27/rebuilt at 27_28 — just
        // needs to exist and accept a row with the exact defaults Room expects.
        db.execSQL("INSERT INTO stock_movements (barcode, type, qty, createdAt) VALUES ('BC-1','OPENING_STOCK',5.0,1000)")
        db.query("SELECT unit, cost, reference, note FROM stock_movements WHERE barcode='BC-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
            assertEquals(0.0, c.getDouble(1), 0.0001)
        }

        db.close()
    }

    // ================= Layer 2: Room itself must accept the migrated file =================

    @Test
    fun fullChainMatchesRoomsOwnSchema() {
        // Build + migrate exactly as Layer 1, but through this test's own
        // connection first so we start from a known, seeded v13 file on disk.
        val raw = openV13Database()
        seedV13Rows(raw)
        for (migration in allMigrations) {
            migration.migrate(raw)
        }
        raw.version = 32
        raw.close()

        // Now open the SAME file the way PosDatabase.get() does in production.
        // If createV13Schema() or any migration left the on-disk shape even
        // one column/type/default off from what Database.kt's @Entity classes
        // declare, Room throws IllegalStateException right here.
        val roomDb = Room.databaseBuilder(context, PosDatabase::class.java, testDbName)
            .addMigrations(*allMigrations)
            .build()

        // Forces Room to actually open the file and run its schema validation
        // (normally deferred until first use).
        roomDb.openHelper.writableDatabase

        // Spot-check through the real DAOs, not raw SQL, so this also proves
        // the generated *_Impl DAO code agrees with the on-disk shape.
        runBlocking {
            val product = roomDb.productDao().find("BC-1")
            assertTrue("product should be readable via ProductDao after migrating", product != null)
            assertEquals("Test Soap", product?.name)
        }

        roomDb.close()
    }

    // ================= Helpers =================

    /**
     * Hand-built version-13 schema — see the class doc for why this exists
     * instead of a MigrationTestHelper schema asset. Column set/types/defaults
     * for each table are the current @Entity in Database.kt with every later
     * migration's change (ADD COLUMN, type change, new table) subtracted back
     * out; cross-check against MIGRATION_13_14..MIGRATION_31_32 above if this
     * table list ever needs to move to a different oldest-supported version.
     */
    private fun openV13Database(): SupportSQLiteDatabase {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) = createV13Schema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Not used: this test drives every migration explicitly via
                    // migration.migrate(db) below instead of relying on
                    // SQLiteOpenHelper's own upgrade dispatch, so each step is
                    // individually attributable if an assertion fails.
                }
            })
            .build()
        return factory.create(config).writableDatabase
    }

    private fun createV13Schema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE units (name TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE categories (name TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("""
            CREATE TABLE products (
                barcode TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                cost REAL NOT NULL DEFAULT 0.0,
                salePrice REAL NOT NULL DEFAULT 0.0,
                stock INTEGER NOT NULL DEFAULT 0,
                reorderLevel INTEGER NOT NULL DEFAULT 0,
                expiry TEXT NOT NULL DEFAULT '',
                unit TEXT NOT NULL DEFAULT 'pcs',
                unitSize INTEGER NOT NULL DEFAULT 1,
                unitNote TEXT NOT NULL DEFAULT '',
                secondaryUnit TEXT NOT NULL DEFAULT '',
                secondaryUnitQty REAL NOT NULL DEFAULT 0.0,
                wholesalePrice REAL NOT NULL DEFAULT 0.0,
                openingStock INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                phone TEXT NOT NULL DEFAULT '',
                creditLimit REAL NOT NULL DEFAULT 0.0,
                balance REAL NOT NULL DEFAULT 0.0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE suppliers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                phone TEXT NOT NULL DEFAULT '',
                balance REAL NOT NULL DEFAULT 0.0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE sales (
                invoice TEXT PRIMARY KEY NOT NULL,
                customerId INTEGER,
                subtotal REAL NOT NULL,
                discount REAL NOT NULL,
                tax REAL NOT NULL,
                total REAL NOT NULL,
                paid REAL NOT NULL,
                paymentMethod TEXT NOT NULL,
                saleType TEXT NOT NULL DEFAULT 'retail',
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE sale_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                invoice TEXT NOT NULL,
                barcode TEXT NOT NULL,
                product TEXT NOT NULL,
                qty INTEGER NOT NULL,
                unitPrice REAL NOT NULL,
                cost REAL NOT NULL,
                amount REAL NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                reference TEXT NOT NULL,
                partyType TEXT NOT NULL,
                partyId INTEGER,
                amount REAL NOT NULL,
                method TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE purchases (
                billNo TEXT PRIMARY KEY NOT NULL,
                supplierId INTEGER,
                total REAL NOT NULL,
                paid REAL NOT NULL,
                createdAt INTEGER NOT NULL,
                subtotal REAL NOT NULL DEFAULT 0.0,
                discount REAL NOT NULL DEFAULT 0.0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE purchase_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                billNo TEXT NOT NULL,
                barcode TEXT NOT NULL,
                qty REAL NOT NULL,
                unitCost REAL NOT NULL,
                amount REAL NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE returns (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                reference TEXT NOT NULL,
                type TEXT NOT NULL,
                barcode TEXT NOT NULL,
                qty REAL NOT NULL,
                amount REAL NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE users (
                username TEXT PRIMARY KEY NOT NULL,
                displayName TEXT NOT NULL,
                role TEXT NOT NULL,
                passwordHash TEXT NOT NULL,
                active INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                username TEXT NOT NULL,
                action TEXT NOT NULL,
                reference TEXT NOT NULL DEFAULT '',
                details TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                category TEXT NOT NULL,
                description TEXT NOT NULL,
                amount REAL NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE held_bills (
                holdId TEXT PRIMARY KEY NOT NULL,
                payload TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE cash_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                type TEXT NOT NULL,
                method TEXT NOT NULL,
                amount REAL NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                reference TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE cash_register (
                date TEXT PRIMARY KEY NOT NULL,
                openingCash REAL NOT NULL DEFAULT 0.0,
                closingCash REAL NOT NULL DEFAULT 0.0,
                openingBank REAL NOT NULL DEFAULT 0.0,
                closingBank REAL NOT NULL DEFAULT 0.0,
                closed INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE TABLE app_settings (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    /** A minimal but representative row in every table a real v13 device would have data in. */
    private fun seedV13Rows(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO products (barcode,name,category,cost,salePrice,stock,reorderLevel,expiry,unit,unitSize,unitNote,secondaryUnit,secondaryUnitQty,wholesalePrice,openingStock) VALUES ('BC-1','Test Soap','Grocery',15.0,25.0,100,10,'','pcs',1,'','',0.0,0.0,100)")
        db.execSQL("INSERT INTO customers (name,phone,creditLimit,balance) VALUES ('Ali','0300-0000000',500.0,0.0)")
        db.execSQL("INSERT INTO sales (invoice,customerId,subtotal,discount,tax,total,paid,paymentMethod,saleType,createdAt) VALUES ('INV-1',1,25.0,0.0,0.0,25.0,25.0,'cash','retail',1000)")
        db.execSQL("INSERT INTO sale_items (invoice,barcode,product,qty,unitPrice,cost,amount) VALUES ('INV-1','BC-1','Test Soap',1,25.0,15.0,25.0)")
        db.execSQL("INSERT INTO suppliers (name,phone,balance) VALUES ('Wholesaler A','0300-1111111',0.0)")
        db.execSQL("INSERT INTO purchases (billNo,supplierId,total,paid,createdAt,subtotal,discount) VALUES ('BILL-1',1,150.0,150.0,1000,150.0,0.0)")
        db.execSQL("INSERT INTO purchase_items (billNo,barcode,qty,unitCost,amount) VALUES ('BILL-1','BC-1',10.0,15.0,150.0)")
        db.execSQL("INSERT INTO users (username,displayName,role,passwordHash,active) VALUES ('admin','Admin','owner','hash',1)")
    }
}
