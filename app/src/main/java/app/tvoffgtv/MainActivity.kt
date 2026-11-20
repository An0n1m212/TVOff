package app.tvoffgtv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Запустился -> нашёл ТВ -> выключил -> закрылся. Код сопряжения спрашивается только в первый раз. */
class MainActivity : Activity() {

    private fun py() = Python.getInstance().getModule("tvoff")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        background { py().callAttr("power_off", filesDir.absolutePath).toString() }
    }

    private fun background(block: () -> String) {
        Thread {
            val res = try { block() } catch (e: Exception) { "error:${e.message}" }
            runOnUiThread { handle(res) }
        }.start()
    }

    private fun handle(res: String) {
        when {
            res == "pair" -> askCode()
            res == "ok" -> done("ТВ выключается")
            res == "already_off" -> done("ТВ уже выключен")
            res == "notfound" -> done("ТВ не найден в Wi-Fi (он должен быть включён)")
            else -> done("Не удалось: ${res.removePrefix("error:")}")
        }
    }

    private fun askCode() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this)
            .setTitle("Код с экрана ТВ")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val code = input.text.toString()
                background { py().callAttr("finish_pairing", code).toString() }
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .show()
    }

    private fun done(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
