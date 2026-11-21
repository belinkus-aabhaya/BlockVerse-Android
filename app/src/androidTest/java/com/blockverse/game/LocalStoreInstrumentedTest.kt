package com.blockverse.game

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase

/** Exercises the actual Android SQLite implementation; run with connectedAndroidTest. */
class LocalStoreInstrumentedTest : InstrumentationTestCase() {
    private val databaseName = "blockverse.db"

    private fun freshContext() = instrumentation.targetContext

    override fun setUp() {
        super.setUp()
        freshContext().deleteDatabase(databaseName)
    }

    override fun tearDown() {
        freshContext().deleteDatabase(databaseName)
        super.tearDown()
    }

    fun testSaveAndCompletionAreTransactionalAndIdempotent() {
        val store = LocalStore(freshContext())
        try {
            store.saveGame("payload-v3", "session-1", 45_000L)
            assertEquals("payload-v3", store.loadGame())
            assertEquals("session-1", store.loadSessionId())
            assertEquals(45_000L, store.loadTimeRemaining())
            assertTrue(store.finishGameOnce("session-1", 123))
            assertFalse(store.finishGameOnce("session-1", 123))
            assertEquals(Triple(123, 1, 123L), store.stats())
        } finally {
            store.close()
        }
    }

    fun testVersionOneDatabaseMigratesSavedTimerAndSettings() {
        createVersionOneDatabase("45000")
        val store = LocalStore(freshContext())
        try {
            assertEquals(45_000L, store.loadTimeRemaining())
            assertNotNull(store.loadSessionId())
            assertEquals("light", store.setting("theme", "dark"))
            store.saveGame("migrated", "session-migrated", 30_000L)
            assertEquals("migrated", store.loadGame())
        } finally {
            store.close()
        }
    }

    fun testInvalidLegacyTimerUsesSafeDefault() {
        createVersionOneDatabase("not-a-number")
        val store = LocalStore(freshContext())
        try {
            assertEquals(90_000L, store.loadTimeRemaining())
        } finally {
            store.close()
        }
    }

    fun testStatisticsCountersSaturateInsteadOfOverflowing() {
        val store = LocalStore(freshContext())
        try {
            store.writableDatabase.execSQL(
                "UPDATE stats SET games=?, total_score=? WHERE id=1",
                arrayOf(Int.MAX_VALUE, Long.MAX_VALUE - 1L)
            )
            assertTrue(store.finishGameOnce("session-overflow", 10))
            assertEquals(Triple(10, Int.MAX_VALUE, Long.MAX_VALUE), store.stats())
        } finally {
            store.close()
        }
    }

    fun testRejectedSaveDoesNotReplacePreviouslyCommittedSession() {
        val store = LocalStore(freshContext())
        try {
            store.saveGame("first", "session-first", 80_000L)
            try {
                store.saveGame("second", "contains spaces", 20_000L)
                fail("Invalid session identifier must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected validation failure occurs before the database transaction.
            }
            assertEquals("first", store.loadGame())
            assertEquals("session-first", store.loadSessionId())
            assertEquals(80_000L, store.loadTimeRemaining())
        } finally {
            store.close()
        }
    }

    fun testOversizedDatabasePayloadIsDiscardedBeforeStringMaterialization() {
        val store = LocalStore(freshContext())
        try {
            store.writableDatabase.execSQL(
                "INSERT INTO game_state(id,payload) VALUES(1,?)",
                arrayOf("x".repeat(16_385))
            )
            assertNull(store.loadGame())
            assertNull(store.loadSessionId())
        } finally {
            store.close()
        }
    }

    fun testClearGameRemovesPayloadAndActiveSessionTogether() {
        val store = LocalStore(freshContext())
        try {
            store.saveGame("payload", "session-clear", 10_000L)
            store.clearGame()
            assertNull(store.loadGame())
            assertNull(store.loadSessionId())
        } finally {
            store.close()
        }
    }

    private fun createVersionOneDatabase(timerValue: String) {
        val context = freshContext()
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        if (path.exists()) path.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            db.execSQL("CREATE TABLE game_state(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE stats(id INTEGER PRIMARY KEY CHECK(id=1), best INTEGER NOT NULL DEFAULT 0, games INTEGER NOT NULL DEFAULT 0, total_score INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO stats(id,best,games,total_score) VALUES(1,0,0,0)")
            db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO settings(key,value) VALUES('theme','light')")
            db.execSQL("INSERT INTO settings(key,value) VALUES('time_remaining_ms',?)", arrayOf(timerValue))
            db.version = 1
        } finally {
            db.close()
        }
    }
}
