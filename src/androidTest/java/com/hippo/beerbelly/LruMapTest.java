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

import junit.framework.TestCase;

public class LruMapTest extends TestCase {

    public void testLruMap() {
        LruMap<Integer, String> lruMap = new LruMap<>();

        lruMap.put(1, "121");
        lruMap.put(1, "12");
        lruMap.put(1, "12fsafa1");

        assertEquals(1, lruMap.size());
        assertEquals(lruMap.mapSize(), lruMap.listSize());

        LruMap.Entry<Integer, String> entry = lruMap.removeTail();

        assertEquals(Integer.valueOf(1), entry.key);
        assertEquals("12fsafa1", entry.value);
        assertEquals(0, lruMap.size());
        assertEquals(lruMap.mapSize(), lruMap.listSize());

        entry = lruMap.removeTail();

        assertEquals(null, entry);
        assertEquals(0, lruMap.size());
        assertEquals(lruMap.mapSize(), lruMap.listSize());

        lruMap.put(1, "1");
        lruMap.put(10, "10");
        lruMap.put(8, "8");
        lruMap.put(9, "999");
        lruMap.remove(10);
        lruMap.put(3, "3");
        lruMap.put(232, "232");
        lruMap.put(7, "7");
        lruMap.put(3, "3");
        lruMap.get(232);
        lruMap.put(2, "2");
        String value = lruMap.put(9, "9");


        assertEquals(value, "999");
        assertEquals(7, lruMap.size());
        assertEquals(lruMap.mapSize(), lruMap.listSize());

        entry = lruMap.removeTail();
        assertEquals(Integer.valueOf(1), entry.key);
        assertEquals("1", entry.value);

        entry = lruMap.removeTail();
        assertEquals(Integer.valueOf(8), entry.key);
        assertEquals("8", entry.value);

        entry = lruMap.removeTail();
        assertEquals(Integer.valueOf(7), entry.key);
        assertEquals("7", entry.value);

        entry = lruMap.removeTail();
        assertEquals(Integer.valueOf(3), entry.key);
        assertEquals("3", entry.value);

        entry = lruMap.removeTail();
        assertEquals(Integer.valueOf(232), entry.key);
        assertEquals("232", entry.value);

        entry = lruMap.removeTail();
        assertEquals(Integer.valueOf(2), entry.key);
        assertEquals("2", entry.value);

        entry = lruMap.removeTail();
        assertEquals(Integer.valueOf(9), entry.key);
        assertEquals("9", entry.value);
    }
}
