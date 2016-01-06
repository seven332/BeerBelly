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

import java.util.Comparator;

public class LruMap<K, V> {

    private static final String TAG = LruMap.class.getSimpleName();

    private SparseObjectArray<K, Entry<K, V>> mMap;

    private Entry<K, V> mHead = null;
    private Entry<K, V> mTail = null;

    public static class Entry<K, V> {
        public K key;
        public V value;
        private Entry<K, V> previous = null;
        private Entry<K, V> next = null;
    }

    public LruMap(Comparator<K> comparator) {
        mMap = new SparseObjectArray<>(comparator);
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

    public V get(@NonNull K key) {
        int index = mMap.indexOfKey(key);
        if (index < 0) {
            return null;
        }

        Entry<K, V> entry = mMap.valueAt(index);
        frontEntry(entry);
        return entry.value;
    }

    /**
     * @return Old value or null
     */
    public V put(K key, V value) {
        Entry<K, V> oldEntry = null;
        V oldValue = null;
        int index = mMap.indexOfKey(key);
        if (index >= 0) {
            oldEntry = mMap.valueAt(index);
            oldValue = oldEntry.value;
            if (oldValue == value) {
                // The old value is the new value
                // Just put it to the head
                frontEntry(oldEntry);
                return null;
            }

            // Remove old one from map
            mMap.removeAt(index);
            // Remove old one from list
            removeEntry(oldEntry);
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

        return oldValue;
    }

    public V remove(K key) {
        // Find it
        int index = mMap.indexOfKey(key);
        if (index < 0) {
            // Can't find it
            return null;
        }

        Entry<K, V> entry = mMap.valueAt(index);
        // Remove it from map
        mMap.removeAt(index);
        // Remove it from list
        removeEntry(entry);
        entry.key = null;
        V value = entry.value;
        entry.value = null;
        return value;
    }

    public Entry<K, V> removeTail() {
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
}
