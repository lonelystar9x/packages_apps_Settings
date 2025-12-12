package com.android.settings.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.wallpaper.weathereffects.provider.WallpaperInfoContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for integrating weather wallpaper effects into your ROM.
 * Provides simple API to configure and apply weather effects without Pixel launcher dependency.
 */
object WeatherEffectsHelper {
    
    private const val WEATHER_SERVICE_PACKAGE = "com.google.android.wallpaper.weathereffects"
    private const val WEATHER_SERVICE_CLASS = "$WEATHER_SERVICE_PACKAGE.WeatherWallpaperService"
    
    /**
     * Check if weather effects service is available on the device
     */
    fun isWeatherEffectsAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(WEATHER_SERVICE_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Apply weather wallpaper with specified parameters
     * 
     * @param context Application context
     * @param foregroundPath Absolute path to foreground image
     * @param backgroundPath Absolute path to background image
     * @param weatherEffect Type of weather effect to apply
     * @return Boolean indicating success
     */
    suspend fun applyWeatherWallpaper(
        context: Context,
        foregroundPath: String,
        backgroundPath: String,
        weatherEffect: WallpaperInfoContract.WeatherEffect
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Update wallpaper data via content provider
            val uri = WallpaperInfoContract.getUpdateWallpaperUri()
                .appendQueryParameter(
                    WallpaperInfoContract.FOREGROUND_TEXTURE_PARAM,
                    foregroundPath
                )
                .appendQueryParameter(
                    WallpaperInfoContract.BACKGROUND_TEXTURE_PARAM,
                    backgroundPath
                )
                .appendQueryParameter(
                    WallpaperInfoContract.WEATHER_EFFECT_PARAM,
                    weatherEffect.value
                )
                .build()
            
            context.contentResolver.query(uri, null, null, null, null)?.close()
            
            // Set as live wallpaper
            withContext(Dispatchers.Main) {
                setWeatherLiveWallpaper(context)
            }
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Apply weather effect to existing wallpaper
     * Uses current wallpaper as both foreground and background
     */
    suspend fun applyWeatherEffectToCurrentWallpaper(
        context: Context,
        weatherEffect: WallpaperInfoContract.WeatherEffect
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val currentWallpaper = wallpaperManager.drawable
            
            // Convert drawable to bitmap and save temporarily
            // In production, you'd save this to a proper location
            val tempPath = saveBitmapTemporarily(context, currentWallpaper)
            
            applyWeatherWallpaper(context, tempPath, tempPath, weatherEffect)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Clear weather effect (set to no effect)
     */
    suspend fun clearWeatherEffect(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = WallpaperInfoContract.getUpdateWallpaperUri()
                .appendQueryParameter(
                    WallpaperInfoContract.WEATHER_EFFECT_PARAM,
                    null
                )
                .build()
            
            context.contentResolver.query(uri, null, null, null, null)?.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Set the weather effects live wallpaper as active
     */
    fun setWeatherLiveWallpaper(context: Context) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(WEATHER_SERVICE_PACKAGE, WEATHER_SERVICE_CLASS)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * Get list of available weather effects
     */
    fun getAvailableWeatherEffects(): List<WeatherEffectInfo> {
        return listOf(
            WeatherEffectInfo(
                WallpaperInfoContract.WeatherEffect.RAIN,
                "Rain",
                "Animated rain drops with water splashes"
            ),
            WeatherEffectInfo(
                WallpaperInfoContract.WeatherEffect.SNOW,
                "Snow",
                "Falling snow with accumulation effects"
            ),
            WeatherEffectInfo(
                WallpaperInfoContract.WeatherEffect.FOG,
                "Fog",
                "Misty fog layers with depth"
            ),
            WeatherEffectInfo(
                WallpaperInfoContract.WeatherEffect.SUN,
                "Sunny",
                "Warm sunny effect with color grading"
            )
        )
    }
    
    /**
     * Update weather effect intensity
     * Note: This requires modifying the WeatherEngine to support runtime intensity changes
     */
    fun updateEffectIntensity(context: Context, intensity: Float) {
        // Send broadcast to update intensity
        val intent = Intent("com.google.android.wallpaper.weathereffects.UPDATE_INTENSITY").apply {
            putExtra("intensity", intensity)
            setPackage(WEATHER_SERVICE_PACKAGE)
        }
        context.sendBroadcast(intent)
    }
    
    private fun saveBitmapTemporarily(context: Context, drawable: android.graphics.drawable.Drawable?): String {
        // TODO: Implement bitmap saving
        // This is a placeholder - you'd need to actually save the bitmap
        return ""
    }
    
    /**
     * Data class representing weather effect information
     */
    data class WeatherEffectInfo(
        val effect: WallpaperInfoContract.WeatherEffect,
        val displayName: String,
        val description: String
    )
}