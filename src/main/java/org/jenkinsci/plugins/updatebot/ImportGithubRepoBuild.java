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

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.plugins.git.RevisionParameterAction;
import hudson.util.PersistedList;
import io.fabric8.updatebot.Configuration;
import io.fabric8.updatebot.commands.EnableFabric8;
import io.fabric8.utils.Strings;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchPropertyStrategy;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.mixin.ChangeRequestSCMHead2;
import jenkins.scm.api.trait.SCMHeadAuthority;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import jenkins.scm.impl.trait.WildcardSCMHeadFilterTrait;
import org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceRequest;
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.updatebot.support.JenkinsHelpers;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jenkins.scm.api.SCMSourceOwner;

import jenkins.plugins.git.AbstractGitSCMSource;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static java.util.logging.Level.SEVERE;
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
                    logger.println("Found " + name + " = " + value);

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

        if (Strings.isNullOrBlank(repository)) {
            configuration.error(LOG, "No repository parameter found!");
            return Result.FAILURE;
        }
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

        try {
            command.run(configuration);
        } catch (IOException e) {
            configuration.error(LOG, "Failed to enable fabric8 CI / CD: " + e, e);
            return Result.FAILURE;
        }

        // lets create a new Multi-Branch build!
        String[] paths = repository.split("/", 2);
        String organisation = paths[0];
        String repo = paths[1];
        Jenkins jenkins = Jenkins.getInstance();
        Folder parent = (Folder) JenkinsHelpers.getOrCreateFolder(configuration, jenkins, organisation);

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
        JenkinsHelpers.createItem(configuration, parent, repository, project, "WorkflowMultiBranchProject for " + repository);

        // now lets trigger a scan
        List<Action> buildActions = new ArrayList<Action>();
        buildActions.add(new CauseAction(new Cause.UserIdCause()));
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
