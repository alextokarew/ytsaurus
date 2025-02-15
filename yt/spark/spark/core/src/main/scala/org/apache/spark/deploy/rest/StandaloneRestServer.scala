/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.rest

import java.io.File
import javax.servlet.http.HttpServletResponse

import org.apache.spark.{SPARK_VERSION => sparkVersion, SparkConf}
import org.apache.spark.deploy.{Command, DeployMessages, DriverDescription}
import org.apache.spark.deploy.ClientArguments._
import org.apache.spark.internal.config
import org.apache.spark.launcher.SparkLauncher
import org.apache.spark.resource.ResourceUtils
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.Utils

/**
 * A server that responds to requests submitted by the [[RestSubmissionClient]].
 * This is intended to be embedded in the standalone Master and used in cluster mode only.
 *
 * This server responds with different HTTP codes depending on the situation:
 *   200 OK - Request was processed successfully
 *   400 BAD REQUEST - Request was malformed, not successfully validated, or of unexpected type
 *   468 UNKNOWN PROTOCOL VERSION - Request specified a protocol this server does not understand
 *   500 INTERNAL SERVER ERROR - Server throws an exception internally while processing the request
 *
 * The server always includes a JSON representation of the relevant [[SubmitRestProtocolResponse]]
 * in the HTTP body. If an error occurs, however, the server will include an [[ErrorResponse]]
 * instead of the one expected by the client. If the construction of this error response itself
 * fails, the response will consist of an empty body with a response code that indicates internal
 * server error.
 *
 * @param host the address this server should bind to
 * @param requestedPort the port this server will attempt to bind to
 * @param masterConf the conf used by the Master
 * @param masterEndpoint reference to the Master endpoint to which requests can be sent
 * @param masterUrl the URL of the Master new drivers will attempt to connect to
 */
private[deploy] class StandaloneRestServer(
    host: String,
    requestedPort: Int,
    masterConf: SparkConf,
    masterEndpoint: RpcEndpointRef,
    masterUrl: String)
  extends RestSubmissionServer(host, requestedPort, masterConf) {

  protected override val submitRequestServlet =
    new StandaloneSubmitRequestServlet(masterEndpoint, masterUrl, masterConf)
  protected override val killRequestServlet =
    new StandaloneKillRequestServlet(masterEndpoint, masterConf)
  protected override val statusRequestServlet =
    new StandaloneStatusRequestServlet(masterEndpoint, masterConf)
  protected override val masterStateRequestServlet =
    new StandaloneMasterStateRequestServlet(masterEndpoint, masterConf)
  protected override val appIdRequestServlet =
    new StandaloneAppIdRequestServlet(masterEndpoint, masterConf)
  protected override val appStatusRequestServlet =
    new StandaloneAppStatusRequestServlet(masterEndpoint, masterConf)
}

/**
 * A servlet for handling kill requests passed to the [[StandaloneRestServer]].
 */
private[rest] class StandaloneKillRequestServlet(masterEndpoint: RpcEndpointRef, conf: SparkConf)
  extends KillRequestServlet {

  protected def handleKill(submissionId: String): KillSubmissionResponse = {
    val response = masterEndpoint.askSync[DeployMessages.KillDriverResponse](
      DeployMessages.RequestKillDriver(submissionId))
    val k = new KillSubmissionResponse
    k.serverSparkVersion = sparkVersion
    k.message = response.message
    k.submissionId = submissionId
    k.success = response.success
    k
  }
}

/**
 * A servlet for handling status requests passed to the [[StandaloneRestServer]].
 */
private[rest] class StandaloneStatusRequestServlet(masterEndpoint: RpcEndpointRef, conf: SparkConf)
  extends StatusRequestServlet {

  protected def handleStatus(submissionId: String): SubmissionStatusResponse = {
    val response = masterEndpoint.askSync[DeployMessages.DriverStatusResponse](
      DeployMessages.RequestDriverStatus(submissionId))
    val message = response.exception.map { s"Exception from the cluster:\n" + formatException(_) }
    val d = new SubmissionStatusResponse
    d.serverSparkVersion = sparkVersion
    d.submissionId = submissionId
    d.success = response.found
    d.driverState = response.state.map(_.toString).orNull
    d.workerId = response.workerId.orNull
    d.workerHostPort = response.workerHostPort.orNull
    d.message = message.orNull
    d
  }

  protected def handleStatuses: SubmissionStatusesResponse = {
    val response = masterEndpoint.askSync[DeployMessages.DriverStatusesResponse](
      DeployMessages.RequestDriverStatuses)
    val resp = new SubmissionStatusesResponse
    resp.serverSparkVersion = sparkVersion
    resp.success = response.exception.isEmpty
    resp.message = response.exception.map(s"Exception from the cluster:\n"
      + formatException(_)).orNull
    resp.statuses = response.statuses.map(r => {
      val d = new SubmissionsStatus
      d.driverId = r.id
      d.status = r.state
      d.startedAt = r.startTimeMs
      d
    })
    resp
  }
}

