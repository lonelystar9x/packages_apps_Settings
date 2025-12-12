package com.android.settings.wallpaper.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * TensorFlow Lite based image segmentation.
 * Uses DeepLabV3+ or similar segmentation models.
 * 
 * This is an alternative to MediaPipe with better ROM integration support.
 */
class TFLiteSegmentationHelper(private val context: Context) {
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isInitialized = false
    
    // Model input/output dimensions
    private var inputWidth = 257
    private var inputHeight = 257
    private var numClasses = 21 // DeepLabV3 has 21 classes
    
    companion object {
        private const val TAG = "TFLiteSegmentation"
        private const val MODEL_FILE = "deeplabv3_257_mv_gpu.tflite"
        
        // Person class in PASCAL VOC (DeepLabV3)
        private const val PERSON_CLASS = 15
        
        // Image preprocessing constants
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f
    }
    
    /**
     * Initialize TensorFlow Lite interpreter with GPU acceleration.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext true
            
            // Load model
            val model = loadModelFile()
            
            // Try GPU delegate first
            try {
                gpuDelegate = GpuDelegate()
                val options = Interpreter.Options().apply {
                    addDelegate(gpuDelegate)
                    setNumThreads(4)
                }
                interpreter = Interpreter(model, options)
                Log.d(TAG, "Using GPU acceleration")
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate failed, using CPU", e)
                gpuDelegate?.close()
                gpuDelegate = null
                
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interpreter = Interpreter(model, options)
            }
            
            // Get actual input dimensions from model
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            if (inputShape != null && inputShape.size >= 3) {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }
            
            isInitialized = true
            Log.d(TAG, "Model initialized: ${inputWidth}x${inputHeight}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model", e)
            false
        }
    }
    
    /**
     * Perform image segmentation using TFLite model.
     */
    suspend fun segmentImage(inputBitmap: Bitmap): SegmentationResult = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            val initialized = initialize()
            if (!initialized) {
                return@withContext createFallbackSegmentation(inputBitmap)
            }
        }
        
        try {
            // Prepare input
            val scaledBitmap = Bitmap.createScaledBitmap(inputBitmap, inputWidth, inputHeight, true)
            val inputBuffer = prepareInputBuffer(scaledBitmap)
            
            // Prepare output
            val outputBuffer = ByteBuffer.allocateDirect(inputHeight * inputWidth * numClasses * 4)
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)
            
            // Process output
            val maskBitmap = processMask(outputBuffer, inputHeight, inputWidth)
            
            // Scale mask back to original size
            val fullSizeMask = Bitmap.createScaledBitmap(
                maskBitmap,
                inputBitmap.width,
                inputBitmap.height,
                true
            )
            
            // Extract foreground and background
            val foreground = extractForeground(inputBitmap, fullSizeMask)
            val background = extractBackground(inputBitmap, fullSizeMask)
            
            // Clean up
            scaledBitmap.recycle()
            maskBitmap.recycle()
            fullSizeMask.recycle()
            
            SegmentationResult(
                foreground = foreground,
                background = background,
                confidence = calculateSegmentationQuality(fullSizeMask)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed", e)
            createFallbackSegmentation(inputBitmap)
        }
    }
    
    /**
     * Load TFLite model from assets.
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * Prepare input buffer with proper normalization.
     */
    private fun prepareInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputHeight * inputWidth * 3)
        buffer.order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        
        for (pixel in pixels) {
            // Normalize RGB values
            val r = (Color.red(pixel) - IMAGE_MEAN) / IMAGE_STD
            val g = (Color.green(pixel) - IMAGE_MEAN) / IMAGE_STD
            val b = (Color.blue(pixel) - IMAGE_MEAN) / IMAGE_STD
            
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        
        return buffer
    }
    
    /**
     * Process model output into segmentation mask.
     */
    private fun processMask(outputBuffer: ByteBuffer, height: Int, width: Int): Bitmap {
        outputBuffer.rewind()
        
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        // For each pixel, find the class with highest probability
        for (y in 0 until height) {
            for (x in 0 until width) {
                var maxProb = Float.MIN_VALUE
                var maxClass = 0
                
                // Get probabilities for all classes at this pixel
                val baseIndex = (y * width + x) * numClasses
                for (c in 0 until numClasses) {
                    outputBuffer.position((baseIndex + c) * 4)
                    val prob = outputBuffer.float
                    if (prob > maxProb) {
                        maxProb = prob
                        maxClass = c
                    }
                }
                
                // Create alpha mask (white for person, black for background)
                val alpha = if (maxClass == PERSON_CLASS) 255 else 0
                pixels[y * width + x] = Color.argb(alpha, alpha, alpha, alpha)
            }
        }
        
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }
    
    /**
     * Extract foreground using mask.
     */
    private fun extractForeground(original: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // Draw original
        canvas.drawBitmap(original, 0f, 0f, null)
        
        // Apply mask using DST_IN (keep only where mask is opaque)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(mask, 0f, 0f, paint)
        
        // Feather edges
        return featherEdges(result)
    }
    
    /**
     * Extract background using inverted mask.
     */
    private fun extractBackground(original: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // Draw original
        canvas.drawBitmap(original, 0f, 0f, null)
        
        // Apply inverted mask using DST_OUT (keep only where mask is transparent)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        canvas.drawBitmap(mask, 0f, 0f, paint)
        
        return result
    }
    
    /**
     * Apply edge feathering for smoother transitions.
     */
    private fun featherEdges(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val feathered = IntArray(width * height)
        val featherRadius = 2
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val alpha = Color.alpha(pixels[idx])
                
                // Only feather edges (pixels with alpha between 0 and 255)
                if (alpha > 0 && alpha < 255) {
                    var sumAlpha = 0
                    var count = 0
                    
                    for (dy in -featherRadius..featherRadius) {
                        for (dx in -featherRadius..featherRadius) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                sumAlpha += Color.alpha(pixels[ny * width + nx])
                                count++
                            }
                        }
                    }
                    
                    val avgAlpha = sumAlpha / count
                    feathered[idx] = Color.argb(
                        avgAlpha,
                        Color.red(pixels[idx]),
                        Color.green(pixels[idx]),
                        Color.blue(pixels[idx])
                    )
                } else {
                    feathered[idx] = pixels[idx]
                }
            }
        }
        
        result.setPixels(feathered, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * Calculate segmentation quality/confidence.
     */
    private fun calculateSegmentationQuality(mask: Bitmap): Float {
        var foregroundPixels = 0
        var edgePixels = 0
        val totalPixels = mask.width * mask.height
        
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                val alpha = Color.alpha(mask.getPixel(x, y))
                if (alpha > 200) {
                    foregroundPixels++
                } else if (alpha > 50) {
                    edgePixels++
                }
            }
        }
        
        val foregroundRatio = foregroundPixels.toFloat() / totalPixels
        val edgeRatio = edgePixels.toFloat() / totalPixels
        
        // Good segmentation: reasonable foreground size, smooth edges
        val sizeScore = if (foregroundRatio in 0.1f..0.7f) 1.0f else 0.5f
        val edgeScore = if (edgeRatio < 0.1f) 1.0f else 0.7f
        
        return (sizeScore + edgeScore) / 2.0f
    }
    
    /**
     * Fallback when ML is unavailable.
     */
    private fun createFallbackSegmentation(bitmap: Bitmap): SegmentationResult {
        Log.w(TAG, "Using fallback segmentation")
        
        // Simple center-weighted segmentation
        val foreground = createCenterMask(bitmap)
        val background = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        
        return SegmentationResult(
            foreground = foreground,
            background = background,
            confidence = 0.1f
        )
    }
    
    /**
     * Create center-weighted mask as fallback.
     */
    private fun createCenterMask(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        
        // Create radial gradient mask
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = kotlin.math.min(width, height) / 2f
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                val alpha = ((1.0f - (distance / maxRadius).coerceIn(0f, 1f)) * 255).toInt()
                
                val pixel = pixels[y * width + x]
                pixels[y * width + x] = Color.argb(
                    alpha,
                    Color.red(pixel),
                    Color.green(pixel),
                    Color.blue(pixel)
                )
            }
        }
        
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * Release resources.
     */
    fun release() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        isInitialized = false
    }
    
    data class SegmentationResult(
        val foreground: Bitmap,
        val background: Bitmap,
        val confidence: Float
    )
}