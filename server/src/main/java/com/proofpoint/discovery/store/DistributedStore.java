package com.proofpoint.discovery.store;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.proofpoint.units.Duration;
import org.joda.time.DateTime;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.not;

public class DistributedStore
{
    private final InMemoryStore localStore;
    private final RemoteStore remoteStore;
    private final Provider<DateTime> timeProvider;
    private final Duration tombstoneMaxAge;
    
    private final ScheduledExecutorService garbageCollector;

    @Inject
    public DistributedStore(InMemoryStore localStore, RemoteStore remoteStore, StoreConfig config, Provider<DateTime> timeProvider)
    {
        this.localStore = localStore;
        this.remoteStore = remoteStore;
        this.timeProvider = timeProvider;

        tombstoneMaxAge = config.getTombstoneMaxAge();
        
        garbageCollector = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("distributed-store-gc-%d").setDaemon(true).build());
    }

    @PostConstruct
    public void start()
    {
        garbageCollector.scheduleAtFixedRate(new Runnable()
        {
            @Override
            public void run()
            {
                removeExpiredEntries();
            }
        }, 0, 10, TimeUnit.SECONDS); // TODO: make configurable
    }

    private void removeExpiredEntries()
    {
        for (Entry entry : localStore.getAll()) {
            if (isExpired(entry)) {
                localStore.delete(entry.getKey(), entry.getVersion());
            }
        }
    }

    private boolean isExpired(Entry entry)
    {
        long ageInMs = timeProvider.get().getMillis() - entry.getTimestamp();

        return entry.getValue() == null && ageInMs > tombstoneMaxAge.toMillis() ||  // TODO: this is repeated in StoreResource
                entry.getMaxAgeInMs() != null && ageInMs > entry.getMaxAgeInMs();
    }

    @PreDestroy
    public void shutdown()
    {
        garbageCollector.shutdownNow();
    }

    public void put(byte[] key, byte[] value)
    {
        Preconditions.checkNotNull(key, "key is null");
        Preconditions.checkNotNull(value, "value is null");

        long now = timeProvider.get().getMillis();

        Entry entry = new Entry(key, value, new Version(now), now, null);

        localStore.put(entry);
        remoteStore.put(entry);
    }
    
    public void put(byte[] key, byte[] value, Duration maxAge)
    {
        Preconditions.checkNotNull(key, "key is null");
        Preconditions.checkNotNull(value, "value is null");
        Preconditions.checkNotNull(maxAge, "maxAge is null");

        long now = timeProvider.get().getMillis();

        Entry entry = new Entry(key, value, new Version(now), now, (long) maxAge.toMillis());

        localStore.put(entry);
        remoteStore.put(entry);
    }

    public byte[] get(byte[] key)
    {
        Preconditions.checkNotNull(key, "key is null");

        Entry entry = localStore.get(key);
        
        byte[] result = null;
        if (entry != null && entry.getValue() != null && !isExpired(entry)) {
            result = Arrays.copyOf(entry.getValue(), entry.getValue().length);
        }

        return result;
    }

    public void delete(byte[] key)
    {
        Preconditions.checkNotNull(key, "key is null");

        long now = timeProvider.get().getMillis();

        Entry entry = new Entry(key, null, new Version(now), now, null);

        localStore.put(entry);
        remoteStore.put(entry);
    }

    public Iterable<Entry> getAll()
    {
        return Iterables.filter(localStore.getAll(), and(nonExpiredEntries(), not(tombstone())));
    }

    private Predicate<? super Entry> nonExpiredEntries()
    {
        return new Predicate<Entry>()
        {
            public boolean apply(Entry entry)
            {
                return !isExpired(entry);
            }
        };
    }

    private Predicate<? super Entry> tombstone()
    {
        return new Predicate<Entry>()
        {
            public boolean apply(Entry entry)
            {
                return entry.getValue() == null;
            }
        };
    }
}