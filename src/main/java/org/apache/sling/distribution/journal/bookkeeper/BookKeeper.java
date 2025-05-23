/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.distribution.journal.bookkeeper;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.singletonMap;
import static org.apache.sling.api.resource.ResourceResolverFactory.SUBSERVICE;
import static org.apache.sling.distribution.event.DistributionEventProperties.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.metrics.Timer;
import org.apache.sling.distribution.ImportPostProcessException;
import org.apache.sling.distribution.ImportPostProcessor;
import org.apache.sling.distribution.ImportPreProcessException;
import org.apache.sling.distribution.ImportPreProcessor;
import org.apache.sling.distribution.InvalidationProcessException;
import org.apache.sling.distribution.InvalidationProcessor;
import org.apache.sling.distribution.common.DistributionException;
import org.apache.sling.distribution.journal.impl.event.DistributionFailureEvent;
import org.apache.sling.distribution.journal.messages.LogMessage;
import org.apache.sling.distribution.journal.messages.PackageMessage;
import org.apache.sling.distribution.journal.messages.PackageStatusMessage;
import org.apache.sling.distribution.journal.messages.PackageStatusMessage.Status;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps track of offset and processed status and manages 
 * coordinates the import/retry handling.
 * 
 * The offset store is identified by the agentName only.
 *
 * With non clustered publish instances deployment, each
 * instance stores the offset in its own node store, thus
 * avoiding mix ups. Moreover, when cloning an instance
 * from a node store, the cloned instance will implicitly
 * recover the offsets and start from the last processed
 * offset.
 *
 * With clustered publish instances deployment, only one
 * Subscriber agent must run on the cluster in order to
 * avoid mix ups.
 *
 * The clustered and non clustered publish instances use
 * cases can be supported by only running the Subscriber
 * agent on the leader instance.
 */
public class BookKeeper {
    public static final String STORE_TYPE_STATUS = "statuses";
    public static final String KEY_OFFSET = "offset";
    public static final int COMMIT_AFTER_NUM_SKIPPED = 10;
    private static final String SUBSERVICE_IMPORTER = "importer";
    private static final String SUBSERVICE_BOOKKEEPER = "bookkeeper";
    private static final int RETRY_SEND_DELAY = 1000;
    public static final int NUM_ERRORS_BLOCKING = 4;
    

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final ResourceResolverFactory resolverFactory;
    private final SubscriberMetrics subscriberMetrics;
    private final PackageHandler packageHandler;
    private final EventAdmin eventAdmin;
    private final Consumer<PackageStatusMessage> sender;
    private final Consumer<LogMessage> logSender;
    private final BookKeeperConfig config;
    private final boolean errorQueueEnabled;

    private final PackageRetries packageRetries = new PackageRetries();
    private final LocalStore statusStore;
    private final LocalStore processedOffsets;
    private final LocalStore clearStore;
    private final ImportPreProcessor importPreProcessor;
    private final ImportPostProcessor importPostProcessor;
    private final InvalidationProcessor invalidationProcessor;
    private int skippedCounter = 0;

    public BookKeeper(ResourceResolverFactory resolverFactory, SubscriberMetrics subscriberMetrics,
        PackageHandler packageHandler, EventAdmin eventAdmin, Consumer<PackageStatusMessage> sender, Consumer<LogMessage> logSender,
        BookKeeperConfig config, 
        ImportPreProcessor importPreProcessor, 
        ImportPostProcessor importPostProcessor, 
        InvalidationProcessor invalidationProcessor) {
    	
        this.packageHandler = packageHandler;
        this.eventAdmin = eventAdmin;
        this.sender = sender;
        this.logSender = logSender;
        this.config = config;
        
        subscriberMetrics.currentRetries(packageRetries::getSum);
        this.resolverFactory = resolverFactory;
        this.subscriberMetrics = subscriberMetrics;
        // Error queues are enabled when the number
        // of retry attempts is limited ; disabled otherwise
        this.errorQueueEnabled = (config.getMaxRetries() >= 0);
        this.statusStore = new LocalStore(resolverFactory, STORE_TYPE_STATUS, config.getSubAgentName());
        this.processedOffsets = new LocalStore(resolverFactory, config.getPackageNodeName(), config.getSubAgentName());
        this.clearStore = new LocalStore(resolverFactory, config.getCommandNodeName(), config.getSubAgentName());
        this.importPreProcessor = importPreProcessor;
        this.importPostProcessor = importPostProcessor;
        this.invalidationProcessor = invalidationProcessor;
        log.info("Started bookkeeper {}.", config);
    }
    
