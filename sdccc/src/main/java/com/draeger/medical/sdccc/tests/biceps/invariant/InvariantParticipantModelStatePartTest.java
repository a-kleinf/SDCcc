/*
 * This Source Code Form is subject to the terms of the "SDCcc non-commercial use license".
 *
 * Copyright (C) 2025 Draegerwerk AG & Co. KGaA
 */

package com.draeger.medical.sdccc.tests.biceps.invariant;

import static com.draeger.medical.sdccc.configuration.TestParameterConfig.BICEPS_547_TIME_INTERVAL;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.draeger.medical.sdccc.configuration.EnabledTestConfig;
import com.draeger.medical.sdccc.manipulation.precondition.impl.ManipulationPreconditions;
import com.draeger.medical.sdccc.messages.MessageStorage;
import com.draeger.medical.sdccc.messages.mapping.ManipulationData;
import com.draeger.medical.sdccc.messages.mapping.ManipulationParameter;
import com.draeger.medical.sdccc.sdcri.testclient.TestClient;
import com.draeger.medical.sdccc.tests.InjectorTestBase;
import com.draeger.medical.sdccc.tests.annotations.RequirePrecondition;
import com.draeger.medical.sdccc.tests.annotations.TestDescription;
import com.draeger.medical.sdccc.tests.annotations.TestIdentifier;
import com.draeger.medical.sdccc.tests.util.ImpliedValueUtil;
import com.draeger.medical.sdccc.tests.util.ManipulationParameterUtil;
import com.draeger.medical.sdccc.tests.util.NoTestData;
import com.draeger.medical.sdccc.tests.util.guice.MdibHistorianFactory;
import com.draeger.medical.sdccc.util.Constants;
import com.draeger.medical.sdccc.util.TestRunObserver;
import com.draeger.medical.t2iapi.ResponseTypes;
import com.google.inject.Key;
import com.google.inject.name.Names;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.somda.sdc.biceps.common.storage.PreprocessingException;
import org.somda.sdc.biceps.consumer.access.RemoteMdibAccess;
import org.somda.sdc.biceps.model.participant.AbstractMetricState;
import org.somda.sdc.biceps.model.participant.ComponentActivation;
import org.somda.sdc.biceps.model.participant.MetricCategory;
import org.somda.sdc.glue.consumer.report.ReportProcessingException;

/**
 * BICEPS participant model state part tests (ch. 5.4).
 */
public class InvariantParticipantModelStatePartTest extends InjectorTestBase {
    public static final String NO_SET_METRIC_STATUS_MANIPULATION =
            "No setMetricStatus manipulation for metrics with category %s performed, test failed.";
    public static final String NO_SUCCESSFUL_MANIPULATION =
            "No successful setMetricStatus manipulation seen, test failed.";
    public static final String NO_REPORT_IN_TIME = "No reports until timestamp %s found, test failed.";
    public static final String NO_METRIC_WITH_EXPECTED_HANDLE = "No metric with handle %s found, test failed.";
    public static final String WRONG_ACTIVATION_STATE =
            "The manipulated activation state for metric %s should be %s but is %s";
    private long buffer;
    private MessageStorage messageStorage;
    private MdibHistorianFactory mdibHistorianFactory;

