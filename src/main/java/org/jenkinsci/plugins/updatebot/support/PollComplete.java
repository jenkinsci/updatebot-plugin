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
package org.jenkinsci.plugins.updatebot.support;

import org.jenkinsci.plugins.workflow.steps.StepContext;

import java.io.PrintStream;

/**
 * An object used to return the status of polling of the UpdateBot
 */
public class PollComplete {
    private Throwable failure;
    private Object success;

    public static PollComplete success(Object success) {
        PollComplete answer = new PollComplete();
        answer.setSuccess(success);
        return answer;
    }

    public static PollComplete failure(Throwable failure) {
        PollComplete answer = new PollComplete();
        answer.setFailure(failure);
        return answer;
    }

    public void apply(StepContext context, PrintStream logger) {
        if (failure != null) {
            logger.println("UpdateBot failed " + failure);
            context.onFailure(failure);
        } else {
            if (success == null) {
                success = "Completed!";
            }
            logger.println("UpdateBot success: " + success);
            context.onSuccess(success);
        }
    }


    public Throwable getFailure() {
        return failure;
    }

    public void setFailure(Throwable failure) {
        this.failure = failure;
    }

    public Object getSuccess() {
        return success;
    }

    public void setSuccess(Object success) {
        this.success = success;
    }
}
