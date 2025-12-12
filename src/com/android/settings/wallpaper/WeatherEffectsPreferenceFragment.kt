package com.android.settings.wallpaper

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.android.settings.R

/**
 * Preference fragment to be added to your ROM's Display settings.
 * Provides quick access to weather wallpaper configuration.
 */
class WeatherEffectsPreferenceFragment : PreferenceFragmentCompat() {
    
    companion object {
        private const val KEY_WEATHER_EFFECTS_ENABLED = "weather_effects_enabled"
        private const val KEY_CONFIGURE_WEATHER = "configure_weather_effects"
        private const val KEY_WEATHER_AUTO_UPDATE = "weather_auto_update"
    }
    
    private lateinit var weatherEnabledPref: SwitchPreference
    private lateinit var configurePref: Preference
    private lateinit var autoUpdatePref: SwitchPreference
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.weather_effects_preferences, rootKey)
        
        weatherEnabledPref = findPreference(KEY_WEATHER_EFFECTS_ENABLED)!!
        configurePref = findPreference(KEY_CONFIGURE_WEATHER)!!
        autoUpdatePref = findPreference(KEY_WEATHER_AUTO_UPDATE)!!
        
        setupPreferences()
    }
    
    private fun setupPreferences() {
        // Enable/Disable weather effects
        weatherEnabledPref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            configurePref.isEnabled = enabled
            autoUpdatePref.isEnabled = enabled
            
            if (!enabled) {
                // Clear weather effect when disabled
                clearWeatherEffect()
            }
            true
        }
        
        // Configure button
        configurePref.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), WeatherEffectsSettingsActivity::class.java)
            startActivity(intent)
            true
        }
        
        // Auto-update based on actual weather
        autoUpdatePref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                // TODO: Integrate with weather service
                // This would require:
                // 1. Location permission
                // 2. Weather API integration
                // 3. Background service to update effects
            }
            true
        }
        
        updatePreferencesState()
    }
    
    private fun updatePreferencesState() {
        val enabled = weatherEnabledPref.isChecked
        configurePref.isEnabled = enabled
        autoUpdatePref.isEnabled = enabled
    }
    
    private fun clearWeatherEffect() {
        // Send intent to clear weather effect
        val uri = com.google.android.wallpaper.weathereffects.provider.WallpaperInfoContract
            .getUpdateWallpaperUri()
            .appendQueryParameter(
                com.google.android.wallpaper.weathereffects.provider.WallpaperInfoContract.WEATHER_EFFECT_PARAM,
                null
            )
            .build()
        
        requireContext().contentResolver.query(uri, null, null, null, null)?.close()
    }
}