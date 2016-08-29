/*
 * Copyright (C) 2015-2016 Hippo Seven
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

import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class BeerBelly<V> {

    private static final String TAG = BeerBelly.class.getSimpleName();

    @Nullable
    private LruCache<String, V> mMemoryCache;
    @Nullable
    private DiskCache<V> mDiskCache;

    public BeerBelly(BeerBellyParams params) {
        params.isValid();

        if (params.hasMemoryCache) {
            initMemoryCache(params.memoryCacheMaxSize, params.memoryCacheThreadSafe);
        }

        if (params.hasDiskCache) {
            initDiskCache(params.diskCacheDir, params.diskCacheMaxSize);
        }
    }

    private void initMemoryCache(int maxSize, boolean threadSafe) {
        mMemoryCache = LruCache.create(maxSize, new MemoryCacheHelper<>(this), threadSafe);
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

    protected abstract void memoryEntryAdded(String key, V value);

    protected abstract void memoryEntryRemoved(boolean evicted, String key, V oldValue, V newValue);

    protected abstract V read(@NonNull InputStreamPipe isPipe);

    protected abstract boolean write(OutputStream os, V value);

    /**
     * Return the memory cache.
     */
    @Nullable
    public LruCache<String, V> getMemoryCache() {
        return mMemoryCache;
    }

    /**
     * Return the disk cache.
     */
    @Nullable
    public SimpleDiskCache getDiskCache() {
        if (mDiskCache != null) {
            return mDiskCache.mDiskCache;
        } else {
            return null;
        }
    }

    /**
     * Return true if has memory cache.
     */
    public boolean hasMemoryCache() {
        return mMemoryCache != null;
    }

    /**
     * Return true if has disk cache.
     */
    public boolean hasDiskCache() {
        return mDiskCache != null;
    }

    /**
     * Return memory cache usage size. -1 for error.
     */
    public int memorySize() {
        if (mMemoryCache != null) {
            return mMemoryCache.size();
        } else {
            return -1;
        }
    }

    /**
     * Return memory cache max size. -1 for error.
     */
    public int memoryMaxSize() {
        if (mMemoryCache != null) {
            return mMemoryCache.maxSize();
        } else {
            return -1;
        }
    }

    /**
     * Return disk cache usage size. -1 for error.
     */
    public long diskSize() {
        if (mDiskCache != null) {
            return mDiskCache.size();
        } else {
            return -1L;
        }
    }

    /**
     * Return disk cache max size. -1 for error.
     */
    public long diskMaxSize() {
        if (mDiskCache != null) {
            return mDiskCache.getMaxSize();
        } else {
            return -1L;
        }
    }

    /**
     * Return value associated to the key from memory cache.
     * Null for missing or no memory cache.
     */
    @Nullable
    public V getFromMemory(@NonNull String key) {
        if (mMemoryCache != null) {
            return mMemoryCache.get(key);
        } else {
            return null;
        }
    }

    /**
     * Return value associated to the key from disk cache.
     * Null for missing or no disk cache or get error.
     * Override {@link #read(InputStreamPipe)} to do it.
     */
    @Nullable
    public V getFromDisk(@NonNull String key) {
        if (mDiskCache != null) {
            return mDiskCache.get(key);
        } else {
            return null;
        }
    }

    /**
     * Return value associated to the key from memory cache and disk cache.
     * If miss in memory cache and get in disk cache,
     * it will put value from disk cache to memory cache.
     */
    @Nullable
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
     * Put the value to memory cache. Return true if done.
     */
    public boolean putToMemory(@NonNull String key, @NonNull V value) {
        if (mMemoryCache != null) {
            mMemoryCache.put(key, value);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Put the value to disk cache. Return true if done.
     */
    public boolean putToDisk(@NonNull String key, @NonNull V value) {
        if (mDiskCache != null) {
            return mDiskCache.put(key, value);
        } else {
            return false;
        }
    }

    /**
     * Put the value to memory cache and disk cache.
     */
    public void put(@NonNull String key, @NonNull V value) {
        putToMemory(key, value);
        putToDisk(key, value);
    }

    /**
     * Remove the value associated to the key from memory cache.
     */
    public void removeFromMemory(@NonNull String key) {
        if (mMemoryCache != null) {
            mMemoryCache.remove(key);
        }
    }

    /**
     * Remove the value associated to the key from disk cache.
     */
    public void removeFromDisk(@NonNull String key) {
        if (mDiskCache != null) {
            mDiskCache.remove(key);
        }
    }

    /**
     * Remove the value associated to the key from memory cache and disk cache.
     */
    public void remove(@NonNull String key) {
        removeFromMemory(key);
        removeFromDisk(key);
    }

    /**
     * Pull raw data from disk cache to OutputStream.
     */
    public boolean pullRawFromDisk(@NonNull String key, @NonNull OutputStream os) {
        if (mDiskCache != null) {
            return mDiskCache.pullRaw(key, os);
        } else {
            return false;
        }
    }

    /**
     * Push raw data from InputStream to disk cache.
     */
    public boolean pushRawToDisk(@NonNull String key, @NonNull InputStream is) {
        if (mDiskCache != null) {
            return mDiskCache.pushRaw(key, is);
        } else {
            return false;
        }
    }

    /**
     * Evicts all of the items from the memory cache.
     */
    public void clearMemory() {
        if (mMemoryCache != null) {
            mMemoryCache.evictAll();
        }
    }

    /**
     * Clear disk cache.
     */
    public void clearDisk() {
        if (mDiskCache != null) {
            mDiskCache.clear();
        }
    }

    /**
     * Evicts all of the items from the memory cache and lets the system know
     * now would be a good time to garbage collect.
     */
    public void clear() {
        clearMemory();
        clearDisk();
    }

    /**
     * Flush disk cache.
     */
    public void flush() {
        if (mDiskCache != null) {
            mDiskCache.flush();
        }
    }

    /**
     * A holder class that contains cache parameters.
     */
    public static class BeerBellyParams {

        /**
         * {@code true} to enable memory cache.
         * <p>
         * Default value is {@code false}.
         */
        public boolean hasMemoryCache = false;

        /**
         * The maximum size of memory cache.
         * It must be greater then 0 if memory cache is enabled.
         * <p>
         * Default value is {@code 0}.
         */
        public int memoryCacheMaxSize = 0;

        /**
         * {@code true} to create thread safe LRU cache.
         * <p>
         * Default value is {@code false}.
         */
        public boolean memoryCacheThreadSafe = false;

        /**
         * {@code true} to enable disk cache.
         * <p>
         * Default value is {@code false}.
         */
        public boolean hasDiskCache = false;

        /**
         * The directory to store disk cache.
         * It must be non-null if disk cache is enabled.
         * <p>
         * Default value is {@code null}.
         */
        public File diskCacheDir = null;

        /**
         * The maximum size of disk cache.
         * It must be greater then 0 if disk cache is enabled.
         * <p>
         * Default value is {@code 0}.
         */
        public int diskCacheMaxSize = 0;

        /**
         * Check whether this BeerBellyParams is valid.
         *
         * @throws IllegalStateException
         */
        public void isValid() throws IllegalStateException {
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
            }
        }
    }

    private class MemoryCacheHelper<E> implements LruCacheHelper<String, E> {

        public BeerBelly<E> mParent;

        public MemoryCacheHelper(BeerBelly<E> parent) {
            mParent = parent;
        }

        @Override
        public int sizeOf(String key, E value) {
            return mParent.sizeOf(key, value);
        }

        @Override
        public E create(String key) {
            return null;
        }

        @Override
        public void onEntryAdded(String key, E value) {
            mParent.memoryEntryAdded(key, value);
        }

        @Override
        public void onEntryRemoved(boolean evicted, String key, E oldValue, E newValue) {
            mParent.memoryEntryRemoved(evicted, key, oldValue, newValue);
        }
    }

    private static class DiskCache<E> {

        private final SimpleDiskCache mDiskCache;
        private final BeerBelly<E> mParent;

        private final int mMaxSize;

        public DiskCache(File cacheDir, int size, BeerBelly<E> parent) throws IOException {
            mDiskCache = new SimpleDiskCache(cacheDir, size);
            mParent = parent;
            mMaxSize = size;
        }

        public int getMaxSize() {
            return mMaxSize;
        }

        public long size() {
            return mDiskCache.size();
        }

        public void flush() {
            mDiskCache.flush();
        }

        public void clear() {
            mDiskCache.clear();
        }

        public E get(String key) {
            final InputStreamPipe isPipe = mDiskCache.getInputStreamPipe(key);
            if (isPipe == null) {
                return null;
            } else {
                return mParent.read(isPipe);
            }
        }

        public boolean put(String key, E value) {
            final OutputStreamPipe osPipe = mDiskCache.getOutputStreamPipe(key);
            try {
                osPipe.obtain();
                final OutputStream os = osPipe.open();
                return mParent.write(os, value);
            } catch (IOException e) {
                return false;
            } finally {
                osPipe.close();
                osPipe.release();
            }
        }

        public void remove(String key) {
            mDiskCache.remove(key);
        }

        public boolean pushRaw(String key, InputStream is) {
            return mDiskCache.put(key, is);
        }

        public boolean pullRaw(@NonNull String key, @NonNull OutputStream os) {
            final InputStreamPipe isPipe = mDiskCache.getInputStreamPipe(key);
            if (isPipe == null) {
                return false;
            } else {
                try {
                    isPipe.obtain();
                    final InputStream is = isPipe.open();
                    Util.copy(is, os);
                    return true;
                } catch (IOException e) {
                    return false;
                } finally {
                    isPipe.close();
                    isPipe.release();
                }
            }
        }
    }
}
