/*
 * This Source Code Form is subject to the terms of the "SDCcc non-commercial use license".
 *
 * Copyright (C) 2025 Draegerwerk AG & Co. KGaA
 */

package com.draeger.medical.sdccc.sdcri.testclient;

import static com.draeger.medical.sdccc.sdcri.testclient.TestClientUtil.CONSUMER_NAME_FORMAT;
import static com.draeger.medical.sdccc.sdcri.testclient.TestClientUtil.NETWORK_THREAD_POOL_NAME_FORMAT;
import static com.draeger.medical.sdccc.sdcri.testclient.TestClientUtil.RESOLVER_THREAD_POOL_NAME_FORMAT;
import static com.draeger.medical.sdccc.sdcri.testclient.TestClientUtil.WATCHDOG_SCHEDULED_EXECUTOR_NAME_FORMAT;
import static com.draeger.medical.sdccc.sdcri.testclient.TestClientUtil.WS_DISCOVERY_NAME_FORMAT;
import static com.draeger.medical.sdccc.util.TriggerOnErrorOrWorseLogAppender.APPENDER_NAME;

import com.draeger.medical.sdccc.configuration.TestSuiteConfig;
import com.draeger.medical.sdccc.util.ReconnectException;
import com.draeger.medical.sdccc.util.ReconnectWaitBarrier;
import com.draeger.medical.sdccc.util.TestRunObserver;
import com.draeger.medical.sdccc.util.TriggerOnErrorOrWorseLogAppender;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.somda.sdc.common.util.ExecutorWrapperService;
import org.somda.sdc.dpws.DpwsFramework;
import org.somda.sdc.dpws.client.Client;
import org.somda.sdc.dpws.client.DiscoveredDevice;
import org.somda.sdc.dpws.client.DiscoveryObserver;
import org.somda.sdc.dpws.client.event.ProbedDeviceFoundMessage;
import org.somda.sdc.dpws.service.HostingServiceProxy;
import org.somda.sdc.dpws.soap.exception.TransportException;
import org.somda.sdc.dpws.soap.interception.InterceptorException;
import org.somda.sdc.glue.consumer.ConnectConfiguration;
import org.somda.sdc.glue.consumer.PrerequisitesException;
import org.somda.sdc.glue.consumer.SdcDiscoveryFilterBuilder;
import org.somda.sdc.glue.consumer.SdcRemoteDevice;
import org.somda.sdc.glue.consumer.SdcRemoteDevicesConnector;
import org.somda.sdc.glue.consumer.WatchdogObserver;
import org.somda.sdc.glue.consumer.event.WatchdogMessage;

/**
 * SDCri consumer used to test SDC providers.
 */
@Singleton
public class TestClientImpl extends AbstractIdleService implements TestClient, WatchdogObserver {
    public static final String COULDN_T_RECONNECT = "Could not reconnect the test client.";
    public static final String TIMEOUT_REACHED_WITH_NO_RECONNECT =
            "Timeout for re-establishing a connection was reached.";
    public static final String RECONNECT_EXECUTOR_SERVICE_NOT_RUNNING = "Reconnect executor service not running.";
    public static final String RECONNECT_ALREADY_ENABLED = "Reconnect is already enabled.";
    public static final String UNEXPECTED_DISCONNECT_DETECTED = "Unexpected disconnect detected.";

    private static final Logger LOG = LogManager.getLogger(TestClientImpl.class);
    private static final String COULDN_T_CONNECT_TO_TARGET = "Couldn't connect to target";
    private static final String COULDN_T_DISCONNECT = "Could not disconnect the test client";

    private static final String LOCATION_CONTEXT_SCOPE_STRING_START = "sdc.ctxt.loc:";
    private static final String MATCHING = "matching";
    private static final String NON_MATCHING = "non-matching";
    private static final String RECONNECT_THREAD_NAME = "sdcccReconnectThread";

    private enum InternalReconnectState {
        DISABLED,
        ENABLED,
        RECONNECTING,
        COMPLETED
    }

