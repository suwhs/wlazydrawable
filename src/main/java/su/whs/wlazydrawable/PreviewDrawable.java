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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import su.whs.images.GifDrawableCompat;

/**
 * Created by igor n. boulliev <igor@whs.su> on 05.12.15.
 *
 * PreviewDrawable - implements two-state Drawable:
 *  1. preview state  (loaded on demand)
 *  2. full size state (loaded on .loadFullDrawable()/.start() calls)
 *
 */
public abstract class PreviewDrawable extends LazyDrawable {
    private static final String TAG="PreviewDrawable";
    private boolean mFullVersionLoaded = false;

    public PreviewDrawable(Object executorTag, int srcWidth, int srcHeight) {
        super(executorTag, srcWidth, srcHeight, ScaleType.SCALE_FIT);
    }

    private LoadingRunnable mFullLoadingRunnable = new LoadingRunnable() {
        private boolean mCancelled = false;
        private boolean mIsRunning = false;
        @Override
        public int getPriority() {
            return PreviewDrawable.this.getLoadingPriority();
        }

        @Override
        public void onExecutionFailed(Throwable t) {
            handleLoadErrorOnFullDrawable();
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

        @Override
        public void run() {
            boolean _cancelled = false;
            synchronized (this) { _cancelled = mCancelled; }
            if (_cancelled) { uncancel(); return; }
            Drawable full = null;
            try {
                synchronized (this) { mIsRunning = true; }
                full = getFullDrawable();
            } finally {
                synchronized (this) { mIsRunning = false; }
            }
            if (full!=null) {
                synchronized (PreviewDrawable.this) {
                    setDrawable(full);
                    mFullVersionLoaded = true;
                }
                handleLoadFinish();
            } else {
                handleLoadErrorOnFullDrawable();
            }
        }
    };

    public void loadFullDrawable() {
        if (super.isLoading())
            super.stopLoading();
        super.setError(false);
        getExecutor().execute(mFullLoadingRunnable);
        invalidateSelf();
    }

    /**
     * swap readDrawable() with getPreviewDrawable()/getFullDrawable() methods
     * @return
     */
    @Override
    protected final Drawable readDrawable() {
        return getPreviewDrawable();
    }

    /**
     * must return 'preview drawable'
     * @return Drawable
     */
    protected abstract Drawable getPreviewDrawable();

    /**
     * must returns 'full size drawable'
     * WARNING: after full size drawable loaded - 'preview drawable' will be unloaded
     * @return Drawable
     */
    protected abstract Drawable getFullDrawable();

    /**
     * returns bitmap (if loaded drawable are BitmapDrawable)
     * @return
     */
    public Bitmap getBitmap() {
        Drawable d = getDrawable();
        if (d instanceof BitmapDrawable) {
            return ((BitmapDrawable)d).getBitmap();
        } else if (d instanceof GifDrawableCompat) {
            GifDrawableCompat gdc = (GifDrawableCompat) d;
            return gdc.getBitmap();
        }
        return null;
    }

    /**
     *
     * @return true if preview/fullsize loading in progress
     */
    @Override
    public boolean isLoading() {
        return super.isLoading() || mFullLoadingRunnable.isRunning();
    }

    protected void handleLoadErrorOnFullDrawable() {
        Log.e(TAG, "error loading full drawable");
    }

    /**
     *
     * @return true, if full size version are loading or ready
     */
    public boolean isFullVersion() {
        return mFullVersionLoaded;
    }

    /**
     * replace loaded drawable with bitmapdrawable, constructed with geometry (getBounds.width(),getBounds.height())
     * useful to reduce memory pressure if drawable actual bounds are less than full size version bounds
     */
    public void resampleToBounds() {
        Rect bounds = new Rect();
        getWrappedDrawableBounds(bounds);
        Bitmap bmp = Bitmap.createBitmap(bounds.width(),bounds.height(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.translate(-bounds.left,-bounds.top);
        getDrawable().draw(canvas);
        BitmapDrawable bitmapDrawable = new BitmapDrawable(Resources.getSystem(),bmp);
        setDrawable(bitmapDrawable);
        mFullVersionLoaded = false;
    }
}
