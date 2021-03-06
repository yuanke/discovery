/*
 * Copyright 2010 Proofpoint, Inc.
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
package com.proofpoint.discovery.store;

import com.google.common.base.Charsets;
import com.proofpoint.discovery.DiscoveryConfig;
import com.proofpoint.units.Duration;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static com.google.common.base.Charsets.UTF_8;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class TestInMemoryStore
{
    private LocalStore store;

    @BeforeMethod
    protected void setUp()
            throws Exception
    {
        DiscoveryConfig config = new DiscoveryConfig().setMaxAge(new Duration(1, TimeUnit.MINUTES));
        store = new InMemoryStore(new ConflictResolver(), config);
    }

    @Test
    public void testPut()
    {
        Entry entry = entryOf("blue", "apple", 1);
        store.put(entry);

        assertEquals(store.get("blue".getBytes(Charsets.UTF_8)), entry);
    }

    @Test
    public void testDelete()
    {
        byte[] key = "blue".getBytes(Charsets.UTF_8);
        Entry entry = entryOf("blue", "apple", 1);
        store.put(entry);

        store.delete(key, entry.getTimestamp());

        assertNull(store.get(key));
    }

    @Test
    public void testDeleteOlderVersion()
    {
        byte[] key = "blue".getBytes(Charsets.UTF_8);
        Entry entry = entryOf("blue", "apple", 5);
        store.put(entry);

        store.delete(key, 2);

        assertEquals(store.get("blue".getBytes(Charsets.UTF_8)), entry);
    }

    @Test
    public void testResolvesConflict()
    {
        Entry entry2 = entryOf("blue", "apple", 2);
        store.put(entry2);

        Entry entry1 = entryOf("blue", "banana", 1);
        store.put(entry1);

        assertEquals(store.get("blue".getBytes(Charsets.UTF_8)), entry2);
    }

    @Test
    public void testDefaultsMaxAge()
    {
        Entry entry = entryOf("blue", "apple", 1);
        store.put(new Entry(entry.getKey(), entry.getValue(), entry.getTimestamp(), null));

        assertEquals(store.get("blue".getBytes(Charsets.UTF_8)), entry);
    }

    private static Entry entryOf(String key, String value, long timestamp)
    {
        return new Entry(key.getBytes(UTF_8), value.getBytes(Charsets.UTF_8), timestamp, 60_000L);
    }
}
