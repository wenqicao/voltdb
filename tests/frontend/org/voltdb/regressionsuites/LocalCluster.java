/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package org.voltdb.regressionsuites;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.voltcore.logging.VoltLogger;
import org.voltdb.BackendTarget;
import org.voltdb.ReplicationRole;
import org.voltdb.ServerThread;
import org.voltdb.StartAction;
import org.voltdb.VoltDB;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.utils.CommandLine;
import org.voltdb.utils.MiscUtils;
import org.voltdb.utils.VoltFile;

/**
 * Implementation of a VoltServerConfig for a multi-process
 * cluster. All cluster processes run locally (keep this in
 * mind if building memory or load intensive tests.)
 */
public class LocalCluster implements VoltServerConfig {

    private enum FailureState {
        ALL_RUNNING,
        ONE_FAILURE,
        ONE_RECOVERING
    }

    // Used to provide out-of-band HostId determination.
    // NOTE: This mechanism can't be used when m_hasEmbeddedServer is enabled
    private static final String clusterHostIdProperty = "__VOLTDB_CLUSTER_HOSTID__";
    private VoltLogger m_log = new VoltLogger("HOST");

    // the timestamp salt for the TransactionIdManager
    // will vary between -3 and 3 uniformly
    private static final int TIMESTAMP_SALT_VARIANCE = 3;

    // how long to wait for startup of external procs
    private static final long PIPE_WAIT_MAX_TIMEOUT = 60 * 1000 *2; //*2 == slow machine allowance

    private String m_callingClassName = "";
    private String m_callingMethodName = "";
    private boolean m_compiled = false;
    private int m_siteCount;
    private int m_hostCount;
    private int m_kfactor = 0;
    private BackendTarget m_target;
    protected String m_jarFileName;
    private boolean m_running = false;
    private boolean m_debugPortEnabled = false;
    private FailureState m_failureState;
    private int m_perLocalClusterExtProcessIndex = 0;
    private boolean m_expectedToCrash = false;
    private boolean m_expectedToInitialize = true;

    // Dedicated paths in the filesystem to be used as a root for each process
    private ArrayList<File> m_subRoots = new ArrayList<File>();

    private boolean m_hasEmbeddedServer = true;

    final ArrayList<PipeToFile> m_pipes = new ArrayList<PipeToFile>();
    final ArrayList<Process> m_cluster = new ArrayList<Process>();
    private final ArrayList<CommandLine> m_cmdLines = new ArrayList<CommandLine>();
    private ServerThread m_localServer = null;
    private final ProcessBuilder m_procBuilder = new ProcessBuilder();
    private final ArrayList<EEProcess> m_eeProcs = new ArrayList<EEProcess>();
    // Produce a (presumably) available IP port number.
    public final PortGeneratorForTest portGenerator = new PortGeneratorForTest();
    private String m_voltdbroot = "";

    private String[] m_versionOverrides = null;
    private String[] m_versionCheckRegexOverrides = null;

    // The base command line - each process copies and customizes this.
    // Each local cluster process has a CommandLine instance configured
    // with the port numbers and command line parameter value specific to that
    // instance.
    private final CommandLine m_templateCmdLine = new CommandLine(StartAction.CREATE);

    public LocalCluster(String jarFileName,
                        int siteCount,
                        int hostCount,
                        int kfactor)
    {
        this(jarFileName, siteCount, hostCount, kfactor, BackendTarget.NATIVE_EE_JNI);
    }

