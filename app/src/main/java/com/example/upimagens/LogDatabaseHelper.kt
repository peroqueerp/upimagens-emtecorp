package com.example.upimagens

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class LogDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "upimagens_logs.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_LOGS = "app_logs"

        private const val KEY_ID = "id"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_EVENT_TYPE = "event_type"
        private const val KEY_MESSAGE = "message"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = ("CREATE TABLE " + TABLE_LOGS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + KEY_EVENT_TYPE + " TEXT,"
                + KEY_MESSAGE + " TEXT" + ")")
        db.execSQL(createTableQuery)
        
        // Insere um log inicial de criação do banco
        val values = ContentValues().apply {
            put(KEY_EVENT_TYPE, "SYSTEM")
            put(KEY_MESSAGE, "Banco de dados de monitoramento inicializado com sucesso.")
        }
        db.insert(TABLE_LOGS, null, values)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    /**
     * Insere um novo log no banco de dados local.
     */
    fun log(eventType: String, message: String) {
        try {
            val db = this.writableDatabase
            val values = ContentValues().apply {
                put(KEY_EVENT_TYPE, eventType)
                put(KEY_MESSAGE, message)
            }
            db.insert(TABLE_LOGS, null, values)
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Retorna os últimos N logs salvos formatados em uma string JSON.
     * Isso facilita a leitura direta pelo JavaScript no WebView.
     */
    fun getLogsJson(limit: Int = 100): String {
        val jsonArray = JSONArray()
        val selectQuery = "SELECT * FROM $TABLE_LOGS ORDER BY $KEY_TIMESTAMP DESC LIMIT $limit"
        
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(selectQuery, null)

            if (cursor.moveToFirst()) {
                do {
                    val logObject = JSONObject().apply {
                        put("id", cursor.getInt(cursor.getColumnIndexOrThrow(KEY_ID)))
                        put("timestamp", cursor.getString(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP)))
                        put("event_type", cursor.getString(cursor.getColumnIndexOrThrow(KEY_EVENT_TYPE)))
                        put("message", cursor.getString(cursor.getColumnIndexOrThrow(KEY_MESSAGE)))
                    }
                    jsonArray.put(logObject)
                } while (cursor.moveToNext())
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return jsonArray.toString()
    }

    /**
     * Retorna a quantidade de logs agrupados por tipo (para construção de gráficos).
     */
    fun getStatsJson(): String {
        val statsObject = JSONObject()
        val query = "SELECT $KEY_EVENT_TYPE, COUNT(*) as count FROM $TABLE_LOGS GROUP BY $KEY_EVENT_TYPE"
        
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(query, null)
            
            if (cursor.moveToFirst()) {
                do {
                    val type = cursor.getString(cursor.getColumnIndexOrThrow(KEY_EVENT_TYPE))
                    val count = cursor.getInt(cursor.getColumnIndexOrThrow("count"))
                    statsObject.put(type, count)
                } while (cursor.moveToNext())
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return statsObject.toString()
    }

    /**
     * Limpa todos os logs do banco de dados.
     */
    fun clearLogs() {
        try {
            val db = this.writableDatabase
            db.delete(TABLE_LOGS, null, null)
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
