/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.services;

import co.cask.cdap.api.data.DatasetContext;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.service.Retries;
import co.cask.cdap.common.service.RetryStrategies;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.internal.app.runtime.BasicArguments;
import co.cask.cdap.internal.app.runtime.ProgramOptionConstants;
import co.cask.cdap.internal.app.runtime.messaging.TopicMessageIdStore;
import co.cask.cdap.messaging.MessagingService;
import co.cask.cdap.proto.BasicThrowable;
import co.cask.cdap.proto.Notification;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.proto.id.ProgramRunId;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import org.apache.tephra.TransactionSystemClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Service that receives program status notifications and persists to the store
 */
public class ProgramNotificationSubscriberService extends AbstractNotificationSubscriberService {
  private static final Logger LOG = LoggerFactory.getLogger(ProgramNotificationSubscriberService.class);
  private static final Gson GSON = new Gson();
  private static final Type STRING_STRING_MAP = new TypeToken<Map<String, String>>() { }.getType();

  private final CConfiguration cConf;
  private final TopicMessageIdStore topicMessageIdStore;
  private final Store store;
  private ExecutorService taskExecutorService;

  @Inject
  ProgramNotificationSubscriberService(MessagingService messagingService, TopicMessageIdStore topicMessageIdStore,
                                       Store store, CConfiguration cConf,
                                       DatasetFramework datasetFramework, TransactionSystemClient txClient) {
    super(messagingService, cConf, datasetFramework, txClient);
    this.cConf = cConf;
    this.topicMessageIdStore = topicMessageIdStore;
    this.store = store;
  }

