package su.whs.wlazydrawable;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import su.whs.images.GifDrawableCompat;

/**
 * Created by igor n. boulliev <igor@whs.su> on 05.12.15.
 */
public abstract class PreviewDrawable extends LazyDrawable {

    public PreviewDrawable(Object executorTag, int srcWidth, int srcHeight) {
        super(executorTag, srcWidth, srcHeight, ScaleType.SCALE_FIT);
    }

    @Override
    public void start() {
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
    }

    @Override
    public boolean isRunning() {
        return super.isRunning();
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

        @Override
        public void run() {
            boolean _cancelled = false;
            synchronized (this) { _cancelled = mCancelled; }
            if (_cancelled) { uncancel(); return; }
            Drawable full = null;
            try {
                synchronized (this) { mIsRunning = true; }
                getFullDrawable();
            } finally {
                synchronized (this) { mIsRunning = false; }
            }
            if (full!=null) {
                synchronized (PreviewDrawable.this) {
                    setDrawable(full);
                }
                handleLoadFinish();
            } else {
                handleLoadError();
            }
        }
    };

    public void loadFullDrawable() {
        getExecutor().execute(mFullLoadingRunnable);
    }

    @Override
    protected final Drawable readDrawable() {
        return getPreviewDrawable();
    }

    protected abstract Drawable getPreviewDrawable();
    protected abstract Drawable getFullDrawable();

    public Bitmap getBitmap() {
        Drawable d = getDrawable();
        if (d instanceof BitmapDrawable) {
            return ((BitmapDrawable)d).getBitmap();
        } else if (d instanceof GifDrawableCompat) {

        }
        return null;
    }

    @Override
    public boolean isLoading() {
        return super.isLoading() || mFullLoadingRunnable.isRunning();
    }
}
