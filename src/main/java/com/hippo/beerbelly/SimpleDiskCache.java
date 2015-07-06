/*
 * Copyright 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.beerbelly;

import android.support.annotation.NonNull;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public class SimpleDiskCache {

    private static final String TAG = SimpleDiskCache.class.getSimpleName();

    private static final int IO_BUFFER_SIZE = 8 * 1024;

    private static final int STATE_DISK_CACHE_NONE = 0;
    private static final int STATE_DISK_CACHE_IN_USE = 1;
    private static final int STATE_DISK_CACHE_BUSY = 2;

    private File mCacheDir;
    private int mSize;

    private final Object mDiskCacheLock = new Object();
    private final Map<String, CounterLock> mDiskCacheLockMap = new HashMap<>();

    private transient int mDiskCacheState;

    private DiskLruCache mDiskLruCache;

    public SimpleDiskCache(File cacheDir, int size) throws IOException {
        mCacheDir = cacheDir;
        mSize = size;

        init();
    }

    public boolean isValid() {
        synchronized (mDiskCacheLock) {
            return mDiskLruCache != null;
        }
    }

    private void init() throws IOException {
        synchronized (mDiskCacheLock) {
            if (!isValid()) {
                mDiskLruCache = DiskLruCache.open(mCacheDir, 1, 1, mSize);
            }
        }
    }

    public long size() {
        synchronized (mDiskCacheLock) {
            return mDiskLruCache.size();
        }
    }

    public void flush() {
        synchronized (mDiskCacheLock) {
            // Wait for cache available
            while (mDiskCacheState != STATE_DISK_CACHE_NONE) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mDiskCacheState = STATE_DISK_CACHE_BUSY;

            if (isValid()) {
                try {
                    mDiskLruCache.flush();
                } catch (IOException e) {
                    Log.e(TAG, "SimpleDiskCache flush", e);
                }
            }

            mDiskCacheState = STATE_DISK_CACHE_NONE;
            mDiskCacheLock.notifyAll();
        }
    }

    public boolean clear() {
        boolean result = true;
        synchronized (mDiskCacheLock) {
            if (!isValid()) {
                return false;
            }

            // Wait for cache available
            while (mDiskCacheState != STATE_DISK_CACHE_NONE) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mDiskCacheState = STATE_DISK_CACHE_BUSY;

            try {
                mDiskLruCache.delete();
            } catch (IOException e) {
                Log.e(TAG, "SimpleDiskCache clearCache", e);
            }
            mDiskLruCache = null;
            try {
                init();
            } catch (IOException e) {
                Log.e(TAG, "SimpleDiskCache init", e);
                result = false;
            }

            mDiskCacheState = STATE_DISK_CACHE_NONE;
            mDiskCacheLock.notifyAll();
        }
        return result;
    }


    private CounterLock obtainLock(String key) {
        CounterLock lock;
        synchronized (mDiskCacheLock) {
            // Wait for clear over
            while (mDiskCacheState == STATE_DISK_CACHE_BUSY) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                    // Empty
                }
            }
            mDiskCacheState = STATE_DISK_CACHE_IN_USE;

            // Get lock from key
            lock = mDiskCacheLockMap.get(key);
            if (lock == null) {
                lock = new CounterLock();
                mDiskCacheLockMap.put(key, lock);
            }
            lock.occupy();
        }
        return lock;
    }

    private void releaseLock(String key, CounterLock lock) {
        synchronized (mDiskCacheLock) {
            lock.release();
            if (lock.isFree()) {
                mDiskCacheLockMap.remove(key);
            }

            if (mDiskCacheLockMap.isEmpty()) {
                mDiskCacheState = STATE_DISK_CACHE_NONE;
                mDiskCacheLock.notifyAll();
            }
        }
    }

    public InputStreamHelper getInputStreamHelper(@NonNull String key) {
        String diskKey = hashKeyForDisk(key);

        CounterLock lock = obtainLock(diskKey);

        if (!isValid()) {
            releaseLock(diskKey, lock);
            return null;
        }

        lock.lock();

        return new InputStreamHelper(mDiskLruCache, lock, diskKey);
    }

    public void releaseInputStreamHelper(InputStreamHelper ish) {
        ish.mLock.unlock();
        releaseLock(ish.mKey, ish.mLock);
        ish.clear();
    }

    public OutputStreamHelper getOutputStreamHelper(@NonNull String key) {
        String diskKey = hashKeyForDisk(key);

        CounterLock lock = obtainLock(diskKey);

        if (!isValid()) {
            releaseLock(diskKey, lock);
            return null;
        }

        lock.lock();

        return new OutputStreamHelper(mDiskLruCache, lock, diskKey);
    }

    public void releaseOutputStreamHelper(OutputStreamHelper osh) {
        osh.mLock.unlock();
        releaseLock(osh.mKey, osh.mLock);
        osh.clear();
    }


    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public boolean put(@NonNull String key, @NonNull InputStream is) {
        String diskKey = hashKeyForDisk(key);

        CounterLock lock = obtainLock(diskKey);

        boolean result = false;
        lock.lock();
        if (isValid()) {
            result = putToDisk(diskKey, is);
        }
        lock.unlock();

        releaseLock(diskKey, lock);

        return result;
    }

    private boolean putToDisk(String key, InputStream is) {
        DiskLruCache.Editor editor = null;
        OutputStream os = null;
        boolean completeEdit = false;
        try {
            editor = mDiskLruCache.edit(key);
            if (editor == null) {
                // The editor is in progress
                return false;
            }

            os = editor.newOutputStream(0);
            if (os != null) {
                copy(is, os, IO_BUFFER_SIZE);
                completeEdit = true;
                editor.commit();
                return true;
            } else {
                // Can't get OutputStream
                completeEdit = true;
                editor.abort();
                return false;
            }
        } catch (IOException e) {
            Util.closeQuietly(os);
            try {
                if (!completeEdit && editor != null) {
                    editor.abort();
                }
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    public static void copy(InputStream is, OutputStream os, int size) throws IOException {
        byte[] buffer = new byte[size];
        int bytesRead;
        while((bytesRead = is.read(buffer)) !=-1) {
            os.write(buffer, 0, bytesRead);
        }
        os.flush();
    }

    /**
     * A hashing method that changes a string (like a URL) into a hash suitable
     * for using as a disk filename.
     *
     * @param key The key used to store the file
     */
    private static String hashKeyForDisk(final String key) {
        String cacheKey;
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(key.getBytes());
            cacheKey = bytesToHexString(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }
        return cacheKey;
    }

    /**
     * http://stackoverflow.com/questions/332079
     *
     * @param bytes The bytes to convert.
     * @return A {@link String} converted from the bytes of a hashable key used
     *         to store a filename on the disk, to hex digits.
     */
    private static String bytesToHexString(final byte[] bytes) {
        final StringBuilder builder = new StringBuilder();
        for (final byte b : bytes) {
            final String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    public static class InputStreamHelper {

        private DiskLruCache mDiskLruCache;
        private CounterLock mLock;
        private String mKey;

        private DiskLruCache.Snapshot mCurrentSnapshot;

        private InputStreamHelper(DiskLruCache diskLruCache, CounterLock lock, String key) {
            mDiskLruCache = diskLruCache;
            mLock = lock;
            mKey = key;
        }

        /**
         * Get the stream to read. Call {@link #close()} to close it.
         *
         * @return the stream to read
         */
        public InputStream open() {
            DiskLruCache.Snapshot snapshot;

            try {
                snapshot = mDiskLruCache.get(mKey);
            } catch (IOException e) {
                // Get trouble
                return null;
            }

            if (snapshot == null) {
                // Miss
                return null;
            }

            mCurrentSnapshot = snapshot;

            return snapshot.getInputStream(0);
        }

        public void close() {
            Util.closeQuietly(mCurrentSnapshot);
            mCurrentSnapshot = null;
        }

        void clear() {
            mDiskLruCache = null;
            mLock = null;
            mKey = null;
        }
    }

    public static class OutputStreamHelper {

        private DiskLruCache mDiskLruCache;
        private CounterLock mLock;
        private String mKey;

        private DiskLruCache.Editor mCurrentEditor;

        private OutputStreamHelper(DiskLruCache diskLruCache, CounterLock lock, String key) {
            mDiskLruCache = diskLruCache;
            mLock = lock;
            mKey = key;
        }

        /**
         * Get the stream to write. Call {@link #close()} to close it.
         *
         * @return the stream to write
         */
        public OutputStream open() {
            DiskLruCache.Editor editor;

            try {
                editor = mDiskLruCache.edit(mKey);
            } catch (IOException e) {
                // Get trouble
                return null;
            }

            if (editor == null) {
                // Miss
                return null;
            }

            OutputStream os;

            try {
                os = editor.newOutputStream(0);
                mCurrentEditor = editor;
                return os;
            } catch (IOException e) {
                // Get trouble
                try {
                    editor.abort();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                return null;
            }
        }

        public void close() {
            try {
                mCurrentEditor.commit();
            } catch (IOException e) {
                // Empty
            }
            mCurrentEditor = null;
        }

        void clear() {
            mDiskLruCache = null;
            mLock = null;
            mKey = null;
        }
    }
}
