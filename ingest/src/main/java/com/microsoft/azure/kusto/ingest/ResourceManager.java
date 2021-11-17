// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.

package com.microsoft.azure.kusto.ingest;

import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.KustoOperationResult;
import com.microsoft.azure.kusto.data.KustoResultSetTable;
import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import com.microsoft.azure.kusto.ingest.exceptions.IngestionClientException;
import com.microsoft.azure.kusto.ingest.exceptions.IngestionServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ResourceManager implements Closeable {

    public enum ResourceType {
        SECURED_READY_FOR_AGGREGATION_QUEUE("SecuredReadyForAggregationQueue"),
        FAILED_INGESTIONS_QUEUE("FailedIngestionsQueue"),
        SUCCESSFUL_INGESTIONS_QUEUE("SuccessfulIngestionsQueue"),
        TEMP_STORAGE("TempStorage"),
        INGESTIONS_STATUS_TABLE("IngestionsStatusTable");

        private final String resourceTypeName;

        ResourceType(String resourceTypeName) {
            this.resourceTypeName = resourceTypeName;
        }

        String getResourceTypeName() {
            return resourceTypeName;
        }

        public static ResourceType findByResourceTypeName(String resourceTypeName) {
            for (ResourceType resourceType : values()) {
                if (resourceType.resourceTypeName.equalsIgnoreCase(resourceTypeName)) {
                    return resourceType;
                }
            }
            return null;
        }
    }

    private Map<ResourceType, IngestionResource<String>> ingestionResources;
    private String identityToken;
    private final Client client;
    private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final Timer timer;
    private final ReadWriteLock ingestionResourcesLock = new ReentrantReadWriteLock();
    private final ReadWriteLock authTokenLock = new ReentrantReadWriteLock();
    private static final long REFRESH_INGESTION_RESOURCES_PERIOD = 1000L * 60 * 60; // 1 hour
    private static final long REFRESH_INGESTION_RESOURCES_PERIOD_ON_FAILURE = 1000L * 60 * 15; // 15 minutes
    private final Long defaultRefreshTime;
    private final Long refreshTimeOnFailure;
    public static final String SERVICE_TYPE_COLUMN_NAME = "ServiceType";
    private IngestionResource<ContainerWithSas> containers;

    private final HttpClient httpClient;
    public ResourceManager(Client client, long defaultRefreshTime, long refreshTimeOnFailure) {
        this.defaultRefreshTime = defaultRefreshTime;
        this.refreshTimeOnFailure = refreshTimeOnFailure;
        this.client = client;
        timer = new Timer(true);
        httpClient = new NettyAsyncHttpClientBuilder().
                responseTimeout(Duration.ofHours(1)).build();
        init();
    }

    ResourceManager(Client client) {
        this(client, REFRESH_INGESTION_RESOURCES_PERIOD, REFRESH_INGESTION_RESOURCES_PERIOD_ON_FAILURE);
    }

    @Override
    public void close() {
        timer.cancel();
        timer.purge();
    }

    private void init() {
        ingestionResources = Collections.synchronizedMap(new EnumMap<>(ResourceType.class));

        class RefreshIngestionResourcesTask extends TimerTask {
            @Override
            public void run() {
                try {
                    refreshIngestionResources();
                    timer.schedule(new RefreshIngestionResourcesTask(), defaultRefreshTime);
                } catch (Exception e) {
                    log.error("Error in refreshIngestionResources.", e);
                    timer.schedule(new RefreshIngestionResourcesTask(), refreshTimeOnFailure);
                }
            }
        }

        class RefreshIngestionAuthTokenTask extends TimerTask {
            @Override
            public void run() {
                try {
                    refreshIngestionAuthToken();
                    timer.schedule(new RefreshIngestionAuthTokenTask(), defaultRefreshTime);
                } catch (Exception e) {
                    log.error("Error in refreshIngestionAuthToken.", e);
                    timer.schedule(new RefreshIngestionAuthTokenTask(), refreshTimeOnFailure);
                }
            }
        }

        timer.schedule(new RefreshIngestionAuthTokenTask(), 0);
        timer.schedule(new RefreshIngestionResourcesTask(), 0);
    }

    String getIngestionResource(ResourceType resourceType) throws IngestionServiceException, IngestionClientException {
        IngestionResource<String> ingestionResource = ingestionResources.get(resourceType);
        if (ingestionResource == null) {
            refreshIngestionResources();
            try {
                // If the write lock is locked, then the read will wait here.
                // In other words if the refresh is running yet, then wait until it ends
                ingestionResourcesLock.readLock().lock();
                ingestionResource = ingestionResources.get(resourceType);
                if (ingestionResource == null) {
                    throw new IngestionServiceException("Unable to get ingestion resources for this type: " + resourceType.getResourceTypeName());
                }
            } finally {
                ingestionResourcesLock.readLock().unlock();
            }
        }
        return ingestionResource.nextResource();
    }

    ContainerWithSas getTempStorage() throws IngestionClientException, IngestionServiceException {
        if (this.containers == null) {
            refreshIngestionResources();
        }
        ContainerWithSas containerWithSas = this.containers.nextResource();
        if (containerWithSas == null) {
            throw new IngestionServiceException("Unable to get ingestion resources for this type: " + ResourceType.TEMP_STORAGE.name());
        }
        return containerWithSas;
    }

    String getIdentityToken() throws IngestionServiceException, IngestionClientException {
        if (identityToken == null) {
            refreshIngestionAuthToken();
            try {
                authTokenLock.readLock().lock();
                if (identityToken == null) {
                    throw new IngestionServiceException("Unable to get Identity token");
                }
            } finally {
                authTokenLock.readLock().unlock();
            }
        }
        return identityToken;
    }

    private void addIngestionResource(String resourceTypeName, String storageUrl) {
        ResourceType resourceType = ResourceType.findByResourceTypeName(resourceTypeName);
        if (resourceType != null) {
            if (!ingestionResources.containsKey(resourceType)) {
                ingestionResources.put(resourceType, new IngestionResource<>(resourceType));
            }
            ingestionResources.get(resourceType).addResource(storageUrl);
        }
    }

    private void refreshIngestionResources() throws IngestionClientException, IngestionServiceException {
        // Here we use tryLock(): If there is another instance doing the refresh, then just skip it.
        if (ingestionResourcesLock.writeLock().tryLock()) {
            try {
                log.info("Refreshing Ingestion Resources");
                KustoOperationResult ingestionResourcesResults = client.execute(Commands.INGESTION_RESOURCES_SHOW_COMMAND);
                ingestionResources = Collections.synchronizedMap(new EnumMap<>(ResourceType.class));
                if (ingestionResourcesResults != null && ingestionResourcesResults.hasNext()) {
                    KustoResultSetTable table = ingestionResourcesResults.next();
                    // Add the received values to a new IngestionResources map
                    while (table.next()) {
                        String resourceTypeName = table.getString(0);
                        String storageUrl = table.getString(1);
                        addIngestionResource(resourceTypeName, storageUrl);
                    }
                }
                createContainerClients();
            } catch (DataServiceException e) {
                throw new IngestionServiceException(e.getIngestionSource(), "Error refreshing IngestionResources", e);
            } catch (DataClientException e) {
                throw new IngestionClientException(e.getIngestionSource(), "Error refreshing IngestionResources", e);
            } finally {
                ingestionResourcesLock.writeLock().unlock();
            }
        }
    }

    // SDK team suggests to keep the container clients
    private void createContainerClients() {
        IngestionResource<String> ingestionResource = ingestionResources.get(ResourceType.TEMP_STORAGE);
        this.containers = new IngestionResource<>(ResourceType.TEMP_STORAGE);
        ingestionResource.resourcesList.forEach(st->
                this.containers.addResource(new ContainerWithSas(st, httpClient)));
    }

    private void refreshIngestionAuthToken() throws IngestionClientException, IngestionServiceException {
        if (authTokenLock.writeLock().tryLock()) {
            try {
                log.info("Refreshing Ingestion Auth Token");
                KustoOperationResult identityTokenResult = client.execute(Commands.IDENTITY_GET_COMMAND);
                if (identityTokenResult != null
                        && identityTokenResult.hasNext()
                        && !identityTokenResult.getResultTables().isEmpty()) {
                    KustoResultSetTable resultTable = identityTokenResult.next();
                    resultTable.next();
                    identityToken = resultTable.getString(0);
                }
            } catch (DataServiceException e) {
                throw new IngestionServiceException(e.getIngestionSource(), "Error refreshing IngestionAuthToken", e);
            } catch (DataClientException e) {
                throw new IngestionClientException(e.getIngestionSource(), "Error refreshing IngestionAuthToken", e);
            } finally {
                authTokenLock.writeLock().unlock();
            }
        }
    }

    protected String retrieveServiceType() throws IngestionServiceException, IngestionClientException {
        log.info("Getting version to determine endpoint's ServiceType");
        try {
            KustoOperationResult versionResult = client.execute(Commands.VERSION_SHOW_COMMAND);
            if (versionResult != null && versionResult.hasNext() && !versionResult.getResultTables().isEmpty()) {
                KustoResultSetTable resultTable = versionResult.next();
                resultTable.next();
                return resultTable.getString(SERVICE_TYPE_COLUMN_NAME);
            }
        } catch (DataServiceException e) {
            throw new IngestionServiceException(e.getIngestionSource(), "Couldn't retrieve ServiceType because of a service exception executing '.show version'", e);
        } catch (DataClientException e) {
            throw new IngestionClientException(e.getIngestionSource(), "Couldn't retrieve ServiceType because of a client exception executing '.show version'", e);
        }
        throw new IngestionServiceException("Couldn't retrieve ServiceType because '.show version' didn't return any records");
    }

    private static class IngestionResource<T> {
        ResourceType resourceType;
        int roundRobinIdx = 0;
        List<T> resourcesList;

        IngestionResource(ResourceType resourceType) {
            this.resourceType = resourceType;
            resourcesList = new ArrayList<>();
        }

        void addResource(T resource) {
            resourcesList.add(resource);
        }

        T nextResource() {
            roundRobinIdx = (roundRobinIdx + 1) % resourcesList.size();
            return resourcesList.get(roundRobinIdx);
        }
    }
}