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

package com.google.android.as.oss.fl.brella.api;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;
import android.os.Parcel;
import androidx.annotation.IntDef;
import com.google.fcp.client.common.internal.safeparcel.SafeParcelable;
import com.google.fcp.client.common.internal.safeparcel.SafeParcelable.Reserved;
import com.google.fcp.client.BaseOptions;
import com.google.common.base.Objects;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import javax.annotation.Nullable;

/** Options use to create an {@link InAppTrainer} instance. */
// TODO: Resolve nullness suppression.
@SuppressWarnings("nullness")
@Reserved({8 /* Deprecated trainedParams field has been removed. */})
@SafeParcelable.Class(creator = "InAppTrainerOptionsCreator")
public final class InAppTrainerOptions extends BaseOptions {
  public static final String APP_FILES_SCHEME = "appfiles";
  public static final String APP_CACHE_SCHEME = "appcache";

  /** Indicates the kind of attestation federated compute will use. */
  @IntDef({
    AttestationMode.DEFAULT,
    AttestationMode.NONE,
  })
  public @interface AttestationMode {
    /** Use federated compute's default attestation mode. */
    int DEFAULT = 0;

    /** Indicates that no client-side attestation should be performed. */
    int NONE = 3;
  }

  /** Returns a new {@link Builder} for {@link InAppTrainerOptions}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for {@link InAppTrainerOptions}. */
  public static final class Builder {
    @Nullable private String sessionName;
    private int jobSchedulerJobId;
    private boolean allowFallbackToAutoGeneratedJobId;
    @Nullable private String federatedPopulationName;
    @AttestationMode private int attestationMode = AttestationMode.DEFAULT;
    @Nullable private Uri personalizationPlan;
    @Nullable private Uri initialParams;
    @Nullable private Uri outputDirectory;
    @Nullable private InAppTrainingConstraints constraints;
    private long overrideDeadlineMillis;
    @Nullable private TrainingInterval trainingInterval;
    private byte[] contextData = {};
    @Nullable private Uri inputDirectory;

    /**
     * Sets the session name to uniquely identify the training job by.
     *
     * <p>Required. Must be non-empty.
     *
     * <p>This name is unique across the app. No other training session can use this name.
     *
     * <p>Because a training job is identified by this name, it can be used to reconfigure (using
     * repeated calls to {@link InAppTrainer#schedule()}) or cancel (using calls to {@link
     * InAppTrainer#cancel()}) the corresponding job at a later point. The name is also included in
     * most Clearcut logs to attribute log events to this job.
     *
     * <p>Note that apps usually use a hardcoded constant as the session name. Also see {@link
     * #setFederatedOptions(String)}.
     */
    @CanIgnoreReturnValue
    public Builder setSessionName(String sessionName) {
      checkNotNull(sessionName);
      checkArgument(!sessionName.isEmpty());
      this.sessionName = sessionName;
      return this;
    }

    /**
     * Sets the ID to use for the JobScheduler job that will run the training for this session (i.e.
     * {@link android.app.job.JobInfo#getId()}).
     *
     * <p>Required. Must be non-zero.
     *
     * <p>This ID is unique across the app. No other training session can use this ID. Therefore if
     * another training session exists with the same ID, then when {@link InAppTrainer#schedule()}
     * is called that existing session will be canceled and removed.
     *
     * <p>The app also must not have any other JobScheduler jobs with this same ID. Therefore if
     * another non-FC JobScheduler job exists with this ID, then when {@link
     * InAppTrainer#schedule()} is called an error will be returned and the training job will not be
     * scheduled (the existing JobScheduler job will remain untouched).
     *
     * <p>If a given training session was previously configured using a different JobScheduler job
     * ID, then when {@link InAppTrainer#schedule()} is called the JobScheduler job with that old ID
     * will be canceled.
     *
     * @param jobSchedulerJobId the JobScheduler job ID to use for this session's training job.
     * @param allowFallbackToAutoGeneratedJobId whether to allow scheduling the job with an
     *     auto-generated ID.
     */
    @CanIgnoreReturnValue
    public Builder setJobSchedulerJobId(
        int jobSchedulerJobId, boolean allowFallbackToAutoGeneratedJobId) {
      this.jobSchedulerJobId = jobSchedulerJobId;
      this.allowFallbackToAutoGeneratedJobId = allowFallbackToAutoGeneratedJobId;
      return this;
    }

