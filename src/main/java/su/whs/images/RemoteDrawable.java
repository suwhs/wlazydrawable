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
package su.whs.images;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import su.whs.wlazydrawable.R;


/**
 * Created by igor n. boulliev on 31.08.15.
 */

/**
 * demo code for show animated drawables in TextViewEx
 *
 * LazyDrawable required for animation on demand launch,
 * and for support AOSP < 11
 *
 *
 */

public abstract class RemoteDrawable extends PreviewDrawable {
    /* separate executor for gif loading */
    private static ThreadPoolExecutor gifExecutor = new ThreadPoolExecutor(1,1,1000L, TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(500));
    private static final String TAG = "RemoteDrawable";
    private static final int MAX_DIMENSION_FOR_PREVIEW = 128;
    private String mUrl;
    private String mFullUrl;
    private boolean mFullVersionLoaded = false;
    private int mRealWidth = 0;
    private int mRealHeight = 0;
    private boolean mIsCrop = false;
    private boolean mIsGif = false;
    private int streamSampling = 2;
    private MemoryLimitPool mMemoryLimitPool;

    public RemoteDrawable(Context context, String url, String mime, int width, int height) {
        super(context,width, height);
        mMemoryLimitPool = MemoryLimitPool.getInstance(context);
        if (Runtime.getRuntime().maxMemory()<100000000)
            streamSampling = 4;
        setInfoDrawables(context);
        mUrl = url;

        if (!TextUtils.isEmpty(mime) && mime.startsWith("image/gif"))
            mIsGif = true;
        else if (url.toLowerCase().endsWith(".gif"))
            mIsGif = true;

        if (width>0 && height>0) {
            mRealWidth = width;
            mRealHeight = height;
            setSize(width,height);
        }
    }

    protected abstract void onSizeDecoded(int width, int height);

    public RemoteDrawable(Context context, String previewUrl, String fullUrl, String mime, int widht, int height) {
        this(context, previewUrl, mime, widht, height);
        mFullUrl = fullUrl;
    }

    /**
     * readPreviewDrawable() must returns initial image for DynamicDrawableSpan (less resolution version, BW version),
     * or single frame from animation
     *
     * @return
     */
    @Override
    protected Drawable getPreviewDrawable() {
        if (mIsGif) {
            try {
                return readGifPreview();
            } catch (IOException e) {
                return null;
            }
        } else {
            return readBitmap(mUrl,streamSampling);
        }
    }

    private static synchronized  ThreadPoolExecutor getGifExecutor() {
        if (gifExecutor.isShutdown()) {
            gifExecutor = new ThreadPoolExecutor(1,1,10L, TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(500));
        }
        return gifExecutor;
    }

    private Drawable readGifPreview() throws IOException {
        GifDecoder decoder = new GifDecoder();
        try {
            decoder.read(getInputStream(mUrl), 0);
            decoder.advance();
            Bitmap frame = decoder.getNextFrame();
            Drawable result = new BitmapDrawable(Resources.getSystem(),frame);
            setDrawable(result);
            invalidateSelfOnUiThread();
            return result;
        } catch (ArithmeticException e) {

        } catch (NullPointerException e) {

        }
        onLoadingError();
        return null;
    }

    private Drawable readFullGif() {
        try {
            return new GifDrawableCompat(getInputStream(mUrl));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * readFullDrawable must returns high-quality version of image, or Animatable instance
     *
     * @return
     */

    @Override
    protected Drawable getFullDrawable() {
        // if (mFullDrawable!=null) return mFullDrawable;
        if (mIsGif)
            return readFullGif();
        if (mFullUrl!=null) {
            return readBitmap(mFullUrl,1);
        }
        return readBitmap(mUrl,1);
    }

    private boolean needReopen(BufferedInputStream bis) {
        try {
            bis.reset();
        } catch (IOException e) {
            return true;
        }
        return false;
    }

    public Drawable readBitmap(String url, int sampling) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            InputStream is = getInputStream(url);
            if (is==null) {
                handleLoadError();
                return null;
            }
            BufferedInputStream bis = new BufferedInputStream(is,16384);
            bis.mark(16384);
            options.inJustDecodeBounds = true;
            Rect bounds = new Rect();
            Bitmap bmp = BitmapFactory.decodeStream(bis, bounds, options);

            final int outWidth = options.outWidth;
            final int outHeight = options.outHeight;
            onSizeDecoded(outWidth,outHeight);
            if (needReopen(bis)) { // some android versions reads ALL stream when 'inJustDecodeBounds=true'
                is.close();
                bis = new BufferedInputStream(getInputStream(url),16384);
            }
            options.inJustDecodeBounds = false;
            if (options.outWidth>MAX_DIMENSION_FOR_PREVIEW && options.outHeight> MAX_DIMENSION_FOR_PREVIEW)
                options.inSampleSize = sampling; // calculateInSampleSize(options,dm.widthPixels,dm.heightPixels);
            bmp = BitmapFactory.decodeStream(bis,null,options);

            bis.close();
            is.close();
            if (bmp!=null && bmp.getWidth()>1 && bmp.getHeight()>1) {
                return new BitmapDrawable(Resources.getSystem(), bmp) {
                    @Override
                    public int getIntrinsicWidth() {
                        return outWidth;
                    }
                    @Override
                    public int getIntrinsicHeight() {
                        return outHeight;
                    }
                };
            } else if (bmp==null) {
                Log.e(TAG, "Bitmap Decode error from url:"+url);
            } else {
                Log.e(TAG, "Bitmap Wrong Geometry from url:"+url);
            }
        } catch (IOException e) {
            Log.e(TAG,"Bitmap read Exception:"+e);
            handleLoadError();
        }
        return null;
    }

    /* call loadFullImage and launch animation */
    @Override
    public void start() {
        if (!mIsGif) return;
        if (!mFullVersionLoaded) {
            loadFullDrawable();
        }
        super.start();
    }

    public String getUrl() {
        return mUrl;
    }

    public String getFullUrl() {
        return mFullUrl == null ? mUrl : mFullUrl;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        if (visible) mMemoryLimitPool.updateLruMark(mUrl,this); // move drawable on top of cache
    }

    private void setInfoDrawables(Context context) {
        Drawable loading = context.getResources().getDrawable(R.mipmap.ic_progress_gray);
        loading.setBounds(0, 0, loading.getIntrinsicWidth(), loading.getIntrinsicHeight());
        setLoadingDrawable(loading);
        loading = context.getResources().getDrawable(R.mipmap.ic_alert_gray);
        loading.setBounds(0, 0, loading.getIntrinsicWidth(), loading.getIntrinsicHeight());
        setErrorDrawable(loading);
        loading = context.getResources().getDrawable(R.mipmap.ic_queued_gray);
        loading.setBounds(0, 0, loading.getIntrinsicWidth(), loading.getIntrinsicHeight());
    }


    @Override
    public void Unload() {
        Drawable d = getDrawable();
        if (d!=null && d instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable)d).getBitmap();
            bmp.recycle();
        }
        mMemoryLimitPool.recycle(mUrl);
        super.Unload();
    }

    protected InputStream getInputStream(String u) throws IOException {
        URL url;
        try {
            url = new URL(u);
        } catch (MalformedURLException e) {
            return  null;
        }
        try {
            URLConnection conn = url.openConnection();
            return conn.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }
}
