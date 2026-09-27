package io.github.revocx35.walmobile.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The app's database (`databases/walm.db`). Same schema as the wa_logger server (its ARCHITECTURE.md §7)
 * minus web sessions and TOTP. Columns ending in `_enc` hold AES-256-GCM field blobs (core/crypto/Keyring).
 * Migrations are append-only: never edit one that has shipped, add a new one.
 */
class Db(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, MIGRATIONS.size) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        // Freed pages are zeroed (matters for wipes and superseded key wraps). This pragma returns a row,
        // which execSQL refuses, so it goes through rawQuery.
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) = onUpgrade(db, 0, MIGRATIONS.size)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in oldVersion until newVersion) {
            for (stmt in MIGRATIONS[v].split(";").map { it.trim() }.filter { it.isNotEmpty() }) db.execSQL(stmt)
        }
    }

    companion object {
        const val NAME = "walm.db"

        private val MIGRATIONS = listOf(
            """
            CREATE TABLE owner (
              id INTEGER PRIMARY KEY CHECK (id = 1),
              pw_salt BLOB NOT NULL,
              pw_auth_hash BLOB NOT NULL,
              pw_log_n INTEGER NOT NULL,
              public_key BLOB NOT NULL,
              privkey_pw BLOB NOT NULL,
              privkey_recovery BLOB NOT NULL,
              recovery_salt BLOB NOT NULL,
              recovery_auth_hash BLOB NOT NULL,
              privkey_bio BLOB,
              failed_unlocks INTEGER NOT NULL DEFAULT 0,
              locked_until INTEGER,
              created_at INTEGER NOT NULL,
              password_changed_at INTEGER NOT NULL
            );
            CREATE TABLE audit_log (
              id INTEGER PRIMARY KEY,
              ts INTEGER NOT NULL,
              event TEXT NOT NULL,
              detail TEXT
            );
            CREATE INDEX audit_log_ts ON audit_log (ts);
            CREATE TABLE app_state (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            CREATE TABLE data_keys (
              id INTEGER PRIMARY KEY,
              eph_pub BLOB NOT NULL,
              wrapped BLOB NOT NULL,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE media (
              id INTEGER PRIMARY KEY,
              path TEXT NOT NULL UNIQUE,
              key_id INTEGER NOT NULL REFERENCES data_keys (id),
              mime_enc BLOB,
              filename_enc BLOB,
              size INTEGER NOT NULL,
              kind TEXT NOT NULL,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE chats (
              id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              name_enc BLOB,
              last_ts INTEGER,
              archived INTEGER NOT NULL DEFAULT 0,
              pinned INTEGER NOT NULL DEFAULT 0,
              muted INTEGER NOT NULL DEFAULT 0,
              removed_at INTEGER,
              avatar_media_id INTEGER REFERENCES media (id) ON DELETE SET NULL,
              avatar_checked_at INTEGER,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            );
            CREATE INDEX chats_last_ts ON chats (last_ts);
            CREATE TABLE contacts (
              id TEXT PRIMARY KEY,
              name_enc BLOB,
              pushname_enc BLOB,
              phone_enc BLOB,
              is_me INTEGER NOT NULL DEFAULT 0,
              is_business INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL
            );
            CREATE TABLE messages (
              id TEXT PRIMARY KEY,
              chat_id TEXT NOT NULL,
              sender_id TEXT,
              from_me INTEGER NOT NULL DEFAULT 0,
              ts INTEGER NOT NULL,
              type TEXT NOT NULL,
              body_enc BLOB,
              meta_enc BLOB,
              thumb_enc BLOB,
              quoted_id TEXT,
              media_id INTEGER REFERENCES media (id) ON DELETE SET NULL,
              media_status TEXT NOT NULL DEFAULT 'none',
              media_attempts INTEGER NOT NULL DEFAULT 0,
              media_size INTEGER,
              media_w REAL,
              media_h REAL,
              media_duration REAL,
              is_view_once INTEGER NOT NULL DEFAULT 0,
              is_forwarded INTEGER NOT NULL DEFAULT 0,
              is_status INTEGER NOT NULL DEFAULT 0,
              ack INTEGER,
              deleted_at INTEGER,
              deleted_by TEXT,
              deleted_for_me_at INTEGER,
              edited_at INTEGER,
              last_edit_key TEXT,
              source TEXT NOT NULL,
              captured_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            );
            CREATE INDEX messages_chat_ts ON messages (chat_id, ts);
            CREATE INDEX messages_deleted ON messages (deleted_at) WHERE deleted_at IS NOT NULL;
            CREATE INDEX messages_media_pending ON messages (media_status) WHERE media_status = 'pending';
            CREATE TABLE message_edits (
              id INTEGER PRIMARY KEY,
              message_id TEXT NOT NULL,
              body_enc BLOB,
              captured_at INTEGER NOT NULL
            );
            CREATE INDEX message_edits_msg ON message_edits (message_id);
            CREATE TABLE reactions (
              message_id TEXT NOT NULL,
              sender_id TEXT NOT NULL,
              emoji_enc BLOB,
              ts INTEGER NOT NULL,
              removed_at INTEGER,
              PRIMARY KEY (message_id, sender_id)
            )
            """,
        )
    }
}
