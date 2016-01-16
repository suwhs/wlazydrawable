package su.whs.images;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import su.whs.wlazydrawable.LazyDrawable;
import su.whs.wlazydrawable.RemoteDrawable;

/**
 * Created by igor n. boulliev on 04.11.15.
 */
public class MemoryLimitPool {
    public static Map<Context,MemoryLimitPool> mContextDependedPools = new HashMap<Context,MemoryLimitPool>();
    private static int MAX_CACHED_ITEMS = 100;

    private Context mContext;
    private LruCache<String,WeakReference<LazyDrawable>> mRemoteDrawables = new LruCache<String,WeakReference<LazyDrawable>>(MAX_CACHED_ITEMS) {
        @Override
        protected boolean removeEldestEntry(Entry eldest) {
            boolean result = super.removeEldestEntry(eldest);
            if (result) {
                RemoteDrawable remoteDrawable = ((WeakReference<RemoteDrawable>) eldest.getValue()).get();
                if (remoteDrawable!=null)
                    remoteDrawable.Unload();
            }
            return result;
        }
    };

    private MemoryLimitPool(Context context) {
        mContext = context;
    }

    public static MemoryLimitPool getInstance(Context context) {
        if (!mContextDependedPools.containsKey(context))
            mContextDependedPools.put(context,new MemoryLimitPool(context));
        return mContextDependedPools.get(context);
    }

    public boolean contains(String url) {
        return mRemoteDrawables.containsKey(url);
    }

    public void updateLruMark(String url, RemoteDrawable drawable) {
        if (!mRemoteDrawables.containsKey(url)) {
            mRemoteDrawables.put(url,new WeakReference<LazyDrawable>(drawable));
        } else {
            mRemoteDrawables.get(url);
        }
    }

    public void recycle(String url) {
        if (mRemoteDrawables.containsKey(url))
            mRemoteDrawables.remove(url).get().Unload();
    }

    public void setPoolSize(int size) {
        mRemoteDrawables.setSize(size);
    }

    public void reference(String url) {

    }

    private class LruCache<A, B> extends LinkedHashMap<A, B> {
        private int maxEntries;

        public LruCache(final int maxEntries) {
            super(maxEntries + 1, 1.0f, true);
            this.maxEntries = maxEntries;
        }

        public void setSize(int size) {
            maxEntries = size;
        }

        /**
         * Returns <tt>true</tt> if this <code>LruCache</code> has more entries than the maximum specified when it was
         * created.
         *
         * <p>
         * This method <em>does not</em> modify the underlying <code>Map</code>; it relies on the implementation of
         * <code>LinkedHashMap</code> to do that, but that behavior is documented in the JavaDoc for
         * <code>LinkedHashMap</code>.
         * </p>
         *
         * @param eldest
         *            the <code>Entry</code> in question; this implementation doesn't care what it is, since the
         *            implementation is only dependent on the size of the cache
         * @return <tt>true</tt> if the oldest
         * @see java.util.LinkedHashMap#removeEldestEntry(Map.Entry)
         */
        @Override
        protected boolean removeEldestEntry(final Map.Entry<A, B> eldest) {
            return super.size() > maxEntries;
        }
    }
}
