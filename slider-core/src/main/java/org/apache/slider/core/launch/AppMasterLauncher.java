/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.slider.core.launch;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenIdentifier;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.Records;
import org.apache.slider.client.SliderYarnClientImpl;
import org.apache.slider.common.tools.CoreFileSystem;
import org.apache.slider.common.tools.SliderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Set;

public class AppMasterLauncher extends AbstractLauncher {


  private static final Logger log =
    LoggerFactory.getLogger(AppMasterLauncher.class);

  public final YarnClientApplication application;
  public final String name;
  public final String type;
  public final ApplicationSubmissionContext submissionContext;
  public final ApplicationId appId;
  public final boolean secureCluster;
  private int maxAppAttempts = 0;
  private boolean keepContainersOverRestarts = true;
  private String queue = YarnConfiguration.DEFAULT_QUEUE_NAME;
  private int priority = 1;
  private final Resource resource = Records.newRecord(Resource.class);
  private final SliderYarnClientImpl yarnClient;
  private Long submitTime;

  /**
   * Build the AM Launcher
   * @param name app name
   * @param type application type
   * @param conf hadoop config
   * @param fs filesystem binding
   * @param yarnClient yarn client
   * @param secureCluster flag to indicate secure cluster
   * @param options map of options. All values are extracted in this constructor only
   * @param resourceGlobalOptions global options
   * @param applicationTags any app tags
   * @throws IOException
   * @throws YarnException
   */
  public AppMasterLauncher(String name,
                           String type,
                           Configuration conf,
                           CoreFileSystem fs,
                           SliderYarnClientImpl yarnClient,
                           boolean secureCluster,
                           Map<String, String> options,
                           Map<String, String> resourceGlobalOptions,
                           Set<String> applicationTags
                          ) throws IOException, YarnException {
    super(conf, fs);
    this.yarnClient = yarnClient;
    this.application = yarnClient.createApplication();
    this.name = name;
    this.type = type;
    this.secureCluster = secureCluster;

    submissionContext = application.getApplicationSubmissionContext();
    appId = submissionContext.getApplicationId();
    // set the application name;
    submissionContext.setApplicationName(name);
    // app type used in service enum;
    submissionContext.setApplicationType(type);
    if (!applicationTags.isEmpty()) {
      submissionContext.setApplicationTags(applicationTags);
    }
    submissionContext.setNodeLabelExpression(extractLabelExpression(options));
    extractResourceRequirements(resource, options);
    extractLogAggregationContext(resourceGlobalOptions);
  }

  public void setMaxAppAttempts(int maxAppAttempts) {
    this.maxAppAttempts = maxAppAttempts;
  }

  public void setKeepContainersOverRestarts(boolean keepContainersOverRestarts) {
    this.keepContainersOverRestarts = keepContainersOverRestarts;
  }


  public Resource getResource() {
    return resource;
  }

  public void setMemory(int memory) {
    resource.setMemory(memory);
  }

  public void setVirtualCores(int cores) {
    resource.setVirtualCores(cores);
  }

  public ApplicationId getApplicationId() {
    return appId;
  }

  public int getMaxAppAttempts() {
    return maxAppAttempts;
  }

  public boolean isKeepContainersOverRestarts() {
    return keepContainersOverRestarts;
  }

  public String getQueue() {
    return queue;
  }

  public int getPriority() {
    return priority;
  }

  public void setQueue(String queue) {
    this.queue = queue;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  /**
   * Complete the launch context (copy in env vars, etc).
   * @return the container to launch
   */
  public ApplicationSubmissionContext completeAppMasterLaunch() throws
                                                                IOException {



    //queue priority
    Priority pri = Records.newRecord(Priority.class);
    pri.setPriority(priority);
    submissionContext.setPriority(pri);

    // Set the queue to which this application is to be submitted in the RM
    // Queue for App master

    submissionContext.setQueue(queue);


    //container requirements
    submissionContext.setResource(resource);
    submissionContext.setLogAggregationContext(logAggregationContext);

    if (keepContainersOverRestarts) {
      log.debug("Requesting cluster stays running over AM failure");
      submissionContext.setKeepContainersAcrossApplicationAttempts(true);
    }

    if (maxAppAttempts > 0) {
      log.debug("Setting max AM attempts to {}", maxAppAttempts);
      submissionContext.setMaxAppAttempts(maxAppAttempts);
    }

    if (secureCluster) {
      addSecurityTokens();
    } else {
      propagateUsernameInInsecureCluster();
    }
    completeContainerLaunch();
    submissionContext.setAMContainerSpec(containerLaunchContext);
    return submissionContext;
  }

  /**
   * Add the security tokens if this is a secure cluster
   * @throws IOException
   */
  private void addSecurityTokens() throws IOException {

    String tokenRenewer = SecurityUtil.getServerPrincipal(
        getConf().get(YarnConfiguration.RM_PRINCIPAL),
        InetAddress.getLocalHost().getCanonicalHostName());
    if (SliderUtils.isUnset(tokenRenewer)) {
      throw new IOException(
        "Can't get Master Kerberos principal for the RM to use as renewer: "
        + YarnConfiguration.RM_PRINCIPAL
      );
    }

    Token<? extends TokenIdentifier>[] tokens = null;
    boolean tokensProvided =
        this.getConf().get(MAPREDUCE_JOB_CREDENTIALS_BINARY) != null;
    if (!tokensProvided) {
        // For now, only getting tokens for the default file-system.
        FileSystem fs = coreFileSystem.getFileSystem();
        tokens = fs.addDelegationTokens(tokenRenewer, credentials);
    }
    // obtain the token expiry from the first token - should be the same for all
    // HDFS tokens
    if (tokens != null && tokens.length > 0) {
      AbstractDelegationTokenIdentifier id =
        (AbstractDelegationTokenIdentifier)tokens[0].decodeIdentifier();
      Date d = new Date(id.getIssueDate() + 24*60*60*1000);
      log.info("HDFS delegation tokens for AM launch context require renewal by {}",
               DateFormat.getDateTimeInstance().format(d));
    } else {
      if (!tokensProvided) {
        log.warn("No HDFS delegation tokens obtained for AM launch context");
      } else {
        log.info("Tokens provided via "+ MAPREDUCE_JOB_CREDENTIALS_BINARY +" property "
                 + "being used for AM launch");
      }

    }

   }

  /**
   * Submit the application. 
   * @return a launched application representing the submitted application
   * @throws IOException
   * @throws YarnException
   */
  public LaunchedApplication submitApplication() throws IOException, YarnException {
    completeAppMasterLaunch();
    log.info("Submitting application to Resource Manager");
    ApplicationId applicationId =
      yarnClient.submitApplication(submissionContext);
    // implicit success; record the time
    submitTime = System.currentTimeMillis();
    return new LaunchedApplication(applicationId, yarnClient);
  }

  /**
   * Build a serializable application report. This is a very minimal
   * report that contains the application Id, name and type —the information
   * available
   * @return a data structure which can be persisted
   */
  public SerializedApplicationReport createSerializedApplicationReport() {
    SerializedApplicationReport sar = new SerializedApplicationReport();
    sar.applicationId = appId.toString();
    sar.name = name;
    sar.applicationType = type;
    sar.queue = queue;
    sar.submitTime = submitTime;
    return sar;
  }
}
