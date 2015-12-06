package su.whs.browserselector.images;

import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Created by igor n. boulliev on 05.11.15.
 */
public class CachedInputStream extends InputStream {
    private static final String TAG="CachedInputStream";
    // TODO: stage1 test
    // no cache stream - just wraps reads and setMark()/reset()
    private boolean mCachingEnabled = false;
    private InputStream mSourceStream = null;
    private InputStream mWrappedInputStream = null;
    private OutputStream mStoreOutputStream = null;
    private byte[] mBuffer;
    private ByteBuffer mBufferWrapper;
    private boolean mIsMarkSet = false;
    private long mPosition = 0;
    private long mMarkedPosition = 0;
    private long mMarkLimit = 0;
    private int mBufferFilled = 0; /* mMarkedPosition + mBufferFilled < mMarkLimit + 1*/
    private StreamProvider mStreamProvider = null;
    private enum Mode {
        CACHING,
        READING
    }

    private Mode mMode = Mode.READING;

    public interface StreamProvider {
        InputStream getSourceStream() throws IOException;
        OutputStream makeCacheOutputStream();
        InputStream getCacheInputStream();
        void onCachingFinished();
    }

    public CachedInputStream(StreamProvider streams) throws IOException {
        mStreamProvider = streams;
        mWrappedInputStream = streams.getCacheInputStream();
        if (mWrappedInputStream==null) {
            mMode = Mode.CACHING;
            mSourceStream = streams.getSourceStream();
            if (mCachingEnabled)
                mStoreOutputStream = streams.makeCacheOutputStream();
        }
    }

    private byte[] mIntReadBuffer = new byte[4];
    private ByteBuffer mIntByteBuffer = ByteBuffer.wrap(mIntReadBuffer);

    @Override
    public int read() throws IOException {
        if (read(mIntReadBuffer,0,4)<4) throw new EOFException();
        mIntByteBuffer.rewind();
        return mIntByteBuffer.getInt();
    }

    @Override
    public int available() throws IOException {
        switch (mMode) {
            case CACHING:
                return mSourceStream.available();
            case READING:
            default:
                return mWrappedInputStream.available();
        }
    }

    @Override
    public void close() throws IOException {
        switch (mMode) {
            case CACHING:
                mSourceStream.close();
                if (mCachingEnabled)
                    mStoreOutputStream.close();
                break;
            case READING:
                mWrappedInputStream.close();
        }
        super.close();
    }

    @Override
    public void mark(int readlimit) {
        switch (mMode) {
            case CACHING:
                if (mIsMarkSet) {
                    Log.e(TAG,"mark() called twice");
                }
                mIsMarkSet = true;
                mMarkedPosition = mPosition;
                mMarkLimit = mMarkedPosition + readlimit;
                mBuffer = new byte[readlimit];
                mBufferWrapper = ByteBuffer.wrap(mBuffer);
                mBufferFilled = 0;
                break;
            case READING:
                mWrappedInputStream.mark(readlimit);
        }
    }

    @Override
    public boolean markSupported() {
        if (mMode==Mode.CACHING)
            return true;
        else
            return mWrappedInputStream.markSupported();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return read(buffer,0,buffer.length);
    }

    @Override
    public int read(byte[] buffer, int byteOffset, int byteCount) throws IOException {
        int wasRead = -1;
        // mPosition < mMarkLimit means that we must buffer from mMarkedPosition
        // mPosition > mMarkStart + mBufferFilled || mMarkLimit == 0
        switch (mMode) {
            case CACHING:
                /*
                if (mPosition>=mMarkedPosition && mPosition<mMarkLimit) {
                    // read from buffer, (we already pass this data to output stream)
                    if (mPosition+byteCount<mMarkedPosition+mBufferFilled) {
                        // all requested data are in buffer
                        long bufferPosition = mPosition - mMarkedPosition;
                        mBufferWrapper.get(buffer, (int) bufferPosition, byteCount);
                        mPosition += byteCount;
                        return byteCount; // exit
                    }
                    // not sufficient data in buffer, read until mBufferFilled
                    int leftFilledBytes = (int) ((mMarkedPosition+mBufferFilled)-(mPosition-mMarkedPosition));
                    mBufferWrapper.get(buffer,byteOffset, leftFilledBytes);
                    wasRead = leftFilledBytes;
                    byteCount -= wasRead;
                    byteOffset += wasRead;
                    mPosition += leftFilledBytes;
                }
                int leftToRead = byteCount;
                // read from mUrlConnectionInputStream, write to mStoreOutputStream

                int fromInput = mSourceStream.read(buffer,byteOffset, leftToRead);
                if (fromInput<1) {
                    return wasRead;
                }
                wasRead = fromInput;
                if (mIsMarkSet && mPosition+leftToRead<mMarkLimit) {
                    // we need to copy buffer[byteOffset:byteOffset+leftToRead] to mBuffer
                    ByteBuffer wb = ByteBuffer.wrap(mBuffer,mBufferFilled,fromInput);
                    wb.put(buffer,byteOffset,fromInput);
                }
                mPosition += fromInput;
                // now write from buffer[byteOffset:byteOffset+fromInput] to cache
                if (mCachingEnabled)
                    mStoreOutputStream.write(buffer,byteOffset,fromInput);
                if (mIsMarkSet && mPosition>mMarkLimit) {
                    // cancel buffering, reset() will throw IOException
                    mBufferWrapper = null;
                    mBuffer = null;
                }
                wasRead += fromInput;
                return wasRead;
                */
                return mSourceStream.read(buffer,byteOffset,byteCount);
            case READING:
                return mWrappedInputStream.read(buffer,byteOffset,byteCount);
            default:
                throw new IOException("unknown Mode");
        }
    }

    @Override
    public synchronized void reset() throws IOException {
        if (mMode==Mode.READING)
            mWrappedInputStream.reset();
        else if (mPosition>mMarkLimit) {
            // copy left data to mStoreOutputStream, close it and throw exception
            if (mCachingEnabled) {
                byte[] left = new byte[65535];
                int wasRead = 0;
                while ((wasRead = mSourceStream.read(left)) > -1) {
                    if (wasRead > 0) {
                        mStoreOutputStream.write(left, 0, wasRead);
                    }
                }
                mSourceStream.close();
                mStoreOutputStream.close();
                notifyFileCachedInternal();

            }
            throw new IOException("reset() out of mark");
        }
        mPosition = mMarkedPosition;
        mIsMarkSet = false;
    }

    @Override
    public long skip(long byteCount) throws IOException {
        switch (mMode) {
            case CACHING:
                throw new IllegalStateException("cannot skip while caching mode");
            case READING:
            default:
                return mWrappedInputStream.skip(byteCount);
        }
    }

    private void notifyFileCachedInternal() {
        Log.v(TAG,"caching finished");
        if (mStreamProvider!=null)
            mStreamProvider.onCachingFinished();
    }
}
