/*
 * Copyright (c) 2012 Kiril Raychev
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

import org.linkedin.glu.agent.api.ShellExecException

/**
 * The purpose of this glu script is to deploy (in an atomic fashion), a tomcat container and some
 * webapps.
 *
 * <p>
 * This glu script has been tested with tomcat v7.0.29
 *
 * <p>
 * This glu script uses the following <code>initParameters</code>:
 * <ul>
 * <li>
 * skeleton: a url pointing to where the tar ball for tomcat is located. Required.
 * Example: http://www.us.apache.org/dist/tomcat/tomcat-7/v7.0.29/bin/apache-tomcat-7.0.29.tar.gz
 * Note that in production, you should not point to this distribution directly but instead point to a local repo.
 * </li>
 * <li>
 * port: which port should tomcat run on. Optional (default to 8080)
 * </li>
 * <li>
 * shutdownPort: shutdown port for tomcat. Optional (default to port + 200)
 * </li>
 * <li>
 * maxThreads: max number of threads in tomcat thread pool (default to 150)
 * </li>
 * <li>
 * minSpareThreads: minimal number of free threads (default to 4)
 * </li>
 * <li>
 * unpackWARs: should wars be unpacked (default to true)
 * </li>
 * <li>
 * autoDeploy: should new or changed wars be deployed automatically (default to true)
 * </li>
 * <li>
 * webapps: an array of webapps entries with each entry being a map:
 * <pre>
 *    contextPath: "/cp1" // where to 'mount' the webapp in tomcat (required)
 *    war: a url pointing to the war (similar to skeleton value) (required)
 *    monitor: "/monitor" // a path (relative to contextPath!) for monitoring (optional)
 * </pre>
 * </li>
 * <li>
 * serverMonitorFrequency: how often to run the monitor. Optional (default to 15s)
 * </li>
 * <li>
 * startTimeout: how long to wait for declaring the server up. Optional (default to
 * <code>null</code> which means forever). Note that 'forever' is usually the right value
 * as you can always interrupt an action if it takes an abnormal long time to execute.
 * </li>
 * <li>
 * stopTimeout: how long to wait for declaring the server down. Optional (default to
 * <code>null</code> which means forever). Note that 'forever' is usually the right value
 * as you can always interrupt an action if it takes an abnormal long time to execute.
 * </li>
 * </ul>
 *
 * <p>Here is an example of system representing those values (json format)
 * <pre>
 * "initParameters": {
 *  "port": 9000,
 *  "skeleton": http://www.us.apache.org/dist/tomcat/tomcat-7/v7.0.29/bin/apache-tomcat-7.0.29.tar.gz,
 *  "webapps": [
 *    {
 *      "contextPath": "/cp1",
 *      "monitor": "/monitor",
 *      "war": "http://localhost:8080/glu/repository/wars/org.linkedin.glu.samples.sample-webapp-1.6.0-SNAPSHOT.war"
 *    },
 *    {
 *      "contextPath": "/cp2",
 *      "monitor": "/monitor",
 *      "war": "http://localhost:8080/glu/repository/wars/org.linkedin.glu.samples.sample-webapp-1.6.0-SNAPSHOT.war"
 *    }
 *  ]
 *}
 * </pre>
 */
class TomcatGluScript
{
  // this is how you express a dependency on a given agent version (it is a min requirement, meaning
  // if the agent is at least v1.6.0 then this glu script can run in it
  static requires = {
    agent(version: '1.6.0')
  }

  /*******************************************************
   * Script state
   *******************************************************/

  // the following fields represent the state of the script and will be exported to ZooKeeper
  // automatically thus will be available in the console or any other program 'listening' to
  // ZooKeeper

  // this @script.version@ is replaced at build time
  def version = '@script.version@'
  def serverRoot
  def serverCmd
  def pidCmd
  def logsDir
  def serverLog
  def pid
  def ajpPort
  def port
  def webapps

  /*******************************************************
   * install phase
   *******************************************************/

  // * log, shell and mountPoint are 3 'variables' available in any glu script
  // * note how we use 'mountPoint' for the tomcat installation. It is done this way because the
  // agent automatically cleans up whatever goes in mountPoint on uninstall. Also mountPoint is
  // guaranteed to be unique so it is a natural location to install the software which allows
  // to install more than one instance of it on a given machine/agent.
  // * every file system call (going through shell.xx methods) is always relative to wherever
  // the agent apps folder was configured