    /**
     * We aim at processing the packages exactly once. Processing the packages
     * exactly once is possible with the following conditions
     *
     * I. The package importer is configured to disable auto-committing changes.
     *
     * II. A single commit aggregates three content updates
     *
     * C1. install the package 
     * C2. store the processing status 
     * C3. store the offset processed
     *
     * Some package importers require auto-saving or issue partial commits before
     * failing. For those packages importers, we aim at processing packages at least
     * once, thanks to the order in which the content updates are applied.
     */
    public void importPackage(PackageMessage pkgMsg, long offset, Date createdTime, Date importStartTime) throws DistributionException {
        log.debug("Importing distribution package {} at offset={}", pkgMsg, offset);
        try (Timer.Context context = subscriberMetrics.getImportedPackageDuration().time();
                ResourceResolver importerResolver = getServiceResolver(SUBSERVICE_IMPORTER)) {
            // Execute the pre-processor
            preProcess(pkgMsg);
            subscriberMetrics.setCurrentImport(new CurrentImportInfo(pkgMsg, offset, importStartTime.getTime()));
            packageHandler.apply(importerResolver, pkgMsg);
            if (config.isEditable()) {
                storeStatus(importerResolver, new PackageStatus(Status.IMPORTED, offset, pkgMsg.getPubAgentName()));
            }
            storeOffset(importerResolver, offset);
            importerResolver.commit();
            subscriberMetrics.getImportedPackageSize().update(pkgMsg.getPkgLength());
            subscriberMetrics.getPackageDistributedDuration().update((currentTimeMillis() - createdTime.getTime()), TimeUnit.MILLISECONDS);
            
            // Execute the post-processor
            postProcess(pkgMsg);
            
            clearPackageRetriesOnSuccess(pkgMsg);

            Event event = new AppliedEvent(pkgMsg, config.getSubAgentName()).toEvent();
            eventAdmin.postEvent(event);
            Duration currentImporturation = Duration.ofMillis(System.currentTimeMillis() - importStartTime.getTime());
            log.info("Imported distribution package {} at offset={} took importDurationMs={} created={}", pkgMsg, offset, currentImporturation.toMillis(), createdTime);
            subscriberMetrics.getPackageStatusCounter(pkgMsg.getPubAgentName(), Status.IMPORTED).increment();
        } catch (DistributionException | LoginException | IOException | RuntimeException | ImportPreProcessException |ImportPostProcessException e) {
            failure(pkgMsg, offset, createdTime, e);
        } finally {
            subscriberMetrics.clearCurrentImport();
        }
    }

    public void invalidateCache(PackageMessage pkgMsg, long offset, Date createdTime, Date importStartTime) throws DistributionException {
        log.debug("Invalidating the cache for the package {} at offset={}", pkgMsg, offset);
        try (ResourceResolver resolver = getServiceResolver(SUBSERVICE_BOOKKEEPER)) {
            Map<String, Object> props = this.buildProcessorPropertiesFromMessage(pkgMsg);

            long invalidationStartTime = currentTimeMillis();
            subscriberMetrics.getInvalidationProcessRequest().increment();

            invalidationProcessor.process(props);

            if (config.isEditable()) {
                storeStatus(resolver, new PackageStatus(Status.IMPORTED, offset, pkgMsg.getPubAgentName()));
            }

            storeOffset(resolver, offset);
            resolver.commit();

            clearPackageRetriesOnSuccess(pkgMsg);

            Event event = new AppliedEvent(pkgMsg, config.getSubAgentName()).toEvent();
            eventAdmin.postEvent(event);
            long currentImporturationMs = System.currentTimeMillis() - importStartTime.getTime();
            log.info("Invalidated the cache for the package {} at offset={}. This took importDurationMs={}", pkgMsg, offset, currentImporturationMs);

            subscriberMetrics.getPackageStatusCounter(pkgMsg.getPubAgentName(), Status.IMPORTED).increment();
            subscriberMetrics.getInvalidationProcessDuration().update((currentTimeMillis() - invalidationStartTime), TimeUnit.MILLISECONDS);
            subscriberMetrics.getInvalidationProcessSuccess().increment();
        } catch (LoginException | PersistenceException | InvalidationProcessException | RuntimeException e) {
            failure(pkgMsg, offset, createdTime, e);
        }
    }

