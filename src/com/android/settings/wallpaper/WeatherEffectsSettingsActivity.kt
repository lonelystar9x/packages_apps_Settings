package com.android.settings.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.wallpaper.weathereffects.provider.WallpaperInfoContract
import kotlinx.coroutines.*

/**
 * Settings activity to configure and apply weather wallpaper effects
 * independently of the Pixel launcher.
 */
class WeatherEffectsSettingsActivity : AppCompatActivity() {
    
    private lateinit var wallpaperManager: WallpaperManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // UI Components
    private lateinit var previewImage: ImageView
    private lateinit var weatherSpinner: Spinner
    private lateinit var intensitySlider: SeekBar
    private lateinit var intensityValue: TextView
    private lateinit var selectImageButton: Button
    private lateinit var applyButton: Button
    private lateinit var statusText: TextView
    
    // State
    private var selectedImageUri: Uri? = null
    private var currentWeatherEffect = WallpaperInfoContract.WeatherEffect.RAIN
    private var currentIntensity = 1.0f
    
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            loadImagePreview(it)
            applyButton.isEnabled = true
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather_effects_settings)
        
        wallpaperManager = WallpaperManager.getInstance(this)
        
        initializeViews()
        setupWeatherSpinner()
        setupIntensitySlider()
        setupButtons()
        
        // Check if weather effects service is available
        checkServiceAvailability()
    }
    
    private fun initializeViews() {
        previewImage = findViewById(R.id.preview_image)
        weatherSpinner = findViewById(R.id.weather_spinner)
        intensitySlider = findViewById(R.id.intensity_slider)
        intensityValue = findViewById(R.id.intensity_value)
        selectImageButton = findViewById(R.id.select_image_button)
        applyButton = findViewById(R.id.apply_button)
        statusText = findViewById(R.id.status_text)
        
        applyButton.isEnabled = false
    }
    
    private fun setupWeatherSpinner() {
        val weatherOptions = listOf("Rain", "Snow", "Fog", "Sun", "None")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, weatherOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        weatherSpinner.adapter = adapter
        
        weatherSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentWeatherEffect = when (position) {
                    0 -> WallpaperInfoContract.WeatherEffect.RAIN
                    1 -> WallpaperInfoContract.WeatherEffect.SNOW
                    2 -> WallpaperInfoContract.WeatherEffect.FOG
                    3 -> WallpaperInfoContract.WeatherEffect.SUN
                    else -> WallpaperInfoContract.WeatherEffect.RAIN
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupIntensitySlider() {
        intensitySlider.max = 100
        intensitySlider.progress = 100
        
        intensitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentIntensity = progress / 100f
                intensityValue.text = "$progress%"
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun setupButtons() {
        selectImageButton.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
        
        applyButton.setOnClickListener {
            applyWeatherWallpaper()
        }
    }
    
    private fun loadImagePreview(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                withContext(Dispatchers.Main) {
                    previewImage.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@WeatherEffectsSettingsActivity,
                        "Error loading image: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private fun applyWeatherWallpaper() {
        val uri = selectedImageUri ?: return
        
        statusText.text = "Applying wallpaper..."
        applyButton.isEnabled = false
        
        scope.launch(Dispatchers.IO) {
            try {
                // Get the real file path from URI
                val filePath = getRealPathFromUri(uri)
                
                if (filePath == null) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Error: Could not get file path"
                        applyButton.isEnabled = true
                    }
                    return@launch
                }
                
                // Process image (create foreground/background if needed)
                val (foreground, background) = processImage(filePath)
                
                // Update wallpaper via content provider
                updateWallpaperViaProvider(foreground, background, currentWeatherEffect)
                
                // Set the live wallpaper
                setLiveWallpaper()
                
                withContext(Dispatchers.Main) {
                    statusText.text = "Wallpaper applied successfully!"
                    Toast.makeText(
                        this@WeatherEffectsSettingsActivity,
                        "Weather wallpaper applied!",
                        Toast.LENGTH_LONG
                    ).show()
                    applyButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(
                        this@WeatherEffectsSettingsActivity,
                        "Failed to apply wallpaper: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    applyButton.isEnabled = true
                }
            }
        }
    }
    
    private fun getRealPathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }
    
    // Add segmentation helper
    private var segmentationHelper: TFLiteSegmentationHelper? = null
    
    private suspend fun processImage(filePath: String): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            statusText.text = "Processing image with AI..."
            
            // Initialize segmentation helper if needed
            if (segmentationHelper == null) {
                segmentationHelper = TFLiteSegmentationHelper(this@WeatherEffectsSettingsActivity)
            }
            
            // Load the image
            val originalBitmap = BitmapFactory.decodeFile(filePath)
                ?: throw Exception("Failed to load image")
            
            withContext(Dispatchers.Main) {
                statusText.text = "Separating foreground and background..."
            }
            
            // Perform ML-based segmentation
            val result = segmentationHelper!!.segmentImage(originalBitmap)
            
            withContext(Dispatchers.Main) {
                statusText.text = "Segmentation confidence: ${(result.confidence * 100).toInt()}%"
            }
            
            // Save segmented images
            val tempDir = File(cacheDir, "weather_temp")
            tempDir.mkdirs()
            
            val foregroundFile = File(tempDir, "foreground_${System.currentTimeMillis()}.png")
            val backgroundFile = File(tempDir, "background_${System.currentTimeMillis()}.png")
            
            FileOutputStream(foregroundFile).use { out ->
                result.foreground.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            FileOutputStream(backgroundFile).use { out ->
                result.background.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            // Clean up
            originalBitmap.recycle()
            result.foreground.recycle()
            result.background.recycle()
            
            // Show preview of foreground
            withContext(Dispatchers.Main) {
                val previewBitmap = BitmapFactory.decodeFile(foregroundFile.absolutePath)
                previewImage.setImageBitmap(previewBitmap)
            }
            
            Pair(foregroundFile.absolutePath, backgroundFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "ML segmentation failed, using original image", e)
            withContext(Dispatchers.Main) {
                statusText.text = "Using original image (segmentation unavailable)"
            }
            // Fallback to using same image for both
            Pair(filePath, filePath)
        }
    }
    
    private suspend fun updateWallpaperViaProvider(
        foregroundPath: String,
        backgroundPath: String,
        weatherEffect: WallpaperInfoContract.WeatherEffect
    ) {
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
        
        contentResolver.query(uri, null, null, null, null)?.close()
    }
    
    private fun setLiveWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(
                    "com.google.android.wallpaper.weathereffects",
                    "com.google.android.wallpaper.weathereffects.WeatherWallpaperService"
                )
            )
        }
        startActivity(intent)
    }
    
    private fun checkServiceAvailability() {
        try {
            val packageInfo = packageManager.getPackageInfo(
                "com.google.android.wallpaper.weathereffects",
                0
            )
            statusText.text = "Weather effects service available (v${packageInfo.versionName})"
        } catch (e: Exception) {
            statusText.text = "Weather effects service not found!"
            applyButton.isEnabled = false
            Toast.makeText(
                this,
                "Please ensure weather effects service is installed",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        segmentationHelper?.release()
    }
}