    public LocalCluster(String jarFileName,
            int siteCount,
            int hostCount,
            int kfactor,
            BackendTarget target)
    {
        assert (jarFileName != null);
        assert (siteCount > 0);
        assert (hostCount > 0);

        // get the name of the calling class
        StackTraceElement[] traces = Thread.currentThread().getStackTrace();
        m_callingClassName = "UnknownClass";
        m_callingMethodName = "unknownMethod";
        //ArrayUtils.reverse(traces);
        int i;
        // skip all stack frames below this method
        for (i = 0; traces[i].getClassName().equals(getClass().getName()) == false; i++);
        // skip all stack frames from localcluster itself
        for (;      traces[i].getClassName().equals(getClass().getName()); i++);
        // skip the package name
        int dot = traces[i].getClassName().lastIndexOf('.');
        m_callingClassName = traces[i].getClassName().substring(dot + 1);
        m_callingMethodName = traces[i].getMethodName();

        m_log.info("Instantiating LocalCluster for " + jarFileName + " with class.method: " +
                m_callingClassName + "." + m_callingMethodName);
        m_log.info("Sites: " + siteCount + " hosts: " + hostCount + " replication factor: " + kfactor);

        m_cluster.ensureCapacity(hostCount);

        m_siteCount = siteCount;
        m_hostCount = hostCount;
        if (kfactor > 0 && !MiscUtils.isPro()) {
            m_kfactor = 0;
        } else {
            m_kfactor = kfactor;
        }
        m_jarFileName = jarFileName;
        m_failureState = FailureState.ALL_RUNNING;

        // if the user wants valgrind and it makes sense, give it to 'em
        // For now only one host works.
        if (isMemcheckDefined() && (target == BackendTarget.NATIVE_EE_JNI) && m_hostCount == 1) {
            // Note: For test suites that use MultiConfigSuiteBuilder to process LocalCluster configs,
            // an "isMemCheckDefined()" condition causes any LocalCluster
            // that is multi-host OR uses the HSQL backend OR has its embedded server enabled
            // to be SKIPPED.
            // So, at least for that common style of test suite, switching m_target here on
            // a LocalCluster with an embedded server enabled won't matter.
            // It MIGHT still matter in other LocalCluster scenarios, such as
            // in test suites that explicitly startUp one or more LocalClusters.
            // There DOES appear to be valgrind handling in both the embedded server and
            // non-embedded server code paths.
            m_target = BackendTarget.NATIVE_EE_VALGRIND_IPC;
        }
        else {
            m_target = target;
        }

        String buildDir = System.getenv("VOLTDB_BUILD_DIR");  // via build.xml
        if (buildDir == null) {
            buildDir = System.getProperty("user.dir") + "/obj/release";
        }

        String jzmq_dir = System.getenv("VOLTDB_JZMQ_DIR"); // via build.xml
        if (jzmq_dir == null) {
            jzmq_dir = System.getProperty("user.dir") + "/third_party/cpp/jnilib";
        }

        // set the java lib path to the one for this process - default to obj/release/nativelibs
        String java_library_path = buildDir + "/nativelibs" + ":" + jzmq_dir;
        java_library_path = System.getProperty("java.library.path", java_library_path);

        String classPath = System.getProperty("java.class.path") + ":" + buildDir
            + File.separator + m_jarFileName + ":" + buildDir + File.separator + "prod";

        // First try 'ant' syntax and then 'eclipse' syntax...
        String log4j = System.getProperty("log4j.configuration");
        if (log4j == null) {
            log4j = "file://" + System.getProperty("user.dir") + "/tests/log4j-allconsole.xml";
        }

        // set the working directory to obj/release/prod
        //m_procBuilder.directory(new File(m_buildDir + File.separator + "prod"));
        m_procBuilder.redirectErrorStream(true);

        Thread shutdownThread = new Thread(new ShutDownHookThread());
        java.lang.Runtime.getRuntime().addShutdownHook(shutdownThread);

        // Create the base command line that each process can makeCopy and modify
        m_templateCmdLine.
            addTestOptions(true).
            leader("").
            target(m_target).
            startCommand("create").
            jarFileName(VoltDB.Configuration.getPathToCatalogForTest(m_jarFileName)).
            buildDir(buildDir).
            javaLibraryPath(java_library_path).
            classPath(classPath).
            pathToLicense(ServerThread.getTestLicensePath()).
            log4j(log4j);
        m_templateCmdLine.m_noLoadLibVOLTDB = m_target == BackendTarget.HSQLDB_BACKEND;
        // "tag" this command line so it's clear which test started it
        m_templateCmdLine.m_tag = m_callingClassName + ":" + m_callingMethodName;
    }

    public void enableDebugPort() { m_debugPortEnabled = true; }

    public File getSubRoot(int index) {
        return m_subRoots.get(index);
    }

    public void disableEmbeddedServer() { m_hasEmbeddedServer = false; }

    public boolean hasEmbeddedServer() { return m_hasEmbeddedServer; }

    public void setFailureStateToOneFailure() {
        assert(m_kfactor >= 1);
        if (m_kfactor < 1) {
            return;
        }
        m_failureState = FailureState.ONE_FAILURE;
    }

    public void setFailureStateToOneRecovering() {
        assert(m_kfactor >= 1);
        if (m_kfactor < 1) {
            return;
        }
        m_failureState = FailureState.ONE_RECOVERING;
    }

    /**
     * Override the Valgrind backend with a JNI backend.
     * Called after a constructor but before startup.
     */
    public void overrideAnyRequestForValgrind() {
        if (m_templateCmdLine.m_backend == BackendTarget.NATIVE_EE_VALGRIND_IPC) {
            m_target = BackendTarget.NATIVE_EE_JNI;
            m_templateCmdLine.m_backend = BackendTarget.NATIVE_EE_JNI;
        }
    }

    public void setJavaProperty(String property, String value) {
        m_templateCmdLine.setJavaProperty(property, value);
    }

    @Override
    public void setCallingMethodName(String name) {
        m_callingMethodName = name;
    }

    @Override
    public boolean compile(VoltProjectBuilder builder) {
        if (!m_compiled) {
            m_compiled = builder.compile(m_templateCmdLine.jarFileName(), m_siteCount, m_hostCount, m_kfactor);
            m_templateCmdLine.pathToDeployment(builder.getPathToDeployment());
            m_voltdbroot = builder.getPathToVoltRoot().getAbsolutePath();
        }
        return m_compiled;
    }

    @Override
    public boolean compileWithPartitionDetection(VoltProjectBuilder builder, String snapshotPath, String ppdPrefix) {
        if (!m_compiled) {
            m_compiled = builder.compile(m_templateCmdLine.jarFileName(), m_siteCount, m_hostCount, m_kfactor,
                    null, true, snapshotPath, ppdPrefix);
            m_templateCmdLine.pathToDeployment(builder.getPathToDeployment());
            m_voltdbroot = builder.getPathToVoltRoot().getAbsolutePath();
        }
        return m_compiled;
    }

