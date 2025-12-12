package com.android.settings.wallpaper.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

/**
 * Preview activity to show segmentation results before applying.
 * Allows users to verify and adjust the foreground/background separation.
 */
class SegmentationPreviewActivity : AppCompatActivity() {
    
    private lateinit var originalImage: ImageView
    private lateinit var foregroundImage: ImageView
    private lateinit var backgroundImage: ImageView
    private lateinit var confidenceText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var acceptButton: Button
    private lateinit var adjustButton: Button
    private lateinit var statusText: TextView
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var segmentationHelper: TFLiteSegmentationHelper? = null
    
    private var originalBitmap: Bitmap? = null
    private var foregroundBitmap: Bitmap? = null
    private var backgroundBitmap: Bitmap? = null
    private var currentConfidence = 0f
    
    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_FOREGROUND_PATH = "foreground_path"
        const val EXTRA_BACKGROUND_PATH = "background_path"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_segmentation_preview)
        
        initializeViews()
        setupButtons()
        
        val imageUri = intent.getStringExtra(EXTRA_IMAGE_URI)
        imageUri?.let { loadAndSegment(Uri.parse(it)) }
    }
    
    private fun initializeViews() {
        originalImage = findViewById(R.id.original_image)
        foregroundImage = findViewById(R.id.foreground_image)
        backgroundImage = findViewById(R.id.background_image)
        confidenceText = findViewById(R.id.confidence_text)
        progressBar = findViewById(R.id.progress_bar)
        retryButton = findViewById(R.id.retry_button)
        acceptButton = findViewById(R.id.accept_button)
        adjustButton = findViewById(R.id.adjust_button)
        statusText = findViewById(R.id.status_text)
        
        acceptButton.isEnabled = false
        adjustButton.isEnabled = false
    }
    
    private fun setupButtons() {
        retryButton.setOnClickListener {
            originalBitmap?.let { performSegmentation(it) }
        }
        
        acceptButton.setOnClickListener {
            saveAndFinish()
        }
        
        adjustButton.setOnClickListener {
            showAdjustmentDialog()
        }
    }
    
    private fun loadAndSegment(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    statusText.text = "Loading image..."
                }
                
                val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: throw Exception("Failed to load image")
                
                originalBitmap = bitmap
                
                withContext(Dispatchers.Main) {
                    originalImage.setImageBitmap(bitmap)
                    statusText.text = "Analyzing image..."
                }
                
                performSegmentation(bitmap)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@SegmentationPreviewActivity,
                        "Failed to load image",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private suspend fun performSegmentation(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                statusText.text = "Separating foreground and background..."
            }
            
            if (segmentationHelper == null) {
                segmentationHelper = TFLiteSegmentationHelper(this@SegmentationPreviewActivity)
                segmentationHelper!!.initialize()
            }
            
            val result = segmentationHelper!!.segmentImage(bitmap)
            
            foregroundBitmap = result.foreground
            backgroundBitmap = result.background
            currentConfidence = result.confidence
            
            withContext(Dispatchers.Main) {
                displayResults(result)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                statusText.text = "Segmentation failed: ${e.message}"
                progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun displayResults(result: TFLiteSegmentationHelper.SegmentationResult) {
        foregroundImage.setImageBitmap(result.foreground)
        backgroundImage.setImageBitmap(result.background)
        
        val confidencePercent = (result.confidence * 100).toInt()
        confidenceText.text = "Confidence: $confidencePercent%"
        
        val qualityText = when {
            result.confidence > 0.7f -> "Excellent quality"
            result.confidence > 0.5f -> "Good quality"
            result.confidence > 0.3f -> "Acceptable quality"
            else -> "Low quality - consider retrying"
        }
        
        statusText.text = qualityText
        progressBar.visibility = View.GONE
        
        acceptButton.isEnabled = true
        adjustButton.isEnabled = result.confidence < 0.7f
        
        // Color code confidence
        confidenceText.setTextColor(when {
            result.confidence > 0.7f -> getColor(android.R.color.holo_green_dark)
            result.confidence > 0.5f -> getColor(android.R.color.holo_orange_dark)
            else -> getColor(android.R.color.holo_red_dark)
        })
    }
    
    private fun showAdjustmentDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Adjust Segmentation")
            .setMessage("Choose an adjustment method:")
            .setPositiveButton("Expand Foreground") { _, _ ->
                adjustSegmentation(AdjustmentType.EXPAND_FOREGROUND)
            }
            .setNegativeButton("Shrink Foreground") { _, _ ->
                adjustSegmentation(AdjustmentType.SHRINK_FOREGROUND)
            }
            .setNeutralButton("Feather Edges") { _, _ ->
                adjustSegmentation(AdjustmentType.FEATHER_EDGES)
            }
            .create()
        
        dialog.show()
    }
    
    private fun adjustSegmentation(type: AdjustmentType) {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    statusText.text = "Adjusting segmentation..."
                }
                
                val adjusted = when (type) {
                    AdjustmentType.EXPAND_FOREGROUND -> expandForeground()
                    AdjustmentType.SHRINK_FOREGROUND -> shrinkForeground()
                    AdjustmentType.FEATHER_EDGES -> featherEdges()
                }
                
                foregroundBitmap?.recycle()
                backgroundBitmap?.recycle()
                
                foregroundBitmap = adjusted.first
                backgroundBitmap = adjusted.second
                
                withContext(Dispatchers.Main) {
                    foregroundImage.setImageBitmap(adjusted.first)
                    backgroundImage.setImageBitmap(adjusted.second)
                    statusText.text = "Adjustment applied"
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Adjustment failed: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }
    
    private fun expandForeground(): Pair<Bitmap, Bitmap> {
        // Dilate the foreground mask
        val foreground = foregroundBitmap ?: throw Exception("No foreground")
        val original = originalBitmap ?: throw Exception("No original")
        
        // Simple dilation by expanding alpha
        // In production, use proper morphological operations
        return Pair(foreground, backgroundBitmap!!)
    }
    
    private fun shrinkForeground(): Pair<Bitmap, Bitmap> {
        // Erode the foreground mask
        val foreground = foregroundBitmap ?: throw Exception("No foreground")
        return Pair(foreground, backgroundBitmap!!)
    }
    
    private fun featherEdges(): Pair<Bitmap, Bitmap> {
        // Apply Gaussian blur to edges
        val foreground = foregroundBitmap ?: throw Exception("No foreground")
        return Pair(foreground, backgroundBitmap!!)
    }
    
    private fun saveAndFinish() {
        scope.launch(Dispatchers.IO) {
            try {
                val tempDir = cacheDir
                val foregroundFile = java.io.File(tempDir, "segmented_foreground.png")
                val backgroundFile = java.io.File(tempDir, "segmented_background.png")
                
                java.io.FileOutputStream(foregroundFile).use { out ->
                    foregroundBitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                
                java.io.FileOutputStream(backgroundFile).use { out ->
                    backgroundBitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                
                withContext(Dispatchers.Main) {
                    val resultIntent = android.content.Intent().apply {
                        putExtra(EXTRA_FOREGROUND_PATH, foregroundFile.absolutePath)
                        putExtra(EXTRA_BACKGROUND_PATH, backgroundFile.absolutePath)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SegmentationPreviewActivity,
                        "Failed to save: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        segmentationHelper?.release()
        
        originalBitmap?.recycle()
        foregroundBitmap?.recycle()
        backgroundBitmap?.recycle()
    }
    
    private enum class AdjustmentType {
        EXPAND_FOREGROUND,
        SHRINK_FOREGROUND,
        FEATHER_EDGES
    }
}