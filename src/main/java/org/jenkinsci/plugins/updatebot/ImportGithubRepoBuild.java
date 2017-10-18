/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.updatebot;

import hudson.FilePath;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.util.PersistedList;
import io.fabric8.updatebot.Configuration;
import io.fabric8.updatebot.UpdateBot;
import io.fabric8.updatebot.commands.CommandSupport;
import io.fabric8.updatebot.commands.EnableFabric8;
import io.fabric8.updatebot.commands.StatusInfo;
import io.fabric8.utils.Strings;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.updatebot.support.JenkinsHelpers;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import static org.jenkinsci.plugins.updatebot.UpdateBotPushStepExecution.configureFromGlobalPluginConfiguration;

/**
 */
public class ImportGithubRepoBuild extends Build<ImportGithubRepoProject, ImportGithubRepoBuild> {
    private static final transient Logger LOG = LoggerFactory.getLogger(ImportGithubRepoBuild.class);

    public ImportGithubRepoBuild(ImportGithubRepoProject project) throws IOException {
        super(project);
    }

    public ImportGithubRepoBuild(ImportGithubRepoProject job, Calendar timestamp) {
        super(job, timestamp);
    }

    public ImportGithubRepoBuild(ImportGithubRepoProject project, File buildDir) throws IOException {
        super(project, buildDir);
    }

    @Override
    public void run() {
        execute(new RepoImportExecution());

    }

    protected Result doBuild(@Nonnull BuildListener listener) throws IOException {
        PrintStream logger = listener.getLogger();

        Configuration configuration = new Configuration();
        configureFromGlobalPluginConfiguration(configuration, logger);
        EnableFabric8 command = new EnableFabric8();

        String repository = null;
        String pipeline = null;
        ParametersAction parameters = getAction(ParametersAction.class);
        if (parameters != null) {
            List<ParameterValue> allParameters = parameters.getAllParameters();
            if (allParameters != null) {
                for (ParameterValue parameter : allParameters) {
                    String name = parameter.getName();
                    Object value = parameter.getValue();

                    if (value != null) {
                        switch (name) {
                            case "repository":
                                repository = value.toString();
                                break;
                            case "pipeline":
                                pipeline = value.toString();
                                break;
                            default:
                                configuration.warn(LOG, "Unknown parameter " + name + " = " + value);
                        }
                    }
                }
            }
        }
        configuration.logCommand(LOG, "importing github repository " + repository + " with pipeline " + pipeline);

        if (Strings.isNullOrBlank(repository)) {
            configuration.error(LOG, "No repository parameter found!");
            return Result.FAILURE;
        }

        // remove whitespace or / prefixes or suffixes in case they are copy/paste issues from URL fragments ;)
        repository = Strings.stripPrefix(Strings.stripSuffix(repository.trim(), "/"), "/");
        // lets let folks copy/paste a github repo URL too
        repository = Strings.stripPrefix(repository, "https://github.com/");
        try {
            command.setOrganisationAndRepository(repository);
        } catch (Exception e) {
            configuration.error(LOG, "Failed to configure github organisation/repository from " + repository + ". " + e, e);
            return Result.FAILURE;
        }
        if (Strings.notEmpty(pipeline)) {
            command.setPipeline(pipeline);
        }
        configuration.info(LOG, "Enabling fabric8 CI / CD on the repository " + repository);


        FilePath currentWorkspace = getWorkspace();
        if (currentWorkspace != null) {
            try {
                String file = currentWorkspace.toURI().toString();
                configuration.setWorkDir(file);
                configuration.setSourcePath(file);
            } catch (Exception e) {
                configuration.warn(LOG, "Failed to find the current workspace directory");
            }
        }

        try {
            command.run(configuration);
        } catch (IOException e) {
            configuration.error(LOG, "Failed to enable fabric8 CI / CD: " + e, e);
            return Result.FAILURE;
        }

        String fullJobPath = createMultiBranchProject(configuration, repository);

        Result answer = waitForPullRequestMerge(listener, configuration, command);
        if (answer != null) {
            return answer;
        }

        return triggerScanBuild(configuration, fullJobPath);
    }

