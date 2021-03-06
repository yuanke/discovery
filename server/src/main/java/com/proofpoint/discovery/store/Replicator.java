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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.proofpoint.discovery.InitializationTracker;
import com.proofpoint.discovery.InitializationTracker.CompletionNotifier;
import com.proofpoint.discovery.client.ServiceDescriptor;
import com.proofpoint.discovery.client.ServiceSelector;
import com.proofpoint.http.client.HttpClient;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.Response;
import com.proofpoint.http.client.ResponseHandler;
import com.proofpoint.http.client.balancing.HttpServiceBalancerStats;
import com.proofpoint.http.client.balancing.HttpServiceBalancerStats.Status;
import com.proofpoint.log.Logger;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.units.Duration;
import org.weakref.jmx.Managed;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.EOFException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Replicator
{
    private static final Logger log = Logger.get(Replicator.class);

    private final String name;
    private final NodeInfo node;
    private final ServiceSelector selector;
    private final HttpClient httpClient;
    private final HttpServiceBalancerStats httpServiceBalancerStats;
    private final LocalStore localStore;
    private final Duration replicationInterval;
    private final CompletionNotifier completionNotifier;

    private ScheduledFuture<?> future;
    private ScheduledExecutorService executor;

    private final ObjectMapper mapper = new ObjectMapper(new SmileFactory()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AtomicLong lastReplicationTimestamp = new AtomicLong();

    public Replicator(String name,
            NodeInfo node,
            ServiceSelector selector,
            HttpClient httpClient,
            HttpServiceBalancerStats httpServiceBalancerStats,
            LocalStore localStore,
            StoreConfig config,
            InitializationTracker initializationTracker)
    {
        this.name = name;
        this.node = node;
        this.selector = selector;
        this.httpClient = httpClient;
        this.httpServiceBalancerStats = httpServiceBalancerStats;
        this.localStore = localStore;

        this.replicationInterval = config.getReplicationInterval();
        completionNotifier = initializationTracker.createTask();
    }

    @PostConstruct
    public synchronized void start()
    {
        if (future == null) {
            executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("replicator-" + name + "-%d").setDaemon(true).build());

            future = executor.scheduleAtFixedRate(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        synchronize();
                    }
                    catch (Throwable t) {
                        log.warn(t, "Error replicating state");
                    }
                }
            }, 0, replicationInterval.toMillis(), TimeUnit.MILLISECONDS);
        }

        // TODO: need fail-safe recurrent scheduler with variable delay
    }

    @PreDestroy
    public synchronized void shutdown()
    {
        if (future != null) {
            future.cancel(true);
            executor.shutdownNow();

            executor = null;
            future = null;
        }
    }

    @Managed
    public long getLastReplicationTimestamp()
    {
        return lastReplicationTimestamp.get();
    }

    private void synchronize()
    {
        for (ServiceDescriptor descriptor : selector.selectAllServices()) {
            if (descriptor.getNodeId().equals(node.getNodeId())) {
                // don't write to ourselves
                continue;
            }

            final String uri = descriptor.getProperties().get("http");
            if (uri == null) {
                log.error("service descriptor for node %s is missing http uri", descriptor.getNodeId());
                continue;
            }

            // TODO: build URI from resource class
            Request request = Request.Builder.prepareGet()
                    .setUri(URI.create(uri + "/v1/store/" + name))
                    .build();

            try {
                final long startTime = System.nanoTime();
                httpClient.execute(request, new ResponseHandler<Void, Exception>()
                {
                    @Override
                    public Void handleException(Request request, Exception exception)
                            throws Exception
                    {
                        URI uri1 = URI.create(uri);
                        httpServiceBalancerStats.responseTime(uri1, Status.FAILURE).add(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
                        httpServiceBalancerStats.failure(uri1, exception.getClass().getSimpleName()).update(1);
                        throw exception;
                    }

                    @Override
                    public Void handle(Request request, Response response)
                            throws Exception
                    {
                        // TODO: read server date (to use to calibrate entry dates)

                        URI uri1 = URI.create(uri);
                        if (response.getStatusCode() == 200) {
                            httpServiceBalancerStats.responseTime(uri1, Status.SUCCESS).add(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
                            try {
                                List<Entry> entries = mapper.readValue(response.getInputStream(), new TypeReference<List<Entry>>() {});
                                for (Entry entry : entries) {
                                    localStore.put(entry);
                                }
                            }
                            catch (EOFException e) {
                                // ignore
                            }
                        }
                        else {
                            httpServiceBalancerStats.responseTime(uri1, Status.FAILURE).add(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
                            httpServiceBalancerStats.failure(uri1, response.getStatusCode() + " status code").update(1);
                        }

                        return null;
                    }
                });
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (Exception e) {
                // ignore
            }
        }

        completionNotifier.complete();
        lastReplicationTimestamp.set(System.currentTimeMillis());
    }
}
