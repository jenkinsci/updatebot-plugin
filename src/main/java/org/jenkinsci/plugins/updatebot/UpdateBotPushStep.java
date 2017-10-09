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

import com.google.common.collect.ImmutableSet;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.util.Set;

public class UpdateBotPushStep extends Step {
    private static final long serialVersionUID = 1L;

    private static final long DEFAULT_POLL_PERIOD = 15000L;

    private String file;
    private long pollPeriodMS = DEFAULT_POLL_PERIOD;

    @DataBoundConstructor
    public UpdateBotPushStep() {
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new UpdateBotPushStepExecution(this, context);
    }

    public String getFile() {
        return file;
    }

    @DataBoundSetter
    public void setFile(String file) {
        this.file = file;
    }

    public long getPollPeriodMS() {
        return pollPeriodMS;
    }

    @DataBoundSetter
    public void setPollPeriodMS(long pollPeriodMS) {
        this.pollPeriodMS = pollPeriodMS;
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        public DescriptorImpl() {
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(Run.class, Launcher.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "updateBotPush";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.UpdateBotPushStep_DescriptorImpl_DisplayName();
        }
    }
}
