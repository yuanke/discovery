package com.proofpoint.discovery.store;

import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.proofpoint.discovery.client.ServiceSelector;
import com.proofpoint.http.client.HttpClient;
import com.proofpoint.http.client.HttpClientModule;
import com.proofpoint.node.NodeInfo;
import org.joda.time.DateTime;
import org.weakref.jmx.MBeanExporter;
import org.weakref.jmx.guice.MBeanModule;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import java.lang.annotation.Annotation;

import static com.google.inject.multibindings.MapBinder.newMapBinder;
import static com.proofpoint.configuration.ConfigurationModule.bindConfig;
import static org.weakref.jmx.guice.MBeanModule.newExporter;

/**
 * Expects a LocalStore to be bound elsewhere.
 * Provides a DistributedStore with the specified annotation.
 */
public class ReplicatedStoreModule
    implements Module
{
    private final String name;
    private final Class<? extends Annotation> annotation;
    private final Class<? extends LocalStore> localStoreClass;

    public ReplicatedStoreModule(String name, Class<? extends Annotation> annotation, Class<? extends LocalStore> localStoreClass)
    {
        this.name = name;
        this.annotation = annotation;
        this.localStoreClass = localStoreClass;
    }

    @Override
    public void configure(Binder binder)
    {
        binder.requireExplicitBindings();
        binder.disableCircularProxies();

        // global
        binder.bind(StoreResource.class).in(Scopes.SINGLETON);
        binder.bind(DateTime.class).toProvider(RealTimeProvider.class);
        binder.bind(SmileMapper.class).in(Scopes.SINGLETON);
        binder.bind(ConflictResolver.class).in(Scopes.SINGLETON);

        // per store
        Key<HttpClient> httpClientKey = Key.get(HttpClient.class, annotation);
        Key<LocalStore> localStoreKey = Key.get(LocalStore.class, annotation);
        Key<StoreConfig> storeConfigKey = Key.get(StoreConfig.class, annotation);

        bindConfig(binder).annotatedWith(annotation).prefixedWith(name).to(StoreConfig.class);
        binder.install(new HttpClientModule(name, annotation));
        binder.bind(DistributedStore.class).annotatedWith(annotation).toProvider(new DistributedStoreProvider(name, localStoreKey, httpClientKey, storeConfigKey)).in(Scopes.SINGLETON);
        binder.bind(Replicator.class).annotatedWith(annotation).toProvider(new ReplicatorProvider(name, localStoreKey, httpClientKey, storeConfigKey)).in(Scopes.SINGLETON);
        binder.bind(LocalStore.class).annotatedWith(annotation).to(localStoreClass).in(Scopes.SINGLETON);

        newExporter(binder).export(HttpRemoteStore.class).annotatedWith(annotation).withGeneratedName();
        newExporter(binder).export(Replicator.class).annotatedWith(annotation).withGeneratedName();

        newMapBinder(binder, String.class, LocalStore.class)
            .addBinding(name)
            .to(localStoreKey);

        newMapBinder(binder, String.class, StoreConfig.class)
                .addBinding(name)
                .to(storeConfigKey);
    }

    private static class ReplicatorProvider
        implements Provider<Replicator>
    {
        private final String name;
        private final Key<? extends LocalStore> localStoreKey;
        private final Key<? extends HttpClient> httpClientKey;
        private final Key<StoreConfig> storeConfigKey;

        private Injector injector;
        private NodeInfo nodeInfo;
        private ServiceSelector serviceSelector;
        private Replicator replicator;

        private ReplicatorProvider(String name, Key<? extends LocalStore> localStoreKey, Key<? extends HttpClient> httpClientKey, Key<StoreConfig> storeConfigKey)
        {
            this.name = name;
            this.localStoreKey = localStoreKey;
            this.httpClientKey = httpClientKey;
            this.storeConfigKey = storeConfigKey;
        }

        @Override
        public synchronized Replicator get()
        {
            if (replicator == null) {
                LocalStore localStore = injector.getInstance(localStoreKey);
                HttpClient httpClient = injector.getInstance(httpClientKey);
                StoreConfig storeConfig = injector.getInstance(storeConfigKey);

                replicator = new Replicator(name, nodeInfo, serviceSelector, httpClient, localStore, storeConfig);
                replicator.start();
            }

            return replicator;
        }

        @PreDestroy
        public synchronized void shutdown()
        {
            if (replicator != null) {
                replicator.shutdown();
            }
        }

        @Inject
        public void setInjector(Injector injector)
        {
            this.injector = injector;
        }

        @Inject
        public void setNodeInfo(NodeInfo nodeInfo)
        {
            this.nodeInfo = nodeInfo;
        }

        @Inject
        public void setServiceSelector(ServiceSelector serviceSelector)
        {
            this.serviceSelector = serviceSelector;
        }
    }

    private static class DistributedStoreProvider
            implements Provider<DistributedStore>
    {
        private final String name;
        private final Key<? extends LocalStore> localStoreKey;
        private final Key<? extends HttpClient> httpClientKey;
        private final Key<StoreConfig> storeConfigKey;

        private Injector injector;
        private NodeInfo nodeInfo;
        private ServiceSelector serviceSelector;
        private Provider<DateTime> dateTimeProvider;
        private DistributedStore store;
        private HttpRemoteStore remoteStore;

        public DistributedStoreProvider(String name, Key<? extends LocalStore> localStoreKey, Key<? extends HttpClient> httpClientKey, Key<StoreConfig> storeConfigKey)
        {
            this.name = name;
            this.localStoreKey = localStoreKey;
            this.httpClientKey = httpClientKey;
            this.storeConfigKey = storeConfigKey;
        }

        @Override
        public synchronized DistributedStore get()
        {
            if (store == null) {
                LocalStore localStore = injector.getInstance(localStoreKey);
                HttpClient httpClient = injector.getInstance(httpClientKey);
                StoreConfig storeConfig = injector.getInstance(storeConfigKey);

                remoteStore = new HttpRemoteStore(name, nodeInfo, serviceSelector, storeConfig, httpClient);
                remoteStore.start();

                store = new DistributedStore(name, localStore, remoteStore, storeConfig, dateTimeProvider);
                store.start();
            }

            return store;
        }

        @PreDestroy
        public synchronized void shutdown()
        {
            if (store != null) {
                store.shutdown();
                remoteStore.shutdown();
            }
        }

        @Inject
        public void setInjector(Injector injector)
        {
            this.injector = injector;
        }

        @Inject
        public void setNodeInfo(NodeInfo nodeInfo)
        {
            this.nodeInfo = nodeInfo;
        }

        @Inject
        public void setServiceSelector(ServiceSelector serviceSelector)
        {
            this.serviceSelector = serviceSelector;
        }

        @Inject
        public void setDateTimeProvider(Provider<DateTime> dateTimeProvider)
        {
            this.dateTimeProvider = dateTimeProvider;
        }
    }

}
