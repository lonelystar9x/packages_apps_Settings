/*
 * Copyright (C) 2024 Infinity X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.ui;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

public class AnimateWallpaperView extends ImageView {

    private Context contextM;
    private Bitmap wallpaperBitmap;
    private Paint paint;
    private Paint dimPaint;
    private RectF viewportRect;
    private ValueAnimator animator;
    private long animationDuration = 150000L;
    private float zoomFactor = 1.1f;
    private float dimAlpha = 0.2f;

    public AnimateWallpaperView(Context context) {
        super(context);
        contextM = context;
        init();
    }

    public AnimateWallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        contextM = context;
        init();
    }

    public AnimateWallpaperView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        contextM = context;
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setColorFilter(null);
        viewportRect = new RectF(0, 0, 100, 100);
        
        dimPaint = new Paint();
        dimPaint.setColor(Color.argb((int) (dimAlpha * 255), 0, 0, 0));
        dimPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        WallpaperManager wallpaperManager = WallpaperManager.getInstance(contextM);
        wallpaperBitmap = ((BitmapDrawable) wallpaperManager.getDrawable()).getBitmap();
        updateViewport(w, h);
        startFloatingAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (wallpaperBitmap != null) {
            canvas.drawBitmap(wallpaperBitmap, 
                    new android.graphics.Rect((int)viewportRect.left, (int)viewportRect.top, 
                    (int)(viewportRect.right), (int)(viewportRect.bottom)), 
                    new android.graphics.Rect(0, 0, getWidth(), getHeight()), paint);
        }
        
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
    }
    
    private void updateViewport(int viewWidth, int viewHeight) {
        int wallpaperWidth = wallpaperBitmap.getWidth();
        int wallpaperHeight = wallpaperBitmap.getHeight();
        float viewportWidth = wallpaperWidth / zoomFactor;
        float viewportHeight = wallpaperHeight / zoomFactor;

        viewportRect.set(0, 0, viewportWidth, viewportHeight);
    }

    private void startFloatingAnimation() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(animationDuration);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());

        animator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            float dx = fraction * (wallpaperBitmap.getWidth() - getWidth());
            float dy = fraction * (wallpaperBitmap.getHeight() - getHeight());

            viewportRect.set(dx, dy, dx + (getWidth() / zoomFactor), dy + (getHeight() / zoomFactor));

            invalidate();
        });

        animator.start();
    }

    public void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator.removeAllUpdateListeners();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }
    
    public void setDimAlpha(float alpha) {
        dimAlpha = alpha;
        dimPaint.setColor(Color.argb((int) (dimAlpha * 255), 0, 0, 0));
        invalidate();
    }
}

