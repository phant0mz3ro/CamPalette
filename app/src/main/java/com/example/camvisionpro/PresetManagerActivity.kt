package com.example.camvisionpro

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.camvisionpro.databinding.ActivityPresetManagerBinding
import kotlinx.coroutines.*

class PresetManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPresetManagerBinding
    private lateinit var database: AppDatabase
    private var presets: MutableList<Preset> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPresetManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)
        loadPresets()

        binding.presetSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                fillFieldsFromPreset(presets[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        binding.saveAsExistingButton.setOnClickListener { saveToSelectedPreset() }
        binding.saveAsNewButton.setOnClickListener { saveAsNewPreset() }
        binding.deleteButton.setOnClickListener { deleteSelectedPreset() }
    }

    private fun loadPresets() {
        CoroutineScope(Dispatchers.IO).launch {
            presets = database.presetDao().getAll().toMutableList()
            withContext(Dispatchers.Main) {
                val names = presets.map { it.name }
                binding.presetSpinner.adapter = ArrayAdapter(
                    this@PresetManagerActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    names
                )
                if (presets.isNotEmpty()) fillFieldsFromPreset(presets[0])
            }
        }
    }

    private fun fillFieldsFromPreset(preset: Preset) {
        binding.nameInput.setText(preset.name)
        binding.curveInput.setText(preset.curveStrength.toString())
        binding.saturationInput.setText(preset.saturationMultiplier.toString())
        binding.contrastInput.setText(preset.contrastValue.toString())
        binding.warmthInput.setText(preset.warmthValue.toString())
    }

    private fun readFieldsAsPreset(existingId: Int = 0): Preset? {
        val name = binding.nameInput.text.toString().trim()
        val curve = binding.curveInput.text.toString().toDoubleOrNull()
        val saturation = binding.saturationInput.text.toString().toDoubleOrNull()
        val contrast = binding.contrastInput.text.toString().toIntOrNull()
        val warmth = binding.warmthInput.text.toString().toIntOrNull()

        if (name.isEmpty() || curve == null || saturation == null || contrast == null || warmth == null) {
            android.widget.Toast.makeText(this, "Please fill all fields correctly", android.widget.Toast.LENGTH_SHORT).show()
            return null
        }
        return Preset(id = existingId, name = name, curveStrength = curve, saturationMultiplier = saturation, contrastValue = contrast, warmthValue = warmth)
    }

    private fun saveToSelectedPreset() {
        val selectedIndex = binding.presetSpinner.selectedItemPosition
        if (selectedIndex < 0 || selectedIndex >= presets.size)
        {
            android.widget.Toast.makeText(this, "No preset selected", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val existingId = presets[selectedIndex].id
        val updated = readFieldsAsPreset(existingId) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            database.presetDao().update(updated)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@PresetManagerActivity, "Saved", android.widget.Toast.LENGTH_SHORT).show()
                loadPresets()
            }
        }
    }

    private fun saveAsNewPreset() {
        val newPreset = readFieldsAsPreset() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            database.presetDao().insert(newPreset)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@PresetManagerActivity, "Created", android.widget.Toast.LENGTH_SHORT).show()
                loadPresets()
            }
        }
    }

    private fun deleteSelectedPreset() {
        val selectedIndex = binding.presetSpinner.selectedItemPosition
        if (selectedIndex < 0 || selectedIndex >= presets.size)  {
            android.widget.Toast.makeText(this, "No preset selected", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val toDelete = presets[selectedIndex]

        CoroutineScope(Dispatchers.IO).launch {
            database.presetDao().delete(toDelete)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@PresetManagerActivity, "Deleted", android.widget.Toast.LENGTH_SHORT).show()
                loadPresets()
            }
        }
    }
}