/**
 * A servlet for handling submit requests passed to the [[StandaloneRestServer]].
 */
private[rest] class StandaloneSubmitRequestServlet(
    masterEndpoint: RpcEndpointRef,
    masterUrl: String,
    conf: SparkConf)
  extends SubmitRequestServlet {

  /**
   * Build a driver description from the fields specified in the submit request.
   *
   * This involves constructing a command that takes into account memory, java options,
   * classpath and other settings to launch the driver. This does not currently consider
   * fields used by python applications since python is not supported in standalone
   * cluster mode yet.
   */
  private def buildDriverDescription(request: CreateSubmissionRequest): DriverDescription = {
    // Required fields, including the main class because python is not yet supported
    val appResource = Option(request.appResource).getOrElse {
      throw new SubmitRestMissingFieldException("Application jar is missing.")
    }
    val mainClass = Option(request.mainClass).getOrElse {
      throw new SubmitRestMissingFieldException("Main class is missing.")
    }

    // Optional fields
    val sparkProperties = request.sparkProperties
    val driverMemory = sparkProperties.get(config.DRIVER_MEMORY.key)
    val driverCores = sparkProperties.get(config.DRIVER_CORES.key)
    val driverDefaultJavaOptions = sparkProperties.get(SparkLauncher.DRIVER_DEFAULT_JAVA_OPTIONS)
    val driverExtraJavaOptions = sparkProperties.get(config.DRIVER_JAVA_OPTIONS.key)
    val driverExtraClassPath = sparkProperties.get(config.DRIVER_CLASS_PATH.key)
    val driverExtraLibraryPath = sparkProperties.get(config.DRIVER_LIBRARY_PATH.key)
    val superviseDriver = sparkProperties.get(config.DRIVER_SUPERVISE.key)
    // The semantics of "spark.master" and the masterUrl are different. While the
    // property "spark.master" could contain all registered masters, masterUrl
    // contains only the active master. To make sure a Spark driver can recover
    // in a multi-master setup, we use the "spark.master" property while submitting
    // the driver.
    val masters = sparkProperties.get("spark.master")
    val (_, masterPort) = Utils.extractHostPortFromSparkUrl(masterUrl)
    val masterRestPort = this.conf.get(config.MASTER_REST_SERVER_PORT)
    val updatedMasters = masters.map(
      _.replace(s":$masterRestPort", s":$masterPort")).getOrElse(masterUrl)
    val appArgs = request.appArgs
    // Filter SPARK_LOCAL_(IP|HOSTNAME) environment variables from being set on the remote system.
    val environmentVariables =
      request.environmentVariables.filterNot(x => x._1.matches("SPARK_LOCAL_(IP|HOSTNAME)"))
    val pyFiles = sparkProperties.get("spark.python.files")
      .map(_.split(",").toSeq).getOrElse(Seq.empty)

    // Construct driver description
    val conf = new SparkConf(false)
      .setAll(sparkProperties)
      .set("spark.master", updatedMasters)
    val extraClassPath = driverExtraClassPath.toSeq.flatMap(_.split(File.pathSeparator))
    val extraLibraryPath = driverExtraLibraryPath.toSeq.flatMap(_.split(File.pathSeparator))
    val defaultJavaOpts = driverDefaultJavaOptions.map(Utils.splitCommandString)
      .getOrElse(Seq.empty)
    val extraJavaOpts = driverExtraJavaOptions.map(Utils.splitCommandString).getOrElse(Seq.empty)
    val sparkJavaOpts = Utils.sparkJavaOpts(conf)
    val javaOpts = sparkJavaOpts ++ defaultJavaOpts ++ extraJavaOpts
    val command = new Command(
      "org.apache.spark.deploy.worker.DriverWrapper",
      Seq("{{WORKER_URL}}", "{{USER_JAR}}", mainClass) ++ appArgs, // args to the DriverWrapper
      environmentVariables, extraClassPath, extraLibraryPath, javaOpts)
    val actualDriverMemory = driverMemory.map(Utils.memoryStringToMb).getOrElse(DEFAULT_MEMORY)
    val actualDriverCores = driverCores.map(_.toInt).getOrElse(DEFAULT_CORES)
    val actualSuperviseDriver = superviseDriver.map(_.toBoolean).getOrElse(DEFAULT_SUPERVISE)
    val driverResourceReqs = ResourceUtils.parseResourceRequirements(conf,
      config.SPARK_DRIVER_PREFIX)
    new DriverDescription(
      appResource, pyFiles, actualDriverMemory, actualDriverCores, actualSuperviseDriver, command,
      driverResourceReqs)
  }

  /**
   * Handle the submit request and construct an appropriate response to return to the client.
   *
   * This assumes that the request message is already successfully validated.
   * If the request message is not of the expected type, return error to the client.
   */
  protected override def handleSubmit(
      requestMessageJson: String,
      requestMessage: SubmitRestProtocolMessage,
      responseServlet: HttpServletResponse): SubmitRestProtocolResponse = {
    requestMessage match {
      case submitRequest: CreateSubmissionRequest =>
        val driverDescription = buildDriverDescription(submitRequest)
        val response = masterEndpoint.askSync[DeployMessages.SubmitDriverResponse](
          DeployMessages.RequestSubmitDriver(driverDescription))
        val submitResponse = new CreateSubmissionResponse
        submitResponse.serverSparkVersion = sparkVersion
        submitResponse.message = response.message
        submitResponse.success = response.success
        submitResponse.submissionId = response.driverId.orNull
        val unknownFields = findUnknownFields(requestMessageJson, requestMessage)
        if (unknownFields.nonEmpty) {
          // If there are fields that the server does not know about, warn the client
          submitResponse.unknownFields = unknownFields
        }
        submitResponse
      case unexpected =>
        responseServlet.setStatus(HttpServletResponse.SC_BAD_REQUEST)
        handleError(s"Received message of unexpected type ${unexpected.messageType}.")
    }
  }
}

