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

/*
 * Created by Hippo on 8/29/2016.
 */

import android.support.annotation.NonNull;

public abstract class LruCache<K, V> {

    /**
     * Create a LRU cache.
     *
     * @param maxSize must greater then 0.
     * @param threadSafe {@code true} to create a thread safe LRU cache.
     */
    public static <M, N> LruCache<M, N> create(int maxSize,
            @NonNull LruCacheHelper<M, N> helper, boolean threadSafe) {
        return threadSafe ? new ThreadSafeLruCache<>(maxSize, helper) :
                new NonThreadSafeLruCache<>(maxSize, helper);
    }

    /**
     * Returns the sum of the sizes of the entries in this cache.
     */
    public abstract int size();

    /**
     * Returns the maximum sum of the sizes of the entries in this cache.
     */
    public abstract int maxSize();

    /**
     * Reset the maximum sum of the sizes of the entries in this cache.
     *
     * @param maxSize The new maximum size. It must greater then 0.
     */
    public abstract void resize(int maxSize);

    /**
     * Close the cache, all values will be removed.
     * If a value is put, it will be removed at once.
     * Resize to reopen the cache.
     */
    public abstract void close();

    /**
     * Returns the value for {@code key} if it exists in the cache or can be
     * created by {@code #create}. If a value was returned, it is moved to the
     * head of the queue. This returns null if a value is not cached and cannot
     * be created.
     */
    public abstract V get(K key);

    /**
     * Caches {@code value} for {@code key}. The value is moved to the head of
     * the queue.
     *
     * @return the previous value mapped by {@code key}.
     */
    public abstract V put(K key, V value);

    /**
     * Removes the entry for {@code key} if it exists.
     *
     * @return the previous value mapped by {@code key}.
     */
    public abstract V remove(K key);

    /**
     * Remove the eldest entries until the total of remaining entries is at or
     * below the requested size. It will not change the max size of the cache.
     *
     * @param maxSize the maximum size of the cache before returning. May be -1
     *            to evict even 0-sized elements.
     */
    public abstract void trimToSize(int maxSize);

    /**
     * Clear the cache.
     */
    public abstract void evictAll();
}
