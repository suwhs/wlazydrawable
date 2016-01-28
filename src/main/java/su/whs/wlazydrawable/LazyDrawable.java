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
import android.support.v7.appcompat.BuildConfig;
import android.util.Log;

import java.util.Comparator;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by igor n. boulliev <igor@whs.su> on 29.08.15.
 */

/**
 * abstract class for loading on demand drawable
 * for example, it may display local cached jpeg as preview, and load high quality png on
 * request
 *
 */

public abstract class LazyDrawable extends Drawable implements Animatable, Drawable.Callback {
    private static final String TAG = "LazyDrawable";
    private static WeakHashMap<Object,ThreadPoolExecutor> executor = new WeakHashMap<Object, ThreadPoolExecutor>();//new ThreadPoolExecutor(1,1,1000L, TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(500));
    private Drawable.Callback mCallbackCompat = null;
    private Drawable mDrawable = null;
    private Drawable mLoadingDrawable = null;
    private Drawable mErrorDrawable = null;
    private Drawable mPlaceholderDrawable = null;
    private boolean mIsError = false;
    private Object mExecutorTag = null;
    protected Rect mBounds = new Rect();
    private ScaleType mScaleType = ScaleType.CENTER_CROP;
    private int mEdgeColor = Color.WHITE;
    private int mRealWidth = -1;
    private int mRealHeight = -1;
    private int mLoadingPriority = 0;
    /**
     *
     * @param executorTag - tag for queue
     * @param srcWidth    - source image width
     * @param srcHeight   - source image height
     * @param scaleType   - scale type
     */
    public LazyDrawable(Object executorTag, int srcWidth, int srcHeight, ScaleType scaleType) {
        mExecutorTag = executorTag;
        mScaleType = scaleType;
        setSize(srcWidth,srcHeight);
    }
    public synchronized void setLoadingPriority(int priority) {
        mLoadingPriority = priority;
    }

    public synchronized int getLoadingPriority() { return mLoadingPriority; }
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

    public boolean isLoading() { return mInitialLoadingRunnable.isRunning(); }

    public synchronized void Unload() {
        setDrawable(null);
        synchronized (this) {
            mIsError = false;
            mDrawable = null;
        }
    }

    public abstract void onVisibilityChanged(boolean visible);

    public synchronized int getWrappedDrawableWidth() {
        if (mDrawable!=null) {
            return mDrawable.getIntrinsicWidth();
        }
        return getIntrinsicWidth();
    }

    public synchronized int getWrappedDrawableHeight() {
        if (mDrawable!=null) {
            return mDrawable.getIntrinsicHeight();
        }
        return getIntrinsicHeight();
    }

    /**
     * ScaleType (if real image geometry different to srcWidth/srcHeight, passed with constuctor
     */

    public enum ScaleType {
        NONE,
        /** fill entire srcWidth/srcHeight **/
        FILL,
        /** scale to fit, if real width/height are bigger than passed to constructor **/
        SCALE_FIT,
        /** scale and crop, so no empty spaces in srcWidth/srcHeight **/
        CENTER_CROP
    }

    /**
     *
     * @param tag
     * @return ThreadPoolExecute for tag
     */

    private static synchronized ThreadPoolExecutor getExecutorWithTag(Object tag, int poolSize, int maxPoolSize) {
        if (!executor.containsKey(tag)) {
            BlockingQueue<Runnable> bq = new PriorityBlockingQueue<>(5, new RunnableComparator());
            executor.put(tag, new CustomThreadPoolExecutor(poolSize, maxPoolSize, 100L, TimeUnit.SECONDS, bq));
        }
        return executor.get(tag);
    }

    /**
     * MUST returns Drawable on Demand
     * @return
     */

    protected abstract Drawable readDrawable();

    /**
     * must be implemented - it's
     * called when readDrawable() returns NULL
     */

    protected abstract void onLoadingError();

    /**
     * handle loading error
     */
    protected synchronized void handleLoadError() {
        mIsError = true;
        onLoadingError();
        invalidateSelfOnUiThread();
    }