    /**
     * Sets the name of the Federated Learning server population this client is part of. Only one of
     * the three methods {@link #setFederatedOptions}, {@link #setPersonalizedOptions} and this one
     * must be called.
     *
     * <p>Required. Must be non-empty.
     *
     * <p>If a given training session was previously configured using a different population name,
     * or as personalized training, then when {@link InAppTrainer#schedule()} is called the
     * population name (and, if appropriate, the training type) for the session will be updated.
     *
     * <p>Note that apps often chose to configure this population name via a device config flag.
     * That way, you can use experiments to divide your app's install base into one or more
     * populations, while still being able to group your training jobs using the hardcoded session
     * name.
     */
    @CanIgnoreReturnValue
    public Builder setFederatedOptions(String population) {
      checkNotNull(population);
      checkArgument(!population.isEmpty());
      this.federatedPopulationName = population;
      return this;
    }

    /**
     * Defines options for legacy personalized training. Only one of the three methods {@link
     * #setFederatedOptions}, {@link #setLocalComputationOptions} or this one must be called.
     *
     * <p>If another training session exists with the same (personalization plan, initial params) or
     * trained params, that session will be replaced.
     *
     * <p>If a given training session was previously configured using a different personalization
     * plan, initial params, trained params, or as federated training, then when {@link
     * InAppTrainer#schedule()} is called the personalization parameters (and, if appropriate, the
     * training type) for the session will be updated.
     *
     * <p>Only Uri without authorities, fragments and queries are allowed. The Uri should be
     * absolute and hierarchical. Only supports "appfiles:" and "appcache:" schemes for the Uri. The
     * "appfiles" scheme refers to files in the app's file directory {@code Context#getFilesDir()}.
     * For example, "appfiles:/fcp/myfile" will map to the file at Context.getFilesDir() +
     * "/fcp/myfile". The "appcache" scheme refers to files in the app's cache directory {@code
     * Context#getCacheDir()}. For example, "appcache:/fcp/myfile" will map to the file at
     * Context.getCacheDir() + "/fcp/myfile".
     *
     * <p>Both files pointed by personalizationPlan and initialParams are expected to exist. If they
     * don't exist at the time the training job runs, it will fail and re-run at a later time. On
     * the other hand, outputDirectory points to a directory instead of file. The directory pointed
     * to by outputDirectory should not exist. Federated compute will create the output directory
     * and any necessary parent directories.
     *
     * <p>The latest training checkpoint will be stored in the output directory as {@link
     * InAppTrainingConstants#IN_APP_PERSONALIZATION_CHECKPOINT}
     *
     * <p>The training metrics for the latest training round will be stored in the output directory
     * as {@link InAppTrainingConstants#IN_APP_PERSONALIZATION_METRICS}, which is binary proto of
     * {@code TrainingMetrics}.
     *
     * @param personalizationPlan the uri of the training plan
     * @param initialParams the uri of the initial model parameters
     * @param outputDirectory the uri of the output directory
     * @deprecated This option only supports legacy personalization plans which do not contain
     *     {@code google.internal.federated.plan.TensorflowSpec} For newer plans, please use {@link
     *     #setLocalComputationOptions} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setPersonalizedOptions(
        Uri personalizationPlan, Uri initialParams, Uri outputDirectory) {
      checkUri(personalizationPlan);
      checkUri(initialParams);
      checkUri(outputDirectory);
      this.attestationMode = AttestationMode.DEFAULT;
      this.personalizationPlan = personalizationPlan;
      this.initialParams = initialParams;
      this.outputDirectory = outputDirectory;
      return this;
    }

    /**
     * Defines options for local computation. Only one of the three methods {@link
     * #setFederatedOptions}, {@link #setPersonalizedOptions} or this one must be called.
     *
     * <p>Only plans with {@code google.internal.federated.plan.TensorflowSpec} are supported by
     * using this option.
     *
     * <p>If another training session exists with the same plan, input directory or output directory
     * that session will be replaced.
     *
     * <p>If a given training session was previously configured using a different plan, input
     * directory, output directory, as a personalization training or as federated training session,
     * then when {@link InAppTrainer#schedule()} is called the local computation parameters (and, if
     * appropriate, the training type) for the session will be updated.
     *
     * <p>Only Uri without authorities, fragments and queries are allowed. The Uri should be
     * absolute and hierarchical. Only supports "appfiles:" and "appcache:" schemes for the Uri. The
     * "appfiles" scheme refers to files in the app's file directory {@link
     * android.content.Context#getFilesDir()}. For example, "appfiles:/fcp/myfile" will map to the
     * file at {@link android.content.Context#getFilesDir()} + "/fcp/myfile". The "appcache" scheme
     * refers to files in the app's cache directory {@link android.content.Context#getCacheDir()}.
     * For example, "appcache:/fcp/myfile" will map to the file at {@link
     * android.content.Context#getCacheDir()} + "/fcp/myfile".
     *
     * <p>Inputs for the local computation including the initial checkpoint (if required) should be
     * put inside the inputDirectory.
     *
     * @param localComputationPlan the uri of the local computation plan
     * @param inputDirectory the uri of the input directory
     * @param outputDirectory the uri of the output directory
     */
    @CanIgnoreReturnValue
    public Builder setLocalComputationOptions(
        Uri localComputationPlan, Uri inputDirectory, Uri outputDirectory) {
      checkUri(localComputationPlan);
      checkUri(inputDirectory);
      checkUri(outputDirectory);
      this.attestationMode = AttestationMode.DEFAULT;
      this.personalizationPlan = localComputationPlan;
      this.inputDirectory = inputDirectory;
      this.outputDirectory = outputDirectory;
      return this;
    }