  def install = {
    log.info "Installing..."

    // fetching/installing tomcat
    def tomcatSkeleton = shell.fetch(params.skeleton)
    def distribution = shell.untar(tomcatSkeleton)
    shell.rmdirs(mountPoint)
    serverRoot = shell.mv(shell.ls(distribution)[0], mountPoint)

    // assigning variables
    logsDir = serverRoot.'logs'
    serverLog = logsDir.'catalina.out'

    // the tar ball contains some default webapps that we don't want
    shell.ls(serverRoot.'webapps').each {
        shell.rmdirs(it)
    }

    // make sure all bin/*.sh files are executable
    shell.ls(serverRoot.bin) {
      include(name: '*.sh')
    }.each { shell.chmodPlusX(it) }

    log.info "Install complete."
  }

  /*******************************************************
   * configure phase
   *******************************************************/

  // in this phase we set up a timer which will monitor the server. The reason why it is setup
  // in the configure phase rather than the start phase is because this way we can both detect
  // when the server goes down and up! (for example if you kill it on the command line and
  // restart it without going through glu, the monitor will detect it)

  def configure = {
    log.info "Configuring..."

    // first we configure the server
    configureServer()

    // second we configure the apps
    configureWebapps()

    // setting up a timer to monitor the server
    timers.schedule(timer: serverMonitor,
                    repeatFrequency: params.serverMonitorFrequency ?: '15s')

    log.info "Configuration complete."
  }

  /*******************************************************
   * start phase
   *******************************************************/
  def start = {
    log.info "Starting..."

    shell.exec("${serverCmd} start > /dev/null 2>&1 &")

    // we wait for the process to be started (should be quick)
    shell.waitFor(timeout: '5s', heartbeat: '250') {
      pid = isProcessUp()
    }

    // now that the process should be up, we wait for the server to be up
    // when tomcat starts, it also starts all the contexts and only then start listening on
    // the port... this will effectively wait for all the apps to be up!
    shell.waitFor(timeout: params.startTimeout, heartbeat: '1s') { duration ->
      log.info "${duration}: Waiting for server to be up"

      // we check if the server is down already... in which case we throw an exception
      if(isProcessDown())
        shell.fail("Server could not start. Check the log file for errors.")

      return isServerUp()
    }

    if(checkWebapps() == 'dead')
    {
      shell.fail("Webapps did not deploy properly, server has been shutdown. Check the log file for errors.")
    }
    else
    {
      log.info "Started tomcat on port ${port}."
    }
  }

  /*******************************************************
   * stop phase
   *******************************************************/
  def stop = { args ->
    log.info "Stopping..."

    doStop()

    log.info "Stopped."
  }

  /*******************************************************
   * unconfigure phase
   *******************************************************/

  // we remove the timer set in the configure phase

  def unconfigure = {
    log.info "Unconfiguring..."

    timers.cancel(timer: serverMonitor)

    port = null

    log.info "Unconfiguration complete."
  }

  /*******************************************************
   * uninstall phase
   *******************************************************/

  // note that since it does nothing, it can simply be removed. It is there just to enforce the
  // fact that it really does nothing. Indeed the agent will automatically clean up after this
  // phase and delete whatever was installed under 'mountPoint'

  def uninstall = {
    // nothing
  }

  // a closure called by the rest of the code but not by the agent directly
  private def doStop = {
    if(isProcessDown())
    {
      log.info "Server already down."
    }
    else
    {
      // invoke the stop command
      shell.exec("${serverCmd} stop")

      // we wait for the process to be stopped
      shell.waitFor(timeout: params.stopTimeout, heartbeat: '1s') { duration ->
        log.info "${duration}: Waiting for server to be down"
        isProcessDown()
      }
    }

    pid = null
  }

  // a method called by the rest of the code but not by the agent directly

  // why use closure vs method ? the rule is simple: if you are modifying any field (the ones
  // defined at the top of this file), then use a closure otherwise the update won't make it to
  // ZooKeeper.

  private Integer isProcessUp()
  {
    try
    {
      def output = shell.exec("${pidCmd}")
      def result = output as int
      if (result == 0)
      {
          result = null
      }
      return result
    }
    catch(ShellExecException e)
    {
      return null
    }
  }

  private Integer isServerUp()
  {
    Integer pid = isProcessUp()
    if(pid && shell.listening('localhost', port))
      return pid
    else
      return null
  }

  private boolean isProcessDown()
  {
    isProcessUp() == null
  }

