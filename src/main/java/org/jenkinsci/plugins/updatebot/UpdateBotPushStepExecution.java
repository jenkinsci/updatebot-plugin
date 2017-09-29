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

import hudson.FilePath;
import hudson.model.TaskListener;
import jenkins.util.Timer;
import org.jenkinsci.plugins.updatebot.support.ListHelpers;
import org.jenkinsci.plugins.updatebot.support.PollComplete;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UpdateBotPushStepExecution extends AbstractStepExecutionImpl {
    public static final String UPDATEBOT_CLASSNAME = "io.fabric8.updatebot.UpdateBot";
    private static final long serialVersionUID = 1L;
    @Inject
    private transient UpdateBotPushStep step;
    private transient Future<?> task;
    private transient boolean invokedPush;
    private transient boolean shouldStop;

    private transient TaskListener listener;
    private transient PrintStream logger;
    private transient Class<?> updateBotClazz;
    private transient Object updatebot;
    private transient FilePath workspace;

    public UpdateBotPushStepExecution(UpdateBotPushStep step, StepContext context) {
        super(context);
        this.step = step;
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

    protected void pollUpdateBot() {
        PollComplete complete = null;
        if (!invokedPush) {
            complete = runUpdatePush();
            invokedPush = true;
        } else {
            complete = runUpdateBotUpdate();
        }
        if (complete != null) {
            complete.apply(getContext(), getLogger());
        } else {
            scheduleNextPoll();
        }
    }

    protected PollComplete runUpdatePush() {
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
        List<String> arguments = Arrays.asList("push", "--dir", file);
        return runUpdateBotCLI(arguments);
    }

    /**
     * Runs the updatebot poll operation to check if any pending PRs have completed or need rebasing
     *
     * @return true if the all the pull requests and issues are completed
     */
    protected PollComplete runUpdateBotUpdate() {
        try {
            Method pollMethod = findMethod(getUpdateBotClazz(), "poll");

            Object answer = invokeMethod(pollMethod);

            List<String> pullRequests = new ArrayList<>();
            boolean pending = true;
            if (answer instanceof List) {
                List list = (List) answer;
                pending = false;
                for (Object object : list) {
                    if (object instanceof Map) {
                        Map map = (Map) object;
                        Object status = map.get("status");
                        if (status != null && !"complete".equalsIgnoreCase(status.toString())) {
                            pending = true;
                        }
                        Object pr = map.get("pr");
                        if (pr != null) {
                            pullRequests.add(pr.toString());
                        }
                    }
                }
            }
            if (!pending) {
                return PollComplete.success(pullRequests);
            }
            return null;
        } catch (Exception e) {
            return PollComplete.failure(e);
        }
    }

    protected PollComplete runUpdateBotCLI(List<String> originalArguments) {
        List<String> globalArguments = new ArrayList<>();

        GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
        addCliOption(globalArguments, "--github-username", config.getGithubUsername());

        getLogger().println("Running: updatebot " + ListHelpers.join(" ", ListHelpers.concat(globalArguments, originalArguments)));

        // hide these arguments from the CLI
        addCliOption(globalArguments, "--github-password", config.getGithubPassword());
        addCliOption(globalArguments, "--github-token", config.getGithubToken());

        List<String> arguments = ListHelpers.concat(globalArguments, originalArguments);
        try {
            Method runMethod = findMethod(getUpdateBotClazz(), "run", String[].class);

            String[] args = arguments.toArray(new String[arguments.size()]);
            Object[] methodArgs = {args};
            invokeMethod(runMethod, methodArgs);
            return null;
        } catch (Exception e) {
            return PollComplete.failure(e);
        }
    }

    private void addCliOption(List<String> list, String optionName, String value) {
        if (value != null) {
            list.add(optionName);
            list.add(value);
        }
    }

    protected Object invokeMethod(Method method, Object... arguments) throws Exception {
        Object updateBot = getUpdateBot();
        try {
            return method.invoke(updateBot, arguments);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof Exception) {
                throw (Exception) targetException;
            } else {
                throw new Exception(targetException);
            }
        } catch (Exception e) {
            throw new Exception("Failed to invoke UpdateBot method " + method.getName() + " " + e, e);
        }
    }

    protected Object getUpdateBot() throws Exception {
        if (updatebot == null) {
            Class<?> clazz = getUpdateBotClazz();
            try {
                updatebot = clazz.newInstance();
            } catch (Exception e) {
                throw new Exception("Failed to instantiate " + clazz.getName() + " " + e, e);
            }
        }
        return updatebot;
    }

    protected Class<?> getUpdateBotClazz() throws Exception {
        if (updateBotClazz == null) {
            final URL url = getClass().getClassLoader().getResource("/updatebot.jar");
            if (url == null) {
                throw new Exception("Failed to find updatebot.jar on the classpath!");
            }
            URLClassLoader classLoader = AccessController.doPrivileged(new PrivilegedAction<URLClassLoader>() {
                @Override
                public URLClassLoader run() {
                    return new URLClassLoader(new URL[]{url});
                }
            });
            if (classLoader == null) {
                throw new Exception("AccessControl did not let us create a URLClassLoader so we could not load UpdateBot!");
            }
            try {
                updateBotClazz = classLoader.loadClass(UPDATEBOT_CLASSNAME);
            } catch (ClassNotFoundException e) {
                throw new Exception("Could not find class " + UPDATEBOT_CLASSNAME + " in jar " + url + ". " + e, e);
            }
        }
        return updateBotClazz;
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
        task = timer.schedule(createUpdateBotPoller(), step.pollPeriodMS(), TimeUnit.MILLISECONDS);
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
                pollUpdateBot();
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

    protected Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = null;
        try {
            method = clazz.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new Exception("Could not find the " + methodName + " method on UpdateBot class " + clazz.getName() + ". " + e, e);
        }
        return method;
    }
}
