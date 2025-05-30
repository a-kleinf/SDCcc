/*
 * This Source Code Form is subject to the terms of the "SDCcc non-commercial use license".
 *
 * Copyright (C) 2025 Draegerwerk AG & Co. KGaA
 */

package com.draeger.medical.sdccc.tests.glue.invariant;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.draeger.medical.sdccc.configuration.EnabledTestConfig;
import com.draeger.medical.sdccc.messages.MessageStorage;
import com.draeger.medical.sdccc.sdcri.testclient.TestClient;
import com.draeger.medical.sdccc.tests.InjectorTestBase;
import com.draeger.medical.sdccc.tests.annotations.TestDescription;
import com.draeger.medical.sdccc.tests.annotations.TestIdentifier;
import com.draeger.medical.sdccc.tests.util.NoTestData;
import com.draeger.medical.sdccc.tests.util.guice.MdibHistorianFactory;
import com.draeger.medical.sdccc.util.TestRunObserver;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.somda.sdc.biceps.common.MdibEntity;
import org.somda.sdc.biceps.model.participant.AbstractComplexDeviceComponentDescriptor;
import org.somda.sdc.biceps.model.participant.AbstractDescriptor;
import org.somda.sdc.biceps.model.participant.AbstractMetricDescriptor;
import org.somda.sdc.biceps.model.participant.AbstractOperationDescriptor;
import org.somda.sdc.biceps.model.participant.AlertConditionDescriptor;
import org.somda.sdc.biceps.model.participant.ChannelDescriptor;

/**
 * Glue Sdc Participant Model Binding tests (ch. 7).
 */
public class InvariantSdcParticipantModelBindingTest extends InjectorTestBase {
    private MessageStorage messageStorage;
    private MdibHistorianFactory mdibHistorianFactory;

    @BeforeEach
    void setup() {
        this.messageStorage = getInjector().getInstance(MessageStorage.class);
        final var riInjector = getInjector().getInstance(TestClient.class).getInjector();
        this.mdibHistorianFactory = riInjector.getInstance(MdibHistorianFactory.class);
    }

    @Test
    @TestIdentifier(EnabledTestConfig.GLUE_R0080)
    @TestDescription("Starting from the initially retrieved mdib, applies every episodic report to the mdib and"
            + " verifies that the Type attribute for every AbstractComplexDeviceComponentDescriptor,"
            + " ChannelDescriptor, AbstractOperationDescriptor, AlertConditionDescriptor and AbstractMetricDescriptor"
            + " is present.")
    void testRequirementR0080() throws NoTestData, IOException {
        final var mdibHistorian = mdibHistorianFactory.createMdibHistorian(
                messageStorage, getInjector().getInstance(TestRunObserver.class));

        final var acceptableSequenceSeen = new AtomicBoolean(false);

        mdibHistorian.processAllRemoteMdibAccess(first -> {
            acceptableSequenceSeen.compareAndSet(
                    false,
                    checkForTypeAttribute(
                            first.findEntitiesByType(AbstractComplexDeviceComponentDescriptor.class),
                            AbstractComplexDeviceComponentDescriptor.class));
            acceptableSequenceSeen.compareAndSet(
                    false,
                    checkForTypeAttribute(first.findEntitiesByType(ChannelDescriptor.class), ChannelDescriptor.class));
            acceptableSequenceSeen.compareAndSet(
                    false,
                    checkForTypeAttribute(
                            first.findEntitiesByType(AbstractOperationDescriptor.class),
                            AbstractOperationDescriptor.class));
            acceptableSequenceSeen.compareAndSet(
                    false,
                    checkForTypeAttribute(
                            first.findEntitiesByType(AlertConditionDescriptor.class), AlertConditionDescriptor.class));
            acceptableSequenceSeen.compareAndSet(
                    false,
                    checkForTypeAttribute(
                            first.findEntitiesByType(AbstractMetricDescriptor.class), AbstractMetricDescriptor.class));
        });

        assertTestData(acceptableSequenceSeen.get(), "No suitable descriptors seen, test failed.");
    }

    private boolean checkForTypeAttribute(
            final Collection<MdibEntity> entities, final Class<? extends AbstractDescriptor> descClass) {
        for (var entity : entities) {
            final var descriptor = entity.getDescriptor(descClass);
            assertNotNull(
                    descriptor.orElseThrow().getType(),
                    String.format(
                            "No Type attribute provided for %s",
                            descriptor.orElseThrow().getHandle()));
        }
        return !entities.isEmpty();
    }
}