  private def configureServer = {

    serverCmd = shell.saveContent(serverRoot.'bin/catalina-ctl.sh', DEFAULT_CATALINA_CTL)
    shell.chmodPlusX(serverCmd)
    pidCmd = shell.saveContent(serverRoot.'bin/get-pid.sh', GET_PID_CMD)
    shell.chmodPlusX(pidCmd)
    
    
    port = (params.port ?: 8080) as int
    ajpPort = port + 30000
    def shutdownPort = (params.shutdownPort ?: port + 200) as int
    
    def maxThreads = (params.maxThreads ?: 150) as int
    def minSpareThreads = (params.minSpareThreads ?: 4) as int
    
    def unpackWARs = (params.unpackWARs ?: true) as boolean
    def autoDeploy = (params.autoDeploy ?: true) as boolean
    
    def params = [
        port : port,
        shutdownPort : shutdownPort,
        maxThreads : maxThreads,
        minSpareThreads: minSpareThreads,
        unpackWARs : unpackWARs,
        autoDeploy : autoDeploy
        ]
    
 	shell.saveContent(serverRoot.'bin/setenv.sh', DEFAULT_CATALINA_OPTS, [xmx:'1800m',xms:'512m',newSize:'256m',maxNewSize:'256m',permSize:'256m',maxPermSize:'256m'])
    shell.saveContent(serverRoot.'conf/server.xml', DEFAULT_SERVER_XML, params)
  }
  
  private def configureWebapps = {
    def w = params.webapps ?: []

    // case when only one webapp provided as a map
    if(w instanceof Map)
      w = [w]

    def ws = [:]

    w.each {
      def webapp = configureWebapp(it)
      if(ws.containsKey(webapp.contextPath))
        shell.fail("deplicate contextPath ${webapp.contextPath}")
      ws[webapp.contextPath] = webapp
    }

    webapps = ws
  }

  private def configureWebapp = { webapp ->
    if(webapp.war)
      configureWar(webapp)
    else
    {
      if(webapp.resources)
        configureResources(webapp)
      else
        shell.fail("cannot configure webapp: ${webapp}")
    }
  }

  private def configureWar = { webapp ->
    String contextPath = (webapp.contextPath ?: '/').toString()
    String name = null
    if (contextPath.equals('/'))
    {
        name = 'ROOT'
    }
    else
    {
        name = contextPath
        if (name.startsWith('/'))
        {
            name = contextPath.substring(1)
        }
        if (name.indexOf('/') != -1)
        {
            shell.fail("cannot have nested contextPath ${contextPath}")
        }
    }
    
    def war = shell.fetch(webapp.war, serverRoot."webapps/${name}.war")
    

    return [
      remoteWar: webapp.war,
      localWar: war,
      contextPath: contextPath,
      monitor: webapp.monitor
    ]
  }

  private def configureResources = { webapp ->
    // TODO : add configuration for resource only wars
  }

  /**
   * @return a map of failed apps. The map is empty if there is none. The key is the context path
   * and the value can be 'busy', 'dead' or 'unknown' if in the process of being deployed
   */
  private Map<String, String> getFailedWebapps()
  {
    Map<String, String> failedWebapps = [:]

    // when no webapps at all there is no need to talk to the server
    if(!webapps)
      return failedWebapps

    webapps.keySet().each { String contextPath ->
      def monitor = webapps[contextPath]?.monitor
      if(monitor)
      {
        try
        {
          def head = shell.httpHead("http://localhost:${port}${contextPath}${monitor}")

          switch(head.responseCode)
          {
            case 200:
              failedWebapps.remove(contextPath)
              break

            case 503:
              if(head.responseMessage == 'BUSY')
                failedWebapps[contextPath] = 'busy'
              else
                failedWebapps[contextPath] = 'dead'
              break

            default:
              log.warn "Unexpected response code: ${head.responseCode} for ${contextPath}"
              failedWebapps[contextPath] = 'dead'
          }
        }
        catch(IOException e)
        {
          log.debug("Could not talk to ${contextPath}", e)
          failedWebapps[contextPath] = 'dead'
        }
      }
    }

    return failedWebapps
  }

  /**
   * @return 'ok' if all apps are good, 'dead' if any app is dead, 'busy' if any app is busy,
   *         otherwise 'unknown' (which is when the apps are in the process of being deployed)
   */
  private String checkWebapps()
  {
    def failedApps = getFailedWebapps()

    if(failedApps.isEmpty())
      return 'ok'

    if(failedApps.values().find { it == 'dead' })
    {
      log.warn ("Failed apps: ${failedApps}. Shutting down server...")
      doStop()
      return 'dead'
    }
    else
    {
      if(failedApps.values().find { it == 'busy'} )
        return 'busy'
    }

    return 'unknown'
  }

  /**
   * Check that both server and webapps are up
   */
  private def checkServerAndWebapps = {
    def up = [server: false, webapps: 'unknown']

    pid = isServerUp()
    up.server = pid != null
    if(up.server)
      up.webapps = checkWebapps()

    return up
  }