    private final Pattern locExtractionPattern = Pattern.compile("^sdc.ctxt.loc:/.*\\?"
            + "(?=(.*fac=(?<fac>[^&]*))?)"
            + "(?=(.*bldng=(?<bldng>[^&]*))?)"
            + "(?=(.*poc=(?<poc>[^&]*))?)"
            + "(?=(.*flr=(?<flr>[^&]*))?)"
            + "(?=(.*rm=(?<rm>[^&]*))?)"
            + "(?=(.*bed=(?<bed>[^&]*))?)"
            + ".*$");

    // max time to wait for futures
    private final Duration maxWait;
    private final String targetDeviceFacility;
    private final String targetDeviceBuilding;
    private final String targetDevicePointOfCare;
    private final String targetDeviceFloor;
    private final String targetDeviceRoom;
    private final String targetDeviceBed;
    private final Injector injector;
    private final String targetEpr;
    private final NetworkInterface networkInterface;
    private final Client client;
    private final SdcRemoteDevicesConnector connector;
    private final TestRunObserver testRunObserver;
    // tracks the expected connection state
    private final AtomicBoolean shouldBeConnected;
    private final String eprSearchLogString;
    private final String facilitySearchLogString;
    private final String buildingSearchLogString;
    private final String pointOfCareSearchLogString;
    private final String floorSearchLogString;
    private final String roomSearchLogString;
    private final String bedSearchLogString;
    private DpwsFramework dpwsFramework;
    private SdcRemoteDevice sdcRemoteDevice;
    private HostingServiceProxy hostingServiceProxy;
    private List<String> targetXAddrs;

    private final ExecutorWrapperService<ExecutorService> reconnectExecutor;
    private final Object resourceLock;
    private final long reconnectTries;
    private final long reconnectWait;
    private CompletableFuture<Boolean> reconnectFuture;
    private Future<?> reconnectScheduledTimeoutTask;
    private final ReentrantLock reconnectLock;
    private final Condition reconnectLockCondition;
    // only update this state with the reconnectLock held
    private final AtomicReference<InternalReconnectState> reconnectState;
    private final ReconnectWaitBarrier providerWaitBarrier;
    private final AtomicBoolean isConnected;

