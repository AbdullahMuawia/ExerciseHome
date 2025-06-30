package com.example.exercisehome

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.example.exercisehome.databinding.ActivitySettingsBinding
import java.lang.NumberFormatException

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("ExerciseHomePrefs", Context.MODE_PRIVATE)
    }

    companion object {
        const val KEY_STRIDE_LENGTH = "stride_length"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()

        binding.saveSettingsButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        val strideLength = prefs.getFloat(KEY_STRIDE_LENGTH, 0.7f)
        binding.strideLengthEditText.setText(strideLength.toString())
    }

    private fun saveSettings() {
        val strideLengthStr = binding.strideLengthEditText.text.toString()
        if (strideLengthStr.isNotEmpty()) {
            try {
                val strideLength = strideLengthStr.toFloat()
                if (strideLength > 0) {
                    prefs.edit().putFloat(KEY_STRIDE_LENGTH, strideLength).apply()
                    Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Please enter a positive number.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Invalid number format.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Stride length cannot be empty.", Toast.LENGTH_SHORT).show()
        }
    }
}