    protected Result waitForPullRequestMerge(BuildListener listener, Configuration configuration, CommandSupport lastCommand) throws IOException {
        UpdateBot updatebot = new UpdateBot();
        updatebot.setConfiguration(configuration);
        updatebot.setCommand(lastCommand);

        while (true) {
            try {
                Map<String, StatusInfo> status = updatebot.poll();
                if (!StatusInfo.isPending(status)) {
                    return null;
                }
            } catch (IOException e) {
                configuration.warn(LOG, "Failed to poll PullRequests " + e, e);
            }
            // TODO how to detect we should terminate the build??
            try {
                Thread.sleep(10000L);
            } catch (InterruptedException e) {
                configuration.warn(LOG, "Build terminated: " + e, e);
                return Result.ABORTED;
            }
        }
    }

    protected String createMultiBranchProject(Configuration configuration, String repository) {
        // lets create a new Multi-Branch build!
        String[] paths = repository.split("/", 2);
        String organisation = paths[0];
        String repo = paths[1];
        Jenkins jenkins = Jenkins.getInstance();
        String parentFolderName = "GitHub";
        ItemGroup githubItemGroup = JenkinsHelpers.getOrCreateFolder(configuration, jenkins, parentFolderName);
        ModifiableTopLevelItemGroup gitHubParent = null;
        if (githubItemGroup instanceof ModifiableTopLevelItemGroup) {
            gitHubParent = (ModifiableTopLevelItemGroup) githubItemGroup;
        } else {
            configuration.warn(LOG, "Folder for GitHub was not a ModifiableTopLevelItemGroup but was " + githubItemGroup);
            gitHubParent = jenkins;
        }
        String orgJobName = parentFolderName + "/" + organisation;
        ItemGroup parentItemGroup = JenkinsHelpers.getOrCreateFolder(configuration, jenkins, orgJobName, gitHubParent);
        ModifiableTopLevelItemGroup parent = null;
        if (parentItemGroup instanceof ModifiableTopLevelItemGroup) {
            parent = (ModifiableTopLevelItemGroup) parentItemGroup;
        } else {
            configuration.warn(LOG, "Folder for " + orgJobName + " was not a ModifiableTopLevelItemGroup but was " + parentItemGroup);
            parent = jenkins;
        }

        WorkflowMultiBranchProject project = new WorkflowMultiBranchProject(parent, repo);
        PersistedList<BranchSource> sourcesList = project.getSourcesList();
        GitHubSCMSource source = new GitHubSCMSource(organisation, repo);
        source.setCredentialsId("cd-github");
        List<SCMSourceTrait> traits = new ArrayList<>();
        traits.add(new BranchDiscoveryTrait(1));
        traits.add(new OriginPullRequestDiscoveryTrait(1));
        ForkPullRequestDiscoveryTrait.TrustContributors trust = new ForkPullRequestDiscoveryTrait.TrustContributors();
        traits.add(new ForkPullRequestDiscoveryTrait(1, trust));
        traits.add(new RegexSCMHeadFilterTrait("master|PR.*"));
        source.setTraits(traits);
        BranchSource branchSource = new BranchSource(source);
        DefaultBranchPropertyStrategy strategy = new DefaultBranchPropertyStrategy(new BranchProperty[0]);
        branchSource.setStrategy(strategy);
        sourcesList.add(branchSource);
        String jobPath = parentFolderName + "/" + repository;
        JenkinsHelpers.createItem(configuration, parent, jobPath, project, "WorkflowMultiBranchProject for " + jobPath);
        return jobPath;
    }

    protected Result triggerScanBuild(Configuration configuration, String repository) {
        Jenkins jenkins = Jenkins.getInstance();
        Item item = jenkins.getItemByFullName(repository);
        if (item instanceof WorkflowMultiBranchProject) {
            WorkflowMultiBranchProject job = (WorkflowMultiBranchProject) item;
            job.scheduleBuild(0, new Cause.UserIdCause());
            configuration.info(LOG, "Triggered scan job " + repository);
        } else {
            configuration.error(LOG, "Failed to trigger scan job " + repository + " as it is not a WorkflowMultiBranchProject but is " + item);
            return Result.FAILURE;
        }
        return null;
    }

    protected class RepoImportExecution extends BuildExecution {
        @Override
        protected Result doRun(@Nonnull BuildListener listener) throws Exception {
            return doBuild(listener);
        }

        @Override
        public void cleanUp(@Nonnull BuildListener listener) throws Exception {
        }
    }

}
