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

public interface LruCacheHelper<K, V> {

    /**
     * Returns the size of the entry for {@code key} and {@code value} in
     * user-defined units.  The default implementation returns 1 so that size
     * is the number of entries and max size is the maximum number of entries.
     *
     * <p>An entry's size must not change while it is in the cache.
     */
    int sizeOf(K key, V value);

    /**
     * Called after a cache miss to compute a value for the corresponding key.
     * Returns the computed value or null if no value can be computed. The
     * default implementation returns null.
     *
     * <p>The method is called without synchronization: other threads may
     * access the cache while this method is executing.
     *
     * <p>If a value for {@code key} exists in the cache when this method
     * returns, the created value will be released with {@link #onEntryRemoved}
     * and discarded. This can occur when multiple threads request the same key
     * at the same time (causing multiple values to be created), or when one
     * thread calls {@link LruCache#put} while another is creating a value for the same
     * key.
     */
    V create(K key);

    /**
     * Called for each entry when added to the cache.
     */
    void onEntryAdded(K key, V value);

    /**
     * Called for entries that have been evicted or removed. This method is
     * invoked when a value is evicted to make space, removed by a call to
     * {@link LruCache#remove}, or replaced by a call to {@link LruCache#put}.
     * The default implementation does nothing.
     *
     * <p>The method is called without synchronization: other threads may
     * access the cache while this method is executing.
     *
     * @param evicted true if the entry is being removed to make space, false
     *     if the removal was caused by a {@link LruCache#put} or {@link LruCache#remove}.
     * @param newValue the new value for {@code key}, if it exists. If non-null,
     *     this removal was caused by a {@link LruCache#put}. Otherwise it was caused by
     *     an eviction or a {@link LruCache#remove}.
     */
    void onEntryRemoved(boolean evicted, K key, V oldValue, V newValue);
}