    /**
     * Initiates pre-processing for a given package message.
     * It constructs properties from the message, logs the event, processes the message,
     * and updates relevant metrics. Throws {@link ImportPreProcessException} on failure.
     *
     * @param packageMessage the message to pre-process
     * @throws ImportPreProcessException if pre-processing fails
     */
    private void preProcess(PackageMessage packageMessage) throws ImportPreProcessException {
        log.debug("Executing import pre processor for package [{}]", packageMessage);
        Map<String, Object> processorProperties = this.buildProcessorPropertiesFromMessage(packageMessage);

        long preProcessStartTime = currentTimeMillis();
        subscriberMetrics.getImportPreProcessRequest().increment();

        this.importPreProcessor.process(processorProperties);

        log.debug("Executed import pre processor for package [{}]", packageMessage.getPkgId());

        subscriberMetrics.getImportPreProcessDuration().update(
                (currentTimeMillis() - preProcessStartTime), TimeUnit.MILLISECONDS);
        subscriberMetrics.getImportPreProcessSuccess().increment();
    }

    private void postProcess(PackageMessage pkgMsg) throws ImportPostProcessException {
        log.debug("Executing import post processor for package [{}]", pkgMsg);

        Map<String, Object> props = this.buildProcessorPropertiesFromMessage(pkgMsg);

        long postProcessStartTime = currentTimeMillis();
        subscriberMetrics.getImportPostProcessRequest().increment();
        importPostProcessor.process(props);

        log.debug("Executed import post processor for package [{}]", pkgMsg.getPkgId());

        subscriberMetrics.getImportPostProcessDuration().update((currentTimeMillis() - postProcessStartTime), TimeUnit.MILLISECONDS);
        subscriberMetrics.getImportPostProcessSuccess().increment();
    }
    
    /**
     * Should be called on a exception while importing a package.
     * 
     * When we use an error queue and the max retries is reached the package is removed.
     * In all other cases a DistributionException is thrown that signals that we should retry the
     * package.
     *
     * @throws DistributionException if the package should be retried
     */
    private void failure(PackageMessage pkgMsg, long offset, Date createdTime, Exception e) throws DistributionException {
        subscriberMetrics.getFailedPackageImports().mark();

        String pubAgentName = pkgMsg.getPubAgentName();
        int retries = packageRetries.get(pubAgentName);
        boolean giveUp = errorQueueEnabled && retries >= config.getMaxRetries();
        String retriesSt = errorQueueEnabled ? Integer.toString(config.getMaxRetries()) : "infinite";
        String action = giveUp ? "skip the package" : "retry later";
        String msg = format("Failed attempt (%s/%s) to import the distribution package %s at offset=%d because of '%s', the importer will %s", retries, retriesSt, pkgMsg.toString(false), offset, e.getMessage(), action);
        try {
            LogMessage logMessage = getLogMessage(pubAgentName, msg, e);
            logSender.accept(logMessage);
        } catch (Exception e2) {
            log.warn("Error sending log message", e2);
        }
        Event event = DistributionFailureEvent.build(pkgMsg, offset, createdTime, retries, config.getMaxRetries(), giveUp, e);
        eventAdmin.postEvent(event);
        if (giveUp) {
            log.warn(msg, e);
            removeFailedPackage(pkgMsg, offset);
            subscriberMetrics.getPermanentImportErrors().increment();
        } else {
            if (retries == NUM_ERRORS_BLOCKING) { // Only count after a few retries to allow transient errors to recover
                subscriberMetrics.getBlockingImportErrors().increment();
            }
            packageRetries.increase(pubAgentName);
            throw new DistributionException(msg, e);
        }
    }

