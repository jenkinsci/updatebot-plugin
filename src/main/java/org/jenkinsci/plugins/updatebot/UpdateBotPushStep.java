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

import javax.annotation.Nonnull;
import java.util.Set;

public class UpdateBotPushStep extends Step {
    private static final long serialVersionUID = 1L;

    private static final long DEFAULT_POLL_PERIOD = 15000L;

    private final String file;
    private final Long pollPeriodMS;

    @DataBoundConstructor
    public UpdateBotPushStep(String file, Long pollPeriodMS) {
        this.file = file != null && file.length() > 0 ? file : ".";
        this.pollPeriodMS = pollPeriodMS;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new UpdateBotPushStepExecution(this, context);
    }

    public String getFile() {
        return file;
    }

    public Long getPollPeriodMS() {
        return pollPeriodMS;
    }

    public long pollPeriodMS() {
        if (pollPeriodMS == null || pollPeriodMS.longValue() == 0L) {
            return DEFAULT_POLL_PERIOD;
        }
        return pollPeriodMS.longValue();
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
            return "Pushes dependency versions from the local source code into dependent projects using UpdateBot";
        }
    }
}
