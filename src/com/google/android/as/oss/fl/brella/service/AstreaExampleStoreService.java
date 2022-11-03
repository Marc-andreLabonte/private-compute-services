/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.as.oss.fl.brella.service;

import static com.google.android.as.oss.networkusage.db.ConnectionDetails.ConnectionType.FC_TRAINING_START_QUERY;

import android.os.IInterface;
import android.os.RemoteException;
import androidx.annotation.VisibleForTesting;
import arcs.core.data.proto.PolicyProto;
import com.google.android.as.oss.common.ExecutorAnnotations.FlExecutorQualifier;
import com.google.android.as.oss.fl.Annotations.AsiPackageName;
import com.google.android.as.oss.fl.Annotations.ExampleStoreClientsInfo;
import com.google.android.as.oss.fl.Annotations.GppsPackageName;
import com.google.android.as.oss.fl.brella.api.IExampleStore;
import com.google.android.as.oss.fl.brella.api.StartQueryCallback;
import com.google.android.as.oss.fl.brella.api.proto.TrainingError;
import com.google.android.as.oss.fl.brella.service.ConnectionManager.ConnectionType;
import com.google.android.as.oss.fl.brella.service.util.PolicyFinder;
import com.google.android.as.oss.networkusage.db.NetworkUsageLogRepository;
import com.google.android.as.oss.networkusage.db.NetworkUsageLogUtils;
import com.google.android.as.oss.networkusage.ui.content.UnrecognizedNetworkRequestException;
import com.google.android.as.oss.proto.AstreaProtos.AstreaQuery;
import com.google.fcp.client.ExampleStoreService;
import com.google.android.as.oss.policies.api.Policy;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.intelligence.fcp.client.SelectorContext;
import com.google.protobuf.Any;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An implementation of federated compute's {@link ExampleStoreService} that forwards calls to the
 * appropriate delegate in client based on the passed-in query.
 */
@AndroidEntryPoint(ExampleStoreService.class)
public final class AstreaExampleStoreService extends Hilt_AstreaExampleStoreService {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Inject @ExampleStoreClientsInfo ImmutableMap<String, String> packageToActionMap;
  @Inject Multimap<String, PolicyProto> installedPolicies;
  @Inject @AsiPackageName String asiPackageName;
  @Inject @GppsPackageName String gppsPackageName;
  @Inject NetworkUsageLogRepository networkUsageLogRepository;
  @Inject @FlExecutorQualifier Executor flExecutor;

  private ConnectionManager connectionManager;

  public AstreaExampleStoreService() {}

  @VisibleForTesting(otherwise = VisibleForTesting.NONE)
  AstreaExampleStoreService(ConnectionManager connectionManager) {
    this.connectionManager = connectionManager;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    logger.atFine().log("AstreaExampleStoreService.onCreate()");
    connectionManager =
        new ConnectionManager(
            this,
            packageToActionMap,
            ConnectionType.EXAMPLE_STORE,
            asiPackageName,
            gppsPackageName);
  }

  @Override
  public void onDestroy() {
    logger.atFine().log("AstreaExampleStoreService.onDestroy()");
    connectionManager.unbindService();
    super.onDestroy();
  }