    /**
     * background loading
     */
    private LoadingRunnable mInitialLoadingRunnable = new LoadingRunnable() {
        private boolean mCancelled = false;
        private boolean mIsRunning = false;

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public void run() {
            boolean _cancelled = false;
            synchronized (this) { _cancelled = mCancelled; }
            if (_cancelled) { uncancel(); return; }
            Drawable d = null;
            try {
                synchronized (this) { mIsRunning = true; }
                 d = readDrawable();
            } finally {
                synchronized (this) { mIsRunning = false; }
            }
            if (d==null) {
                handleLoadError();
            } else {
                setDrawable(d);
                handleLoadFinish();
            }
        }

        @Override
        public void onExecutionFailed(Throwable t) {
            handleLoadError();
        }

        @Override
        public synchronized void cancel() {
            mCancelled = true;
        }

        @Override
        public synchronized void uncancel() {
            mCancelled = false;
        }

        @Override
        public synchronized boolean isRunning() {
            return mIsRunning;
        }
    };

    /**
     * if Drawable loaded - draw it using {#ScaleType}, or draw loading progress, or draw error sign
     * @param canvas
     */

    private Paint dbgPaint = new Paint();
    {
        dbgPaint.setStyle(Paint.Style.STROKE);
        dbgPaint.setColor(Color.RED);
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
            load();
            if (isLoading()) {
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
        /* canvas.drawRect(mBounds.left+5,mBounds.top+5,mBounds.right-5,mBounds.bottom-5,dbgPaint); */
    }

    /**
     *
     * @param who
     * @param what
     * @param when
     */
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

    /**
     *
     * @return ThreadPoolExecutor
     */
    protected ThreadPoolExecutor getExecutor() {
        return getExecutorWithTag(mExecutorTag,getExecutorPoolSize(),getExecutorMaxPoolSize());
    }

    protected int getExecutorPoolSize() { return 3; }
    protected int getExecutorMaxPoolSize() { return 10; }

    /**
     * set loading progress drawable
     * @param drawable
     */
    public void setLoadingDrawable(Drawable drawable) {
        mLoadingDrawable = drawable;
    }

    /**
     * set error sign drawable
     * @param drawable
     */
    public void setErrorDrawable(Drawable drawable) {
        mErrorDrawable = drawable;
    }

    public void setPlaceholderDrawable(Drawable drawable) { mPlaceholderDrawable = drawable; }

    @Override
    public int getIntrinsicWidth() {
        // synchronized (this) { if (mDrawable!=null) return mDrawable.getIntrinsicWidth(); }
        // if (mLoadingDrawable!=null) return mLoadingDrawable.getIntrinsicWidth();
        return mRealWidth; //mBounds.width();
    }

    @Override
    public int getIntrinsicHeight() {
        // synchronized (this) { if (mDrawable!=null) return mDrawable.getIntrinsicHeight(); }
        // if (mLoadingDrawable!=null) return mLoadingDrawable.getIntrinsicHeight();
        return mRealHeight; // mBounds.height();
    }

    @Override
    public void setBounds(final int left, final int top, final int right, final int bottom) {
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
                Log.e(TAG,"WARNING: bounds size are zero");
            }
            invalidateSelfOnUiThread();
            return;
        }
        int w = drawable.getIntrinsicWidth() * getSampling();
        int h = drawable.getIntrinsicHeight() * getSampling();
        if (w<1||h<1) {
            synchronized (this) {
                mIsError = true;
            }
            invalidateSelfOnUiThread();
            return;
        }
        switch (mScaleType) {
            case NONE:
                drawable.setBounds(mBounds);
                break;
            case FILL: // combination of center_crop and scale_fit
                if (w<mBounds.width() && h<mBounds.height()) {
                    int dW = mBounds.width() - w;
                    int dH = mBounds.height() - h;
                    int sX = dW / 2;
                    int sY = dH / 2;
                    drawable.setBounds(mBounds.left+sX,mBounds.top+sY,mBounds.right-sX, mBounds.bottom-sY);
                    break;
                }
                float sH = (float)mBounds.height() / h;
                float sW = (float)mBounds.width() / w;
                float ratio = sW > sH ? sH : sW;
                int dW = (int) (ratio < 1f ? (w * ratio) : w);
                int dH = (int) (ratio < 1f ? (h * ratio) : h);
                int sX = (mBounds.width()-dW) / 2;
                int sY = (mBounds.height()-dH) / 2;
                drawable.setBounds(mBounds.left+sX,mBounds.top+sY,mBounds.right-sX, mBounds.bottom-sY);
                break;
            case CENTER_CROP:
                dW = mBounds.width() - w;
                dH = mBounds.height() - h;
                sX = dW / 2;
                sY = dH / 2;
                drawable.setBounds(mBounds.left+sX,mBounds.top+sY,mBounds.right-sX, mBounds.bottom-sY);
                break;
            case SCALE_FIT:
                if (w<1||h<1) {
                    synchronized (this) {
                        mIsError = true;
                    }
                    invalidateSelfOnUiThread();
                    return;
                }
                sH = (float)mBounds.height() / h;
                sW = (float)mBounds.width() / w;
                ratio = sW > sH ? sH : sW;
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
        mRealWidth = srcWidth;
        mRealHeight = srcHeight;
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
        canvas.drawRect(0, mBounds.bottom - mVerticalEdgeSize, mBounds.right, mBounds.bottom, mEdgePaint);
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
            drawProgress(canvas, progress, angle, 255);
        }
    }

    /**
     * draw progress drawable on canvas
     * @param canvas
     * @param progress - drawable
     * @param angle - rotate angle
     * @param alpha - alpha
     */
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
        if (drawable==null) return;
        drawable.setCallback(null);
        if (drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable)drawable).getBitmap();
            recycleBitmap(bmp);
        }
    }

    protected void recycleBitmap(Bitmap bmp) {
        if (bmp!=null && !bmp.isRecycled())
            bmp.recycle();
    }

    protected synchronized void handleLoadFinish() {
        invalidateSelfOnUiThread();
    }

    protected synchronized void setDrawable(Drawable drawable) {
        if (mDrawable!=null && isRunning()) {
            mDrawable.setCallback(null); // remove callbacks from drawable
            onDrawableReleased(mDrawable);
            stop();
        }
        mDrawable = drawable;
        // mIsError = (drawable == null);
        if (mDrawable!=null) {
            applyBounds(drawable);
            mDrawable.setCallback(this);
        }
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        if (getCallbackCompat()!=null)
            invalidateSelfOnUiThread();
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

    protected abstract class LoadingRunnable extends ComparableRunnable {
        public abstract void onExecutionFailed(Throwable t);
        public abstract void cancel();
        public abstract void uncancel();
        public abstract boolean isRunning();
    }

    protected static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        private boolean mIsCancelled = false;
        public CustomThreadPoolExecutor(
                int corePoolSize, int maximumPoolSize, long keepAliveTime,
                TimeUnit unit, BlockingQueue<Runnable> workQueue) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            super.beforeExecute(t, r);
            if (mIsCancelled) getQueue().clear();
        }

        @Override
        public void execute(Runnable command) {
            if (!mIsCancelled)
                super.execute(command);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return super.submit(task);
        }

        @Override
        public void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t != null && r instanceof LoadingRunnable) {
                LoadingRunnable lr = (LoadingRunnable)r;
                lr.onExecutionFailed(t);
            }
        }

        @Override
        public void terminated() {
            super.terminated();
        }
    }

    protected synchronized void retry() {
        mIsError = false;
        mInitialLoadingRunnable.uncancel();
    }

    public synchronized void load() {
        if (mDrawable!=null||isLoading()) return;
        ThreadPoolExecutor executor = getExecutor();
        if (executor.getQueue().contains(mInitialLoadingRunnable)) return;
        try {
            executor.execute(mInitialLoadingRunnable);
        } catch (RejectedExecutionException e) {
            Log.e(TAG,"too mach task!");
            handleLoadError();
        }
    }

    public synchronized void stopLoading() {
        mInitialLoadingRunnable.cancel();
    }

    protected abstract int getSampling();

    static abstract class ComparableRunnable implements Runnable {
        public abstract int getPriority();
    }

    public static class RunnableComparator implements Comparator<Runnable> {
        public int compare(Runnable r1, Runnable r2){
            ComparableRunnable t1 = (ComparableRunnable)r1;
            ComparableRunnable t2 = (ComparableRunnable)r2;
            return t1.getPriority()-t2.getPriority();
        }
    }
}
