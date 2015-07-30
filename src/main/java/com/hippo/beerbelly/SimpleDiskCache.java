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
import android.support.annotation.Nullable;
import android.util.Log;

import com.hippo.yorozuya.io.InputStreamPipe;
import com.hippo.yorozuya.io.OutputStreamPipe;

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

    private volatile int mDiskCacheState;

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

    // TODO Need a better way to handle clear failed
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

    public boolean contain(@NonNull String key) {
        String diskKey = hashKeyForDisk(key);

        CounterLock lock = obtainLock(diskKey);

        if (!isValid()) {
            releaseLock(diskKey, lock);
            return false;
        }

        lock.lock();

        boolean result;
        try {
            DiskLruCache.Snapshot snapshot = mDiskLruCache.get(diskKey);
            if (snapshot != null) {
                snapshot.close();
                result = true;
            } else {
                result = false;
            }
        } catch (IOException e) {
            // Get trouble
            result = false;
        }

        lock.unlock();

        releaseLock(diskKey, lock);

        return result;
    }

    public void remove(@NonNull String key) {
        String diskKey = hashKeyForDisk(key);

        CounterLock lock = obtainLock(diskKey);

        if (!isValid()) {
            releaseLock(diskKey, lock);
            return;
        }

        lock.lock();

        try {
            mDiskLruCache.remove(diskKey);
        } catch (IOException e) {
            // Ignore
        }

        lock.unlock();

        releaseLock(diskKey, lock);
    }


    /**
     * @param key the key of the target
     * @return the InputStreamPipe, <code>null</code> for missing
     */
    public @Nullable InputStreamPipe getInputStreamPipe(@NonNull String key) {
        if (contain(key)) {
            String diskKey = hashKeyForDisk(key);
            return new CacheInputStreamPipe(diskKey);
        } else {
            return null;
        }
    }

    public @NonNull OutputStreamPipe getOutputStreamPipe(@NonNull String key) {
        String diskKey = hashKeyForDisk(key);
        return new CacheOutputStreamPipe(diskKey);
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

    class CacheInputStreamPipe implements InputStreamPipe {

        private String mKey;
        private CounterLock mLock;
        private DiskLruCache.Snapshot mCurrentSnapshot;

        private CacheInputStreamPipe(String key) {
            mKey = key;
        }

        @Override
        public void obtain() {
            if (mLock == null) {
                mLock = obtainLock(mKey);
                mLock.lock();
            }
        }

        @Override
        public void release() {
            if (mCurrentSnapshot != null) {
                throw new IllegalStateException("Please close it first");
            }

            if (mLock != null) {
                mLock.unlock();
                releaseLock(mKey, mLock);
                mLock = null;
            }
        }

        @Override
        public @NonNull InputStream open() throws IOException {
            if (mLock == null) {
                throw new IllegalStateException("Please obtain it first");
            }
            if (mCurrentSnapshot != null) {
                throw new IllegalStateException("Please close it before reopen");
            }

            DiskLruCache.Snapshot snapshot;
            snapshot = mDiskLruCache.get(mKey);
            if (snapshot == null) {
                throw new IOException("Miss the key " + mKey);
            }
            mCurrentSnapshot = snapshot;
            return snapshot.getInputStream(0);
        }

        @Override
        public void close() {
            Util.closeQuietly(mCurrentSnapshot);
            mCurrentSnapshot = null;
        }
    }

    class CacheOutputStreamPipe implements OutputStreamPipe {

        private String mKey;
        private CounterLock mLock;
        private DiskLruCache.Editor mCurrentEditor;

        private CacheOutputStreamPipe(String key) {
            mKey = key;
        }

        @Override
        public void obtain() {
            if (mLock == null) {
                mLock = obtainLock(mKey);
                mLock.lock();
            }
        }

        @Override
        public void release() {
            if (mCurrentEditor != null) {
                throw new IllegalStateException("Please close it first");
            }

            if (mLock != null) {
                mLock.unlock();
                releaseLock(mKey, mLock);
                mLock = null;
            }
        }

        @Override
        public @NonNull OutputStream open() throws IOException {
            if (mLock == null) {
                throw new IllegalStateException("Please obtain it first");
            }
            if (mCurrentEditor != null) {
                throw new IllegalStateException("Please close it before reopen");
            }

            DiskLruCache.Editor editor = mDiskLruCache.edit(mKey);
            if (editor == null) {
                throw new IOException("Miss the key " + mKey);
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
                } catch (IOException ex) {
                    // Ignore
                }
                throw e;
            }
        }

        @Override
        public void close() {
            if (mCurrentEditor != null) {
                try {
                    mCurrentEditor.commit();
                } catch (IOException e) {
                    // Empty
                }
                mCurrentEditor = null;
            }
        }
    }
}
