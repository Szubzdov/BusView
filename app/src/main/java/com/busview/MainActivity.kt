package com.busview

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var home: EditText
    private lateinit var work: EditText
    private lateinit var priority: EditText
    private lateinit var save: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        home = findViewById(R.id.home)
        work = findViewById(R.id.work)
        priority = findViewById(R.id.priority)
        save = findViewById(R.id.save)
        status = findViewById(R.id.status)

        Prefs.home(this)?.let { home.setText(it.query) }
        Prefs.work(this)?.let { work.setText(it.query) }
        priority.setText(Prefs.priorityText(this))
        if (Schedule.hasData(this)) status.text = "Ustawienia zapisane. Widget jest aktywny."

        save.setOnClickListener { onSave() }
    }

    private fun onSave() {
        val homeText = home.text.toString().trim()
        val workText = work.text.toString().trim()
        val prioText = priority.text.toString().trim()
        if (homeText.isEmpty() || workText.isEmpty()) {
            status.text = "Wpisz dom i pracę."
            return
        }
        save.isEnabled = false
        status.text = "Szukam adresów…"
        thread {
            val msg = try {
                val old = Prefs.home(this) to Prefs.work(this)
                val h = old.first?.takeIf { it.query == homeText } ?: Geo.find(homeText)
                if (old.first?.query != homeText) Thread.sleep(1100) // Nominatim: max 1 request per second
                val w = old.second?.takeIf { it.query == workText } ?: Geo.find(workText)
                when {
                    h == null -> "Nie znaleziono: $homeText"
                    w == null -> "Nie znaleziono: $workText"
                    else -> {
                        runOnUiThread { status.text = "Pobieram rozkład ZTM Kielce…" }
                        val r = Gtfs.build(this, h, w)
                        Prefs.save(this, h, w, prioText)
                        RefreshWorker.schedule(this)
                        BusWidget.refresh(this)
                        buildString {
                            append("Zapisano.\n\n")
                            append("Przystanki przy domu: ${r.homeStops.joinToString()}\n\n")
                            append("Przystanki przy pracy: ${r.workStops.joinToString()}\n\n")
                            append("Kursy bezpośrednie: dom→praca ${r.toWork}, praca→dom ${r.toHome}\n\n")
                            if (r.toWork == 0 || r.toHome == 0) {
                                append("Brak bezpośrednich kursów w jedną ze stron – spróbuj dokładniejszego adresu.")
                            } else {
                                append("Dodaj widget BusView na ekranie głównym.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                "Błąd: ${e.message ?: e.javaClass.simpleName}. Sprawdź internet i spróbuj ponownie."
            }
            runOnUiThread {
                status.text = msg
                save.isEnabled = true
            }
        }
    }
}