    /**
     * Creates an SDCri consumer instance.
     *
     * @param targetDeviceEpr         EPR address to filter for
     * @param targetDeviceFacility    facility to filter for
     * @param targetDeviceBuilding    building to filter for
     * @param targetDevicePointOfCare point of care to filter for
     * @param targetDeviceFloor       floor to filter for
     * @param targetDeviceRoom        room to filter for
     * @param targetDeviceBed         bed to filter for
     * @param adapterAddress          ip of the network interface to bind to
     * @param maxWait                 max waiting time to find and connect to target device
     * @param reconnectTries          number of tries a reconnection is attempted
     * @param reconnectWait           the wait time between reconnection attempts in seconds
     * @param testClientUtil          test client utility
     * @param testRunObserver         observer for invalidating test runs on unexpected errors
     */
    @Inject
    public TestClientImpl(
            @Named(TestSuiteConfig.CONSUMER_DEVICE_EPR) final @Nullable String targetDeviceEpr,
            @Named(TestSuiteConfig.CONSUMER_DEVICE_LOCATION_FACILITY) final @Nullable String targetDeviceFacility,
            @Named(TestSuiteConfig.CONSUMER_DEVICE_LOCATION_BUILDING) final @Nullable String targetDeviceBuilding,
            @Named(TestSuiteConfig.CONSUMER_DEVICE_LOCATION_POINT_OF_CARE)
                    final @Nullable String targetDevicePointOfCare,
            @Named(TestSuiteConfig.CONSUMER_DEVICE_LOCATION_FLOOR) final @Nullable String targetDeviceFloor,
            @Named(TestSuiteConfig.CONSUMER_DEVICE_LOCATION_ROOM) final @Nullable String targetDeviceRoom,
            @Named(TestSuiteConfig.CONSUMER_DEVICE_LOCATION_BED) final @Nullable String targetDeviceBed,
            @Named(TestSuiteConfig.NETWORK_INTERFACE_ADDRESS) final String adapterAddress,
            @Named(TestSuiteConfig.NETWORK_MAX_WAIT) final long maxWait,
            @Named(TestSuiteConfig.NETWORK_RECONNECT_TRIES) final long reconnectTries,
            @Named(TestSuiteConfig.NETWORK_RECONNECT_WAIT) final long reconnectWait,
            final TestClientUtil testClientUtil,
            final TestRunObserver testRunObserver) {
        this.injector = testClientUtil.getInjector();
        this.client = injector.getInstance(Client.class);
        this.connector = injector.getInstance(SdcRemoteDevicesConnector.class);
        this.testRunObserver = testRunObserver;
        this.shouldBeConnected = new AtomicBoolean(false);
        this.resourceLock = new Object();
        this.reconnectTries = reconnectTries;
        this.reconnectWait = reconnectWait;
        this.maxWait = Duration.ofSeconds(maxWait);

        // get interface for address
        try {
            this.networkInterface = NetworkInterface.getByInetAddress(Inet4Address.getByName(adapterAddress));
        } catch (final SocketException | UnknownHostException e) {
            LOG.error("Error while retrieving network adapter for ip {}", adapterAddress, e);
            throw new RuntimeException(e);
        }

        if (this.networkInterface == null) {
            LOG.error(
                    "Error while setting network interface, adapter for address {} seems unavailable", adapterAddress);
            throw new RuntimeException("Error while setting network interface, adapter seems unavailable");
        }

        this.targetEpr = targetDeviceEpr;
        this.targetDeviceFacility = targetDeviceFacility;
        this.targetDeviceBuilding = targetDeviceBuilding;
        this.targetDevicePointOfCare = targetDevicePointOfCare;
        this.targetDeviceFloor = targetDeviceFloor;
        this.targetDeviceRoom = targetDeviceRoom;
        this.targetDeviceBed = targetDeviceBed;

        this.eprSearchLogString = this.targetEpr == null ? "any epr" : "the epr \"" + this.targetEpr + "\"";
        this.facilitySearchLogString = this.targetDeviceFacility == null
                ? "any facility"
                : "the facility \"" + this.targetDeviceFacility + "\"";
        this.buildingSearchLogString = this.targetDeviceBuilding == null
                ? "any building"
                : "the building \"" + this.targetDeviceBuilding + "\"";
        this.pointOfCareSearchLogString = this.targetDevicePointOfCare == null
                ? "any point of care"
                : "the point of care \"" + this.targetDevicePointOfCare + "\"";
        this.floorSearchLogString =
                this.targetDeviceFloor == null ? "any floor" : "the floor \"" + this.targetDeviceFloor + "\"";
        this.roomSearchLogString =
                this.targetDeviceRoom == null ? "any room" : "the room \"" + this.targetDeviceRoom + "\"";
        this.bedSearchLogString = this.targetDeviceBed == null ? "any bed" : "the bed \"" + this.targetDeviceBed + "\"";

        LOG.info(
                "Configured to search for a device with {}, {}, {}, {}, {}, {} and {}",
                this.eprSearchLogString,
                this.facilitySearchLogString,
                this.buildingSearchLogString,
                this.pointOfCareSearchLogString,
                this.floorSearchLogString,
                this.roomSearchLogString,
                this.bedSearchLogString);
        reconnectExecutor = new ExecutorWrapperService<>(
                () -> Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                        .setNameFormat(RECONNECT_THREAD_NAME)
                        .setDaemon(true)
                        .build()),
                "ReconnectExecutor",
                "InstanceIdentifier");
        this.reconnectScheduledTimeoutTask = null;
        this.reconnectLock = new ReentrantLock();
        this.reconnectLockCondition = reconnectLock.newCondition();
        this.reconnectState = new AtomicReference<>(InternalReconnectState.DISABLED);
        this.providerWaitBarrier = new ReconnectWaitBarrier();
        this.isConnected = new AtomicBoolean(false);
    }

    @Override
    protected void startUp() {
        // provide the name of your network adapter
        this.dpwsFramework = injector.getInstance(DpwsFramework.class);
        this.dpwsFramework.setNetworkInterface(networkInterface);
        dpwsFramework.startAsync().awaitRunning();
        client.startAsync().awaitRunning();
        reconnectExecutor.startAsync().awaitRunning();
    }

    @Override
    protected void shutDown() {
        reconnectExecutor.stopAsync().awaitTerminated();
        client.stopAsync().awaitTerminated();
        dpwsFramework.stopAsync().awaitTerminated();
    }

    @Override
    public boolean isClientRunning() {
        return this.isRunning();
    }

    @Override
    public void startService(final Duration waitTime) throws TimeoutException {
        startAsync().awaitRunning(waitTime);
    }

    @Override
    public void stopService(final Duration waitTime) throws TimeoutException {
        stopAsync().awaitTerminated(waitTime);
    }

    private static void logDeviceFound(final String type, final DiscoveredDevice payload) {
        LOG.info(
                "Found {} device with epr {} and location context scope(s) {}",
                type,
                payload.getEprAddress(),
                payload.getScopes().stream()
                        .filter(scope -> scope.startsWith(LOCATION_CONTEXT_SCOPE_STRING_START))
                        .toList()
                        .toString());
    }

    @Override
    public void connect() throws InterceptorException, TransportException, IOException {
        // set expected connection state to true
        shouldBeConnected.set(true);

        LOG.info(
                "Starting discovery for a device with {}, {}, {}, {}, {}, {} and {}",
                this.eprSearchLogString,
                this.facilitySearchLogString,
                this.buildingSearchLogString,
                this.pointOfCareSearchLogString,
                this.floorSearchLogString,
                this.roomSearchLogString,
                this.bedSearchLogString);

        final SettableFuture<DiscoveredDevice> discoveredDeviceSettableFuture = SettableFuture.create();
        final DiscoveryObserver obs = new DiscoveryObserver() {
            @SuppressFBWarnings(
                    value = {"UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS"},
                    justification = "This is not uncallable")
            @Subscribe
            void deviceFound(final ProbedDeviceFoundMessage message) {
                final DiscoveredDevice payload = message.getPayload();

                if (targetDeviceFacility == null
                        && targetDeviceBuilding == null
                        && targetDevicePointOfCare == null
                        && targetDeviceFloor == null
                        && targetDeviceRoom == null
                        && targetDeviceBed == null) {
                    if (targetEpr == null || Objects.equals(payload.getEprAddress(), targetEpr)) {
                        logDeviceFound(MATCHING, payload);
                        discoveredDeviceSettableFuture.set(payload);
                    } else {
                        logDeviceFound(NON_MATCHING, payload);
                    }
                    return;
                }

                for (final String scope : payload.getScopes()) {
                    if (scope.startsWith("sdc.ctxt.loc:")) {
                        final Matcher matcher = locExtractionPattern.matcher(scope);

                        if (matcher.matches()) {
                            if ((targetDeviceFacility == null
                                            || Objects.equals(matcher.group("fac"), targetDeviceFacility))
                                    && (targetDeviceBuilding == null
                                            || Objects.equals(matcher.group("bldng"), targetDeviceBuilding))
                                    && (targetDevicePointOfCare == null
                                            || Objects.equals(matcher.group("poc"), targetDevicePointOfCare))
                                    && (targetDeviceFloor == null
                                            || Objects.equals(matcher.group("flr"), targetDeviceFloor))
                                    && (targetDeviceRoom == null
                                            || Objects.equals(matcher.group("rm"), targetDeviceRoom))
                                    && (targetDeviceBed == null
                                            || Objects.equals(matcher.group("bed"), targetDeviceBed))
                                    && (targetEpr == null || Objects.equals(payload.getEprAddress(), targetEpr))) {

                                logDeviceFound(MATCHING, payload);
                                discoveredDeviceSettableFuture.set(payload);
                                return;
                            }
                        } else {
                            LOG.error("The location context scope {} could not be parsed", scope);
                        }
                    }
                }

                logDeviceFound(NON_MATCHING, payload);
            }
        };
        client.registerDiscoveryObserver(obs);

        // filter discovery for SDC devices only
        final SdcDiscoveryFilterBuilder discoveryFilterBuilder = SdcDiscoveryFilterBuilder.create();
        client.probe(discoveryFilterBuilder.get());

        final DiscoveredDevice discoveredDevice;
        try {
            discoveredDevice = discoveredDeviceSettableFuture.get(maxWait.toSeconds(), TimeUnit.SECONDS);
            targetXAddrs = discoveredDevice.getXAddrs();

            if (discoveredDevice.getEprAddress() == null) {
                LOG.error("No EPR available for the discoveredDevice object, "
                        + "connections to devices without an EPR are not supported/implemented");
                throw new IOException(COULDN_T_CONNECT_TO_TARGET);
            }
        } catch (final InterruptedException | TimeoutException | ExecutionException e) {
            LOG.error(
                    "Couldn't find a device with {}, {}, {}, {}, {}, {} and {}",
                    this.eprSearchLogString,
                    this.facilitySearchLogString,
                    this.buildingSearchLogString,
                    this.pointOfCareSearchLogString,
                    this.floorSearchLogString,
                    this.roomSearchLogString,
                    this.bedSearchLogString,
                    e);
            throw new IOException(COULDN_T_CONNECT_TO_TARGET);
        } finally {
            client.unregisterDiscoveryObserver(obs);
        }

        LOG.info("Connecting to {}", discoveredDevice.getEprAddress());
        final var hostingServiceFuture = client.connect(discoveredDevice.getEprAddress());

        hostingServiceProxy = null;
        try {
            hostingServiceProxy = hostingServiceFuture.get(maxWait.toSeconds(), TimeUnit.SECONDS);
        } catch (final InterruptedException | TimeoutException | ExecutionException e) {
            LOG.error("Couldn't connect to EPR {}", discoveredDevice.getEprAddress(), e);
            throw new IOException(COULDN_T_CONNECT_TO_TARGET);
        }

        LOG.info("Attaching to remote mdib and subscriptions for {}", discoveredDevice.getEprAddress());
        final ListenableFuture<SdcRemoteDevice> remoteDeviceFuture;
        sdcRemoteDevice = null;
        try {
            remoteDeviceFuture = connector.connect(
                    hostingServiceProxy,
                    ConnectConfiguration.create(ConnectConfiguration.ALL_EPISODIC_AND_WAVEFORM_REPORTS));
            sdcRemoteDevice = remoteDeviceFuture.get(maxWait.toSeconds(), TimeUnit.SECONDS);
        } catch (final PrerequisitesException | InterruptedException | ExecutionException | TimeoutException e) {
            LOG.error("Couldn't attach to remote mdib and subscriptions for {}", discoveredDevice.getEprAddress(), e);
            throw new IOException("Couldn't attach to remote mdib and subscriptions");
        }

        sdcRemoteDevice.registerWatchdogObserver(this);
        isConnected.set(true);
    }

    @Override
    public void enableReconnect(final long timeoutInSeconds) throws IllegalStateException {
        reconnectLock.lock();
        try {
            if (reconnectState.get() != InternalReconnectState.DISABLED) {
                testRunObserver.invalidateTestRun(RECONNECT_ALREADY_ENABLED);
                throw new IllegalStateException(RECONNECT_ALREADY_ENABLED);
            }

            final var actualTimeout = getReconnectFeatureTimeout(timeoutInSeconds);
            LOG.debug("Reconnect enabled for {} seconds.", actualTimeout);

            providerWaitBarrier.reset();

            final var ctx =
                    (LoggerContext) LogManager.getContext(this.getClass().getClassLoader(), false);
            final var appender =
                    (TriggerOnErrorOrWorseLogAppender) ctx.getConfiguration().getAppender(APPENDER_NAME);
            appender.setThreadNameWhitelist(buildThreadNameWhiteList());

            this.reconnectFuture = new CompletableFuture<>();

            final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
            try {
                reconnectScheduledTimeoutTask =
                        timeoutScheduler.schedule(this::handleReconnectTimeout, actualTimeout, TimeUnit.SECONDS);
            } finally {
                timeoutScheduler.shutdown();
            }

            reconnectState.set(InternalReconnectState.ENABLED);
        } finally {
            reconnectLock.unlock();
        }
    }

    @Override
    public boolean notifyReconnectProviderReady() {
        return providerWaitBarrier.notifyProviderIsReady();
    }

    @Override
    public boolean getReconnectResult() {
        reconnectLock.lock();
        try {
            while (reconnectState.get() != InternalReconnectState.COMPLETED) {
                reconnectLockCondition.await();
            }
            return reconnectFuture.get();
        } catch (final InterruptedException | ExecutionException e) {
            LOG.debug("Error while waiting for reconnect future", e);
            return false;
        } finally {
            reconnectLock.unlock();
        }
    }

    @Override
    public void disableReconnect() {
        reconnectLock.lock();
        try {
            if (reconnectScheduledTimeoutTask != null) {
                reconnectScheduledTimeoutTask.cancel(true);
                reconnectScheduledTimeoutTask = null;
            }

            completeReconnectFutureExceptionally(
                    new ReconnectException("Reconnect feature was disabled before the process was completed."));

            LOG.info(TriggerOnErrorOrWorseLogAppender.RESET_WHITELIST_MARKER, "Disable reconnect feature.");
            reconnectState.set(InternalReconnectState.DISABLED);
        } finally {
            reconnectLock.unlock();
        }
    }

    @Override
    public synchronized void disconnect() throws TimeoutException {
        shouldBeConnected.set(false);
        disconnect(true);
    }

    private synchronized void disconnect(final Boolean shouldStopClient) throws TimeoutException {
        if (sdcRemoteDevice != null) {
            sdcRemoteDevice.stopAsync().awaitTerminated(maxWait.toSeconds(), TimeUnit.SECONDS);
            sdcRemoteDevice = null;
        }
        hostingServiceProxy = null;
        if (shouldStopClient) {
            client.stopAsync().awaitTerminated(maxWait.toSeconds(), TimeUnit.SECONDS);
        }
        isConnected.set(false);
    }

    @Override
    public Client getClient() {
        return client;
    }

    @Override
    public SdcRemoteDevicesConnector getConnector() {
        return connector;
    }

    @Override
    public SdcRemoteDevice getSdcRemoteDevice() {
        return restrictedGetter(this::getActualSdcRemoteDevice);
    }

    private SdcRemoteDevice getActualSdcRemoteDevice() {
        return sdcRemoteDevice;
    }

    @Override
    public HostingServiceProxy getHostingServiceProxy() {
        return restrictedGetter(this::getActualHostingServiceProxy);
    }

    private HostingServiceProxy getActualHostingServiceProxy() {
        return hostingServiceProxy;
    }

    @Override
    public String getTargetEpr() {
        return targetEpr;
    }

    @Override
    public List<String> getTargetXAddrs() {
        return targetXAddrs;
    }

    @Override
    public Injector getInjector() {
        return injector;
    }

    @Subscribe
    void onConnectionLoss(final WatchdogMessage watchdogMessage) {
        LOG.info("Watchdog detected disconnect from provider.");
        if (shouldBeConnected.get()
                && reconnectState.compareAndSet(InternalReconnectState.ENABLED, InternalReconnectState.RECONNECTING)) {
            LOG.info("The disconnect from provider was expected, trying to reconnect.");
            if (reconnectExecutor.isRunning()) {
                reconnectExecutor.get().submit(() -> {
                    synchronized (resourceLock) {
                        try {
                            disconnect(false);
                        } catch (TimeoutException e) {
                            LOG.error(COULDN_T_DISCONNECT, e);
                        }
                        final var reconnectResult = tryToReconnect();
                        completeReconnectFuture(reconnectResult);
                        resourceLock.notifyAll();
                    }
                });
            } else {
                LOG.debug("ReconnectExecutor is not running.");
                completeReconnectFutureExceptionally(new ReconnectException(RECONNECT_EXECUTOR_SERVICE_NOT_RUNNING));
            }
        } else if (shouldBeConnected.get()) {
            LOG.info("The disconnect from the provider was unexpected.");
            invalidateAfterUnexpectedConnectionLoss(watchdogMessage);
        } else {
            LOG.info("The disconnect from the provider was expected.");
        }
    }

    private void completeReconnectFuture(final boolean result) {
        reconnectLock.lock();
        try {
            if (reconnectState.getAndSet(InternalReconnectState.COMPLETED) != InternalReconnectState.COMPLETED) {
                if (!isConnected.get()) {
                    LOG.debug("No connection established during reconnect process, invalidating the test run.");
                    testRunObserver.invalidateTestRun(COULDN_T_RECONNECT);
                }
                reconnectFuture.complete(result);
            }
            reconnectLockCondition.signalAll();
        } finally {
            reconnectLock.unlock();
        }
    }

    private void completeReconnectFutureExceptionally(final Exception exception) {
        reconnectLock.lock();
        try {
            if (reconnectState.getAndSet(InternalReconnectState.COMPLETED) != InternalReconnectState.COMPLETED) {
                LOG.error("Something went wrong during reconnect.", exception);
                testRunObserver.invalidateTestRun(exception.getMessage());
                reconnectFuture.completeExceptionally(exception);
            }
            reconnectLockCondition.signalAll();
        } finally {
            reconnectLock.unlock();
        }
    }

    private long getReconnectFeatureTimeout(final long proposedTimeout) {
        var actualTimeout = proposedTimeout;
        final var minimalTimeout = reconnectTries * reconnectWait;
        if (proposedTimeout < minimalTimeout) {
            LOG.info(
                    "The provided timeout of {} seconds is too short for the configured ReconnectTries {} and ReconnectWait {}.",
                    proposedTimeout,
                    reconnectTries,
                    reconnectWait);
            LOG.info("Replacing the timeout with minimal applicable value {}.", minimalTimeout);
            actualTimeout = minimalTimeout;
        }
        return actualTimeout;
    }

    private void handleReconnectTimeout() {
        reconnectLock.lock();
        try {
            if (reconnectState.get() != InternalReconnectState.COMPLETED) {
                if (isConnected.get()) {
                    completeReconnectFuture(false);
                } else {
                    // timeout reached but no reconnection happened
                    completeReconnectFutureExceptionally(new ReconnectException(TIMEOUT_REACHED_WITH_NO_RECONNECT));
                }
            }
        } finally {
            reconnectLock.unlock();
        }
    }

    private void invalidateAfterUnexpectedConnectionLoss(final WatchdogMessage watchdogMessage) {
        testRunObserver.invalidateTestRun(String.format(
                "%s. Lost connection to device %s. Reason: %s",
                UNEXPECTED_DISCONNECT_DETECTED,
                watchdogMessage.getPayload(),
                watchdogMessage.getReason().getMessage()));
        try {
            disconnect(true);
        } catch (TimeoutException e) {
            LOG.error(COULDN_T_DISCONNECT, e);
        }
    }

    private <T> T restrictedGetter(final RestrictedGetter<T> getter) {
        synchronized (resourceLock) {
            while (reconnectState.get() == InternalReconnectState.RECONNECTING) {
                LOG.debug("Attempted to access a connection-dependent object while the connection is interrupted, "
                        + "wait for connection to be re-established.");
                try {
                    resourceLock.wait();
                } catch (InterruptedException e) {
                    LOG.error("Waiting for lock interrupted.", e);
                }
            }
            return getter.invoke();
        }
    }

    /**
     * Attempts to reconnect to the provider, when there are still remaining attempts and the connection is not
     * established and the enabled reconnect feature is not timed out.
     *
     * @return true if the reconnection was successful, false otherwise
     */
    private boolean tryToReconnect() {
        for (int count = 1;
                count <= reconnectTries && reconnectState.get() == InternalReconnectState.RECONNECTING;
                count++) {
            try {
                if (count == 1) {
                    LOG.info("Wait for at most {} seconds before attempting to reconnect.", reconnectWait);
                    providerWaitBarrier.waitForProvider(reconnectWait);
                } else {
                    LOG.info("Wait for {} seconds before attempting to reconnect.", reconnectWait);
                    TimeUnit.SECONDS.sleep(reconnectWait);
                }
                LOG.info("Trying to reconnect, attempt {} of {}.", count, reconnectTries);
                connect();
                LOG.info("Successfully reconnected.");
                return true;
            } catch (InterceptorException | TransportException | IOException e) {
                LOG.info("{}. reconnection attempt failed.", count);
            } catch (InterruptedException | BrokenBarrierException ex) {
                LOG.error("Reconnect attempt {} interrupted.", count, ex);
            }
        }
        return false;
    }

    private List<String> buildThreadNameWhiteList() {
        return List.of(
                RECONNECT_THREAD_NAME,
                convertToRegex(NETWORK_THREAD_POOL_NAME_FORMAT),
                convertToRegex(WS_DISCOVERY_NAME_FORMAT),
                convertToRegex(RESOLVER_THREAD_POOL_NAME_FORMAT),
                convertToRegex(CONSUMER_NAME_FORMAT),
                convertToRegex(WATCHDOG_SCHEDULED_EXECUTOR_NAME_FORMAT));
    }

    private String convertToRegex(final String pattern) {
        return pattern.replaceAll("%d", "[\\\\d]+");
    }

    private interface RestrictedGetter<T> {
        T invoke();
    }
}
