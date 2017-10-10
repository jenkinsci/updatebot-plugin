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

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.BulkChange;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Saveable;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.RevisionParameterAction;
import hudson.util.XStream2;
import io.fabric8.updatebot.Configuration;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;
import jenkins.model.ParameterizedJobMixIn;
import org.apache.tools.ant.filters.StringInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import static java.util.logging.Level.SEVERE;

/**
 */
public class JenkinsHelpers {
    private static final transient Logger LOG = LoggerFactory.getLogger(JenkinsHelpers.class);

    /**
     * Returns a folder for the given name or returns null if it could not be created
     */
    public static ItemGroup getOrCreateFolder(Configuration configuration, Jenkins jenkins, String name) {
        Item parent = jenkins.getItemByFullName(name);
        if (parent instanceof ItemGroup) {
            return (ItemGroup) parent;
        } else {
            // lets lazily create a new folder for this namespace parent
            Folder folder = new Folder(jenkins, name);
            try {
                folder.setDescription("Folder for the github organisation: " + name);
            } catch (IOException e) {
                // ignore
            }
            createItem(configuration, jenkins, name, folder, "the Folder: " + name);
            // lets look it up again to be sure
            parent = jenkins.getItemByFullName(name);
            if (parent instanceof ItemGroup) {
                return (ItemGroup) parent;
            }
        }
        configuration.warn(LOG, "Failed to create Folder: " + name);
        return null;
    }

    public static void createItem(Configuration configuration, ModifiableTopLevelItemGroup jenkins, String name, Saveable saveable, String description) {
        InputStream jobStream = new StringInputStream(new XStream2().toXML(saveable));
        BulkChange bk = new BulkChange(saveable);
        try {
            jenkins.createProjectFromXML(
                    name,
                    jobStream
            ).save();
        } catch (IOException e) {
            configuration.warn(LOG, "Failed to create " + description);
        }
        try {
            bk.commit();
        } catch (IOException e) {
            configuration.warn(LOG, "Failed to commit toe BulkChange for " + description);
        }
    }


    public static QueueTaskFuture scheduleBuild(ParameterizedJobMixIn.ParameterizedJob job, List<Action> buildActions) {
        return job.scheduleBuild2(0, buildActions.toArray(new Action[buildActions.size()]));
    }
}