    /**
     * Sets the attestation mode.
     *
     * <p>If the user does not specify an attestationMode, {@code AttestationMode.DEFAULT} will be
     * used.
     *
     * <p>The attestation mode is ignored when using in-app personalization (that is, when {@link
     * #setPersonalizedOptions} has been called.
     */
    @CanIgnoreReturnValue
    public Builder setAttestationMode(@AttestationMode int attestationMode) {
      checkArgument(isValidAttestationMode(attestationMode));
      if (personalizationPlan == null) {
        this.attestationMode = attestationMode;
      } else {
        throw new IllegalArgumentException(
            "Attestation is not supported for personalization or local computation.");
      }
      return this;
    }

    /**
     * Sets the {@link TrainingInterval}. It is required for all local computation/personalization
     * tasks, and optional for federated tasks.
     *
     * <p>Please note if training interval is scheduled for recurrent tasks, the earliest time this
     * task could start is after the minimum training interval expires. E.g. If the task is set to
     * run maximum once per day, the first run of this task will one day after this task is
     * scheduled. This ensures that the specified period is never violated, even during edge cases
     * like when the task is briefly canceled and then immediately rescheduled.
     */
    @CanIgnoreReturnValue
    public Builder setTrainingInterval(TrainingInterval trainingInterval) {
      this.trainingInterval = trainingInterval;
      return this;
    }

    /**
     * Sets the context data that will be passed back to the client in the {@code
     * IInAppTrainingResultCallback} method.
     *
     * <p>The context data must be of small size. Federated compute guarantees to support data blobs
     * up to 4KB, but any data of bigger size above may fail a scheduler call with {@link
     * com.google.fcp.client.common.api.CommonStatusCodes#DEVELOPER_ERROR}.
     *
     * <p>An empty array will be used if no context data is provided by customer using this method.
     */
    @CanIgnoreReturnValue
    public Builder setContextData(byte[] contextData) {
      checkNotNull(contextData);
      this.contextData = Arrays.copyOf(contextData, contextData.length);
      return this;
    }

    /**
     * Creates an {@link InAppTrainerOptions} instance using the provided options.
     *
     * @throws IllegalArgumentException if the session name is unspecified or empty.
     * @throws IllegalArgumentException if the JobScheduler ID is unspecified or zero.
     * @throws IllegalArgumentException if the population name is unspecified or empty.
     * @throws IllegalArgumentException if the attestation mode is invalid.
     * @throws IllegalArgumentException if the initialParams, trainedParams or personalizationPlan
     *     uses an unsupported uri scheme.
     * @throws IllegalArgumentException if the training interval is not set for local
     *     computation/personalization.
     */
    public InAppTrainerOptions build() {
      return new InAppTrainerOptions(
          sessionName,
          jobSchedulerJobId,
          allowFallbackToAutoGeneratedJobId,
          federatedPopulationName,
          attestationMode,
          personalizationPlan,
          initialParams,
          constraints,
          overrideDeadlineMillis,
          outputDirectory,
          trainingInterval,
          contextData,
          inputDirectory);
    }
  }

  public static final Creator<InAppTrainerOptions> CREATOR = new InAppTrainerOptionsCreator();

  @Field(id = 1, getter = "getSessionName")
  private final String sessionName;

  @Field(id = 2, getter = "getJobSchedulerJobId")
  private final int jobSchedulerJobId;

  @Field(id = 3, getter = "getAllowFallbackToAutoGeneratedJobId")
  private final boolean allowFallbackToAutoGeneratedJobId;

