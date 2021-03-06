package au.org.massive.strudel_web.ssh;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A non-native SSH client implmentation that forks SSH processes for each request.
 * Depends on an SSH binary in the search path. Certificates are written to disk at the beginning of each command
 * and deleted upon command completion.
 *
 * @author jrigby
 */
public class ForkedSSHClient extends AbstractSSHClient {

    private final static Logger logger = LogManager.getLogger(ForkedSSHClient.class);
    private final CertAuthInfo authInfo;

    public ForkedSSHClient(CertAuthInfo authInfo, String remoteHost) {
        super(authInfo, remoteHost);
        this.authInfo = authInfo;
    }

    public ForkedSSHClient(CertAuthInfo authInfo, String viaGateway, String remoteHost) {
        super(authInfo, viaGateway, remoteHost);
        this.authInfo = authInfo;
    }

    private class CertFiles implements Closeable {
        private final File tempDirectory;
        private final File privKeyFile;
        private final File certFile;

        public CertFiles(CertAuthInfo authInfo) throws IOException {
            super();
            Path tempDirectoryPath = Files.createTempDirectory("ssh-authz-" + authInfo.getUserName());
            privKeyFile = tempDirectoryPath.resolve("id_rsa").toFile();
            privKeyFile.createNewFile();
            certFile = tempDirectoryPath.resolve("id_rsa-cert.pub").toFile();
            tempDirectory = tempDirectoryPath.toFile();
            certFile.createNewFile();

            PrintWriter out = new PrintWriter(privKeyFile);
            out.println(authInfo.getPrivateKey());
            out.close();

            out = new PrintWriter(certFile);
            out.println(authInfo.getCertificate());
            out.close();

            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            Files.setPosixFilePermissions(privKeyFile.toPath(), perms);
        }

        public File getPrivKeyFile() {
            return privKeyFile;
        }

        @SuppressWarnings("unused")
        public File getCertFile() {
            return certFile;
        }

        @Override
        public void close() throws IOException {
            privKeyFile.delete();
            certFile.delete();
            tempDirectory.delete();
        }
    }

    private int findFreePort() throws IOException {
        ServerSocket s = new ServerSocket(0);
        int freePort = s.getLocalPort();
        s.close();
        return freePort;
    }

    /**
     * Starts an SSH tunnel
     *
     * @param remotePort the port to forward
     * @param maxUptimeInSeconds the maximum length of time for the tunnel to remain open. Zero represents infinity.
     * @return a {@link Tunnel} object
     * @throws IOException thrown on errors reading data streams
     */
    public Tunnel startTunnel(final int remotePort, int maxUptimeInSeconds) throws IOException {
        final int localPort = findFreePort();
        final ExecuteWatchdog watchdog;
        if (maxUptimeInSeconds < 1) {
            watchdog = new ExecuteWatchdog(ExecuteWatchdog.INFINITE_TIMEOUT);
        } else {
            watchdog = new ExecuteWatchdog((long) maxUptimeInSeconds * 1000);
        }
        getExecutorService().submit(new Callable<String>() {

            @Override
            public String call() throws Exception {
                Map<String, String> tunnelFlags = new HashMap<>();
                tunnelFlags.put("-L" + localPort + ":"+getRemoteHost()+":" + remotePort, "");
                return exec("sleep infinity", tunnelFlags, watchdog);
            }

        });

        return new Tunnel() {

            @Override
            public int getLocalPort() {
                return localPort;
            }

            @Override
            public int getRemotePort() {
                return remotePort;
            }

            @Override
            public String getRemoteHost() {
                return ForkedSSHClient.this.getRemoteHost();
            }

            @Override
            public void stopTunnel() {
                watchdog.destroyProcess();
            }

            @Override
            public boolean isRunning() {
                return watchdog.isWatching();
            }

        };
    }

    /**
     * Executes a remote command
     *
     * @param remoteCommands commands to execute
     * @param watchdog       can be used to kill runaway processes
     * @return the command results
     */
    @Override
    public String exec(String remoteCommands, ExecuteWatchdog watchdog) throws IOException, SSHExecException {
        return exec(remoteCommands, null, watchdog);
    }

    /**
     * Exeucte a remote command
     *
     * @param remoteCommands commands to execute
     * @param extraFlags     a map of flags and arguments for the SSH process; map values are allowed to be empty or null
     * @param watchdog can be used to kill runaway processes
     * @return the command results
     * @throws IOException thrown on errors reading data streams
     * @throws SSHExecException thrown on errors caused during SSH command execution
     */
    public String exec(String remoteCommands, Map<String, String> extraFlags, ExecuteWatchdog watchdog) throws IOException, SSHExecException {
        CertFiles certFiles = new CertFiles(authInfo);

        CommandLine cmdLine = new CommandLine("ssh");
        cmdLine.addArgument("-q");
        cmdLine.addArgument("-i");
        cmdLine.addArgument(certFiles.getPrivKeyFile().getAbsolutePath());
        cmdLine.addArgument("-oStrictHostKeyChecking=no"); // TODO: Remove me when ready
        cmdLine.addArgument("-oBatchMode=yes");
        cmdLine.addArgument("-oKbdInteractiveAuthentication=no");
        cmdLine.addArgument("-l");
        cmdLine.addArgument(getAuthInfo().getUserName());
        cmdLine.addArgument(getViaGateway());

        // Add extra flags
        if (extraFlags != null && extraFlags.size() > 0) {
            for (String key : extraFlags.keySet()) {
                String value = extraFlags.get(key);
                cmdLine.addArgument(key);
                if (value != null && value.length() > 0) {
                    cmdLine.addArgument(value);
                }
            }
        }

        boolean hasCommands = remoteCommands != null && remoteCommands.length() > 0;
        if (hasCommands) {
            cmdLine.addArgument("bash");
            cmdLine.addArgument("-s");
            cmdLine.addArgument("--");
        } else {
            remoteCommands = "";
        }

        ByteArrayInputStream input = new ByteArrayInputStream(remoteCommands.getBytes());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Executor exec = getForkedProcessExecutor(watchdog);
        exec.setStreamHandler(new PumpStreamHandler(output, output, input));
        try {
            exec.execute(cmdLine);
        } catch (ExecuteException e) {
            String error = "SSH command failed for user "+this.authInfo.getUserName()+": "+cmdLine.toString()+"; "+
                    "Remote commands: "+remoteCommands+"; "+
                    "Remote server said: " + output.toString();
            error = error.replaceAll("[\\t\\n\\r]"," ");
            logger.error(error);
            throw new SSHExecException(output.toString(), e);
        } finally {
            certFiles.close();
        }
        return output.toString();
    }

}
