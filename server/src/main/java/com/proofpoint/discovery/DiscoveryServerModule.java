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
package com.proofpoint.discovery;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.PrivateBinder;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.proofpoint.discovery.client.ServiceDescriptor;
import com.proofpoint.discovery.client.ServiceInventory;
import com.proofpoint.discovery.client.ServiceSelector;
import com.proofpoint.discovery.store.InMemoryStore;
import com.proofpoint.discovery.store.PersistentStore;
import com.proofpoint.discovery.store.PersistentStoreConfig;
import com.proofpoint.discovery.store.ReplicatedStoreModule;
import com.proofpoint.http.client.AsyncHttpClient;
import com.proofpoint.http.client.balancing.BalancingAsyncHttpClient;
import com.proofpoint.http.client.balancing.BalancingHttpClientConfig;
import com.proofpoint.http.client.balancing.ForBalancingHttpClient;
import com.proofpoint.http.client.balancing.HttpServiceBalancer;
import com.proofpoint.http.client.balancing.HttpServiceBalancerImpl;
import com.proofpoint.http.client.balancing.HttpServiceBalancerStats;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.reporting.ReportCollectionFactory;
import org.weakref.jmx.ObjectNameBuilder;

import javax.inject.Singleton;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.proofpoint.configuration.ConfigurationModule.bindConfig;
import static com.proofpoint.discovery.client.DiscoveryBinder.discoveryBinder;
import static com.proofpoint.http.client.HttpClientBinder.httpClientPrivateBinder;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class DiscoveryServerModule
        implements Module
{
    public void configure(Binder binder)
    {
        bindConfig(binder).to(DiscoveryConfig.class);
        binder.bind(ServiceResource.class).in(Scopes.SINGLETON);
        binder.bind(InitializationTracker.class).in(Scopes.SINGLETON);

        discoveryBinder(binder).bindHttpAnnouncement("discovery");

        // dynamic announcements
        binder.bind(DynamicAnnouncementResource.class).in(Scopes.SINGLETON);
        binder.bind(DynamicStore.class).to(ReplicatedDynamicStore.class).in(Scopes.SINGLETON);
        binder.install(new ReplicatedStoreModule("dynamic", ForDynamicStore.class, InMemoryStore.class));

        // static announcements
        binder.bind(StaticAnnouncementResource.class).in(Scopes.SINGLETON);
        binder.bind(StaticStore.class).to(ReplicatedStaticStore.class).in(Scopes.SINGLETON);
        binder.install(new ReplicatedStoreModule("static", ForStaticStore.class, PersistentStore.class));
        bindConfig(binder).prefixedWith("static").to(PersistentStoreConfig.class);

        // proxy announcements
        PrivateBinder privateBinder = binder.newPrivateBinder();
        privateBinder.bind(HttpServiceBalancer.class).annotatedWith(ForBalancingHttpClient.class).toProvider(ProxyBalancerProvider.class);
        httpClientPrivateBinder(privateBinder, binder).bindAsyncHttpClient("discovery.proxy", ForBalancingHttpClient.class);
        bindConfig(privateBinder).prefixedWith("discovery.proxy").to(BalancingHttpClientConfig.class);
        privateBinder.bind(AsyncHttpClient.class).annotatedWith(ForProxyStore.class).to(BalancingAsyncHttpClient.class).in(Scopes.SINGLETON);
        privateBinder.expose(AsyncHttpClient.class).annotatedWith(ForProxyStore.class);
        newExporter(binder).export(AsyncHttpClient.class).annotatedWith(ForProxyStore.class).withGeneratedName();
        binder.bind(ProxyStore.class).in(Scopes.SINGLETON);
    }

    @Singleton
    @Provides
    public ServiceSelector getServiceInventory(final ServiceInventory inventory, final NodeInfo nodeInfo)
    {
        return new ServiceSelector()
        {
            @Override
            public String getType()
            {
                return "discovery";
            }

            @Override
            public String getPool()
            {
                return nodeInfo.getPool();
            }

            @Override
            public List<ServiceDescriptor> selectAllServices()
            {
                return ImmutableList.copyOf(inventory.getServiceDescriptors(getType()));
            }
        };
    }

    private static class ProxyBalancerProvider implements Provider<HttpServiceBalancer>
    {
        private final DiscoveryConfig discoveryConfig;
        private final ReportCollectionFactory reportCollectionFactory;

        @Inject
        private ProxyBalancerProvider(DiscoveryConfig discoveryConfig, ReportCollectionFactory reportCollectionFactory)
        {
            this.discoveryConfig = checkNotNull(discoveryConfig, "discoveryConfig is null");
            this.reportCollectionFactory = checkNotNull(reportCollectionFactory, "reportCollectionFactory is null");
        }

        @Override
        public HttpServiceBalancer get()
        {
            String name = new ObjectNameBuilder(HttpServiceBalancerStats.class.getPackage().getName())
                    .withProperty("serviceType", "discovery-upstream")
                    .build();
            HttpServiceBalancerImpl proxyBalancer = new HttpServiceBalancerImpl("discovery-upstream",
                    reportCollectionFactory.createReportCollection(HttpServiceBalancerStats.class, name));
            if (discoveryConfig.getProxyUri() != null) {
                proxyBalancer.updateHttpUris(ImmutableSet.of(discoveryConfig.getProxyUri()));
            }
            return proxyBalancer;
        }
    }
}
