package com.example.silent

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SecondActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        try {
            val back = findViewById<Button>(R.id.secondBack)
            back.setOnClickListener { finish() }
        } catch (_: Exception) { /* ignore if view missing */ }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
