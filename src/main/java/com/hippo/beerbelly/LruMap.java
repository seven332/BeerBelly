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

import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.hippo.yorozuya.ArrayUtils;
import com.hippo.yorozuya.sparse.ContainerHelpers;

import java.util.HashMap;
import java.util.Map;

public class LruMap<K, V> {

    private static final String TAG = LruMap.class.getSimpleName();

    private final Map<K, Entry<K, V>> mMap;
    private DuplicateJLMap<Entry<K, V>> mTimeoutMap;

    private Entry<K, V> mHead = null;
    private Entry<K, V> mTail = null;

    private final long mTimeout;
    private final boolean mSupportTimeout;

    public static class Entry<K, V> {
        public K key;
        public V value;
        public long expired; // Expired
        private Entry<K, V> previous = null;
        private Entry<K, V> next = null;
    }

    public LruMap() {
        this(0);
    }

    /**
     * @param timeout 0 or negation for no time out
     */
    public LruMap(long timeout) {
        mMap = new HashMap<>();
        mTimeout = timeout;
        mSupportTimeout = timeout > 0;
        if (mSupportTimeout) {
            mTimeoutMap = new DuplicateJLMap<>();
        }
    }

    private void removeEntry(Entry<K, V> entry) {
        // Take out the entry
        Entry<K, V> previous = entry.previous;
        Entry<K, V> next = entry.next;
        if (previous != null) {
            previous.next = next;
        }
        if (next != null) {
            next.previous = previous;
        }
        entry.previous = null;
        entry.next = null;

        // Update head
        if (entry == mHead) {
            mHead = next;
        }

        // Update tail
        if (entry == mTail) {
            mTail = previous;
        }
    }

    private void frontEntry(Entry<K, V> entry) {
        if (entry == mHead) {
            return;
        }

        // Remove the entry
        removeEntry(entry);

        // Put the entry to the head
        if (mHead != null) {
            mHead.previous = entry;
            entry.next = mHead;
            entry.previous = null;
            mHead = entry;
        } else {
            Log.e(TAG, "WTF ? Head is null !");
        }
    }

    private void trimToTimeout() {
        if (!mSupportTimeout) {
            return;
        }

        int removeSize = mTimeoutMap.getRemoveSize(System.currentTimeMillis());
        if (removeSize <= 0) {
            return;
        }

        for (int i = 0; i < removeSize; i++) {
            // Get the entry
            Entry<K, V> entry = mTimeoutMap.valueAt(i);
            // Remove it from list
            removeEntry(entry);
            // Remove it from map
            mMap.remove(entry.key);
        }
        mTimeoutMap.removeAtRange(0, removeSize);
    }

    public V get(@NonNull K key) {
        trimToTimeout();

        Entry<K, V> entry = mMap.get(key);
        if (null != entry) {
            frontEntry(entry);
            return entry.value;
        } else {
            return null;
        }
    }

    /**
     * @return Old value or null
     */
    public V put(@NonNull K key, @NonNull V value) {
        trimToTimeout();

        Entry<K, V> oldEntry;
        V oldValue = null;

        // Check is the key in the map
        oldEntry = mMap.remove(key);
        if (oldEntry != null) {
            // Get old value
            oldValue = oldEntry.value;
            // Remove old one from list
            removeEntry(oldEntry);
            // Remove from time map
            if (mSupportTimeout) {
                mTimeoutMap.delete(oldEntry.expired, oldEntry);
            }
            oldEntry.key = null;
            oldEntry.value = null;
        }

        Entry<K, V> entry = oldEntry != null ? oldEntry : new Entry<K, V>();
        entry.key = key;
        entry.value = value;
        // Put it to head
        if (mHead == null) {
            mHead = entry;
            mTail = entry;
        } else {
            mHead.previous = entry;
            entry.next = mHead;
            mHead = entry;
        }
        // Add it to map
        mMap.put(key, entry);
        // Add it to timeout map if necessary
        if (mSupportTimeout) {
            entry.expired = System.currentTimeMillis() + mTimeout;
            mTimeoutMap.put(entry.expired, entry);
        }

        return oldValue;
    }

