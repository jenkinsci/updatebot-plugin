/*
 * Copyright (C) 2015 Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins.updatebot;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.EnvironmentSpecific;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.ansicolor.AnsiHelper;
import hudson.security.ACL;
import hudson.slaves.NodeSpecific;
import hudson.tasks.Maven;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import io.fabric8.updatebot.Configuration;
import io.fabric8.updatebot.UpdateBot;
import io.fabric8.updatebot.commands.PushSourceChanges;
import io.fabric8.updatebot.commands.StatusInfo;
import io.fabric8.utils.Strings;
import jenkins.util.Timer;
import org.acegisecurity.Authentication;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.updatebot.support.PollComplete;
import org.jenkinsci.plugins.updatebot.support.SystemHelper;
import org.jenkinsci.plugins.updatebot.support.ToolInfo;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UpdateBotPushStepExecution extends AbstractStepExecutionImpl {
    public static final String JDK = "JDK";
    public static final String MAVEN = "Maven";
    public static final String NODE_JS = "NodeJS";
    private static final transient Logger LOG = LoggerFactory.getLogger(UpdateBotPushStepExecution.class);
    private static final long serialVersionUID = 1L;

    @Inject
    private transient UpdateBotPushStep step;
    private transient Future<?> task;
    private transient boolean invokedPush;
    private transient boolean shouldStop;

    private transient TaskListener listener;
    private transient PrintStream logger;
    private transient UpdateBot updatebot;
    private transient FilePath workspace;
    private transient boolean useReflection = false;
    private transient Exception failed;

    public UpdateBotPushStepExecution(UpdateBotPushStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    public static List<DomainRequirement> githubDomainRequirements(String apiUri) {
        return URIRequirementBuilder.fromUri(StringUtils.defaultIfEmpty(apiUri, "https://github.com")).build();
    }

    public static CredentialsMatcher githubScanCredentialsMatcher() {
        // TODO OAuth credentials
        return CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));
    }

    public static PrintStream configureFromGlobalPluginConfiguration(Configuration configuration, PrintStream logger) throws IOException {
        GlobalPluginConfiguration config = GlobalPluginConfiguration.get();

        if (config.isUseAnsiColor()) {
            logger = new PrintStream(AnsiHelper.createAnsiStream(logger), true, Charset.defaultCharset().name());
            logger.println("Using Ansi Color logging!");
        }

        configuration.setPrintStream(logger);
        configuration.setUseHttpsTransport(true);

        String jenkinsfileLibraryGitCloneURL = config.getJenkinsfileLibraryGitCloneURL();
        if (Strings.notEmpty(jenkinsfileLibraryGitCloneURL)) {
            configuration.setJenksinsfileGitRepo(jenkinsfileLibraryGitCloneURL);
        }
        String credentialsId = config.getCredentialsId();
        UsernamePasswordCredentials usernamePasswordCredentials = null;
        if (Strings.notEmpty(credentialsId)) {
            Item context = null;
            Authentication authentication = ACL.SYSTEM;
/*
            Authentication authentication = context instanceof Queue.Task
                    ? Tasks.getDefaultAuthenticationOf((Queue.Task) context)
                    : ACL.SYSTEM;
*/
            StandardUsernameCredentials credentials = null;
            try {
                credentials = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentials(
                                StandardUsernameCredentials.class,
                                context,
                                authentication,
                                githubDomainRequirements("")
                        ),
                        CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId), githubScanCredentialsMatcher())
                );
            } catch (Exception e) {
                configuration.error(LOG, "looking up credentials: " + e, e);
            }

            //logger.println("Found credentials " + credentials + " for " + credentialsId);

            if (credentials == null) {
                throw new IOException("Could not find the credentials " + credentialsId + ". Please check the UpdateBot configuration on the Manage Jenkins page!");
            }
            if (credentials instanceof UsernamePasswordCredentials) {
                usernamePasswordCredentials = (UsernamePasswordCredentials) credentials;
            } else {
                throw new IOException("The chosen credential " + credentialsId + " has no username and password! Please choose another credential on the UpdateBot section of the Manage Jenkins page!");
            }
        } else {
            throw new IOException("No credentials configured for the UpdateBot plugin! Please update the configuration on the Manage Jenkins page!");
        }
        configuration.setGithubUsername(usernamePasswordCredentials.getUsername());
        configuration.setGithubPassword(usernamePasswordCredentials.getPassword().getPlainText());
        return logger;
    }

    @Override
    public boolean start() throws Exception {
        shouldStop = false;
        invokedPush = false;
        task = Timer.get().submit(createUpdateBotPoller());
        return false;
    }

    @Override
    public void stop(@Nonnull Throwable cause) throws Exception {
        shouldStop = true;
        if (task != null) {
            task.cancel(true);
            getContext().onFailure(cause);
        }
    }

    protected void pollUpdateBot() throws IOException {
        PollComplete complete = null;
        if (!invokedPush) {
            complete = runUpdateBotCommand();
            invokedPush = true;
        } else {
            complete = pollUpdateBotStatus();
        }
        if (failed != null) {
            return;
        }
        if (complete != null) {
            complete.apply(getContext(), getLogger());
        } else {
            scheduleNextPoll();
        }
    }

    protected PollComplete runUpdateBotCommand() throws IOException {
        String file = step.getFile();
        FilePath currentWorkspace = getWorkspace();
        if (currentWorkspace != null) {
            FilePath configFile = currentWorkspace;
            try {
                file = configFile.toURI().toString();
            } catch (Exception e) {
                file = configFile.toString();
            }
        }
        updatebot = new UpdateBot();
        Configuration configuration = updatebot.getConfiguration();
        configureUpdateBot(configuration);
        configuration.setSourcePath(file);
        PushSourceChanges command = new PushSourceChanges();
        updatebot.setCommand(command);

        command.run(configuration);
        // TODO would we ever return complete immediately?
        return null;
    }

    /**
     * Runs the updatebot poll operation to check if any pending PRs have completed or need rebasing
     *
     * @return true if the all the pull requests and issues are completed
     */
    protected PollComplete pollUpdateBotStatus() {
        try {
            boolean pending = false;
            Collection<StatusInfo> statusInfos = updatebot.poll().values();
            for (StatusInfo statusInfo : statusInfos) {
                if (statusInfo.isPending()) {
                    pending = true;
                }
            }
            if (!pending) {
                return PollComplete.success(null);
            }
            return null;
        } catch (Exception e) {
            return PollComplete.failure(e);
        }
    }

    protected void configureUpdateBot(Configuration configuration) throws IOException {
        PrintStream logger = getLogger();
        configureFromGlobalPluginConfiguration(configuration, logger);

        Set<String> tools = new HashSet<>(Arrays.asList(JDK, MAVEN, NODE_JS));

        Map<String, ToolInfo> toolInfoMap = new HashMap<>();

        for (ToolDescriptor<?> desc : ToolInstallation.all()) {
            String displayName = desc.getDisplayName();
            if (tools.contains(displayName)) {
                //getLogger().println("Found tool " + displayName);
                ToolInfo toolInfo = new ToolInfo();
                EnvVars envVars = new EnvVars();
                if (desc instanceof Maven.MavenInstallation.DescriptorImpl) {
                    Maven.MavenInstallation.DescriptorImpl descriptor = (Maven.MavenInstallation.DescriptorImpl) desc;
                }
                ToolInstallation[] installations = desc.getInstallations();
                boolean installs = false;
                if (installations != null) {
                    for (ToolInstallation tool : installations) {
                        try {
                            if (tool instanceof NodeSpecific) {
                                tool = (ToolInstallation) ((NodeSpecific<?>) tool).forNode(getContext().get(Node.class), getContext().get(TaskListener.class));
                            }
                            if (tool instanceof EnvironmentSpecific) {
                                tool = (ToolInstallation) ((EnvironmentSpecific<?>) tool).forEnvironment(getContext().get(EnvVars.class));
                            }
                            toolInfo.setHome(tool.getHome());
                            tool.buildEnvVars(envVars);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                }
/*
                if (!toolInfo.hasHome()) {
                    List<? extends ToolInstaller> defaultInstallers = desc.getDefaultInstallers();
                    if (defaultInstallers != null) {
                        for (ToolInstaller installer : defaultInstallers) {
                            getLogger().println("Has default installer for " + displayName + " " + installer);
                            getLogger().println("Installing tool " + displayName + " " + installer);

                            if (installer instanceof Maven.MavenInstaller) {
                                Maven.MavenInstaller mavenInstaller = (Maven.MavenInstaller) installer;
                                DownloadFromUrlInstaller.Installable installable = mavenInstaller.getInstallable();
                                System.out.println("Installable : "+ installable);
                            }
                            try {
                                Node node = getContext().get(Node.class);
                                TaskListener log = getContext().get(TaskListener.class);
                                ToolInstallation tool = null;
                                FilePath filePath = installer.performInstallation(tool, node, log);
                                if (filePath != null) {
                                    getLogger().println("Installed at " + filePath);
                                    toolInfo.setHome(new File(filePath.toURI()).getAbsolutePath());
                                }
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }
                    }
                }
*/
                toolInfoMap.put(displayName, toolInfo);
                if (toolInfo.hasHome()) {
                    logger.println(displayName + " at " + toolInfo.getHome());
                    break;
                } else if (!installs) {
                    logger.println(displayName + " as no installations");
                }
            }
        }
        ToolInfo mavenInfo = toolInfoMap.get(MAVEN);
        ToolInfo nodeInfo = toolInfoMap.get(NODE_JS);
        ToolInfo javaInfo = toolInfoMap.get(JDK);
        String suffix = SystemHelper.isWindows() ? ".cmd" : "";
        if (mavenInfo != null && mavenInfo.hasHome()) {
            String mvn = new File(mavenInfo.getHome(), "bin/mvn" + suffix).getCanonicalPath();
            Map<String, String> envVarMap = mavenInfo.getEnvVarMap();
            if (javaInfo != null) {
                Map<String, String> javaInfoEnvVarMap = javaInfo.getEnvVarMap();
                envVarMap.putAll(javaInfoEnvVarMap);
            } else {
                configuration.warn(LOG, "no Java tool found so cannot set the JAVA environment variables required for maven!");
            }
            logger.println("Using mvn executable: " + mvn + " with env vars: " + envVarMap);
            configuration.setMvnCommand(mvn);
            configuration.setMvnEnvironmentVariables(envVarMap);
        } else {
            configuration.warn(LOG, "no Maven installation found! May not be able to update maven projects. To fix please use the Manage Jenkins -> Global Tool Configuration and add a Maven installation");
        }
        if (nodeInfo != null && nodeInfo.hasHome()) {
            String npm = new File(nodeInfo.getHome(), "bin/npm" + suffix).getCanonicalPath();
            Map<String, String> envVarMap = nodeInfo.getEnvVarMap();
            logger.println("Using npm executable: " + npm + " with env vars: " + envVarMap);
            configuration.setNpmCommand(npm);
            configuration.setNpmEnvironmentVariables(envVarMap);
        } else {
            configuration.warn(LOG, "no NodeJS installation found! May not be able to update node projects. To fix please use the Manage Jenkins -> Global Tool Configuration and add a NodeJS installation");
        }
    }

    protected void scheduleNextPoll() {
        if (shouldStop) {
            getLogger().println("UpdateBot is terminating");
            return;
        }
        ScheduledExecutorService timer = Timer.get();
        if (step == null) {
            warnMissingField("step");
            return;
        }
        task = timer.schedule(createUpdateBotPoller(), step.getPollPeriodMS(), TimeUnit.MILLISECONDS);
    }

    protected void warnMissingField(String field) {
        getLogger().println("Missing field: " + field);
    }

    protected PrintStream getLogger() {
        if (logger == null) {
            try {
                this.listener = getContext().get(TaskListener.class);
            } catch (Exception e) {
                // ignore
            }
            if (listener != null) {
                this.logger = listener.getLogger();
            }
            if (logger == null) {
                logger = System.out;
            }
        }
        return logger;
    }

    protected Runnable createUpdateBotPoller() {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    pollUpdateBot();
                } catch (Exception e) {
                    PrintStream logger = getLogger();
                    logger.println("Failed to create poller: " + e);
                    e.printStackTrace(logger);
                    failed = e;
                }

                if (failed != null) {
                    try {
                        stop(failed);
                    } catch (Exception e1) {
                        // ignore
                    }
                }
            }
        };
    }

    protected FilePath getWorkspace() {
        if (workspace == null) {
            try {
                this.workspace = getContext().get(FilePath.class);
            } catch (Exception e) {
                getLogger().println("Could not find the FilePath!");
            }
        }
        return workspace;
    }
}