    @Override
    public boolean compileWithAdminMode(VoltProjectBuilder builder, int adminPort, boolean adminOnStartup)
    {
        // ATTN: LocalCluster does not support non-default admin ports.
        // Need a way to correctly initializing the portGenerator
        // and then resetting it after tests to the usual default.
        if (adminPort != VoltDB.DEFAULT_ADMIN_PORT) {
            return false;
        }

        if (!m_compiled) {
            m_compiled = builder.compile(m_templateCmdLine.jarFileName(), m_siteCount, m_hostCount, m_kfactor,
                    adminPort, adminOnStartup);
            m_templateCmdLine.pathToDeployment(builder.getPathToDeployment());
            m_voltdbroot = builder.getPathToVoltRoot().getAbsolutePath();
        }
        return m_compiled;
    }

    @Override
    public void startUp() {
        startUp(true);
    }

    @Override
    public void startUp(boolean clearLocalDataDirectories) {
        startUp(clearLocalDataDirectories, ReplicationRole.NONE);
    }

    public void setDeploymentAndVoltDBRoot(String pathToDeployment, String pathToVoltDBRoot) {
        m_templateCmdLine.pathToDeployment(pathToDeployment);
        m_voltdbroot = pathToVoltDBRoot;
        m_compiled = true;
    }

    public void setHostCount(int hostCount)
    {
        m_hostCount = hostCount;
        // Force recompilation
        m_compiled = false;
    }

