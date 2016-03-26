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
import android.support.annotation.CallSuper;
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
 * abstract class for loading drawable 'on demand'
 *
 *
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
    private boolean mDEBUG = true;
    /**
     * create new LazyDrawable instance
     *   if srcWidth & srcHeight equals image width and height - scaleType has no effect
     *
     * @param executorTag - tag for queue
     * @param srcWidth    - width of area, reserved for image
     * @param srcHeight   - height of area, reserved for image
     * @param scaleType   - scale type - for cases, where image width/height are unknown
     */
    public LazyDrawable(Object executorTag, int srcWidth, int srcHeight, ScaleType scaleType) {
        mExecutorTag = executorTag;
        mScaleType = scaleType;
        setSize(srcWidth,srcHeight);
    }

    /**
     * set priority for image - images with lowest priority will be pushed into head of queue
     * @param priority - integer value
     */
    public synchronized void setLoadingPriority(int priority) {
        mLoadingPriority = priority;
    }

    /**
     * actually, this method are called by executor's Comparator
     * @return actual priority for image
     */
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

    /**
     * set ScaleType for image (if constructed width/height are not same that real image's width and height)
     *
     * @param scaleType
     */

    @CallSuper
    public void setScaleType(ScaleType scaleType) {
        if (mScaleType == scaleType) return;
        mScaleType = scaleType;
        synchronized (this) {
            if (mDrawable!=null) invalidateSelfOnUiThread();
        }
    }

    /**
     * for checking if loading process are started
     * @return
     */
    public boolean isLoading() { return mInitialLoadingRunnable.isRunning(); }

    /**
     * unload drawable from memory (default behavior - calls setDrawable(null)
     */

    @CallSuper
    public synchronized void Unload() {
        setDrawable(null);
        mInitialLoadingRunnable.cancel();
        synchronized (this) {
            mIsError = false;
            mDrawable = null;
        }
    }

    /**
     * UNSTABLE
     * @param visible
     */
    public abstract void onVisibilityChanged(boolean visible);

    /**
     *
     * @return width of loaded drawable, or width passed with constructor (if no drawable loaded yet)
     */
    @Deprecated
    public synchronized int getWrappedDrawableWidth() {
        if (mDrawable!=null) {
            return mDrawable.getIntrinsicWidth();
        }
        return getIntrinsicWidth();
    }

    /**
     *
     * @return height of loaded drawable, or height passed with constructor (if no drawable loaded yet)
     */
    @Deprecated
    public synchronized int getWrappedDrawableHeight() {
        if (mDrawable!=null) {
            return mDrawable.getIntrinsicHeight();
        }
        return getIntrinsicHeight();
    }

    /**
     * returns actual bounds, applied to loaded drawable (within LazyDrawable.getBounds())
     *  to calculate drawable real image rect - move coordinates to LazyDrawable.left/top
     * @param lazyRect
     */
    public void getWrappedDrawableBounds(Rect lazyRect) {
        if (mDrawable!=null) {
            lazyRect.set(mDrawable.getBounds());
        }
    }

    /**
     * ScaleType (if real image geometry different to srcWidth/srcHeight, passed with constuctor
     */

    public enum ScaleType {
        /** no scale **/
        NONE,
        /** fill entire srcWidth/srcHeight, with scale up **/
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
     * WARNING: this method called from background thread
     * @return
     */

    protected abstract Drawable readDrawable();

    /**
     * must be implemented - it's
     * called when readDrawable() returns NULL
     * WARNING: this method called from background thread
     *
     */

    protected abstract void onLoadingError();

    /**
     * handle loading error
     * WARNING: this method called from background thread
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
        if (d!=null) {
            drawDrawable(canvas,d);
        } else {
            load();
        }
        if (mLoadingDrawable!=null && !isError && isLoading()) {
            drawNextLoadingFrame(canvas);
            scheduleSelf(new Runnable() {
                @Override
                public void run() {
                    invalidateSelf();
                }
            }, SystemClock.uptimeMillis()+60);
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
     * see Drawable.Callback.scheduleDrawable()
     * @param who
     * @param what
     * @param when
     */
    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        if (getCallbackCompat()!=null)
            getCallbackCompat().scheduleDrawable(this, what, when);
    }

    /**
     * see Drawable.Callback.unscheduleDrawable()
     * @param who
     * @param what
     */
    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        if (getCallbackCompat()!=null)
            getCallbackCompat().unscheduleDrawable(this, what);
    }

    /**
     * getCallback() not available for API < 11, so we provide getCallbackCompat() method
     * @return
     */
    private Drawable.Callback getCallbackCompat() {
        if (Build.VERSION.SDK_INT>10) return getCallback();
        return mCallbackCompat;
    }

    /**
     * constructs (if need) thread pool executor
     * @return ThreadPoolExecutor
     */
    protected ThreadPoolExecutor getExecutor() {
        return getExecutorWithTag(mExecutorTag,getExecutorPoolSize(),getExecutorMaxPoolSize());
    }

    /**
     * @return executor pool size
     */
    protected int getExecutorPoolSize() { return 3; }

    /**
     *
     * @return executor max pool size
     */
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

    /**
     *
     * @return width, passed with constructor parameter srcWidth, or drawable intrinsicWidth()
     */
    @Override
    public int getIntrinsicWidth() {
        // synchronized (this) { if (mDrawable!=null) return mDrawable.getIntrinsicWidth(); }
        // if (mLoadingDrawable!=null) return mLoadingDrawable.getIntrinsicWidth();
        return mRealWidth; //mBounds.width();
    }

    /**
     *
     * @return height, passed with constructor parameter srcWidth, or drawable intrinsicHeight
     */
    @Override
    public int getIntrinsicHeight() {
        // synchronized (this) { if (mDrawable!=null) return mDrawable.getIntrinsicHeight(); }
        // if (mLoadingDrawable!=null) return mLoadingDrawable.getIntrinsicHeight();
        return mRealHeight; // mBounds.height();
    }

    /**
     * {#Drawable.setBounds()}
     * @param left
     * @param top
     * @param right
     * @param bottom
     */
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

    @Override
    public void setBounds(Rect bounds) {
        setBounds(bounds.left,bounds.top,bounds.right,bounds.bottom);
    }

    /**
     * apply bounds to loaded drawable; calculate scaled bounds if need
     * @param drawable
     */
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
        int dW;
        int dH;
        int sX;
        int sY;
        switch (mScaleType) {
            case NONE:
                drawable.setBounds(mBounds);
                break;
            case FILL: // combination of center_crop and scale_fit
                Rect result = new Rect();
                calcCenter(mBounds.width(),mBounds.height(),w,h,false,result);
                drawable.setBounds(result.left,result.top,result.right,result.bottom);
                break;
            case CENTER_CROP:
                dW = mBounds.width() - w;
                dH = mBounds.height() - h;
                sX = dW / 2;
                sY = dH / 2;
                drawable.setBounds(mBounds.left+sX,mBounds.top+sY,mBounds.right-sX, mBounds.bottom-sY);
                break;
            case SCALE_FIT:
                result = new Rect();
                calcCenter(mBounds.width(),mBounds.height(),w,h,true    ,result);
                drawable.setBounds(result.left,result.top,result.right,result.bottom);
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

    /**
     *
     * @return loaded drawable, and set internal reference to null
     */

    public synchronized Drawable takeDrawable() {
        if (mDrawable==null) return null;
        mDrawable.setCallback(null); // remove callbacks
        Drawable result = mDrawable;
        mDrawable = null;
        return result;
    }

    private Paint dbgPaintRed = new Paint();
    {
        dbgPaintRed.setStyle(Paint.Style.STROKE);
        dbgPaintRed.setColor(Color.RED);
    }
    private void drawDrawable(Canvas canvas, Drawable drawable) {
        int state = canvas.save();
        if (mDEBUG)
            canvas.drawRect(mBounds,dbgPaint);
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

    /**
     * internal methods to draw edges
     * @param canvas
     */
    private void drawVerticalEdges(Canvas canvas) {
        Shader shader;
        shader = new LinearGradient(0, 0, 0, mVerticalEdgeSize, mEdgeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(0, 0, mBounds.right, 0 + mVerticalEdgeSize, mEdgePaint);

        shader = new LinearGradient(0, mBounds.bottom-mVerticalEdgeSize, 0, mBounds.bottom, Color.TRANSPARENT, mEdgeColor, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(0, mBounds.bottom - mVerticalEdgeSize, mBounds.right, mBounds.bottom, mEdgePaint);
    }

    /**
     * internal methods to draw edges
     * @param canvas
     */
    private void drawHorizontalEdges(Canvas canvas) {
        Shader shader;
        shader = new LinearGradient(0, 0, mHorizontalEdgeSize, 0, mEdgeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(0, 0, mHorizontalEdgeSize, mBounds.bottom, mEdgePaint);

        shader = new LinearGradient(mBounds.right-mHorizontalEdgeSize, 0, mBounds.right, 0, Color.TRANSPARENT, mEdgeColor, Shader.TileMode.CLAMP);
        mEdgePaint.setShader(shader);
        canvas.drawRect(mBounds.right - mHorizontalEdgeSize, 0, mBounds.right, mBounds.bottom, mEdgePaint);
    }

    /**
     * just draws errorDrawable on canvas
     * (override to implement custom error paint)
     * @param canvas
     */
    protected void drawLoadError(Canvas canvas) {
        drawProgress(canvas, mErrorDrawable, 0, 255);
    }

    private int angle = 0;

    /**
     * just draws loadingDrawable on canvas and increments angle
     * (override to implement custom loading animation)
     * @param canvas
     */
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
     * (override to implement custom progress paints)
     * @param canvas
     * @param progress - drawable
     * @param angle - rotate angle (from 0 to 360, if drawNextLoadingFrame not overriden)
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

    /**
     * called when bitmap no more required. MUST OVERRIDE if some caching system for bitmaps used
     * @param bmp
     */
    protected void recycleBitmap(Bitmap bmp) {
        if (bmp!=null && !bmp.isRecycled())
            bmp.recycle();
    }

    /**
     * called when loading finished
     */
    protected synchronized void handleLoadFinish() {
        invalidateSelfOnUiThread();
    }

    /**
     * set / replace 'loaded' drawable
     * @param drawable
     */
    @CallSuper
    protected synchronized void setDrawable(Drawable drawable) {
        if (mDrawable!=null && isRunning()) {
            mDrawable.setCallback(null); // remove callbacks from drawable
            onDrawableReleased(mDrawable);
            stop();
        }
        mDrawable = drawable;

        if (mDrawable!=null) {
            setSize(mDrawable.getIntrinsicWidth(),mDrawable.getIntrinsicHeight());
            applyBounds(drawable);
            mDrawable.setCallback(this);
        }
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        if (getCallbackCompat()!=null)
            invalidateSelfOnUiThread();
    }

    /**
     *
     * @return loaded drawable
     */
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

    /**
     * abstract runnable with support cancellation
     */
    protected abstract class LoadingRunnable extends ComparableRunnable {
        public abstract void onExecutionFailed(Throwable t);
        public abstract void cancel();
        public abstract void uncancel();
        public abstract boolean isRunning();
    }

    /**
     * ThreadPoolExecutor with global cancellation support
     */
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

    /**
     * retry loading drawable (if previous attemt failed with error)
     */
    @Deprecated
    protected synchronized void retry() {
        mIsError = false;
        mInitialLoadingRunnable.uncancel();
    }

    /**
     * initiate loading (by default - called with first drawing request)
     */
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

    /**
     * cancel loading
     */
    public synchronized void stopLoading() {
        mInitialLoadingRunnable.cancel();
    }

    /**
     *
     * @return sampling used with BitmapFactory.decodeFromStream
     */
    @Deprecated
    protected abstract int getSampling();

    /**
     * for prioritized queue
     */
    static abstract class ComparableRunnable implements Runnable {
        public abstract int getPriority();
    }

    /**
     * compares priorities of two runnables
     */
    public static class RunnableComparator implements Comparator<Runnable> {
        public int compare(Runnable r1, Runnable r2){
            ComparableRunnable t1 = (ComparableRunnable)r1;
            ComparableRunnable t2 = (ComparableRunnable)r2;
            if  (t1.getPriority()==t2.getPriority()) return -1;
            return t1.getPriority()-t2.getPriority();
        }
    }

    /**
     * Calculate the bounds of an image to fit inside a view after scaling and keeping the aspect ratio.
     * @param vw container view width
     * @param vh container view height
     * @param iw image width
     * @param ih image height
     * @param neverScaleUp if <code>true</code> then it will scale images down but never up when fiting
     * @param out Rect that is provided to receive the result. If <code>null</code> then a new rect will be created
     * @return Same rect object that was provided to the method or a new one if <code>out</code> was <code>null</code>
     */
    public static Rect calcCenter (int vw, int vh, int iw, int ih, boolean neverScaleUp, Rect out) {

        double scale = Math.min((double) vw / (double) iw, (double) vh / (double) ih);

        int h = (int)(!neverScaleUp || scale<1.0 ? scale * ih : ih);
        int w = (int)(!neverScaleUp || scale<1.0 ? scale * iw : iw);
        int x = ((vw - w)>>1);
        int y = ((vh - h)>>1);

        if (out == null)
            out = new Rect( x, y, x + w, y + h );
        else
            out.set( x, y, x + w, y + h );

        return out;
    }
}
