package com.blockverse.game

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Private, offline-only storage. Database mutations are transactional and game completion is idempotent. */
class LocalStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "blockverse.db"
        private const val DATABASE_VERSION = 3
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE game_state(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE stats(id INTEGER PRIMARY KEY CHECK(id=1), best INTEGER NOT NULL DEFAULT 0, games INTEGER NOT NULL DEFAULT 0, total_score INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO stats(id,best,games,total_score) VALUES(1,0,0,0)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE finished_sessions(session_id TEXT PRIMARY KEY, score INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT (strftime('%s','now')))")
        db.execSQL("CREATE TABLE active_session(id INTEGER PRIMARY KEY CHECK(id=1), session_id TEXT NOT NULL, time_remaining_ms INTEGER NOT NULL DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS finished_sessions(session_id TEXT PRIMARY KEY, score INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT (strftime('%s','now')))")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS active_session(id INTEGER PRIMARY KEY CHECK(id=1), session_id TEXT NOT NULL, time_remaining_ms INTEGER NOT NULL DEFAULT 0)")
            // Preserve the previous release's saved Time Attack countdown when upgrading.
            db.execSQL("""
                INSERT OR IGNORE INTO active_session(id,session_id,time_remaining_ms)
                VALUES(
                    1,
                    lower(hex(randomblob(16))),
                    COALESCE((
                        SELECT CASE
                            WHEN value <> '' AND value NOT GLOB '*[^0-9]*'
                            THEN MIN(90000, MAX(0, CAST(value AS INTEGER)))
                            ELSE 90000
                        END
                        FROM settings WHERE key='time_remaining_ms'
                    ), 90000)
                )
            """.trimIndent())
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve user data; downgrade is not supported by this app's release policy.
        throw IllegalStateException("Database downgrade is not supported")
    }

    fun saveGame(payload: String, sessionId: String, timeRemainingMs: Long) {
        require(payload.length <= 16_384) { "Game state exceeds safe size" }
        require(sessionId.length in 1..80 && sessionId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
        val db = writableDatabase
        db.beginTransaction()
        try {
            val gameValues = ContentValues().apply { put("id", 1); put("payload", payload) }
            check(db.insertWithOnConflict("game_state", null, gameValues, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Could not save game state"
            }
            val sessionValues = ContentValues().apply {
                put("id", 1); put("session_id", sessionId); put("time_remaining_ms", timeRemainingMs.coerceIn(0L, 90_000L))
            }
            check(db.insertWithOnConflict("active_session", null, sessionValues, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Could not save active session"
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun loadSessionId(): String? = runCatching {
        readableDatabase.rawQuery("SELECT session_id FROM active_session WHERE id=1", null).use {
            if (it.moveToFirst()) it.getString(0)?.takeIf { id ->
                id.length in 1..80 && id.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' }
            } else null
        }
    }.getOrNull()

    fun loadTimeRemaining(defaultMs: Long = 90_000L): Long {
        val safeDefault = defaultMs.coerceIn(0L, 90_000L)
        return runCatching {
            readableDatabase.rawQuery("SELECT time_remaining_ms FROM active_session WHERE id=1", null).use {
                if (it.moveToFirst()) it.getLong(0).coerceIn(0L, 90_000L) else safeDefault
            }
        }.getOrDefault(safeDefault)
    }

    fun loadGame(): String? {
        var oversized = false
        val payload = runCatching {
            readableDatabase.rawQuery("SELECT payload, length(payload) FROM game_state WHERE id=1", null).use {
                if (it.moveToFirst()) {
                    if (it.getLong(1) > 16_384L) {
                        oversized = true
                        null
                    } else it.getString(0)
                } else null
            }
        }.getOrNull()
        if (oversized) clearGame()
        return payload
    }

    fun clearGame() {
        runCatching {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("game_state", "id=1", null)
                db.delete("active_session", "id=1", null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    /** Records one game exactly once, even if Android kills the process between finish and UI refresh. */
    fun finishGameOnce(sessionId: String, score: Int): Boolean {
        if (sessionId.length !in 1..80 || sessionId.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_') } || score < 0) return false
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val inserted = db.insertWithOnConflict(
                "finished_sessions", null,
                ContentValues().apply { put("session_id", sessionId); put("score", score) },
                SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
            if (inserted) {
                db.execSQL("INSERT OR IGNORE INTO stats(id,best,games,total_score) VALUES(1,0,0,0)")
                db.execSQL(
                    """UPDATE stats SET
                        best=MAX(best,?),
                        games=CASE WHEN games < 2147483647 THEN games+1 ELSE games END,
                        total_score=CASE
                            WHEN total_score > 9223372036854775807 - ? THEN 9223372036854775807
                            ELSE total_score + ?
                        END
                        WHERE id=1""".trimIndent(),
                    arrayOf(score, score, score)
                )
            }
            db.setTransactionSuccessful()
            inserted
        } finally { db.endTransaction() }
    }

    fun isSessionFinished(sessionId: String): Boolean = runCatching {
        readableDatabase.rawQuery("SELECT 1 FROM finished_sessions WHERE session_id=? LIMIT 1", arrayOf(sessionId)).use { it.moveToFirst() }
    }.getOrDefault(false)

    fun stats(): Triple<Int,Int,Long> = runCatching {
        readableDatabase.rawQuery("SELECT best,games,total_score FROM stats WHERE id=1", null).use {
            if (it.moveToFirst()) Triple(
                it.getLong(0).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                it.getLong(1).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                it.getLong(2).coerceAtLeast(0L)
            ) else Triple(0,0,0L)
        }
    }.getOrDefault(Triple(0,0,0L))

    fun achievements(): List<String> {
        val s = stats()
        return buildList {
            if (s.second >= 1) add("FIRST STEPS")
            if (s.first >= 250) add("HIGH SCORE")
            if (s.third >= 500) add("SCORE HUNTER")
            if (s.second >= 10) add("PERSISTENT")
            if (s.third >= 2500) add("PUZZLE LEGEND")
        }
    }

    fun setting(key: String, default: String): String {
        if (key.isBlank() || key.length > 80) return default
        return runCatching {
            readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
                if (it.moveToFirst()) it.getString(0)?.take(256) ?: default else default
            }
        }.getOrDefault(default)
    }

    fun setSetting(key: String, value: String) {
        if (key.isBlank() || key.length > 80 || value.length > 256) return
        val values = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