    public V remove(K key) {
        trimToTimeout();

        // Find it
        Entry<K, V> entry = mMap.remove(key);
        if (null != entry) {
            // Remove it from list
            removeEntry(entry);
            // Remove from time map
            if (mSupportTimeout) {
                mTimeoutMap.delete(entry.expired, entry);
            }
            entry.key = null;
            V value = entry.value;
            entry.value = null;
            return value;
        } else {
            return null;
        }
    }

    public Entry<K, V> removeTail() {
        trimToTimeout();

        if (mTail == null) {
            return null;
        }

        Entry<K, V> entry = mTail;
        removeEntry(entry);
        mMap.remove(entry.key);
        return entry;
    }

    public int size() {
        return mMap.size();
    }

    public boolean isEmpty() {
        return mMap.size() == 0;
    }

    @VisibleForTesting
    public int mapSize() {
        return mMap.size();
    }

    @VisibleForTesting
    public int listSize() {
        int size = 0;
        for (Entry entry = mHead; entry != null; entry = entry.next, size++);
        return size;
    }

    // Looks like SparseArray, but key is long and can be duplicate.
    private static class DuplicateJLMap<E> {

        // TODO Is it good for performance
        private static final Object DELETED = new Object();
        private boolean mGarbage = false;

        private long[] mKeys;
        private Object[] mValues;
        private int mSize;

        public DuplicateJLMap() {
            this(10);
        }

        public DuplicateJLMap(int initialCapacity) {
            mKeys = new long[initialCapacity];
            mValues = new Object[initialCapacity];
            mSize = 0;
        }

        private void gc() {
            int n = mSize;
            int o = 0;
            long[] keys = mKeys;
            Object[] values = mValues;

            for (int i = 0; i < n; i++) {
                Object val = values[i];

                if (val != DELETED) {
                    if (i != o) {
                        keys[o] = keys[i];
                        values[o] = val;
                        values[i] = null;
                    }

                    o++;
                }
            }

            mGarbage = false;
            mSize = o;
        }

        public void delete(long key, E o) {
            int index = ContainerHelpers.binarySearch(mKeys, mSize, key);

            if (index >= 0) {
                // Key can be duplicate, so test both side
                for (int i = index; index < mSize; i++) {
                    if (key != mKeys[i]) {
                        break;
                    }
                    if (o == mValues[i]) {
                        mValues[i] = DELETED;
                        mGarbage = true;
                        return;
                    }
                }
                for (int i = index - 1; index >= 0; i--) {
                    if (key != mKeys[i]) {
                        break;
                    }
                    if (o == mValues[i]) {
                        mValues[i] = DELETED;
                        mGarbage = true;
                        return;
                    }
                }
            }
        }

        public void put(long key, E value) {
            int index = ContainerHelpers.binarySearch(mKeys, mSize, key);
            if (index < 0) {
                index = ~index;
            }

            if (index < mSize && mValues[index] == DELETED) {
                mKeys[index] = key;
                mValues[index] = value;
                return;
            }

            if (mGarbage && mSize >= mKeys.length) {
                gc();

                // Search again because indices may have changed.
                index = ContainerHelpers.binarySearch(mKeys, mSize, key);
                if (index < 0) {
                    index = ~index;
                }
            }

            mKeys = ArrayUtils.insert(mKeys, mSize, index, key);
            mValues = ArrayUtils.insert(mValues, mSize, index, value);
            mSize++;
        }

        @SuppressWarnings("unchecked")
        public E valueAt(int index) {
            if (mGarbage) {
                gc();
            }

            return (E) mValues[index];
        }

        public void removeAtRange(int index, int size) {
            final int end = Math.min(mSize, index + size);
            for (int i = index; i < end; i++) {
                removeAt(i);
            }
        }

        public void removeAt(int index) {
            if (mValues[index] != DELETED) {
                mValues[index] = DELETED;
                mGarbage = true;
            }
        }

        public int getRemoveSize(long l) {
            if (mGarbage) {
                gc();
            }

            int index = ContainerHelpers.binarySearch(mKeys, mSize, l);
            if (index < 0) {
                index = ~index;
                if (index > mSize || index <= 0) {
                    // None item is expired
                    return 0;
                }
            } else {
                for (index++; index < mSize; index++) {
                    if (l != mKeys[index]) {
                        break;
                    }
                }
            }
            return index;
        }
    }
}