    private void startLocalServer(int hostId, boolean clearLocalDataDirectories) {
        // Generate a new root for the in-process server if clearing directories.
        File subroot = null;
        if (clearLocalDataDirectories) {
            try {
                subroot = VoltFile.initNewSubrootForThisProcess();
                m_subRoots.add(subroot);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            subroot = m_subRoots.get(0);
        }

        // Make the local Configuration object...
        CommandLine cmdln = (m_templateCmdLine.makeCopy());
        cmdln.setJavaProperty(clusterHostIdProperty, String.valueOf(hostId));
        cmdln.internalPort(portGenerator.nextInternalPort());
        cmdln.voltFilePrefix(subroot.getPath());
        cmdln.internalPort(portGenerator.nextInternalPort());
        cmdln.port(portGenerator.nextClient());
        cmdln.adminPort(portGenerator.nextAdmin());
        cmdln.zkport(portGenerator.nextZkPort());
        // replication port and its two automatic followers.
        cmdln.drAgentStartPort(portGenerator.nextReplicationPort());
        portGenerator.nextReplicationPort();
        portGenerator.nextReplicationPort();
        if (m_target == BackendTarget.NATIVE_EE_VALGRIND_IPC) {
            EEProcess proc = m_eeProcs.get(0);
            assert(proc != null);
            cmdln.m_ipcPort = proc.port();
        }
        if (m_target == BackendTarget.NATIVE_EE_IPC) {
            cmdln.m_ipcPort = portGenerator.next();
        }
        if ((m_versionOverrides != null) && (m_versionOverrides.length > 0)) {
            assert(m_versionOverrides[0] != null);
            assert(m_versionCheckRegexOverrides[0] != null);
            cmdln.m_versionStringOverrideForTest = m_versionOverrides[0];
            cmdln.m_versionCompatibilityRegexOverrideForTest = m_versionCheckRegexOverrides[0];
        }

        // for debug, dump the command line to a unique file.
        // cmdln.dumpToFile("/Users/rbetts/cmd_" + Integer.toString(portGenerator.next()));

        m_cluster.add(null);
        m_pipes.add(null);
        m_cmdLines.add(cmdln);
        m_localServer = new ServerThread(cmdln);
        m_localServer.start();
    }

    private boolean waitForAllReady()
    {
        if (!m_expectedToInitialize) {
            return true;
        }
        long startOfPipeWait = System.currentTimeMillis();
        boolean allReady = false;
        do {
            if ((System.currentTimeMillis() - startOfPipeWait) > PIPE_WAIT_MAX_TIMEOUT) {
                break;
            }

            allReady = true;
            for (PipeToFile pipeToFile : m_pipes) {
                if (pipeToFile == null) {
                    continue;
                }
                synchronized(pipeToFile) {
                    // if process is dead, no point in waiting around
                    if (isProcessDead(pipeToFile.getProcess())) {
                        // dead process means the other pipes won't start,
                        // so bail here
                        return false;
                    }

                    // if eof, then no point in waiting around
                    if (pipeToFile.m_eof.get()) {
                        continue;
                    }

                    // if not eof, then wait for statement of readiness
                    if (pipeToFile.m_witnessedReady.get() != true) {
                        try {
                            // use a timeout to prevent a forever hang
                            pipeToFile.wait(250);
                        }
                        catch (InterruptedException ex) {
                            m_log.error(ex.toString(), ex);
                        }
                        allReady = false;
                    }
                }
            }
        } while (allReady == false);
        return allReady;
    }

    private void printTiming(boolean logtime, String msg) {
        if (logtime) {
            System.out.println("************ " + msg);
        }
    }

    public void startUp(boolean clearLocalDataDirectories, ReplicationRole role) {
        assert (!m_running);
        if (m_running) {
            return;
        }

        // needs to be called before any call to pick a filename
        VoltDB.setDefaultTimezone();

        // set 'replica' option -- known here for the first time.
        m_templateCmdLine.replicaMode(role);

        // set to true to spew startup timing data
        boolean logtime = false;
        long startTime = 0;
        printTiming(logtime, "Starting cluster at: " + System.currentTimeMillis());

        // clear any logs, export or snapshot data for this run
        if (clearLocalDataDirectories) {
            try {
                m_subRoots.clear();
                VoltFile.deleteAllSubRoots();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // reset the port generator. RegressionSuite always expects
        // to find ClientInterface and Admin mode on known ports.
        portGenerator.reset();
        m_templateCmdLine.leaderPort(portGenerator.nextInternalPort());

        m_eeProcs.clear();
        for (int ii = 0; ii < m_hostCount; ii++) {
            String logfile = "LocalCluster_host_" + ii + ".log";
            m_eeProcs.add(new EEProcess(m_templateCmdLine.target(), m_siteCount, logfile));
        }

        m_pipes.clear();
        m_cluster.clear();
        m_cmdLines.clear();
        int oopStartIndex = 0;

        // create the in-process server instance.
        if (m_hasEmbeddedServer) {
            startLocalServer(oopStartIndex, clearLocalDataDirectories);
            ++oopStartIndex;
        }

        // create all the out-of-process servers
        for (int i = oopStartIndex; i < m_hostCount; i++) {
            startOne(i, clearLocalDataDirectories, role, StartAction.CREATE);
        }

        printTiming(logtime, "Pre-witness: " + (System.currentTimeMillis() - startTime) + "ms");
        boolean allReady = waitForAllReady();
        printTiming(logtime, "Post-witness: " + (System.currentTimeMillis() - startTime) + "ms");

        // verify all processes started up and count failures
        int downProcesses = 0;
        for (Process proc : m_cluster) {
            if ((proc != null) && (isProcessDead(proc))) {
                downProcesses++;
            }
        }

        // throw an exception if there were failures starting up
        if ((downProcesses > 0) || (allReady == false)) {
            // poke all the external processes to die (no guarantees)
            for (Process proc : m_cluster) {
                if (proc != null) {
                    try { proc.destroy(); } catch (Exception e) {}
                }
            }

            if (downProcesses > 0) {
                int expectedProcesses = m_hostCount - (m_hasEmbeddedServer ? 1 : 0);
                if (!m_expectedToCrash) {
                    throw new RuntimeException(
                            String.format("%d/%d external processes failed to start",
                            downProcesses, expectedProcesses));
                }
            }
            // this error case should only be from a timeout
            else if (!allReady) {
                throw new RuntimeException(
                        "One or more external processes failed to complete initialization.");
            }
        }

        // Finally, make sure the local server thread is running and wait if it is not.
        if (m_hasEmbeddedServer) {
            m_localServer.waitForInitialization();
        }

        printTiming(logtime, "DONE: " + (System.currentTimeMillis() - startTime) + " ms");
        m_running = true;

        // if supposed to kill a server, it's go time
        if (m_failureState != FailureState.ALL_RUNNING) {
            killOne();
        }

        // after killing a server, bring it back in recovery mode
        if (m_failureState == FailureState.ONE_RECOVERING) {
            int hostId = m_hasEmbeddedServer ? 1 : 0;
            recoverOne(logtime, startTime, hostId, null, "", StartAction.REJOIN);
        }
    }

    private void killOne()
    {
        m_log.info("Killing one cluster member.");
        int procIndex = 0;
        if (m_hasEmbeddedServer) {
            procIndex = 1;
        }

        Process proc = m_cluster.get(procIndex);
        proc.destroy();
        int retval = 0;
        try {
            retval = proc.waitFor();
            EEProcess eeProc = m_eeProcs.get(procIndex);
            eeProc.waitForShutdown();
        } catch (InterruptedException e) {
            m_log.info("External VoltDB process is acting crazy.");
        } finally {
            m_cluster.set(procIndex, null);
        }
        // exit code 143 is the forcible shutdown code from .destroy()
        if (retval != 0 && retval != 143) {
            m_log.info("killOne: External VoltDB process terminated abnormally with return: " + retval);
        }
    }

    private void startOne(int hostId, boolean clearLocalDataDirectories, ReplicationRole replicaMode, StartAction startAction)
    {
        PipeToFile ptf = null;
        CommandLine cmdln = (m_templateCmdLine.makeCopy());
        cmdln.setJavaProperty(clusterHostIdProperty, String.valueOf(hostId));
        try {
            cmdln.internalPort(portGenerator.nextInternalPort());
            // set the dragent port. it uses the start value and
            // the next two sequential port numbers - so burn those two.
            cmdln.drAgentStartPort(portGenerator.nextReplicationPort());
            portGenerator.next();
            portGenerator.next();

            // add the ipc ports
            if (m_target == BackendTarget.NATIVE_EE_IPC) {
                // set 1 port for the EE process
                cmdln.ipcPort(portGenerator.next());
            }
            if (m_target == BackendTarget.NATIVE_EE_VALGRIND_IPC) {
                EEProcess proc = m_eeProcs.get(hostId);
                assert(proc != null);
                cmdln.m_ipcPort = proc.port();
            }

            cmdln.port(portGenerator.nextClient());
            cmdln.adminPort(portGenerator.nextAdmin());
            cmdln.replicaMode(replicaMode);
            cmdln.timestampSalt(getRandomTimestampSalt());

            if (m_debugPortEnabled) {
                cmdln.debugPort(portGenerator.next());
            }

            cmdln.zkport(portGenerator.nextZkPort());

            if (startAction == StartAction.JOIN) {
                cmdln.startCommand(startAction);
                int portNoToRejoin = m_cmdLines.get(0).internalPort();
                cmdln.leader(":" + portNoToRejoin);
            }

            // If local directories are being cleared
            // generate a new subroot, otherwise reuse the existing directory
            File subroot = null;
            if (clearLocalDataDirectories) {
                subroot = VoltFile.getNewSubroot();
                m_subRoots.add(subroot);
            } else {
                if (m_subRoots.size() <= hostId) {
                    m_subRoots.add(VoltFile.getNewSubroot());
                }
                subroot = m_subRoots.get(hostId);
            }
            cmdln.voltFilePrefix(subroot.getPath());
            cmdln.voltRoot(subroot.getPath() + "/" + m_voltdbroot);

            if ((m_versionOverrides != null) && (m_versionOverrides.length > hostId)) {
                assert(m_versionOverrides[hostId] != null);
                assert(m_versionCheckRegexOverrides[hostId] != null);
                cmdln.m_versionStringOverrideForTest = m_versionOverrides[hostId];
                cmdln.m_versionCompatibilityRegexOverrideForTest = m_versionCheckRegexOverrides[hostId];
            }

            m_cmdLines.add(cmdln);
            m_procBuilder.command().clear();
            List<String> cmdlnList = cmdln.createCommandLine();
            String cmdLineFull = "Start cmd host=" + String.valueOf(hostId) + " :";
            for (String element : cmdlnList) {
                assert(element != null);
                cmdLineFull += " " + element;
            }
            m_log.info(cmdLineFull);
            m_procBuilder.command().addAll(cmdlnList);

            // write output to obj/release/testoutput/<test name>-n.txt
            // this may need to be more unique? Also very useful to just
            // set this to a hardcoded path and use "tail -f" to debug.
            String testoutputdir = cmdln.buildDir() + File.separator + "testoutput";
            System.out.println("Process output will be redirected to: " + testoutputdir);
            // make sure the directory exists
            File dir = new File(testoutputdir);
            if (dir.exists()) {
                assert (dir.isDirectory());
            } else {
                boolean status = dir.mkdirs();
                assert (status);
            }

            File dirFile = new VoltFile(testoutputdir);
            if (dirFile.listFiles() != null) {
                for (File f : dirFile.listFiles()) {
                    if (f.getName().startsWith(getName() + "-" + hostId)) {
                        f.delete();
                    }
                }
            }

            Process proc = m_procBuilder.start();
            m_cluster.add(proc);
            String fileName = testoutputdir
                    + File.separator
                    + "LC-"
                    + getFileName() + "-"
                    + hostId + "-"
                    + "idx" + String.valueOf(m_perLocalClusterExtProcessIndex++)
                    + ".txt";
            System.out.println("Process output can be found in: " + fileName);
            ptf = new PipeToFile(
                    fileName,
                    proc.getInputStream(),
                    startAction == StartAction.JOIN ? PipeToFile.m_joinToken : PipeToFile.m_initToken,
                    false,
                    proc);
            m_pipes.add(ptf);
            ptf.setName("ClusterPipe:" + String.valueOf(hostId));
            ptf.start();
        }
        catch (IOException ex) {
            m_log.error("Failed to start cluster process:" + ex.getMessage(), ex);
            assert (false);
        }

        if (startAction == StartAction.JOIN) {
            waitOnPTFReady(ptf, true, System.currentTimeMillis(), System.currentTimeMillis(), hostId);
        }

        if (hostId > (m_hostCount - 1)) {
            m_hostCount++;
            this.m_compiled = false; //Host count changed, should recompile
        }
    }

    /**
     * Use the weird portable java way to figure out if a cluster is alive
     */
    private boolean isProcessDead(Process p) {
        try {
            p.exitValue();
            return true; // if no exception, process died
        }
        catch (IllegalThreadStateException e) {
            return false; // process is still alive
        }
    }

    public boolean recoverOne(int hostId, Integer portOffset, String rejoinHost, boolean liveRejoin) {
        return recoverOne(
                false,
                0,
                hostId,
                portOffset,
                rejoinHost,
                liveRejoin ? StartAction.LIVE_REJOIN : StartAction.REJOIN);
    }

    public void joinOne(int hostId) {
        startOne(hostId, true, ReplicationRole.NONE, StartAction.JOIN);
    }

    public boolean recoverOne(int hostId, Integer portOffset, String rejoinHost) {
        return recoverOne(false, 0, hostId, portOffset, rejoinHost, StartAction.REJOIN);
    }

    // Re-start a (dead) process. HostId is the enumberation of the host
    // in the cluster (0, 1, ... hostCount-1) -- not an hsid, for example.
    private boolean recoverOne(boolean logtime, long startTime, int hostId, Integer rejoinHostId,
                               String rejoinHost, StartAction startAction) {

        // Lookup the client interface port of the rejoin host
        // I have no idea why this code ignores the user's input
        // based on other state in this class except to say that whoever wrote
        // it this way originally probably eats kittens and hates cake.
        if (rejoinHostId == null || m_hasEmbeddedServer) {
            rejoinHostId = 0;
        }

        int portNoToRejoin = m_cmdLines.get(rejoinHostId).internalPort();

        m_log.info("Rejoining " + hostId + " to hostID: " + rejoinHostId);

        // rebuild the EE proc set.
        EEProcess eeProc = m_eeProcs.get(hostId);
        try {
            eeProc.waitForShutdown();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (m_templateCmdLine.target().isIPC) {
            String logfile = "LocalCluster_host_" + hostId + ".log";
            m_eeProcs.set(hostId, new EEProcess(m_templateCmdLine.target(), m_siteCount, logfile));
        }

        PipeToFile ptf = null;
        long start = 0;
        try {
            CommandLine rejoinCmdLn = m_cmdLines.get(hostId);
            // some tests need this
            rejoinCmdLn.javaProperties = m_templateCmdLine.javaProperties;
            rejoinCmdLn.startCommand(startAction);

            // This shouldn't collide but apparently it sucks.
            // Bump it to avoid collisions on rejoin.
            if (m_debugPortEnabled) {
                rejoinCmdLn.debugPort(portGenerator.next());
            }
            rejoinCmdLn.leader(rejoinHost + ":" + String.valueOf(portNoToRejoin));

            rejoinCmdLn.m_port = portGenerator.nextClient();
            rejoinCmdLn.m_adminPort = portGenerator.nextAdmin();
            rejoinCmdLn.m_zkInterface = "127.0.0.1:" + portGenerator.next();
            rejoinCmdLn.m_internalPort = portGenerator.nextInternalPort();
            setPortsFromConfig(hostId, rejoinCmdLn);

            if ((m_versionOverrides != null) && (m_versionOverrides.length > hostId)) {
                assert(m_versionOverrides[hostId] != null);
                assert(m_versionCheckRegexOverrides[hostId] != null);
                rejoinCmdLn.m_versionStringOverrideForTest = m_versionOverrides[hostId];
                rejoinCmdLn.m_versionCompatibilityRegexOverrideForTest = m_versionCheckRegexOverrides[hostId];
            }

            List<String> rejoinCmdLnStr = rejoinCmdLn.createCommandLine();
            String cmdLineFull = "Rejoin cmd line:";
            for (String element : rejoinCmdLnStr) {
                cmdLineFull += " " + element;
            }
            m_log.info(cmdLineFull);

            m_procBuilder.command().clear();
            m_procBuilder.command().addAll(rejoinCmdLnStr);
            Process proc = m_procBuilder.start();
            start = System.currentTimeMillis();

            // write output to obj/release/testoutput/<test name>-n.txt
            // this may need to be more unique? Also very useful to just
            // set this to a hardcoded path and use "tail -f" to debug.
            String testoutputdir = rejoinCmdLn.buildDir() + File.separator + "testoutput";
            // make sure the directory exists
            File dir = new File(testoutputdir);
            if (dir.exists()) {
                assert(dir.isDirectory());
            }
            else {
                boolean status = dir.mkdirs();
                assert(status);
            }

            ptf = new PipeToFile(
                    testoutputdir +
                    File.separator +
                    "LC-" +
                    getFileName() + "-" +
                    hostId + "-" +
                    "idx" + String.valueOf(m_perLocalClusterExtProcessIndex++) +
                    ".rejoined.txt",
                    proc.getInputStream(),
                    PipeToFile.m_rejoinToken, true, proc);
            synchronized (this) {
                m_pipes.set(hostId, ptf);
                // replace the existing dead proc
                m_cluster.set(hostId, proc);
            }
            Thread t = new Thread(ptf);
            t.setName("ClusterPipe:" + String.valueOf(hostId));
            t.start();
        }
        catch (IOException ex) {
            m_log.error("Failed to start recovering cluster process:" + ex.getMessage(), ex);
            assert (false);
        }

        return waitOnPTFReady(ptf, logtime, startTime, start, hostId);
    }

    /*
     * Wait for the PTF to report initialization/rejoin
     */
    private boolean waitOnPTFReady(PipeToFile ptf, boolean logtime, long startTime, long start, int hostId) {
        // wait for the joining site to be ready
        synchronized (ptf) {
            if (logtime) System.out.println("********** pre witness: " + (System.currentTimeMillis() - startTime) + " ms");
            while (ptf.m_witnessedReady.get() != true) {
                // if eof, then no point in waiting around
                if (ptf.m_eof.get())
                    break;

                // if process is dead, no point in waiting around
                if (isProcessDead(ptf.getProcess()))
                    break;

                try {
                    // wait for explicit notification
                    ptf.wait(1000);
                }
                catch (InterruptedException ex) {
                    m_log.error(ex.toString(), ex);
                }
            }
        }
        if (ptf.m_witnessedReady.get()) {
            long finish = System.currentTimeMillis();
            m_log.info("Took " + (finish - start) +
                     " milliseconds, time from init was " + (finish - ptf.m_initTime));
            return true;
        } else {
            m_log.info("Recovering process exited before recovery completed");
            try {
                silentKillSingleHost(hostId, true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return false;
        }
    }

    @Override
    synchronized public void shutDown() throws InterruptedException {
        // there are couple of ways to shutdown. sysproc @kill could be
        // issued to listener. this would require that the test didn't
        // break the cluster somehow.  Or ... just old fashioned kill?

        try {
            if (m_localServer != null) {
                m_localServer.shutdown();
            }
        }
        catch (Exception e) {
            m_log.error("Failure to shutdown LocalCluster's in-process VoltDB server.", e);
        }
        finally {
            m_running = false;
        }
        shutDownExternal();
    }

    public void killSingleHost(int hostNum) throws InterruptedException
    {
        m_log.info("Killing " + hostNum);
        silentKillSingleHost(hostNum, false);
    }

    private void silentKillSingleHost(int hostNum, boolean forceKillEEProcs) throws InterruptedException {
        Process proc = null;
        //PipeToFile ptf = null;
        EEProcess eeProc = null;
        PipeToFile ptf;
        synchronized (this) {
           proc = m_cluster.get(hostNum);
           //ptf = m_pipes.get(hostNum);
           m_cluster.set(hostNum, null);
           ptf = m_pipes.get(hostNum);
           m_pipes.set(hostNum, null);
           if (m_eeProcs.size() > hostNum) {
               eeProc = m_eeProcs.get(hostNum);
           }
        }

        if (ptf != null && ptf.m_filename != null) {
            //new File(ptf.m_filename).delete();
        }
        if (proc != null) {
            proc.destroy();
            proc.waitFor();
        }

        // if (ptf != null) {
        //     new File(ptf.m_filename).delete();
        // }

        if (eeProc != null) {
            if (forceKillEEProcs) {
                eeProc.destroy();
            }
            eeProc.waitForShutdown();
        }
    }

    public void shutDownExternal() throws InterruptedException {
        shutDownExternal(false);
    }

    public synchronized void shutDownExternal(boolean forceKillEEProcs)
    {
        if (m_cluster != null) {
            // kill all procs
            for (Process proc : m_cluster) {
                if (proc == null)
                    continue;
                proc.destroy();
            }

            // join on all procs
            for (Process proc : m_cluster) {
                if (proc == null)
                    continue;
                int retval = 0;
                try {
                    retval = proc.waitFor();
                }
                catch (InterruptedException e) {
                    m_log.error("Unable to wait for Localcluster process to die: " + proc.toString(), e);
                }
                // exit code 143 is the forcible shutdown code from .destroy()
                if (retval != 0 && retval != 143)
                {
                    m_log.error("External VoltDB process terminated abnormally with return: " + retval);
                }
            }
        }

        if (m_cluster != null) m_cluster.clear();

        for (EEProcess proc : m_eeProcs) {
            try {
                proc.waitForShutdown();
            } catch (InterruptedException e) {
                m_log.error("Unable to wait for EEProcess to die: " + proc.toString(), e);
            }
        }

        if (m_templateCmdLine.target() == BackendTarget.NATIVE_EE_VALGRIND_IPC) {
            if (!EEProcess.m_valgrindErrors.isEmpty()) {
                String failString = "";
                for (final String error : EEProcess.m_valgrindErrors) {
                    failString = failString + "\n" + error;
                }
                org.junit.Assert.fail(failString);
            }
        }

        m_eeProcs.clear();
    }

    @Override
    public String getListenerAddress(int hostId) {
        if (!m_running) {
            return null;
        }
        for (int i = 0; i < m_cmdLines.size(); i++) {
            CommandLine cl = m_cmdLines.get(i);
            String hostIdStr = cl.getJavaProperty(clusterHostIdProperty);

            if (hostIdStr.equals(String.valueOf(hostId))) {
                Process p = m_cluster.get(i);
                // if the process is alive, or is the in-process server
                if ((p != null) || (i == 0 && m_hasEmbeddedServer)) {
                    return "localhost:" + cl.m_port;
                }
            }
        }
        return null;
    }

    @Override
    public List<String> getListenerAddresses() {
        if (!m_running) {
            return null;
        }
        ArrayList<String> listeners = new ArrayList<String>();
        for (int i = 0; i < m_cmdLines.size(); i++) {
            CommandLine cl = m_cmdLines.get(i);
            Process p = m_cluster.get(i);
            // if the process is alive, or is the in-process server
            if ((p != null) || (i == 0 && m_hasEmbeddedServer)) {
                listeners.add("localhost:" + cl.m_port);
            }
        }
        return listeners;
    }

    @Override
    public String getName() {
        String prefix = "localCluster";
        if (m_failureState == FailureState.ONE_FAILURE)
            prefix += "OneFail";
        if (m_failureState == FailureState.ONE_RECOVERING)
            prefix += "OneRecov";
        return prefix +
            "-" + String.valueOf(m_siteCount) +
            "-" + String.valueOf(m_hostCount) +
            "-" + m_templateCmdLine.target().display.toUpperCase();
    }

    private String getFileName() {
        String prefix = m_callingClassName + "-" + m_callingMethodName;
        if (m_failureState == FailureState.ONE_FAILURE)
            prefix += "-OneFail";
        if (m_failureState == FailureState.ONE_RECOVERING)
            prefix += "-OneRecov";
        return prefix +
            "-" + String.valueOf(m_siteCount) +
            "-" + String.valueOf(m_hostCount) +
            "-" + m_templateCmdLine.target().display.toUpperCase();
    }

    @Override
    public int getNodeCount()
    {
        return m_hostCount;
    }

    public boolean isSinglePartitionConfig() { return (m_hostCount * m_siteCount) == (m_kfactor + 1); }

    public int getSiteCount() { return m_siteCount; }

    public int getKfactor() { return m_kfactor; }

    public boolean areAllNonLocalProcessesDead() {
        for (Process proc : m_cluster){
            try {
                if (proc != null) {
                    proc.exitValue();
                }
            }
            catch (IllegalThreadStateException ex) {
                return false;
            }
        }
        return true;
    }

    public int getLiveNodeCount() {
        int count = 0;
        if (m_hasEmbeddedServer) {
            count++;
        }

        if (m_cluster != null) {
            for (Process proc : m_cluster) {
                try {
                    if (proc != null) {
                        proc.exitValue();
                    }
                }
                catch (IllegalThreadStateException ex) {
                    // not dead yet!
                    count++;
                }
            }
        }

        return count;
    }

    public int getBlessedPartitionDetectionProcId() {
        int currMin = Integer.MAX_VALUE;
        int currMinIdx = 0;
        for (int i = 0; i < m_pipes.size(); i++) {
            PipeToFile p = m_pipes.get(i);
            System.out.println("Index " + i + " had hostid: " + p.getHostId());
            if (p.getHostId() < currMin) {
                currMin = p.getHostId();
                currMinIdx = i;
                System.out.println("Setting index: " + i + " to blessed.");
            }
        }
        return currMinIdx;
    }

    @Override
    public void finalize() throws Throwable {
        try {
            shutDownExternal();
        }
        finally {
            super.finalize();
        }
    }

    private class ShutDownHookThread implements Runnable {
        @Override
        public void run() {
            shutDownExternal(true);
        }
    }

    @Override
    public boolean isHSQL() {
        return m_templateCmdLine.target() == BackendTarget.HSQLDB_BACKEND;
    }

    public void setOverridesForHotfix(String[] versions, String[] regexOverrides) {
        assert(versions != null);
        assert(regexOverrides != null);
        assert(versions.length == regexOverrides.length);

        m_versionOverrides = versions;
        m_versionCheckRegexOverrides = regexOverrides;
    }

    @Override
    public void setMaxHeap(int heap) {
        m_templateCmdLine.setMaxHeap(heap);
    }

    public String getPathToDeployment() {
        return m_templateCmdLine.pathToDeployment();
    }

    public String zkinterface(int hostId) {
        return m_cmdLines.get(hostId).zkinterface();
    }

    public int drAgentStartPort(int hostId) {
        return m_cmdLines.get(hostId).drAgentStartPort();
    }

    public int internalPort(int hostId) {
        return m_cmdLines.get(hostId).internalPort();
    }

    public int port(int hostId) {
        return m_cmdLines.get(hostId).port();
    }

    public int adminPort(int hostId) {
        return m_cmdLines.get(hostId).adminPort();
    }

    public void setPortsFromConfig(int hostId, VoltDB.Configuration config) {
        CommandLine cl = m_cmdLines.get(hostId);
        assert(cl != null);
        cl.m_port = config.m_port;
        cl.m_adminPort = config.m_adminPort;
        cl.m_zkInterface = config.m_zkInterface;
        cl.m_internalPort = config.m_internalPort;
        cl.m_leader = config.m_leader;
    }

    public static boolean isMemcheckDefined() {
        final String buildType = System.getenv().get("BUILD");
        if (buildType == null) {
            return false;
        }
        return buildType.toLowerCase().startsWith("memcheck");
    }

    @Override
    public boolean isValgrind() {
        return m_templateCmdLine.m_backend == BackendTarget.NATIVE_EE_VALGRIND_IPC;
    }

    @Override
    public void createDirectory(File path) throws IOException {
        for (File root : m_subRoots) {
            File actualPath = new File(root, path.getPath());
            if (!actualPath.mkdirs()) {
                throw new IOException();
            }
        }
    }

    @Override
    public void deleteDirectory(File path) throws IOException {
        for (File root : m_subRoots) {
            File actualPath = new File(root, path.getPath());
            VoltFile.recursivelyDelete(actualPath);
        }
    }

    @Override
    public ArrayList<File> listFiles(File path) throws IOException {
        ArrayList<File> files = new ArrayList<File>();
        for (File root : m_subRoots) {
            File actualPath = new File(root, path.getPath());
            for (File f : actualPath.listFiles()) {
                files.add(f);
            }
        }
        return files;
    }

    @Override
    public File[] getPathInSubroots(File path) throws IOException {
        File retval[] = new File[m_subRoots.size()];
        for (int ii = 0; ii < m_subRoots.size(); ii++) {
            retval[ii] = new File(m_subRoots.get(ii), path.getPath());
        }
        return retval;
    }

    /**
     * @return the m_expectedToCrash
     */
    public boolean isExpectedToCrash() {
        return m_expectedToCrash;
    }

    /**
     * @param m_expectedToCrash the m_expectedToCrash to set
     */
    public void setExpectedToCrash(boolean expectedToCrash) {
        this.m_expectedToCrash = expectedToCrash;
    }

    /**
     * @return the m_expectedToInitialize
     */
    public boolean isExpectedToInitialize() {
        return m_expectedToInitialize;
    }

    /**
     * @param m_expectedToInitialize the m_expectedToInitialize to set
     */
    public void setExpectedToInitialize(boolean expectedToInitialize) {
        m_expectedToInitialize = expectedToInitialize;
    }

    /**
     * @param watcher watcher to attach to active output pipes
     */
    public void setOutputWatcher(OutputWatcher watcher) {
        for (PipeToFile pipe : m_pipes) {
            if (pipe != null) {
                pipe.setWatcher(watcher);
            }
        }
    }

    private static int getRandomTimestampSalt() {
        Random r = new Random();
        // if variance is 3, get a range between 0 and 6 inclusive
        int retval = r.nextInt(TIMESTAMP_SALT_VARIANCE * 2 + 1);
        // shift that range so it goes from -3 to 3 inclusive
        retval -= TIMESTAMP_SALT_VARIANCE;
        return retval;
    }

}
