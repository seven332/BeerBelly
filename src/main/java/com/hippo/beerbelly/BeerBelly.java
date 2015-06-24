/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.beerbelly;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.LruCache;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public abstract class BeerBelly<V> {

    private static final String TAG = BeerBelly.class.getSimpleName();

    private static final int STATE_DISK_CACHE_NONE = 0;
    private static final int STATE_DISK_CACHE_IN_USE = 1;
    private static final int STATE_DISK_CACHE_BUSY = 2;

    private MemoryCahce<V> mMemoryCache;
    private @Nullable DiskCache<V> mDiskCache;

    private final boolean mHasMemoryCache;
    private final boolean mHasDiskCache;

    private final Object mDiskCacheLock = new Object();
    private final Map<String, CounterLock> mDiskCacheLockMap = new HashMap<>();
    private transient int mDiskCacheState;

    public BeerBelly(BeerBellyParams params) {
        params.isVaild();
        mHasMemoryCache = params.hasMemoryCache;
        mHasDiskCache = params.hasDiskCache;

        if (mHasMemoryCache) {
            initMemoryCache(params.memoryCacheMaxSize);
        }

        if (mHasDiskCache) {
            initDiskCache(params.diskCacheDir, params.diskCacheMaxSize);
            mDiskCacheState = STATE_DISK_CACHE_NONE;
        }
    }

    private void initMemoryCache(int maxSize) {
        mMemoryCache = new MemoryCahce<>(maxSize, this);
    }

    private void initDiskCache(File cacheDir, int maxSize) {
        // Set up disk cache
        try {
            mDiskCache = new DiskCache<>(cacheDir, maxSize, this);
        } catch (IOException e) {
            Log.e(TAG, "Can't create disk cache", e);
        }
    }

    protected abstract int sizeOf(String key, V value);

    protected abstract void memoryEntryRemoved(boolean evicted, String key, V oldValue, V newValue);

    protected abstract V read(InputStreamHelper ish);

    protected abstract boolean write(OutputStream os, V value);

    /**
     * Check if have memory cache
     *
     * @return true if have memory cache
     */
    public boolean hasMemoryCache() {
        return mHasMemoryCache;
    }

    /**
     * Check if have disk cache
     *
     * @return true if have disk cache
     */
    public boolean hasDiskCache() {
        return mHasDiskCache;
    }

    /**
     * Get value from memory cache
     *
     * @param key the key to get value
     * @return the value you get, null for miss or no memory cache
     */
    public V getFromMemory(@NonNull String key) {
        if (mHasMemoryCache) {
            return mMemoryCache.get(key);
        } else {
            return null;
        }
    }

    private CounterLock obtainLock(String key) {
        CounterLock lock;
        synchronized (mDiskCacheLock) {
            // Wait for clear over
            while (mDiskCacheState == STATE_DISK_CACHE_BUSY) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
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

    private void freeLock(String key, CounterLock lock) {
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

    /**
     * Get value from disk cache. Override {@link #read(InputStreamHelper)} to do it
     *
     * @param key the key to get value
     * @return the value you get, null for miss or no memory cache or get error
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public V getFromDisk(@NonNull String key) {
        if (mHasDiskCache) {
            String diskKey = hashKeyForDisk(key);

            CounterLock lock = obtainLock(diskKey);

            V value;
            // Get value from disk cache
            synchronized (lock) {
                if (mDiskCache != null) {
                    value = mDiskCache.get(diskKey);
                } else {
                    value = null;
                }
            }

            freeLock(diskKey, lock);

            return value;
        } else {
            return null;
        }
    }

    /**
     * Get value from memory cache and disk cache. If miss in memory cache and
     * get in disk cache, it will put value from disk cache to memory cache.
     *
     * @param key the key to get value
     * @return the value you get
     */
    public V get(@NonNull String key) {
        V value = getFromMemory(key);

        if (value != null) {
            // Get it in memory cache
            return value;
        }

        value = getFromDisk(key);

        if (value != null) {
            // Get it in disk cache
            putToMemory(key, value);
            return value;
        }

        return null;
    }

    /**
     * Put value to memory cache
     *
     * @param key the key
     * @param value the value
     * @return false if no memory cache
     */
    public boolean putToMemory(@NonNull String key, @NonNull V value) {
        if (mHasMemoryCache) {
            mMemoryCache.put(key, value);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Put value to disk cache
     *
     * @param key the key
     * @param value the value
     * @return false if no disk cache or get error
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public boolean putToDisk(@NonNull String key, @NonNull V value) {
        if (mHasDiskCache) {
            String diskKey = hashKeyForDisk(key);

            CounterLock lock = obtainLock(diskKey);

            boolean result;
            synchronized (lock) {
                if (mDiskCache != null) {
                    result = mDiskCache.put(diskKey, value);
                } else {
                    result = false;
                }
            }

            freeLock(diskKey, lock);

            return result;
        } else {
            return false;
        }
    }

    /**
     * Put value to memory cache and disk cache
     *
     * @param key the key
     * @param value the value
     */
    public void put(@NonNull String key, @NonNull V value) {
        putToMemory(key, value);
        putToDisk(key, value);
    }

    /**
     *
     * @param key the key
     * @param is the input stream to store
     * @return false if no disk cache or get error
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public boolean putRawToDisk(@NonNull String key, @NonNull InputStream is) {
        if (mHasDiskCache) {
            String diskKey = hashKeyForDisk(key);

            CounterLock lock = obtainLock(diskKey);

            boolean result;
            synchronized (lock) {
                if (mDiskCache != null) {
                    result = mDiskCache.putRaw(diskKey, is);
                } else {
                    result = false;
                }
            }

            freeLock(diskKey, lock);

            return result;
        } else {
            return false;
        }
    }

    /**
     * Evicts all of the items from the memory cache
     */
    public void clearMemory() {
        if (mHasMemoryCache) {
            mMemoryCache.evictAll();
        }
    }

    /**
     * Clear disk cache
     */
    public void clearDisk() {
        if (mHasDiskCache) {

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

                if (mDiskCache != null && !mDiskCache.isClosed()) {
                    try {
                        mDiskCache.delete();
                    } catch (IOException e) {
                        Log.e(TAG, "BeerBelly clearCache", e);
                    }
                    File cacheDir = mDiskCache.getCacheDir();
                    int maxSize = mDiskCache.getMaxSize();
                    mDiskCache = null;
                    initDiskCache(cacheDir, maxSize);
                }

                mDiskCacheState = STATE_DISK_CACHE_NONE;
                mDiskCacheLock.notifyAll();
            }
        }
    }

    public void flush() {
        if (mHasDiskCache) {

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

                if (mDiskCache != null) {
                    try {
                        mDiskCache.flush();
                    } catch (IOException e) {
                        Log.e(TAG, "BeerBelly flush", e);
                    }
                }

                mDiskCacheState = STATE_DISK_CACHE_NONE;
                mDiskCacheLock.notifyAll();
            }
        }
    }

    /**
     * Evicts all of the items from the memory cache and lets the system know
     * now would be a good time to garbage collect
     */
    public void clear() {
        clearMemory();
        clearDisk();
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

    /**
     * A holder class that contains cache parameters.
     */
    public static class BeerBellyParams {

        /**
         * is memory cache available
         */
        public boolean hasMemoryCache = false;
        /**
         * the maximum number of bytes the memory cache should use to store
         */
        public int memoryCacheMaxSize = 0;
        /**
         * is disk cache available
         */
        public boolean hasDiskCache = false;
        /**
         * the dir to store disk cache
         */
        public File diskCacheDir = null;
        /**
         * the maximum number of bytes the disk cache should use to store
         */
        public int diskCacheMaxSize = 0;

        /**
         * Check BeerBellyParams is valid
         *
         * @throws IllegalStateException
         */
        public void isVaild() throws IllegalStateException {
            if (!hasMemoryCache && !hasDiskCache) {
                throw new IllegalStateException("No memory cache and no disk cache. What can I do for you?");
            }

            if (hasMemoryCache && memoryCacheMaxSize <= 0) {
                throw new IllegalStateException("Memory cache max size must be bigger than 0.");
            }

            if (hasDiskCache) {
                if (diskCacheDir == null) {
                    throw new IllegalStateException("Disk cache dir can't be null.");
                }
                if (diskCacheMaxSize <= 0) {
                    throw new IllegalStateException("Disk cache max size must be bigger than 0.");
                }
                // TODO Check is disk cache dir vaild
            }
        }
    }

    public class MemoryCahce<E> extends LruCache<String, E> {

        public BeerBelly<E> mParent;

        public MemoryCahce(int maxSize, BeerBelly<E> parent) {
            super(maxSize);
            mParent = parent;
        }

        @Override
        protected int sizeOf(String key, E value) {
            return mParent.sizeOf(key, value);
        }

        @Override
        protected void entryRemoved(boolean evicted, String key, E oldValue, E newValue) {
            mParent.memoryEntryRemoved(evicted, key, oldValue, newValue);
        }
    }

    public static class DiskCache<E> {

        private static final int IO_BUFFER_SIZE = 8 * 1024;

        private DiskLruCache mDiskLruCache;
        private BeerBelly<E> mParent;

        private File mCacheDir;
        private int mMaxSize;

        public DiskCache(File cacheDir, int size, BeerBelly<E> parent) throws IOException {
            mDiskLruCache = DiskLruCache.open(cacheDir, 1, 1, size);
            mParent = parent;

            mCacheDir = cacheDir;
            mMaxSize = size;
        }

        public File getCacheDir() {
            return mCacheDir;
        }

        public int getMaxSize() {
            return mMaxSize;
        }

        public boolean isClosed() {
            return mDiskLruCache.isClosed();
        }

        public void delete() throws IOException {
            mDiskLruCache.delete();
        }

        public void flush() throws IOException {
            mDiskLruCache.flush();
        }

        public E get(String key) {
            InputStreamHelper ish = new InputStreamHelper(mDiskLruCache, key);
            return mParent.read(ish);
        }

        public boolean put(String key, E value) {
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
                    final BufferedOutputStream buffOut =
                            new BufferedOutputStream(os, IO_BUFFER_SIZE);
                    if (mParent.write(buffOut, value)) {
                        completeEdit = true;
                        editor.commit();
                        return true;
                    }
                }
                // Can't get OutputStream or can't write
                Util.closeQuietly(os);
                completeEdit = false;
                editor.abort();
                return false;
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

        public boolean putRaw(String key, InputStream is) {
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
    }

    public static class InputStreamHelper {

        private DiskLruCache mDiskLruCache;
        private String mKey;

        private DiskLruCache.Snapshot mCurrentSnapshot;

        private InputStreamHelper(DiskLruCache diskLruCache, String key) {
            mDiskLruCache = diskLruCache;
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
    }
}
