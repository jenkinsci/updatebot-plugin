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

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import static org.jenkinsci.plugins.updatebot.UpdateBotPushStepExecution.githubDomainRequirements;
import static org.jenkinsci.plugins.updatebot.UpdateBotPushStepExecution.githubScanCredentialsMatcher;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {
    private String credentialsId;
    private boolean useAnsiColor = true;

    @DataBoundConstructor
    public GlobalPluginConfiguration(String credentialsId) {
        this.credentialsId = credentialsId;
        configChange();
    }

    public GlobalPluginConfiguration() {
        load();
        configChange();
        save();
    }

    public static GlobalPluginConfiguration get() {
        return GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
    }

    @Override
    public String getDisplayName() {
        return "UpdateBot Configuration";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws hudson.model.Descriptor.FormException {
        req.bindJSON(this, json);
        configChange();
        save();
        return true;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    private void configChange() {
    }

    public ListBoxModel doFillCredentialsIdItems(@CheckForNull @AncestorInPath Item context,
                                                 @QueryParameter String apiUri,
                                                 @QueryParameter String credentialsId) {
        if (context == null
                ? !Jenkins.getActiveInstance().hasPermission(Jenkins.ADMINISTER)
                : !context.hasPermission(Item.EXTENDED_READ)) {
            return new StandardListBoxModel().includeCurrentValue(credentialsId);
        }
        return listScanCredentials(context, apiUri);
    }

    private ListBoxModel listScanCredentials(Item context, String apiUri) {
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        context instanceof Queue.Task
                                ? Tasks.getDefaultAuthenticationOf((Queue.Task) context)
                                : ACL.SYSTEM,
                        context,
                        StandardUsernameCredentials.class,
                        githubDomainRequirements(apiUri),
                        githubScanCredentialsMatcher()
                );
    }

    public boolean isUseAnsiColor() {
        return useAnsiColor;
    }

    public void setUseAnsiColor(boolean useAnsiColor) {
        this.useAnsiColor = useAnsiColor;
    }
}
