/*
 * Copyright 2016 Hippo Seven
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

import java.util.Comparator;

public class LruCacheEx<K, V> {

    private LruMap<K, V> mLruMap;

    /** Size of this cache in units. Not necessarily the number of elements. */
    private int mSize;
    private int mMaxSize;

    private int mPutCount;
    private int mCreateCount;
    private int mEvictionCount;
    private int mHitCount;
    private int mMissCount;

    /**
     * @param comparator To help sequence the key for quick query
     */
    public LruCacheEx(int maxSize, Comparator<K> comparator) {
        this(maxSize, 0, comparator);
    }

    /**
     * @param comparator To help sequence the key for quick query
     */
    public LruCacheEx(int maxSize, long timeout, Comparator<K> comparator) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        mLruMap = new LruMap<>(comparator, timeout);
        mSize = 0;
        mMaxSize = maxSize;
    }

    /**
     * Sets the size of the cache.
     *
     * @param maxSize The new maximum size.
     */
    public void resize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }

        synchronized (this) {
            mSize = maxSize;
        }
        trimToSize(maxSize);
    }

    /**
     * Returns the value for {@code key} if it exists in the cache or can be
     * created by {@code #create}. If a value was returned, it is moved to the
     * head of the queue. This returns null if a value is not cached and cannot
     * be created.
     */
    public final V get(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V mapValue;
        synchronized (this) {
            mapValue = mLruMap.get(key);
            if (mapValue != null) {
                mHitCount++;
                return mapValue;
            }
            mMissCount++;
            return null;
        }
    }

    /**
     * Caches {@code value} for {@code key}. The value is moved to the head of
     * the queue.
     *
     * @return the previous value mapped by {@code key}.
     */
    public final V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }

        V previous;
        synchronized (this) {
            mPutCount++;
            mSize += safeSizeOf(key, value);
            previous = mLruMap.put(key, value);
            if (previous != null) {
                mSize -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, value);
        }

        trimToSize(mMaxSize, value);
        return previous;
    }

    public void trimToSize(int maxSize) {
        trimToSize(maxSize, null);
    }

    /**
     * Remove the eldest entries until the total of remaining entries is at or
     * below the requested size.
     *
     * @param maxSize the maximum size of the cache before returning. May be -1
     *            to evict even 0-sized elements.
     */
    public void trimToSize(int maxSize, V keepValue) {
        int count;
        int skipTimes = 0;
        synchronized (this) {
            count = mLruMap.size();
        }

        while (true) {
            K key;
            V value;
            synchronized (this) {
                if (mSize < 0 || (mLruMap.isEmpty() && mSize != 0)) {
                    throw new IllegalStateException(getClass().getName()
                            + ".sizeOf() is reporting inconsistent results!");
                }

                if (mSize <= maxSize) {
                    break;
                }

                if (skipTimes == count) {
                    break;
                }

                LruMap.Entry<K, V> toEvict = mLruMap.removeTail();
                if (toEvict == null) {
                    break;
                }

                key = toEvict.key;
                value = toEvict.value;
                if (canBeRemoved(key, value) && keepValue != value) {
                    mSize -= safeSizeOf(key, value);
                    mEvictionCount++;
                    entryRemoved(true, key, value, null);
                } else {
                    // Can not remove it, put it back
                    mLruMap.put(key, value);
                    skipTimes++;
                }
            }
        }
    }

    /**
     * Removes the entry for {@code key} if it exists.
     *
     * @return the previous value mapped by {@code key}.
     */
    public final V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V previous;
        synchronized (this) {
            previous = mLruMap.remove(key);
            if (previous != null) {
                mSize -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, null);
        }

        return previous;
    }

    /**
     * Called for entries that have been evicted or removed. This method is
     * invoked when a value is evicted to make space, removed by a call to
     * {@link #remove}, or replaced by a call to {@link #put}. The default
     * implementation does nothing.
     *
     * <p>The method is called without synchronization: other threads may
     * access the cache while this method is executing.
     *
     * @param evicted true if the entry is being removed to make space, false
     *     if the removal was caused by a {@link #put} or {@link #remove}.
     * @param newValue the new value for {@code key}, if it exists. If non-null,
     *     this removal was caused by a {@link #put}. Otherwise it was caused by
     *     an eviction or a {@link #remove}.
     */
    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {}

    private int safeSizeOf(K key, V value) {
        int result = sizeOf(key, value);
        if (result < 0) {
            throw new IllegalStateException("Negative size: " + key + "=" + value);
        }
        return result;
    }

    /**
     * Returns the size of the entry for {@code key} and {@code value} in
     * user-defined units.  The default implementation returns 1 so that size
     * is the number of entries and max size is the maximum number of entries.
     *
     * <p>An entry's size must not change while it is in the cache.
     */
    protected int sizeOf(K key, V value) {
        return 1;
    }

    protected boolean canBeRemoved(K key, V value) {
        return true;
    }

    /**
     * Clear the cache, calling {@link #entryRemoved} on each removed entry.
     */
    public final void evictAll() {
        trimToSize(-1); // -1 will evict 0-sized elements
    }

    /**
     * For caches that do not override {@link #sizeOf}, this returns the number
     * of entries in the cache. For all other caches, this returns the sum of
     * the sizes of the entries in this cache.
     */
    public synchronized final int size() {
        return mSize;
    }

    /**
     * For caches that do not override {@link #sizeOf}, this returns the maximum
     * number of entries in the cache. For all other caches, this returns the
     * maximum sum of the sizes of the entries in this cache.
     */
    public synchronized final int maxSize() {
        return mMaxSize;
    }
}
