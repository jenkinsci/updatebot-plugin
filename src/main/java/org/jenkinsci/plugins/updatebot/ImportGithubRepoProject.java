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

import hudson.Extension;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.ItemGroup;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Project;
import hudson.model.StringParameterDefinition;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import jenkins.model.Jenkins;
import jenkins.model.item_category.StandaloneProjectsCategory;
import org.jenkins.ui.icon.Icon;
import org.jenkins.ui.icon.IconSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ImportGithubRepoProject extends Project<ImportGithubRepoProject, ImportGithubRepoBuild> implements TopLevelItem {
    private static final transient Logger LOG = LoggerFactory.getLogger(ImportGithubRepoProject.class);

    public ImportGithubRepoProject(ItemGroup parent, String name) {
        super(parent, name);

        // lets default the parameters build
        ParametersDefinitionProperty property = getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            String[] pipelineChoices = {"Release", "ReleaseAndStage", "ReleaseStageAndPromote"};

            StringParameterDefinition repositoryParameter = new StringParameterDefinition("repository", "", "the github organisation and repository name in the format: myorg/myrepo");
            ChoiceParameterDefinition pipelineParameter = new ChoiceParameterDefinition("pipeline", pipelineChoices, "the pipeline to use for the project");
            property = new ParametersDefinitionProperty(repositoryParameter, pipelineParameter);
            try {
                addProperty(property);
            } catch (IOException e) {
                LOG.warn("Failed to default property " + e, e);
            }
        }
    }


    @Override
    protected Class<ImportGithubRepoBuild> getBuildClass() {
        return ImportGithubRepoBuild.class;
    }

    @Override
    public TopLevelItemDescriptor getDescriptor() {
        return (TopLevelItemDescriptor) Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends AbstractProjectDescriptor {


        static {
            IconSet.icons.addIcon(new Icon("icon-freestyle-project icon-sm", "16x16/import-github-repo.png", Icon.ICON_SMALL_STYLE));
            IconSet.icons.addIcon(new Icon("icon-freestyle-project icon-md", "24x24/import-github-repo.png", Icon.ICON_MEDIUM_STYLE));
            IconSet.icons.addIcon(new Icon("icon-freestyle-project icon-lg", "32x32/import-github-repo.png", Icon.ICON_LARGE_STYLE));
            IconSet.icons.addIcon(new Icon("icon-freestyle-project icon-xlg", "48x48/import-github-repo.png", Icon.ICON_XLARGE_STYLE));
        }

        public String getDisplayName() {
            return Messages.ImportGithubRepoProject_DisplayName();
        }

        public ImportGithubRepoProject newInstance(ItemGroup parent, String name) {
            return new ImportGithubRepoProject(parent, name);
        }

        @Override
        public String getDescription() {
            return Messages.ImportGithubRepoProject_Description();
        }

        @Override
        public String getCategoryId() {
            return StandaloneProjectsCategory.ID;
        }

        @Override
        public String getIconFilePathPattern() {
            return (Jenkins.RESOURCE_PATH + "/images/:size/import-github-repo.png").replaceFirst("^/", "");
        }

        @Override
        public String getIconClassName() {
            return "icon-import-github-repo-project";
        }

    }

}