    @BeforeEach
    void setUp() {
        this.messageStorage = getInjector().getInstance(MessageStorage.class);
        final var riInjector = getInjector().getInstance(TestClient.class).getInjector();
        final var timeBufferInSeconds =
                getInjector().getInstance(Key.get(long.class, Names.named(BICEPS_547_TIME_INTERVAL)));
        buffer = TimeUnit.NANOSECONDS.convert(timeBufferInSeconds, TimeUnit.SECONDS);
        this.mdibHistorianFactory = riInjector.getInstance(MdibHistorianFactory.class);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Msrmt and the measurement for the METRIC is being"
            + " performed, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = On.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_0_0)
    @TestDescription("For each metric with the category MSRMT, the device is manipulated to perform measurements and"
            + " then it is verified that the ActivationState of the metric is set to On.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationMSRMTActivationStateON.class
            })
    void testRequirement54700() throws NoTestData {
        final var metricCategory = MetricCategory.MSRMT;
        final var activationState = ComponentActivation.ON;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName(
            "If pm:AbstractMetricDescriptor/@MetricCategory = Msrmt and the measurement for the METRIC  is"
                    + " currently initializing, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = NotRdy.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_1)
    @TestDescription("For each metric with the category MSRMT, the device is manipulated to initialize measurements and"
            + " then it is verified that the ActivationState of the metric is set to NotRdy.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationMSRMTActivationStateNOTRDY.class
            })
    void testRequirement5471() throws NoTestData {
        final var metricCategory = MetricCategory.MSRMT;
        final var activationState = ComponentActivation.NOT_RDY;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Msrmt and the measurement for the METRIC has been"
            + " initialized, but is not being performed, the SERVICE PROVIDER SHALL set"
            + " pm:AbstractMetricState/@ActivationState = StndBy.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_2)
    @TestDescription("For each metric with the category MSRMT, the device is manipulated so that the measurement of the"
            + " metric is initialized but no measurement is performed and then it is verified that the ActivationState of"
            + " the metric is set to StndBy.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationMSRMTActivationStateSTNDBY.class
            })
    void testRequirement5472() throws NoTestData {
        final var metricCategory = MetricCategory.MSRMT;
        final var activationState = ComponentActivation.STND_BY;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Msrmt and the measurement for the "
            + "METRIC is currently de-initializing, the SERVICE PROVIDER SHALL set "
            + "pm:AbstractMetricState/@ActivationState = Shtdn.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_3)
    @TestDescription("For each metric with the category MSRMT, the device is manipulated to de-initialize "
            + "measurements and then it is verified that the ActivationState of the metric is set to Shtdn.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationMSRMTActivationStateSHTDN.class
            })
    void testRequirement5473() throws NoTestData {
        final var metricCategory = MetricCategory.MSRMT;
        final var activationState = ComponentActivation.SHTDN;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Msrmt and the measurement for the METRIC is not"
            + " being performed and is de-initialized, the SERVICE PROVIDER SHALL set"
            + " pm:AbstractMetricState/@ActivationState = Off.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_4)
    @TestDescription("For each metric with the category MSRMT, the device is manipulated so that the measurement of"
            + " the metric is de-initialized and no measurement is performed and then it is verified that the"
            + " ActivationState of the metric is set to Off.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationMSRMTActivationStateOFF.class
            })
    void testRequirement5474() throws NoTestData {
        final var metricCategory = MetricCategory.MSRMT;
        final var activationState = ComponentActivation.OFF;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Msrmt and the measurement for the METRIC has"
            + " failed, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = Fail.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_5)
    @TestDescription("For each metric with the category MSRMT, the device is manipulated so that the measurement of"
            + " the metric has failed and then it is verified that the ActivationState of the metric is set to Fail.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationMSRMTActivationStateFAIL.class
            })
    void testRequirement5475() throws NoTestData {
        final var metricCategory = MetricCategory.MSRMT;
        final var activationState = ComponentActivation.FAIL;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Set and the setting is currently being applied, the"
            + " SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = On.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_6_0)
    @TestDescription("For each metric with the category SET, the device is manipulated to apply settings and"
            + " then it is verified that the ActivationState of the metric is set to On.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationSETActivationStateON.class})
    void testRequirement54760() throws NoTestData {
        final var metricCategory = MetricCategory.SET;
        final var activationState = ComponentActivation.ON;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Set and the setting is currently initializing, "
            + "the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = NotRdy.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_7)
    @TestDescription("For each metric with the category SET, the device is manipulated to initialize the setting "
            + "and then it is verified that the ActivationState of the metric is set to NotRdy.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationSETActivationStateNOTRDY.class
            })
    void testRequirement5477() throws NoTestData {
        final var metricCategory = MetricCategory.SET;
        final var activationState = ComponentActivation.NOT_RDY;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Set and the setting has been initialized, but is not"
            + " being applied, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = StndBy.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_8)
    @TestDescription("For each metric with the category SET, the device is manipulated so that the setting of the"
            + " metric is initialized but no setting is applied and then it is verified that the ActivationState of"
            + " the metric is set to StndBy.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationSETActivationStateSTNDBY.class
            })
    void testRequirement5478() throws NoTestData {
        final var metricCategory = MetricCategory.SET;
        final var activationState = ComponentActivation.STND_BY;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Set and the setting is currently de-initializing,"
            + " the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = Shtdn.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_9)
    @TestDescription("For each metric with the category SET, the device is manipulated to de-initialize the setting of"
            + " the metric and then it is verified that the ActivationState of the metric is set to Shtdn.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationSETActivationStateSHTDN.class
            })
    void testRequirement5479() throws NoTestData {
        final var metricCategory = MetricCategory.SET;
        final var activationState = ComponentActivation.SHTDN;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Set and the setting is not being performed and is"
            + " de-initialized, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = Off.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_10)
    @TestDescription("For each metric with the category SET, the device is manipulated so that the setting"
            + " of the metric is de-initialized and no setting is applied and then it is verified that the ActivationState"
            + " of the metric is set to Off.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationSETActivationStateOFF.class})
    void testRequirement54710() throws NoTestData {
        final var metricCategory = MetricCategory.SET;
        final var activationState = ComponentActivation.OFF;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Set and the setting cannot be applied due to a "
            + "failure, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = Fail.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_11)
    @TestDescription("For each metric with the category SET, the device is manipulated so that the setting has "
            + "failed and then it is verified that the ActivationState of the metric is set to Fail.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationSETActivationStateFAIL.class
            })
    void testRequirement54711() throws NoTestData {
        final var metricCategory = MetricCategory.SET;
        final var activationState = ComponentActivation.FAIL;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Clc and the calculation for the METRIC is being"
            + " performed, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = On.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_12_0)
    @TestDescription("For each metric with the category CLC, the device is manipulated to perform calculations and"
            + " then it is verified that the ActivationState of the metric is set to On.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationCLCActivationStateON.class})
    void testRequirement547120() throws NoTestData {
        final var metricCategory = MetricCategory.CLC;
        final var activationState = ComponentActivation.ON;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Clc and the calculation for the METRIC is currently"
            + " initializing, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = NotRdy.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_13)
    @TestDescription("For each metric with the category CLC, the device is manipulated to initialize calculations and"
            + " then it is verified that the ActivationState of the metric is set to NotRdy.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationCLCActivationStateNOTRDY.class
            })
    void testRequirement54713() throws NoTestData {
        final var metricCategory = MetricCategory.CLC;
        final var activationState = ComponentActivation.NOT_RDY;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Clc and the calculation for the METRIC has been"
            + " initialized, but is not being performed, the SERVICE PROVIDER SHALL set"
            + " pm:AbstractMetricState/@ActivationState = StndBy.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_14)
    @TestDescription("For each metric with the category CLC, the device is manipulated to perform calculations and"
            + " then it is verified that the ActivationState of the metric is set to StndBy.")
    @RequirePrecondition(
            manipulationPreconditions = {
                ManipulationPreconditions.MetricStatusManipulationCLCActivationStateSTNDBY.class
            })
    void testRequirement54714() throws NoTestData {
        final var metricCategory = MetricCategory.CLC;
        final var activationState = ComponentActivation.STND_BY;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Clc and the calculation for the METRIC is currently"
            + " de-initializing, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = Shtdn.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_15)
    @TestDescription("For each metric with the category CLC, the device is manipulated to de-initialize calculations"
            + " and then it is verified that the ActivationState of the metric is set to Shtdn.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationCLCActivationStateSHTDN.class
            })
    void testRequirement54715() throws NoTestData {
        final var metricCategory = MetricCategory.CLC;
        final var activationState = ComponentActivation.SHTDN;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Clc and the calculation for the METRIC is not being"
            + " performed and is de-initialized, the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState"
            + " = Off.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_16)
    @TestDescription("For each metric with the category CLC, the device is manipulated so that the calculation of"
            + " the metric is de-initialized and no calculation is performed and then it is verified that the"
            + " ActivationState of the metric is set to Off.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationCLCActivationStateOFF.class})
    void testRequirement54716() throws NoTestData {
        final var metricCategory = MetricCategory.CLC;
        final var activationState = ComponentActivation.OFF;
        testRequirement547(metricCategory, activationState);
    }

    @Test
    @DisplayName("If pm:AbstractMetricDescriptor/@MetricCategory = Clc and the calculation for the METRIC has failed, "
            + "the SERVICE PROVIDER SHALL set pm:AbstractMetricState/@ActivationState = Fail.")
    @TestIdentifier(EnabledTestConfig.BICEPS_547_17)
    @TestDescription("For each metric with the category CLC, the device is manipulated so that the calculation has "
            + "failed and then it is verified that the ActivationState of the metric is set to Fail.")
    @RequirePrecondition(
            manipulationPreconditions = {ManipulationPreconditions.MetricStatusManipulationCLCActivationStateFAIL.class
            })
    void testRequirement54717() throws NoTestData {
        final var metricCategory = MetricCategory.CLC;
        final var activationState = ComponentActivation.FAIL;
        testRequirement547(metricCategory, activationState);
    }

    private void testRequirement547(final MetricCategory category, final ComponentActivation activation)
            throws NoTestData {
        final var successfulManipulationSeen = new AtomicBoolean(false);
        try (final var manipulations = messageStorage.getManipulationDataByParametersAndManipulation(
                ManipulationParameterUtil.buildMetricStatusManipulationParameterDataWithoutHandle(category, activation),
                Constants.MANIPULATION_NAME_SET_METRIC_STATUS)) {
            assertTestData(
                    manipulations.areObjectsPresent(), String.format(NO_SET_METRIC_STATUS_MANIPULATION, category));
            manipulations
                    .getStream()
                    .filter(it -> it.getResult().equals(ResponseTypes.Result.RESULT_SUCCESS))
                    .forEachOrdered(it -> {
                        successfulManipulationSeen.getAndSet(true);
                        checkAssociatedMetric(it, activation);
                    });
        } catch (IOException e) {
            fail(e);
            // unreachable
            throw new RuntimeException(e);
        }
        assertTestData(successfulManipulationSeen.get(), NO_SUCCESSFUL_MANIPULATION);
    }

    private void checkAssociatedMetric(
            final ManipulationData manipulationData, final ComponentActivation expectedActivationState) {
        final var manipulationParameter = manipulationData.getParameters();
        final var manipulatedHandle = manipulationParameter.stream()
                .filter(it -> it.getParameterName().equals(Constants.MANIPULATION_PARAMETER_HANDLE))
                .map(ManipulationParameter::getParameterValue)
                .findFirst()
                .orElseThrow();
        final var sequenceId = manipulationParameter.stream()
                .filter(it -> it.getParameterName().equals(Constants.MANIPULATION_PARAMETER_SEQUENCE_ID))
                .map(ManipulationParameter::getParameterValue)
                .findFirst()
                .orElseThrow();

        final var historian = mdibHistorianFactory.createMdibHistorian(
                messageStorage, getInjector().getInstance(TestRunObserver.class));

        final var endTimestamp = manipulationData.getFinishTimestamp() + buffer;
        try (final var history = historian.uniqueEpisodicReportBasedHistoryUntilTimestamp(sequenceId, endTimestamp)) {
            try (final var historyNext =
                    historian.uniqueEpisodicReportBasedHistoryUntilTimestamp(sequenceId, endTimestamp)) {

                RemoteMdibAccess first = history.next();
                RemoteMdibAccess second = historyNext.next();
                // skip the first entry so that history and historyNext are off by one entry
                final var skippedElement = historyNext.next();
                assertNotNull(skippedElement, String.format(NO_REPORT_IN_TIME, endTimestamp));

                // fast-forward to last mdib state before the end timestamp
                while (second != null) {
                    first = history.next();
                    second = historyNext.next();
                }
                final var relevantMetricStateOpt = first.getState(manipulatedHandle, AbstractMetricState.class);
                assertTrue(
                        relevantMetricStateOpt.isPresent(),
                        String.format(NO_METRIC_WITH_EXPECTED_HANDLE, manipulatedHandle));
                final var relevantMetricState = relevantMetricStateOpt.orElseThrow();
                Assertions.assertEquals(
                        expectedActivationState,
                        ImpliedValueUtil.getMetricActivation(relevantMetricState),
                        String.format(
                                WRONG_ACTIVATION_STATE,
                                manipulatedHandle,
                                expectedActivationState,
                                ImpliedValueUtil.getMetricActivation(relevantMetricState)));
            }
        } catch (ReportProcessingException | PreprocessingException e) {
            fail(e);
        }
    }
}
