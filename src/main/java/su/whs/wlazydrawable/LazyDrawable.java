/*
 * Copyright 2015 whs.su
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package su.whs.wlazydrawable;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by igor n. boulliev on 29.08.15.
 */

/**
 * abstract class for loading on demand drawable
 * for example, it may display local cached jpeg as preview, and load high quality png on
 * request
 *
 */

public abstract class LazyDrawable extends Drawable implements Animatable, Drawable.Callback {
    private static Map<WeakReference<Object>,ThreadPoolExecutor> executor = new HashMap<WeakReference<Object>, ThreadPoolExecutor>();//new ThreadPoolExecutor(1,1,1000L, TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(500));
    private Drawable.Callback mCallbackCompat = null;
    private Drawable mDrawable = null;
    private Drawable mLoadingDrawable = null;
    private Drawable mErrorDrawable = null;
    private boolean mIsLoading = false;
    private boolean mIsError = false;
    private Object mExecutorTag = null;
    protected Rect mBounds = new Rect();
    private ScaleType mScaleType = ScaleType.CENTER_CROP;
    private int mEdgeColor = Color.WHITE;

    public LazyDrawable(Object executorTag, int srcWidth, int srcHeight, ScaleType scaleType) {
        mExecutorTag = executorTag;
        setBounds(0,0,srcWidth,srcHeight);
        mScaleType = scaleType;
        setBounds(0, 0, srcWidth, srcHeight);
    }

    /*
        on AOSP < 11 this method must be used instead setCallback()
     */

    public void setCallbackCompat(Drawable.Callback cb) {
        if (Build.VERSION.SDK_INT>10) {
            setCallback(cb);
            return;
        }
        mCallbackCompat = cb;
    }

    public void setScaleType(ScaleType scaleType) {
        if (mScaleType == scaleType) return;
        mScaleType = scaleType;
        synchronized (this) {
            if (mDrawable!=null) invalidateSelfOnUiThread();
        }
    }

    public synchronized void Unload() {
        setDrawable(null);
        synchronized (this) {
            mIsLoading = false;
            mIsError = false;
            mDrawable = null;
        }
    }

    public abstract void onVisibilityChanged(boolean visible);

    public enum ScaleType {
        FILL,
        SCALE_FIT,
        CENTER_CROP
    }

    private static synchronized ThreadPoolExecutor getExecutorWithTag(Object tag) {
        WeakReference<Object> wtag = new WeakReference<Object>(tag);
        if (!executor.containsKey(wtag))
            executor.put(wtag, new ThreadPoolExecutor(1, 1, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(100)));
        return executor.get(wtag);
    }

    protected abstract Drawable readDrawable();
    protected abstract void onLoadingError();

    protected synchronized void handleLoadError() {
        mIsLoading = false;
        mIsError = true;
        onLoadingError();
        invalidateSelfOnUiThread();
    }
    private Runnable mInitialLoadingRunnable = new Runnable() {
        @Override
        public void run() {
            Drawable d = readDrawable();
            if (d==null) {
                handleLoadError();
            } else {
                setDrawable(d);
                setLoadingState(false);
            }
        }
    };

    protected synchronized void setLoadingState(boolean loading) {
        if (loading==mIsLoading) return;
        mIsLoading = loading;
        invalidateSelfOnUiThread();
    }