  @Override
  public void startQuery(
      @Nonnull String collection,
      @Nonnull byte[] criteria,
      @Nonnull byte[] resumptionToken,
      @Nonnull QueryCallback callback,
      @Nonnull SelectorContext selectorContext) {
    AstreaQuery query = parseCriteria(criteria);
    if (query == null) {
      callback.onStartQueryFailure(
          TrainingError.TRAINING_ERROR_PCC_FAILED_TO_PARSE_QUERY_VALUE, "Failed to parse query.");
      return;
    }

    Optional<Policy> installedPolicy = extractInstalledPolicyOptional(callback, query);
    if (!installedPolicy.isPresent()) {
      return;
    }

    if (!connectionManager.isClientSupported(query.getClientName())) {
      callback.onStartQueryFailure(
          TrainingError.TRAINING_ERROR_PCC_CLIENT_NOT_SUPPORTED_VALUE,
          "Incorrect client name provided in the query.");
      return;
    }

    if (!checkFederatedConfigs(query, selectorContext, installedPolicy.get())) {
      callback.onStartQueryFailure(
          TrainingError.TRAINING_ERROR_PCC_CONFIG_VALIDATION_FAILED_VALUE,
          "Training configs don't match federation configs defined in the policy.");
      return;
    }

    String featureName = query.getFeatureName().name();

    // Log Unrecognized requests
    if (!networkUsageLogRepository.isKnownConnection(FC_TRAINING_START_QUERY, featureName)) {
      logUnknownConnection(featureName);
    }

    if (networkUsageLogRepository.shouldRejectRequest(FC_TRAINING_START_QUERY, featureName)) {
      logger.atWarning().withCause(UnrecognizedNetworkRequestException.forFeatureName(featureName))
          .log("Rejected unknown FC request to PCS");
      callback.onStartQueryFailure(
          TrainingError.TRAINING_ERROR_PCC_CLIENT_NOT_SUPPORTED_VALUE,
          String.format("Unknown PCS request for feature %s", featureName));
      return;
    }
    insertNetworkUsageLogRowForTrainingEvent(
        query, selectorContext.getComputationProperties().getRunId());

    initializeConnectionAndStartQuery(collection, criteria, resumptionToken, callback, query);
  }

  private Optional<Policy> extractInstalledPolicyOptional(
      @Nonnull QueryCallback callback, @Nonnull AstreaQuery query) {
    if (!query.hasPolicy()) {
      logger.atWarning().log("No policy provided in the query.");
      callback.onStartQueryFailure(
          TrainingError.TRAINING_ERROR_PCC_POLICY_NOT_PRESENT_VALUE,
          "Query does not specify a policy");
      return Optional.absent();
    }

    PolicyProto queryPolicy = query.getPolicy();
    Optional<Policy> installedPolicy =
        PolicyFinder.findCompatiblePolicy(queryPolicy, installedPolicies);
    if (!installedPolicy.isPresent()) {
      callback.onStartQueryFailure(
          TrainingError.TRAINING_ERROR_PCC_POLICY_NOT_PRESENT_VALUE,
          "Query specified policy is not installed, or the installed version is incompatible.");
      return Optional.absent();
    }

    return installedPolicy;
  }

  private void initializeConnectionAndStartQuery(
      @Nonnull String collection,
      byte[] criteria,
      byte[] resumptionToken,
      @Nonnull QueryCallback callback,
      AstreaQuery query) {
    Futures.addCallback(
        connectionManager.initializeServiceConnection(query.getClientName()),
        new FutureCallback<IInterface>() {
          @Override
          public void onSuccess(IInterface result) {
            try {
              IExampleStore binder = (IExampleStore) result;
              binder.startQuery(
                  collection, criteria, resumptionToken, new StartQueryCallback(callback));
            } catch (RemoteException e) {
              connectionManager.resetClient(query.getClientName());
              // We don't expect client to actually throw any RemoteExceptions.
              // If it does happen for some reason then all we can do now is log the exception
              // and swallow it.
              logger.atWarning().withCause(e).log("Failed to delegate startQuery.");
              callback.onStartQueryFailure(
                  TrainingError.TRAINING_ERROR_PCC_TRAINING_DELEGATION_TO_CLIENT_FAILED_VALUE,
                  "Failed to delegate startQuery.");
            }
          }

          @Override
          public void onFailure(Throwable t) {
            logger.atWarning().log("Failed to bind to service");
            connectionManager.resetClient(query.getClientName());
            callback.onStartQueryFailure(
                TrainingError.TRAINING_ERROR_PCC_BINDING_TO_CLIENT_FAILED_VALUE,
                "Failed to bind to service.");
          }
        },
        flExecutor);
  }

