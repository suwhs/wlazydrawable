package su.whs.images;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import su.whs.wlazydrawable.LazyDrawable;

/**
 * Created by igor n. boulliev on 05.12.15.
 */
public abstract class PreviewDrawable extends LazyDrawable {
    private boolean mFullMode = false;
    public PreviewDrawable(Object executorTag, int srcWidth, int srcHeight) {
        super(executorTag, srcWidth, srcHeight, ScaleType.SCALE_FIT);
    }

    /*
    protected enum State {
        NONE,
        QUEUED,
        LOADING,
        PREVIEW,
        FULL,
        ANIMATION,
        ERROR,
        PARAM_ERROR,
    } */

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public boolean isRunning() {
        return false;
    }

    public void loadFullDrawable() {
        setLoadingState(true);
        getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                Drawable full = getFullDrawable();
                if (full!=null) {
                    synchronized (PreviewDrawable.this) {
                        setDrawable(full);
                    }
                    setLoadingState(false);
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
        }
        return null;
    }
}