    public void draw(Canvas canvas) {
        Drawable d;
        boolean isError;
        synchronized (this) {
            d = mDrawable;
            isError = mIsError;
        }
        if (d!=null) drawDrawable(canvas,d); else if (BuildConfig.DEBUG) {
            d = null;
        }

        if (mLoadingDrawable!=null && !isError) {
            boolean isLoading;
            synchronized (this) {
                isLoading = mIsLoading;
            }
            if (!isLoading && d == null) { // start loading if no drawable
                synchronized (this) { mIsLoading = true; isLoading = true; }
                getExecutor().execute(mInitialLoadingRunnable);
            }
            if (isLoading) {
                drawNextLoadingFrame(canvas);
                scheduleSelf(new Runnable() {
                        @Override
                        public void run() {
                            invalidateSelf();
                        }
                    }, SystemClock.uptimeMillis()+60);
            }
        } else if (isError) {
            drawLoadError(canvas);
        } else {
            if (BuildConfig.DEBUG) {
                Log.w("LazyDrawable", "Loading Drawable are null!");
            }
        }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        if (getCallbackCompat()!=null)
            getCallbackCompat().scheduleDrawable(this, what, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        if (getCallbackCompat()!=null)
            getCallbackCompat().unscheduleDrawable(this, what);
    }

    private Drawable.Callback getCallbackCompat() {
        if (Build.VERSION.SDK_INT>10) return getCallback();
        return mCallbackCompat;
    }

    protected ThreadPoolExecutor getExecutor() {
        return getExecutorWithTag(mExecutorTag);
    }
    public void setLoadingDrawable(Drawable drawable) {
        mLoadingDrawable = drawable;
    }

    public void setErrorDrawable(Drawable drawable) {
        mErrorDrawable = drawable;
    }

    @Override
    public int getIntrinsicWidth() {
        return mBounds.width();
    }

    @Override
    public int getIntrinsicHeight() {
        return mBounds.height();
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        mBounds.set(left,top,right,bottom);
        Drawable d;
        synchronized (this) {
            d = mDrawable;
        }
        if (d!=null) {
            applyBounds(d);
        }
        super.setBounds(left,top,right,bottom);
    }

    private void applyBounds(Drawable drawable) {
        if (mBounds.width()==0 || mBounds.height()==0) {
            synchronized (this) {
                mIsError = true;
            }
            return;
        }
        switch (mScaleType) {
            case FILL:
                drawable.setBounds(mBounds);
                break;
            case CENTER_CROP:
                int w = drawable.getIntrinsicWidth();
                int h = drawable.getIntrinsicHeight();
                int dW = mBounds.width() - w;
                int dH = mBounds.height() - h;
                int sX = dW / 2;
                int sY = dH / 2;
                drawable.setBounds(mBounds.left+sX,mBounds.top+sY,mBounds.right-sX, mBounds.bottom-sY);
                break;
            case SCALE_FIT:
                w = drawable.getIntrinsicWidth();
                h = drawable.getIntrinsicHeight();
                if (w<1||h<1) {
                    synchronized (this) {
                        mIsError = true;
                    }
                    invalidateSelfOnUiThread();
                    return;
                }
                float sH = (float)mBounds.height() / h;
                float sW = (float)mBounds.width() / w;
                float ratio = sW > sH ? sH : sW;
                dW = (int) (ratio < 1f ? (w * ratio) : w);
                dH = (int) (ratio < 1f ? (h * ratio) : h);
                sX = (mBounds.width()-dW) / 2;
                sY = (mBounds.height()-dH) / 2;
                drawable.setBounds(mBounds.left+sX,mBounds.top+sY,mBounds.right-sX, mBounds.bottom-sY);
                break;
        }
    }
    /**
     * set size and adjust bounds
     * @param srcWidth
     * @param srcHeight
     */

    protected void setSize(int srcWidth, int srcHeight) {
        mBounds.right = mBounds.left + srcWidth;
        mBounds.bottom = mBounds.top + srcHeight;
        if (mDrawable!=null) {
            applyBounds(mDrawable);
        }
    }

    private void drawDrawable(Canvas canvas, Drawable drawable) {
        int state = canvas.save();
        canvas.clipRect(mBounds);
        if (drawable!=null) {
            drawable.draw(canvas);
            if (mScaleType == ScaleType.CENTER_CROP) {
                if (drawable.getBounds().height() > mBounds.height()) {
                    drawVerticalEdges(canvas);
                } else if (drawable.getBounds().width() > mBounds.width()) {
                    drawHorizontalEdges(canvas);
                }
            }
        }
        canvas.restoreToCount(state);
    }

    private int mVerticalEdgeSize = 24;
    private int mHorizontalEdgeSize = 24;

    private Paint mEdgePaint = new Paint();
    {
        mEdgePaint.setStyle(Paint.Style.FILL);
    }

    private void drawVerticalEdges(Canvas canvas) {
        Shader shader;
        shader = new LinearGradient(0, 0, 0, mVerticalEdgeSize, mEdgeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(0, 0, mBounds.right, 0 + mVerticalEdgeSize, mEdgePaint);

        shader = new LinearGradient(0, mBounds.bottom-mVerticalEdgeSize, 0, mBounds.bottom, Color.TRANSPARENT, mEdgeColor, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(0,mBounds.bottom-mVerticalEdgeSize,mBounds.right,mBounds.bottom,mEdgePaint);
    }

    private void drawHorizontalEdges(Canvas canvas) {
        Shader shader;
        shader = new LinearGradient(0, 0, mHorizontalEdgeSize, 0, mEdgeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(0, 0, mHorizontalEdgeSize, mBounds.bottom, mEdgePaint);

        shader = new LinearGradient(mBounds.right-mHorizontalEdgeSize, 0, mBounds.right, 0, Color.TRANSPARENT, mEdgeColor, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(mBounds.right - mHorizontalEdgeSize, 0, mBounds.right, mBounds.bottom, mEdgePaint);
    }

    protected void drawLoadError(Canvas canvas) {
        drawProgress(canvas, mErrorDrawable, 0, 255);
    }

    private int angle = 0;

    protected void drawNextLoadingFrame(Canvas canvas) {
        Drawable progress = mLoadingDrawable;
        if (progress!=null) {
            angle += 10;
            if (angle>360) angle = 0;
            drawProgress(canvas,progress,angle,255);
        }
    }

    protected void drawProgress(Canvas canvas, Drawable progress, int angle, int alpha) {
        if (progress==null) return;
        int x = mBounds.width() /2 + mBounds.left;
        int y = mBounds.height() / 2 + mBounds.top;
        int state = canvas.save();
        canvas.translate(x,y);
        if (angle>0) {
            canvas.rotate(angle);
        }
        x = -progress.getIntrinsicWidth() / 2;
        y = -progress.getIntrinsicHeight() / 2;

        canvas.translate(x, y);

        progress.setAlpha(alpha);
        progress.draw(canvas);
        canvas.restoreToCount(state);
    }

    private int mAlpha = 255;

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
    }

    private ColorFilter mColorFilter;
    @Override
    public void setColorFilter(ColorFilter cf) {
        mColorFilter = cf;
    }

    private int mOpacity = 0;

    @Override
    public int getOpacity() {
        synchronized (this) {
            if (mDrawable!=null)
                return mDrawable.getOpacity();
            else
                return mOpacity;
        }
    }

    @Override
    public void start() {
        synchronized (this) {
            if (mDrawable instanceof Animatable) {
                ((Animatable)mDrawable).start();
            }
        }
    }

    @Override
    public void stop() {
        synchronized (this) {
            if (mDrawable instanceof Animatable) {
                ((Animatable)mDrawable).stop();
            }
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (this) {
            if (mDrawable instanceof Animatable) {
                return ((Animatable)mDrawable).isRunning();
            }
        }
        return false;
    }

    /**
     * replace wrapped drawable (and reset internal flags)
     * @param drawable
     */

    protected void onDrawableReleased(Drawable drawable) {
        if (drawable!=null && drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable)drawable).getBitmap();
            bmp.recycle();
        }
    }

    private synchronized void handleLoadFinish() {
        mIsLoading = false;
        invalidateSelfOnUiThread();
    }

    protected synchronized void setDrawable(Drawable drawable) {
        if (mDrawable!=null && isRunning()) {
            mDrawable.setCallback(null); // remove callbacks from drawable
            onDrawableReleased(mDrawable);
            stop();
        }
        mDrawable = drawable;
        mIsError = (drawable == null);
        if (mDrawable!=null) {
            applyBounds(drawable);
            mDrawable.setCallback(this);
        }
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        if (getCallbackCompat()!=null)
            getCallbackCompat().invalidateDrawable(this);
    }

    protected synchronized Drawable getDrawable() { return mDrawable; }
    protected void invalidateSelfOnUiThread() {
        if (Looper.getMainLooper().getThread().equals(Thread.currentThread())) {
            invalidateSelf();
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    LazyDrawable.this.invalidateSelf();
                }
            });
        }
    }
}