private[rest] class StandaloneMasterStateRequestServlet(
    masterEndpoint: RpcEndpointRef,
    conf: SparkConf)
  extends MasterStateRequestServlet {
  override protected def handleMasterState(
    responseServlet: HttpServletResponse
  ): MasterStateResponse = {
    val response = masterEndpoint.askSync[DeployMessages.MasterStateResponse](
      DeployMessages.RequestMasterState)
    val masterStateResponse = new MasterStateResponse
    masterStateResponse.workers = response.workers
    masterStateResponse.serverSparkVersion = sparkVersion
    masterStateResponse
  }
}

private[rest] class StandaloneAppIdRequestServlet(
                                                   masterEndpoint: RpcEndpointRef,
                                                   conf: SparkConf)
  extends AppIdRequestServlet {
  override protected def handleGetAppId(
                                         submissionId: String,
                                         responseServlet: HttpServletResponse
                                       ): AppIdRestResponse = {
    val response = masterEndpoint.askSync[DeployMessages.AppIdResponse](
      DeployMessages.RequestAppId(submissionId))
    val appIdResponse = new AppIdRestResponse
    appIdResponse.submissionId = submissionId
    appIdResponse.success = true
    appIdResponse.appId = response.appId.orNull
    appIdResponse.serverSparkVersion = sparkVersion
    appIdResponse
  }
}

private[rest] class StandaloneAppStatusRequestServlet(
                                                       masterEndpoint: RpcEndpointRef,
                                                       conf: SparkConf)
  extends AppStatusRequestServlet {
  override protected def handleGetAppStatus(
                                             appId: String,
                                             responseServlet: HttpServletResponse
                                           ): AppStatusRestResponse = {
    val response = masterEndpoint.askSync[DeployMessages.ApplicationStatusResponse](
      DeployMessages.RequestApplicationStatus(appId))
    val appStatusResponse = new AppStatusRestResponse
    appStatusResponse.appId = appId
    appStatusResponse.success = response.found
    appStatusResponse.appState = response.info.map(_.state.toString).orNull
    appStatusResponse.appSubmittedAt = response.info.map(_.submitDate).orNull
    appStatusResponse.appStartedAt = response.info.map(_.startTime).getOrElse(-1L)
    appStatusResponse.serverSparkVersion = sparkVersion
    appStatusResponse
  }

  override protected def handleGetAllAppStatuses(response: HttpServletResponse):
  AppStatusesRestResponse = {
    val response = masterEndpoint.askSync[DeployMessages.ApplicationStatusesResponse](
      DeployMessages.RequestApplicationStatuses)
    val statusesRestResponse = new AppStatusesRestResponse
    statusesRestResponse.statuses = response.statuses.map { info =>
      val status = new AppStatusRestResponse
      status.appId = info.id
      status.appState = info.state.toString
      status.appSubmittedAt = info.submitDate
      status.appStartedAt = info.startTime
      status.serverSparkVersion = sparkVersion
      status.success = true
      status
    }
    statusesRestResponse.success = true
    statusesRestResponse.serverSparkVersion = sparkVersion
    statusesRestResponse
  }
}