  @Field(id = 4, getter = "getFederatedPopulationName")
  private final String federatedPopulationName;

  @Field(id = 5, getter = "getAttestationMode")
  @AttestationMode
  private final int attestationMode;

  @Field(id = 6, getter = "getPersonalizationPlan")
  @Nullable
  private final Uri personalizationPlan;

  @Field(id = 7, getter = "getInitialParams")
  @Nullable
  private final Uri initialParams;

  @Field(id = 9, getter = "getTrainingConstraints")
  @Nullable
  private final InAppTrainingConstraints trainingConstraints;

  @Field(id = 10, getter = "getOverrideDeadlineMillis")
  private final long overrideDeadlineMillis;

  @Field(id = 11, getter = "getOutputDirectory")
  @Nullable
  private final Uri outputDirectory;

  @Field(id = 12, getter = "getTrainingInterval")
  @Nullable
  private final TrainingInterval trainingInterval;

  @Field(id = 13, getter = "getContextData")
  private final byte[] contextData;

  @Field(id = 14, getter = "getInputDirectory")
  @Nullable
  private final Uri inputDirectory;

  @Constructor
  InAppTrainerOptions(
      @Nullable @Param(id = 1) String sessionName,
      @Param(id = 2) int jobSchedulerJobId,
      @Param(id = 3) boolean allowFallbackToAutoGeneratedJobId,
      @Nullable @Param(id = 4) String federatedPopulationName,
      @Param(id = 5) @AttestationMode int attestationMode,
      @Nullable @Param(id = 6) Uri personalizationPlan,
      @Nullable @Param(id = 7) Uri initialParams,
      @Nullable @Param(id = 9) InAppTrainingConstraints trainingConstraints,
      @Param(id = 10) long overrideDeadlineMillis,
      @Nullable @Param(id = 11) Uri outputDirectory,
      @Nullable @Param(id = 12) TrainingInterval trainingInterval,
      @Nullable @Param(id = 13) byte[] contextData,
      @Nullable @Param(id = 14) Uri inputDirectory) {
    validate(
        sessionName,
        jobSchedulerJobId,
        federatedPopulationName,
        attestationMode,
        personalizationPlan,
        initialParams,
        outputDirectory,
        trainingInterval,
        inputDirectory);
    this.sessionName = sessionName;
    this.jobSchedulerJobId = jobSchedulerJobId;
    this.allowFallbackToAutoGeneratedJobId = allowFallbackToAutoGeneratedJobId;
    this.federatedPopulationName = federatedPopulationName;
    this.attestationMode = attestationMode;
    this.personalizationPlan = personalizationPlan;
    this.initialParams = initialParams;
    this.outputDirectory = outputDirectory;
    this.trainingConstraints = trainingConstraints;
    this.overrideDeadlineMillis = overrideDeadlineMillis;
    this.trainingInterval = trainingInterval;
    this.contextData = contextData != null ? contextData : new byte[] {};
    this.inputDirectory = inputDirectory;
  }

  private static void validate(
      String sessionName,
      int jobSchedulerJobId,
      String federatedPopulationName,
      int attestationMode,
      Uri personalizationPlan,
      Uri initialParams,
      Uri outputDirectory,
      TrainingInterval trainingInterval,
      Uri inputDirectory) {
    checkArgument(!sessionName.isEmpty());
    checkArgument(jobSchedulerJobId != 0);
    if (personalizationPlan != null && federatedPopulationName == null) {
      // legacy p13n task or local computation task
      checkNotNull(outputDirectory);
      checkNotNull(trainingInterval);
      if (inputDirectory == null) {
        // legacy p13n task requires initial params.
        checkNotNull(initialParams);
      } else {
        // initial params should be null for new local computation tasks.
        checkArgument(initialParams == null);
      }
    } else if (personalizationPlan == null && federatedPopulationName != null) {
      checkArgument(!federatedPopulationName.isEmpty());
      checkArgument(isValidAttestationMode(attestationMode));
    } else if (personalizationPlan == null) {
      throw new IllegalArgumentException(
          "must call exactly one of #setFederatedOptions or #setPersonalizedOptions");
    } else {
      throw new IllegalArgumentException(
          "cannot call both #setFederatedOptions and #setPersonalizedOptions");
    }
  }

  private static boolean isValidAttestationMode(@AttestationMode int attestationMode) {
    switch (attestationMode) {
      case AttestationMode.DEFAULT:
      case AttestationMode.NONE:
        return true;
      default:
        return false;
    }
  }

