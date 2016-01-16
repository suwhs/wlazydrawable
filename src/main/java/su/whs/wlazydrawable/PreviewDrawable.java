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

    public void loadFullDrawable() {
        setLoadingState(true);
        getExecutor().execute(new LoadingRunnable() {
            @Override
            public void onExecutionFailed(Throwable t) {
                handleLoadError();
            }

            @Override
            public void cancel() {

            }

            @Override
            public void run() {
                Drawable full = getFullDrawable();
                if (full!=null) {
                    synchronized (PreviewDrawable.this) {
                        setDrawable(full);
                    }
                    handleLoadFinish();
                } else {
                    handleLoadError();
                }
            }
        });
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
}
