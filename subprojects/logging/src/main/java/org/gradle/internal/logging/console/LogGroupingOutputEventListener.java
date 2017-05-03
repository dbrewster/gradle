/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.console;

import com.google.common.collect.Lists;
import org.gradle.api.Nullable;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.events.BatchOutputEventListener;
import org.gradle.internal.logging.events.CategorisedOutputEvent;
import org.gradle.internal.logging.events.EndOutputEvent;
import org.gradle.internal.logging.events.LogEvent;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;
import org.gradle.internal.logging.events.RenderableOutputEvent;
import org.gradle.internal.progress.BuildOperationType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class LogGroupingOutputEventListener extends BatchOutputEventListener {
    // FIXME(ew): This breaks OutputEventRendererTest.rendersLogEventsInConsoleWhenLogLevelIsDebug() — perhaps everything is being output as LogEvents from this thing?

    static final int SCHEDULER_CHECK_PERIOD_MS = 5000;
    private final BatchOutputEventListener listener;
    private final ScheduledExecutorService executor;
    private final Object lock = new Object();

    // Allows us to map progress and complete events to start events
    private final Map<OperationIdentifier, Object> progressIdToBuildOperationIdMap = new LinkedHashMap<OperationIdentifier, Object>();

    // Maintain a hierarchy of all build operation ids, so that child operations are grouped under their ancestor
    private final Map<Object, Object> buildOperationIdForest = new LinkedHashMap<Object, Object>();

    // Event groups that are in-progress and have not been completed
    private final Map<Object, ArrayList<CategorisedOutputEvent>> outputEventGroups = new LinkedHashMap<Object, ArrayList<CategorisedOutputEvent>>();
    private Object lastRenderedOperationId;

    public LogGroupingOutputEventListener(BatchOutputEventListener listener) {
        this(listener, Executors.newSingleThreadScheduledExecutor());
    }

    LogGroupingOutputEventListener(BatchOutputEventListener listener, ScheduledExecutorService executor) {
        this.listener = listener;
        this.executor = executor;
    }

    @Override
    public void onOutput(OutputEvent event) {
        synchronized (lock) {
            if (event instanceof EndOutputEvent) {
                renderAllGroups(outputEventGroups);
                listener.onOutput(event);
//                executor.shutdown();
            } else if (event instanceof ProgressStartEvent) {
                onStart((ProgressStartEvent) event);
            } else if (event instanceof ProgressEvent) {
                ProgressEvent progressEvent = (ProgressEvent) event;
                groupOrForward(progressIdToBuildOperationIdMap.get(progressEvent.getProgressOperationId()), progressEvent);
            } else if (event instanceof RenderableOutputEvent) {
                RenderableOutputEvent renderableOutputEvent = (RenderableOutputEvent) event;
                groupOrForward(renderableOutputEvent.getBuildOperationId(), renderableOutputEvent);
            } else if (event instanceof ProgressCompleteEvent) {
                onComplete((ProgressCompleteEvent) event);
            } else {
                listener.onOutput(event);
            }

            // TODO(ew): Use strategy from ThrottlingOEL to schedule runnable once every 5 seconds on new output
//            executor.schedule(new Runnable() {
//                @Override
//                public void run() {
//                    synchronized (lock) {
//                        renderAllGroups(outputEventGroups);
//                    }
//                }
//            }, SCHEDULER_CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void onStart(ProgressStartEvent event) {
        Object buildOpId = event.getBuildOperationId();
        if (buildOpId != null) {
            buildOperationIdForest.put(buildOpId, event.getParentBuildOperationId());
            progressIdToBuildOperationIdMap.put(event.getProgressOperationId(), buildOpId);

            if (event.getBuildOperationType() == BuildOperationType.TASK || event.getBuildOperationType() == BuildOperationType.CONFIGURE_PROJECT) {
                // TODO: check whether child of another task
                CategorisedOutputEvent header = new LogEvent(event.getTimestamp(), event.getCategory(), LogLevel.QUIET, "[" + event.getDescription() + "]", null);
                outputEventGroups.put(buildOpId, Lists.newArrayList(header, event));
            } else {
                groupOrForward(buildOpId, event);
            }
        } else {
            listener.onOutput(event);
        }
    }

    private void onComplete(ProgressCompleteEvent event) {
        Object buildOperationId = progressIdToBuildOperationIdMap.get(event.getProgressOperationId());
        Object groupId;

        if (outputEventGroups.containsKey(buildOperationId)) {
            // Render group if complete
            List<OutputEvent> group = new ArrayList<OutputEvent>(outputEventGroups.remove(buildOperationId));
            if (hasRenderableEvents(group)) {
                // TODO: write header newline if necessary
                group.add(event);
                // Visually indicate a group with an empty line
                group.add(new LogEvent(event.getTimestamp(), event.getCategory(), LogLevel.QUIET, "", null));
                listener.onOutput(group);
                lastRenderedOperationId = buildOperationId;
            }
        } else if ((groupId = getGroupId(buildOperationId)) != null) {
            // Add to group if possible
            outputEventGroups.get(groupId).add(event);
        } else {
            // Otherwise just forward the event
            listener.onOutput(event);
        }
    }

    // Return the id of the group, checking up the build operation id hierarchy
    // We are assuming that the average height of the build operation id forest is very low
    private Object getGroupId(@Nullable final Object buildOperationId) {
        Object current = buildOperationId;
        while (current != null) {
            // TODO(ew): for composite builds, may need to group by highest TASK parent
            if (outputEventGroups.containsKey(current)) {
                return current;
            }
            current = buildOperationIdForest.get(current);
        }
        return null;
    }

    private void groupOrForward(Object buildOpId, CategorisedOutputEvent event) {
        Object groupId = getGroupId(buildOpId);
        if (groupId != null) {
            outputEventGroups.get(groupId).add(event);
        } else {
            listener.onOutput(event);
        }
    }

    /**
     * return true if any event is a RenderableOutputEvent.
     */
    private boolean hasRenderableEvents(Iterable<OutputEvent> events) {
        int index = 0;
        for (OutputEvent event : events) {
            if (index++ > 0 && event instanceof RenderableOutputEvent) {
                return true;
            }
        }
        return false;
    }

    private void renderAllGroups(Map<Object, ArrayList<CategorisedOutputEvent>> groups) {
        for (Map.Entry<Object, ArrayList<CategorisedOutputEvent>> entry : groups.entrySet()) {
            ArrayList<OutputEvent> group = new ArrayList<OutputEvent>(entry.getValue());
            if (hasRenderableEvents(group)) {
                Object buildOperationId = entry.getKey();
                // Add a new line if not appending to last rendered group
                if (!buildOperationId.equals(lastRenderedOperationId)) {
                    CategorisedOutputEvent event = (CategorisedOutputEvent) group.get(group.size() - 1);
                    group.add(new LogEvent(event.getTimestamp(), event.getCategory(), event.getLogLevel(), "", null));
                }
                listener.onOutput(group);
                // Preserve header
                entry.setValue(Lists.newArrayList((CategorisedOutputEvent) group.get(0)));
                lastRenderedOperationId = buildOperationId;
            }
        }
    }
}