    private LogMessage getLogMessage(String pubAgentName, String msg, Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return LogMessage.builder()
                .pubAgentName(pubAgentName)
                .subSlingId(config.getSubSlingId())
                .subAgentName(config.getSubAgentName())
                .message(msg)
                .stacktrace(sw.getBuffer().toString())
                .build();
    }

    public void removePackage(PackageMessage pkgMsg, long offset) throws LoginException, PersistenceException {
        log.info("Removing distribution package {} of type {} at offset {}", 
                pkgMsg.getPkgId(), pkgMsg.getReqType(), offset);
        Timer.Context context = subscriberMetrics.getRemovedPackageDuration().time();
        try (ResourceResolver resolver = getServiceResolver(SUBSERVICE_BOOKKEEPER)) {
            if (config.isEditable()) {
                storeStatus(resolver, new PackageStatus(Status.REMOVED, offset, pkgMsg.getPubAgentName()));
            }
            storeOffset(resolver, offset);
            resolver.commit();
        }
        packageRetries.clear(pkgMsg.getPubAgentName());
        context.stop();
        subscriberMetrics.getPackageStatusCounter(pkgMsg.getPubAgentName(), Status.REMOVED).increment();
    }
    
    public void skipPackage(long offset) throws LoginException, PersistenceException {
        log.info("Skipping package at offset={}", offset);
        if (shouldCommitSkipped()) {
            try (ResourceResolver resolver = getServiceResolver(SUBSERVICE_BOOKKEEPER)) {
                storeOffset(resolver, offset);
                resolver.commit();
            }
        }
    }

