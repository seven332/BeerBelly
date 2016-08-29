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

import junit.framework.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

class NonThreadSafeLruCache<K, V> extends LruCache<K, V> {

    private int mSize;
    private int mMaxSize;
    private final LruCacheHelper<K, V> mHelper;
    private final LinkedHashMap<K, V> mMap;

    NonThreadSafeLruCache(int maxSize, LruCacheHelper<K, V> helper) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        mMaxSize = maxSize;
        mHelper = helper;
        mMap = new LinkedHashMap<>(0, 0.75f, true);
    }

    @Override
    public int size() {
        return mSize;
    }

    @Override
    public int maxSize() {
        return mMaxSize;
    }

    @Override
    public void resize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }

        mMaxSize = maxSize;
        trimToSize(maxSize);
    }

    @Override
    public void close() {
        mMaxSize = -1;
        trimToSize(mMaxSize);
    }

    @Override
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V mapValue = mMap.get(key);
        if (mapValue != null) {
            return mapValue;
        }

        final V createdValue = mHelper.create(key);
        if (createdValue == null) {
            return null;
        }

        mapValue = mMap.put(key, createdValue);
        Assert.assertNull("Map value is not null, this cache is accessed in another thread.", mapValue);
        mSize += safeSizeOf(key, createdValue);
        trimToSize(mMaxSize);
        return createdValue;
    }

    @Override
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }

        mHelper.onEntryAdded(key, value);

        mSize += safeSizeOf(key, value);
        final V previous = mMap.put(key, value);
        if (previous != null) {
            mSize -= safeSizeOf(key, previous);
        }

        if (previous != null) {
            mHelper.onEntryRemoved(false, key, previous, value);
        }

        trimToSize(mMaxSize);
        return previous;
    }

    @Override
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        final V previous = mMap.remove(key);
        if (previous != null) {
            mSize -= safeSizeOf(key, previous);
        }

        if (previous != null) {
            mHelper.onEntryRemoved(false, key, previous, null);
        }

        return previous;
    }

    @Override
    public void trimToSize(int maxSize) {
        while (true) {
            if (mSize < 0 || (mMap.isEmpty() && mSize != 0)) {
                throw new IllegalStateException(getClass().getName()
                        + ".sizeOf() is reporting inconsistent results!");
            }

            if (mSize <= maxSize || mMap.isEmpty()) {
                break;
            }

            final Map.Entry<K, V> toEvict = mMap.entrySet().iterator().next();
            final K key = toEvict.getKey();
            final V value = toEvict.getValue();
            mMap.remove(key);
            mSize -= safeSizeOf(key, value);

            mHelper.onEntryRemoved(true, key, value, null);
        }
    }

    @Override
    public void evictAll() {
        trimToSize(-1); // -1 will evict 0-sized elements
    }

    private int safeSizeOf(K key, V value) {
        final int result = mHelper.sizeOf(key, value);
        if (result < 0) {
            throw new IllegalStateException("Negative size: " + key + "=" + value);
        }
        return result;
    }
}
