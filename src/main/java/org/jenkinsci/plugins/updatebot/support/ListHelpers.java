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

import java.util.ArrayList;
import java.util.List;

/**
 */
public class ListHelpers {

    public static <T> List<T> concat(List<T>... lists) {
        List<T> answer = new ArrayList<>();
        for (List<T> list : lists) {
            answer.addAll(list);
        }
        return answer;
    }

    public static String join(String separator, Iterable<?> collection) {
        StringBuilder builder = new StringBuilder();
        for (Object o : collection) {
            if (o != null) {
                if (builder.length() > 0) {
                    builder.append(separator);
                }
                builder.append(o);
            }
        }
        return builder.toString();
    }
}