  @Override
  protected void startUp() {
    LOG.info("Starting ProgramNotificationSubscriberService");

    taskExecutorService = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                                                          .setNameFormat("program-status-subscriber-task-%d")
                                                          .build());
    taskExecutorService.submit(new ProgramStatusNotificationSubscriberThread(
      cConf.get(Constants.AppFabric.PROGRAM_STATUS_EVENT_TOPIC)));
  }

  @Override
  protected void shutDown() {
    super.shutDown();
    try {
      taskExecutorService.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      if (!taskExecutorService.isTerminated()) {
        taskExecutorService.shutdownNow();
      }
    }
    LOG.info("Stopped ProgramNotificationSubscriberService.");
  }

  /**
   * Thread that receives TMS notifications and persists the program status notification to the store
   */
  private class ProgramStatusNotificationSubscriberThread extends NotificationSubscriberThread {
    private final String topic;

    ProgramStatusNotificationSubscriberThread(String topic) {
      super(topic);
      this.topic = topic;
    }

    @Override
    public String loadMessageId() {
      return topicMessageIdStore.retrieveSubscriberState(topic);
    }

    @Override
    public void updateMessageId(String lastFetchedMessageId) {
      topicMessageIdStore.persistSubscriberState(topic, lastFetchedMessageId);
    }

    @Override
    public void processNotification(DatasetContext context, Notification notification) throws Exception {
      Map<String, String> properties = notification.getProperties();
      // Required parameters
      String programRunIdString = properties.get(ProgramOptionConstants.PROGRAM_RUN_ID);
      String programStatusString = properties.get(ProgramOptionConstants.PROGRAM_STATUS);

      // Ignore notifications which specify an invalid ProgramRunId or ProgramRunStatus
      if (programRunIdString == null || programStatusString == null) {
        return;
      }

      ProgramRunStatus runStatus = null;
      if (programStatusString != null) {
        try {
          runStatus = ProgramRunStatus.valueOf(programStatusString);
        } catch (IllegalArgumentException e) {
          LOG.warn("Invalid program run status {} passed in notification for program {}",
                   programStatusString, programRunIdString);
          return;
        }
      }

      final ProgramRunId programRunId = GSON.fromJson(programRunIdString, ProgramRunId.class);
      final String twillRunId = notification.getProperties().get(ProgramOptionConstants.TWILL_RUN_ID);

      final long stateChangeTimeSecs = getTimeSeconds(notification.getProperties(),
                                                      ProgramOptionConstants.LOGICAL_START_TIME);
      final long endTimeSecs = getTimeSeconds(notification.getProperties(), ProgramOptionConstants.END_TIME);
      final ProgramRunStatus programRunStatus = runStatus;
      switch(programRunStatus) {
        case STARTING:
          String userArgumentsString = properties.get(ProgramOptionConstants.USER_OVERRIDES);
          String systemArgumentsString = properties.get(ProgramOptionConstants.SYSTEM_OVERRIDES);
          if (userArgumentsString == null || systemArgumentsString == null) {
            throw new IllegalArgumentException((userArgumentsString == null) ? "user" : "system" + " arguments was "
                                               + "not specified in program status notification for program run " +
                                               programRunId);
          }
          if (stateChangeTimeSecs == -1) {
            throw new IllegalArgumentException("Start time was not specified in program starting notification for " +
                                                 "program run " + programRunId);
          }
          final Map<String, String> userArguments = GSON.fromJson(userArgumentsString, STRING_STRING_MAP);
          final Map<String, String> systemArguments = GSON.fromJson(systemArgumentsString, STRING_STRING_MAP);
          Retries.supplyWithRetries(new Supplier<Void>() {
            @Override
            public Void get() {
              store.setStart(programRunId.getParent(), programRunId.getRun(), stateChangeTimeSecs, twillRunId,
                             userArguments, systemArguments);
              return null;
            }
          }, RetryStrategies.fixDelay(Constants.Retry.RUN_RECORD_UPDATE_RETRY_DELAY_SECS, TimeUnit.SECONDS));
          break;
        case RUNNING:
          if (stateChangeTimeSecs == -1) {
            throw new IllegalArgumentException("Run time was not specified in program running notification for " +
                                               "program run {}" + programRunId);
          }
          Retries.supplyWithRetries(new Supplier<Void>() {
            @Override
            public Void get() {
              store.setRunning(programRunId.getParent(), programRunId.getRun(), stateChangeTimeSecs, twillRunId);
              return null;
            }
          }, RetryStrategies.fixDelay(Constants.Retry.RUN_RECORD_UPDATE_RETRY_DELAY_SECS, TimeUnit.SECONDS));
          break;
        case SUSPENDED:
          Retries.supplyWithRetries(new Supplier<Void>() {
            @Override
            public Void get() {
              store.setSuspend(programRunId.getParent(), programRunId.getRun());
              return null;
            }
          }, RetryStrategies.fixDelay(Constants.Retry.RUN_RECORD_UPDATE_RETRY_DELAY_SECS, TimeUnit.SECONDS));
          break;
        case RESUMING:
          Retries.supplyWithRetries(new Supplier<Void>() {
            @Override
            public Void get() {
              store.setResume(programRunId.getParent(), programRunId.getRun());
              return null;
            }
          }, RetryStrategies.fixDelay(Constants.Retry.RUN_RECORD_UPDATE_RETRY_DELAY_SECS, TimeUnit.SECONDS));
          break;
        case COMPLETED:
        case KILLED:
          if (endTimeSecs == -1) {
            throw new IllegalArgumentException("End time was not specified in program status notification for " +
                                               "program run {}" + programRunId);
          }
          Retries.supplyWithRetries(new Supplier<Void>() {
            @Override
            public Void get() {
              store.setStop(programRunId.getParent(), programRunId.getRun(), endTimeSecs, programRunStatus, null);
              return null;
            }
          }, RetryStrategies.fixDelay(Constants.Retry.RUN_RECORD_UPDATE_RETRY_DELAY_SECS, TimeUnit.SECONDS));
          break;
        case FAILED:
          if (endTimeSecs == -1) {
            throw new IllegalArgumentException("End time was not specified in program status notification for " +
                                               "program run {}" + programRunId);
          }
          String errorString = properties.get(ProgramOptionConstants.PROGRAM_ERROR);
          final BasicThrowable cause = (errorString == null)
            ? null
            : GSON.fromJson(errorString, BasicThrowable.class);
          Retries.supplyWithRetries(new Supplier<Void>() {
            @Override
            public Void get() {
              store.setStop(programRunId.getParent(), programRunId.getRun(), endTimeSecs, programRunStatus, cause);
              return null;
            }
          }, RetryStrategies.fixDelay(Constants.Retry.RUN_RECORD_UPDATE_RETRY_DELAY_SECS, TimeUnit.SECONDS));
          break;
        default:
          throw new UnsupportedOperationException(String.format("Cannot persist ProgramRunStatus %s for Program %s",
                                                                programRunStatus, programRunId));
      }
    }

    /**
     * Helper method to extract the time from the given properties map, or return -1 if no value was found
     *
     * @param properties the properties map
     * @param option the key to lookup in the properties map
     * @return the time in seconds, or -1 if not found
     */
    private long getTimeSeconds(Map<String, String> properties, String option) {
      String timeString = properties.get(option);
      return (timeString == null) ? -1 : TimeUnit.MILLISECONDS.toSeconds(Long.valueOf(timeString));
    }
  }
}