  private static void checkUri(Uri paramsUri) {
    checkArgument(paramsUri.isAbsolute(), "%s is not absolute.", paramsUri);
    checkArgument(paramsUri.isHierarchical(), "%s is not hierarchical.", paramsUri);
    checkArgument(paramsUri.getAuthority() == null, "Uri cannot have authority.");
    checkArgument(paramsUri.getFragment() == null, "Uri cannot have fragment part.");
    checkArgument(paramsUri.getQuery() == null, "Uri cannot have query part.");
    checkArgument(
        APP_FILES_SCHEME.equals(paramsUri.getScheme())
            || APP_CACHE_SCHEME.equals(paramsUri.getScheme()),
        "Unsupported scheme: %s",
        paramsUri.getScheme());
  }

  @SuppressWarnings("static-access")
  @Override
  public void writeToParcel(Parcel out, int flags) {
    InAppTrainerOptionsCreator.writeToParcel(this, out, flags);
  }

  @Override
  public boolean equals(Object otherObj) {
    if (this == otherObj) {
      return true;
    }
    if (!(otherObj instanceof InAppTrainerOptions)) {
      return false;
    }

    InAppTrainerOptions otherOptions = (InAppTrainerOptions) otherObj;

    return Objects.equal(sessionName, otherOptions.sessionName)
        && Objects.equal(jobSchedulerJobId, otherOptions.jobSchedulerJobId)
        && Objects.equal(
            allowFallbackToAutoGeneratedJobId, otherOptions.allowFallbackToAutoGeneratedJobId)
        && Objects.equal(federatedPopulationName, otherOptions.federatedPopulationName)
        && attestationMode == otherOptions.attestationMode
        && Objects.equal(personalizationPlan, otherOptions.personalizationPlan)
        && Objects.equal(initialParams, otherOptions.initialParams)
        && Objects.equal(outputDirectory, otherOptions.outputDirectory)
        && Objects.equal(trainingConstraints, otherOptions.trainingConstraints)
        && overrideDeadlineMillis == otherOptions.overrideDeadlineMillis
        && Objects.equal(trainingInterval, otherOptions.trainingInterval)
        && Arrays.equals(contextData, otherOptions.contextData)
        && Objects.equal(inputDirectory, otherOptions.inputDirectory);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        sessionName,
        jobSchedulerJobId,
        allowFallbackToAutoGeneratedJobId,
        federatedPopulationName,
        attestationMode,
        personalizationPlan,
        initialParams,
        outputDirectory,
        trainingConstraints,
        overrideDeadlineMillis,
        trainingInterval,
        Arrays.hashCode(contextData),
        inputDirectory);
  }

  /** Returns the training job's session name. */
  public String getSessionName() {
    return sessionName;
  }

  /** Returns the JobScheduler job ID to use for this training session. */
  public int getJobSchedulerJobId() {
    return jobSchedulerJobId;
  }

  /** Whether to allow the job to be scheduled with an auto-generated JobScheduler job ID */
  public boolean getAllowFallbackToAutoGeneratedJobId() {
    return allowFallbackToAutoGeneratedJobId;
  }

  /** Returns the Federated Learning server population to use. */
  public String getFederatedPopulationName() {
    return federatedPopulationName;
  }

  /** Returns the attestation mode to use. */
  @AttestationMode
  public int getAttestationMode() {
    return attestationMode;
  }

  /** Returns the URI of the personalization plan. */
  @Nullable
  public Uri getPersonalizationPlan() {
    return personalizationPlan;
  }

  /** Returns the initial parameters URI for personaliation. */
  @Nullable
  public Uri getInitialParams() {
    return initialParams;
  }

  /** Returns the trained (output) parameters URI for personaliation. */
  @Nullable
  public Uri getOutputDirectory() {
    return outputDirectory;
  }

  /** Returns the constraints for job scheduler. */
  @Nullable
  public InAppTrainingConstraints getTrainingConstraints() {
    return trainingConstraints;
  }

  /** Returns the deadline in milliseconds by which the training task should run next. */
  public long getOverrideDeadlineMillis() {
    return overrideDeadlineMillis;
  }

  /** Returns the training interval settings. */
  @Nullable
  public TrainingInterval getTrainingInterval() {
    return trainingInterval;
  }

  /** Returns the context data. */
  public byte[] getContextData() {
    return Arrays.copyOf(contextData, contextData.length);
  }

  /** Returns the input directory. */
  @Nullable
  public Uri getInputDirectory() {
    return inputDirectory;
  }
}
