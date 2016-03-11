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

import junit.framework.Assert;
import junit.framework.TestCase;

public class LruCacheExTest extends TestCase {

    public void testLruCacheEx() {
        LruCacheEx<Integer, String> cache = new LruCacheEx<Integer, String>(100)
        {
            @Override
            protected void entryRemoved(boolean evicted, Integer key, String oldValue, String newValue) {
                Assert.assertEquals(50, (int) key);
            }

            @Override
            protected int sizeOf(Integer key, String value) {
                return key;
            }
        };

        cache.put(50, "50");
        cache.put(30, "30");
        cache.put(20, "30");
        cache.put(10, "10");

        Assert.assertEquals(60, cache.size());
    }
}