  private void logUnknownConnection(String featureName) {
    logger.atInfo().log("Network usage log unrecognised FC request for %s", featureName);
  }

  static boolean checkFederatedConfigs(
      AstreaQuery query, SelectorContext selectorContext, Policy installedPolicy) {
    if (!installedPolicy.getConfigs().containsKey("federatedCompute")) {
      logger.atWarning().log("Policy provided doesn't have configs.");
      return false;
    }

    Map<String, String> policyConfigs = installedPolicy.getConfigs().get("federatedCompute");
    if (policyConfigs == null || !policyConfigs.containsKey("minSecAggRoundSize")) {
      logger.atWarning().log("Policy provided doesn't have configs.");
      return false;
    }
    int secAggRoundSize = Integer.parseInt(policyConfigs.getOrDefault("minSecAggRoundSize", "0"));

    if (selectorContext.getComputationProperties().hasEligibilityEval()) {
      if (secAggRoundSize > 1) {
        // EETs are not allowed to run with SecAgg policy.
        logger.atWarning().log("EETs are not allowed to run with SecAgg policy.");
        return false;
      }

      if (!query
          .getPopulationName()
          .equals(
              selectorContext
                  .getComputationProperties()
                  .getEligibilityEval()
                  .getPopulationName())) {
        logger.atWarning().log(
            "Population in the query does not match population in the computation properties.");
        return false;
      }

      return true;
    }

    if (!query
        .getPopulationName()
        .equals(selectorContext.getComputationProperties().getFederated().getPopulationName())) {
      logger.atWarning().log(
          "Population in the query does not match population in the computation properties.");
      return false;
    }

    // Secure Aggregation with size <= 1 is equivalent to disabling SecAgg.
    if (secAggRoundSize > 1) {
      if (!selectorContext.getComputationProperties().getFederated().hasSecureAggregation()) {
        logger.atWarning().log(
            "SecAgg metadata not provided by Federated Compute, but SecAgg is required by policy.");
        return false;
      }

      if (selectorContext
              .getComputationProperties()
              .getFederated()
              .getSecureAggregation()
              .getMinimumClientsInServerVisibleAggregate()
          < secAggRoundSize) {
        logger.atWarning().log(
            "SecAgg round size is less than the round size required by the policy.");
        return false;
      }
    }

    return true;
  }

  private static @Nullable AstreaQuery parseCriteria(byte[] criteria) {
    try {
      Any parsedCriteria = Any.parseFrom(criteria, ExtensionRegistryLite.getGeneratedRegistry());
      return AstreaQuery.parseFrom(
          parsedCriteria.getValue(), ExtensionRegistryLite.getGeneratedRegistry());
    } catch (InvalidProtocolBufferException e) {
      logger.atWarning().withCause(e).log("Couldn't parse criteria.");
    }
    return null;
  }

  // Note: The success/failure status and the upload size in bytes, are reported in another row
  // when we receive a LogEvent of kind TRAIN_RESULT_UPLOADED or TRAIN_FAILURE_UPLOADED.
  private void insertNetworkUsageLogRowForTrainingEvent(AstreaQuery query, long runId) {
    if (networkUsageLogRepository.isNetworkUsageLogEnabled()
        && networkUsageLogRepository.getDbExecutor().isPresent()) {
      networkUsageLogRepository
          .getDbExecutor()
          .get()
          .execute(
              () ->
                  networkUsageLogRepository.insertNetworkUsageEntity(
                      NetworkUsageLogUtils.createFcTrainingStartQueryNetworkUsageEntity(
                          NetworkUsageLogUtils.createFcTrainingStartQueryConnectionDetails(
                              query.getFeatureName().name(), query.getClientName()),
                          runId,
                          query.getPolicy())));
    }
  }
}