  /**
   * Defines the timer that will check for the server to be up and running and will act
   * according if not (change state)
   */
  def serverMonitor = {
    try
    {
      def up = checkServerAndWebapps()

      def currentState = stateManager.state.currentState
      def currentError = stateManager.state.error

      def newState = null
      def newError = null

      // case when current state is running
      if(currentState == 'running')
      {
        if(!up.server || up.webapps == 'dead')
        {
          newState = 'stopped'
          pid = null
          newError = 'Server down detected. Check the log file for errors.'
          log.warn "${newError} => forcing new state ${newState}"
        }
        else
        {
          if(up.webapps == 'busy')
          {
            newError = 'Server is up but some webapps are busy. Check the log file for errors.'
            if(newError != currentError)
            {
              newState = 'running' // remain running but set in error
              log.warn newError
            }
          }
          else
          {
            if(up.webapps == 'ok' && currentError)
            {
              newState = 'running' // remain running
              log.info "All webapps are up, clearing error status."
            }
          }
        }
      }
      else
      {
        if(up.server && up.webapps == 'ok')
        {
          newState = 'running'
          log.info "Server up detected."
        }
      }

      if(newState)
        stateManager.forceChangeState(newState, newError)

      log.debug "Server Monitor: ${stateManager.state.currentState} / ${up}"
    }
    catch(Throwable th)
    {
      log.warn "Exception while running serverMonitor: ${th.message}"
      log.debug("Exception while running serverMonitor (ignored)", th)
    }
  }

  static String DEFAULT_CATALINA_CTL = """#!/bin/bash

DIR="\$( cd "\$( dirname "\${BASH_SOURCE[0]}" )" && pwd )"
cd \${DIR}

CATALINA_PID="../work/tomcat.pid" ./catalina.sh \$@
"""

  static String GET_PID_CMD = """#!/bin/bash

DIR="\$( cd "\$( dirname "\${BASH_SOURCE[0]}" )" && pwd )"
cd \${DIR}
PID_FILE="../work/tomcat.pid"
if [ -e \${PID_FILE} ]
then
    PID=`cat \${PID_FILE}`
    R=`ps aux|grep \${PID}|grep -v grep|awk '{print \$2}'`
    if [ ! -z "\${R}" ]
    then
        for P in \${R}
        do
            if [ \${PID} -eq \${P} ]
            then
                echo "\${PID}"
                exit 0;
            fi;
        done;
    else
        echo "0"
        exit 1;
    fi; 
fi;

echo "0"
exit 1;
"""
  

  static String DEFAULT_SERVER_XML = """<?xml version='1.0' encoding='utf-8'?>
<Server port="@shutdownPort@" shutdown="SHUTDOWN">
  <Listener className="org.apache.catalina.core.AprLifecycleListener" SSLEngine="on" />
  <Listener className="org.apache.catalina.core.JasperListener" />
  <Listener className="org.apache.catalina.mbeans.ServerLifecycleListener" />
  <Listener className="org.apache.catalina.mbeans.GlobalResourcesLifecycleListener" />

  <GlobalNamingResources>
    <Resource name="UserDatabase" auth="Container"
              type="org.apache.catalina.UserDatabase"
              description="User database that can be updated and saved"
              factory="org.apache.catalina.users.MemoryUserDatabaseFactory"
              pathname="conf/tomcat-users.xml" />
  </GlobalNamingResources>

  <Service name="Catalina">
    <Connector port="@port@" protocol="HTTP/1.1" enableLookups="false"
               connectionTimeout="20000" />
    <Connector port="@ajpPort@" protocol="AJP/1.3" enableLookups="false" />
    <Executor name="tomcatThreadPool" namePrefix="catalina-exec-"
        maxThreads="@maxThreads@" minSpareThreads="@minSpareThreads@"/>
    <Engine name="Catalina" defaultHost="localhost">
      <Realm className="org.apache.catalina.realm.UserDatabaseRealm"
             resourceName="UserDatabase"/>
      <Host name="localhost"  appBase="webapps"
            unpackWARs="@unpackWARs@" autoDeploy="@auto"
            xmlValidation="false" xmlNamespaceAware="false">
      </Host>
    </Engine>
  </Service>
</Server>
"""

    static String DEFAULT_CATALINA_OPTS = "export CATALINA_OPTS=\"-Djava.awt.headless=true -Dfile.encoding=UTF-8 -server -Xms@xms@ -Xmx@xmx@ -XX:NewSize=@newSize@ -XX:MaxNewSize=@maxNewSize@ -XX:PermSize=@permSize@ -XX:MaxPermSize=@maxPermSize@ -XX:+DisableExplicitGC\""
}
