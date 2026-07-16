package com.example.upimagens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class UpdateManager(private val context: Context) {

    // URL do arquivo JSON que ficará hospedado no seu GitHub
    private val versionJsonUrl = "https://raw.githubusercontent.com/peroqueerp/upimagens-emtecorp/main/version.json"
    private val executor = Executors.newSingleThreadExecutor()

    fun checkForUpdate() {
        executor.submit {
            try {
                val url = URL(versionJsonUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                // Desabilitar o cache para sempre pegar o JSON mais recente
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val json = JSONObject(response)
                    val remoteVersionCode = json.optInt("versionCode", -1)
                    val apkUrl = json.optString("apkUrl", "")

                    val currentVersionCode = BuildConfig.VERSION_CODE

                    if (remoteVersionCode > currentVersionCode && apkUrl.isNotEmpty()) {
                        // Versão nova encontrada! Pedir para o usuário
                        (context as? MainActivity)?.runOnUiThread {
                            showUpdateDialog(apkUrl)
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateDialog(apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Nova Atualização Disponível")
            .setMessage("Uma nova versão do aplicativo UPImagens está disponível. Deseja baixar e instalar agora?")
            .setCancelable(false)
            .setPositiveButton("Atualizar") { _, _ ->
                startDownload(apkUrl)
            }
            .setNegativeButton("Depois") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun startDownload(apkUrl: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(apkUrl))
            request.setTitle("Atualização UPImagens")
            request.setDescription("Baixando nova versão...")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            
            // Força deletar versão anterior se existir para não dar conflito (UpdateManager sobrescreve, mas é bom garantir)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(dir, "upimagens_update.apk")
            if (file.exists()) {
                file.delete()
            }

            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "upimagens_update.apk")

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(context, "Download da atualização iniciado...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Erro ao iniciar o download.", Toast.LENGTH_SHORT).show()
        }
    }
}