    public synchronized boolean shouldCommitSkipped() {
        skippedCounter ++;
        if (skippedCounter > COMMIT_AFTER_NUM_SKIPPED) {
            skippedCounter = 1;
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return {@code true} if the status has been sent ;
     *         {@code false} otherwise.
     */
    public boolean sendStoredStatus(int retry) {
        PackageStatus status = new PackageStatus(statusStore.load());
        return status.sent || sendStoredStatus(status, retry);
    }

    private boolean sendStoredStatus(PackageStatus status, int retry) {
        try {
            sendStatusMessage(status);
            markStatusSent();
            return true;
        } catch (Exception e) {
            log.warn("Cannot send status (retry {})", retry, e);
            retryDelay();
            return false;
        }
    }
    
    private void sendStatusMessage(PackageStatus status) {
        PackageStatusMessage pkgStatMsg = PackageStatusMessage.builder()
                .subSlingId(config.getSubSlingId())
                .subAgentName(config.getSubAgentName())
                .pubAgentName(status.pubAgentName)
                .offset(status.offset)
                .status(status.status)
                .build();
        sender.accept(pkgStatMsg);
        log.info("Sent status message {}",  pkgStatMsg);
    }

    public void markStatusSent() {
        try (ResourceResolver resolver = getServiceResolver(SUBSERVICE_BOOKKEEPER)) {
            statusStore.store(resolver, "sent", true);
            resolver.commit();
        } catch (Exception e) {
            log.warn("Failed to mark status as sent", e);
        }
    }
    
    public long loadOffset() {
        return processedOffsets.load(KEY_OFFSET, -1L);
    }

    public int getRetries(String pubAgentName) {
        return packageRetries.get(pubAgentName);
    }

    public PackageRetries getPackageRetries() {
        return packageRetries;
    }

    /**
     * This method clears the packageRetries storage for a given package and
     * emits metrics on the success of the retry.
     * @param pkgMsg: package distributed
     */
    public void clearPackageRetriesOnSuccess(PackageMessage pkgMsg) {
        String pubAgentName = pkgMsg.getPubAgentName();
        if (packageRetries.get(pubAgentName) > 0) {
            subscriberMetrics.getTransientImportErrors().increment();
        }

        packageRetries.clear(pubAgentName);
    }

    public void handleInitialOffset(long offset) {
        try (ResourceResolver resolver = getServiceResolver(SUBSERVICE_BOOKKEEPER)) {
            long currentOffset = loadOffset();
            if (currentOffset == -1) {
                log.info("Storing initial offset. packageNodeName={}, subagentName={}, offset={}", 
                        config.getPackageNodeName(), config.getSubAgentName(), offset);
                storeOffset(resolver, offset);
                resolver.commit();
            }
        } catch (Exception e) {
            log.warn("Error storing initial offset={}", offset, e);
        }
    }

    private void removeFailedPackage(PackageMessage pkgMsg, long offset) throws DistributionException {
        log.info("Removing failed distribution package {} at offset={}", pkgMsg, offset);
        Timer.Context context = subscriberMetrics.getRemovedFailedPackageDuration().time();
        try (ResourceResolver resolver = getServiceResolver(SUBSERVICE_BOOKKEEPER)) {
            storeStatus(resolver, new PackageStatus(Status.REMOVED_FAILED, offset, pkgMsg.getPubAgentName()));
            storeOffset(resolver, offset);
            resolver.commit();
        } catch (Exception e) {
            throw new DistributionException("Error removing failed package", e);
        }
        context.stop();
        subscriberMetrics.getPackageStatusCounter(pkgMsg.getPubAgentName(), Status.REMOVED_FAILED).increment();
    }

    private void storeStatus(ResourceResolver resolver, PackageStatus packageStatus) throws PersistenceException {
        Map<String, Object> statusMap = packageStatus.asMap();
        statusStore.store(resolver, statusMap);
        log.info("Stored status {}", statusMap);
    }

    private void storeOffset(ResourceResolver resolver, long offset) throws PersistenceException {
        processedOffsets.store(resolver, KEY_OFFSET, offset);
    }

    private ResourceResolver getServiceResolver(String subService) throws LoginException {
        return resolverFactory.getServiceResourceResolver(singletonMap(SUBSERVICE, subService));
    }

    /**
     * Constructs processor properties from a {@link PackageMessage}.
     * Extracts distribution type, paths, and package ID from the message
     * to create a map used by various processors.
     *
     * @param packageMessage the message to extract properties from
     * @return a map of key properties for processor use
     */
    private Map<String, Object> buildProcessorPropertiesFromMessage(PackageMessage packageMessage) {
        Map<String, Object> processorProperties = new HashMap<>();
        processorProperties.put(DISTRIBUTION_TYPE, packageMessage.getReqType().name());
        processorProperties.put(DISTRIBUTION_PATHS, packageMessage.getPaths());
        processorProperties.put(DISTRIBUTION_PACKAGE_ID, packageMessage.getPkgId());
        processorProperties.put(DISTRIBUTION_COMPONENT_NAME, packageMessage.getPubAgentName());

        return processorProperties;
    }

    static void retryDelay() {
        try {
            Thread.sleep(RETRY_SEND_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class PackageStatus {
        public final Status status;
        final Long offset;
        final String pubAgentName;
        final Boolean sent;

        PackageStatus(Status status, long offset, String pubAgentName) {
            this.status = status;
            this.offset = offset;
            this.pubAgentName = pubAgentName;
            this.sent = false;
        }
        
        public PackageStatus(ValueMap statusMap) {
            Integer statusNum = statusMap.get("statusNumber", Integer.class);
            this.status = statusNum !=null ? Status.fromNumber(statusNum) : null;
            this.offset = statusMap.get(KEY_OFFSET, Long.class);
            this.pubAgentName = statusMap.get("pubAgentName", String.class);
            this.sent = statusMap.get("sent", true);
        }

        Map<String, Object> asMap() {
            Map<String, Object> s = new HashMap<>();
            s.put("pubAgentName", pubAgentName);
            s.put("statusNumber", status.getNumber());
            s.put(KEY_OFFSET, offset);
            s.put("sent", sent);
            return s;
        }
    }
    public Long getClearOffset() {
		return clearStore.load(KEY_OFFSET, Long.class);
	}

	public void storeClearOffset(Long offset) {
		try {
			clearStore.store(KEY_OFFSET, Objects.requireNonNull(offset));
		} catch (PersistenceException e) {
			log.warn("Unable to write clear offset={} for subAgentName={}", offset, config.getSubAgentName());
		}
	}

}
