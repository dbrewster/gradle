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

package org.gradle.internal.progress;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Nullable;
import org.gradle.api.Transformer;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.GradleThread;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.internal.exceptions.DefaultMultiCauseException;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.operations.*;
import org.gradle.internal.time.TimeProvider;
import org.gradle.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

//TODO move to base-services once the ProgressLogger dependency is removed
public class DefaultBuildOperationExecutor implements BuildOperationExecutor, Stoppable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBuildOperationExecutor.class);
    private static final String LINE_SEPARATOR = SystemProperties.getInstance().getLineSeparator();

    private final BuildOperationListener listener;
    private final TimeProvider timeProvider;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final BuildOperationQueueFactory buildOperationQueueFactory;
    private final StoppableExecutor fixedSizePool;

    private final AtomicLong nextId = new AtomicLong();
    private final ThreadLocal<DefaultBuildOperationState> currentOperation = new ThreadLocal<DefaultBuildOperationState>();

    public DefaultBuildOperationExecutor(BuildOperationListener listener, TimeProvider timeProvider, ProgressLoggerFactory progressLoggerFactory,
                                         BuildOperationQueueFactory buildOperationQueueFactory, ExecutorFactory executorFactory, int maxWorkerCount) {
        this.listener = listener;
        this.timeProvider = timeProvider;
        this.progressLoggerFactory = progressLoggerFactory;
        this.buildOperationQueueFactory = buildOperationQueueFactory;
        this.fixedSizePool = executorFactory.create("build operations", maxWorkerCount);
    }

    @Override
    public BuildOperationState getCurrentOperation() {
        BuildOperationState current = currentOperation.get();
        if (current == null) {
            throw new IllegalStateException("No operation is currently running.");
        }
        return current;
    }

    @Override
    public void run(RunnableBuildOperation buildOperation) {
        try {
            execute(buildOperation, new RunnableBuildOperationWorker(), currentOperation.get());
        } finally {
            maybeStopUnmanagedThreadOperation();
        }
    }

    @Override
    public <T> T call(CallableBuildOperation<T> buildOperation) {
        CallableBuildOperationWorker<T> worker = new CallableBuildOperationWorker<T>();
        try {
            execute(buildOperation, worker, currentOperation.get());
        } finally {
            maybeStopUnmanagedThreadOperation();
        }
        return worker.getReturnValue();
    }

    @Override
    public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> schedulingAction) {
        try {
            executeInParallel(new ParentPreservingQueueWorker<O>(new RunnableBuildOperationWorker<O>()), schedulingAction);
        } finally {
            maybeStopUnmanagedThreadOperation();
        }
    }

    @Override
    public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> schedulingAction) {
        try {
            executeInParallel(new ParentPreservingQueueWorker<O>(worker), schedulingAction);
        } finally {
            maybeStopUnmanagedThreadOperation();
        }
    }

    private <O extends BuildOperation> void executeInParallel(BuildOperationQueue.QueueWorker<O> worker, Action<BuildOperationQueue<O>> queueAction) {
        BuildOperationQueue<O> queue = buildOperationQueueFactory.create(fixedSizePool, worker);

        List<GradleException> failures = Lists.newArrayList();
        try {
            queueAction.execute(queue);
        } catch (Exception e) {
            failures.add(new BuildOperationQueueFailure("There was a failure while populating the build operation queue: " + e.getMessage(), e));
            queue.cancel();
        }

        try {
            queue.waitForCompletion();
        } catch (MultipleBuildOperationFailures e) {
            failures.add(e);
        }

        if (failures.size() == 1) {
            throw failures.get(0);
        } else if (failures.size() > 1) {
            throw new DefaultMultiCauseException(formatMultipleFailureMessage(failures), failures);
        }
    }

    private <O extends BuildOperation> void execute(O buildOperation, BuildOperationWorker<O> worker, @Nullable DefaultBuildOperationState defaultParent) {
        BuildOperationDescriptor.Builder descriptorBuilder = buildOperation.description();
        DefaultBuildOperationState parent = (DefaultBuildOperationState) descriptorBuilder.getParentState();
        if (parent == null) {
            parent = defaultParent;
        }

        BuildOperationDescriptor descriptor = createDescriptor(descriptorBuilder, parent);
        DefaultBuildOperationState currentOperation = new DefaultBuildOperationState(descriptor, timeProvider.getCurrentTime());

        assertParentRunning("Cannot start operation (%s) as parent operation (%s) has already completed.", descriptor, parent);

        currentOperation.setRunning(true);

        DefaultBuildOperationState operationBefore = this.currentOperation.get();
        this.currentOperation.set(currentOperation);
        BuildOperationIdentifierRegistry.setCurrentOperationIdentifier(this.currentOperation.get().getId());
        try {
            listener.started(descriptor, new OperationStartEvent(currentOperation.getStartTime()));

            Throwable failure = null;
            DefaultBuildOperationContext context = new DefaultBuildOperationContext();
            try {
                ProgressLogger progressLogger = maybeStartProgressLogging(currentOperation);

                LOGGER.debug("Build operation '{}' started", descriptor.getDisplayName());
                try {
                    worker.execute(buildOperation, context);
                } finally {
                    if (progressLogger != null) {
                        progressLogger.completed();
                    }
                }
                assertParentRunning("Parent operation (%2$s) completed before this operation (%1$s).", descriptor, parent);
            } catch (Throwable t) {
                context.failed(t);
                failure = t;
            }

            long endTime = timeProvider.getCurrentTime();
            listener.finished(descriptor, new OperationFinishEvent(currentOperation.getStartTime(), endTime, context.failure, context.result));

            if (failure != null) {
                throw UncheckedException.throwAsUncheckedException(failure, true);
            }

            LOGGER.debug("Build operation '{}' completed", descriptor.getDisplayName());
        } finally {
            this.currentOperation.set(operationBefore);
            if (parent != null) {
                BuildOperationIdentifierRegistry.setCurrentOperationIdentifier(parent.getId());
            } else {
                BuildOperationIdentifierRegistry.clearCurrentOperationIdentifier();
            }
            currentOperation.setRunning(false);
        }
    }

    private BuildOperationDescriptor createDescriptor(BuildOperationDescriptor.Builder descriptorBuilder, DefaultBuildOperationState parent) {
        OperationIdentifier id = new OperationIdentifier(nextId.getAndIncrement());
        DefaultBuildOperationState current = maybeStartUnmanagedThreadOperation(parent);
        return descriptorBuilder.build(id, current == null ? null : current.getDescription().getId());
    }

    private void assertParentRunning(String message, BuildOperationDescriptor child, DefaultBuildOperationState parent) {
        if (parent != null && !parent.isRunning()) {
            String parentName = parent.getDescription().getDisplayName();
            throw new IllegalStateException(String.format(message, child.getDisplayName(), parentName));
        }
    }

    @Nullable
    private ProgressLogger maybeStartProgressLogging(DefaultBuildOperationState currentOperation) {
        if (providesProgressLogging(currentOperation)) {
            ProgressLogger progressLogger = progressLoggerFactory.newOperation(DefaultBuildOperationExecutor.class, (OperationIdentifier) currentOperation.getId());
            progressLogger.setDescription(currentOperation.getDescription().getDisplayName());
            progressLogger.setShortDescription(currentOperation.getDescription().getProgressDisplayName());
            progressLogger.started();
            return progressLogger;
        } else {
            return null;
        }
    }

    private boolean providesProgressLogging(DefaultBuildOperationState currentOperation) {
        return currentOperation.getDescription().getProgressDisplayName() != null;
    }

    private DefaultBuildOperationState maybeStartUnmanagedThreadOperation(DefaultBuildOperationState parentState) {
        if (!GradleThread.isManaged() && parentState == null) {
            parentState = UnmanagedThreadOperation.create(timeProvider);
            parentState.setRunning(true);
            currentOperation.set(parentState);
            listener.started(parentState.getDescription(), new OperationStartEvent(parentState.getStartTime()));
        }
        return parentState;
    }

    private void maybeStopUnmanagedThreadOperation() {
        DefaultBuildOperationState current = currentOperation.get();
        if (current instanceof UnmanagedThreadOperation) {
            try {
                listener.finished(current.getDescription(), new OperationFinishEvent(current.getStartTime(), timeProvider.getCurrentTime(), null, null));
            } finally {
                currentOperation.set(null);
                current.setRunning(false);
            }
        }
    }

    /**
     * Artificially create a running root operation.
     * Main use case is ProjectBuilder, useful for some of our test fixtures too.
     */
    protected void createRunningRootOperation(String displayName) {
        assert currentOperation.get() == null;
        DefaultBuildOperationState operation = new DefaultBuildOperationState(BuildOperationDescriptor.displayName(displayName).build(new OperationIdentifier(0), null), timeProvider.getCurrentTime());
        operation.setRunning(true);
        currentOperation.set(operation);
    }

    private static String formatMultipleFailureMessage(List<GradleException> failures) {
        return StringUtils.join(CollectionUtils.collect(failures, new Transformer<String, GradleException>() {
            @Override
            public String transform(GradleException e) {
                return e.getMessage();
            }
        }), LINE_SEPARATOR + "AND" + LINE_SEPARATOR);
    }

    @Override
    public void stop() {
        fixedSizePool.stop();
    }

    private static class DefaultBuildOperationContext implements BuildOperationContext {
        Throwable failure;
        Object result;

        @Override
        public void failed(Throwable t) {
            failure = t;
        }

        @Override
        public void setResult(Object result) {
            this.result = result;
        }
    }

    private static class RunnableBuildOperationWorker<O extends RunnableBuildOperation> implements BuildOperationWorker<O> {
        @Override
        public String getDisplayName() {
            return "runnable build operation";
        }

        @Override
        public void execute(O buildOperation, BuildOperationContext context) {
            buildOperation.run(context);
        }
    }

    private static class CallableBuildOperationWorker<T> implements BuildOperationWorker<CallableBuildOperation<T>> {
        private T returnValue;

        @Override
        public String getDisplayName() {
            return "callable build operation";
        }

        @Override
        public void execute(CallableBuildOperation<T> buildOperation, BuildOperationContext context) {
            returnValue = buildOperation.call(context);
            System.out.print("");
        }

        public T getReturnValue() {
            return returnValue;
        }
    }

    /**
     * Remembers the operation running on the executing thread at creation time to use
     * it during execution on other threads.
     */
    private class ParentPreservingQueueWorker<O extends BuildOperation> implements BuildOperationQueue.QueueWorker<O> {
        private DefaultBuildOperationState parent;
        private BuildOperationWorker<O> worker;

        private ParentPreservingQueueWorker(BuildOperationWorker<O> worker) {
            this.parent = maybeStartUnmanagedThreadOperation(currentOperation.get());
            this.worker = worker;
        }

        @Override
        public String getDisplayName() {
            return "runnable worker";
        }

        @Override
        public void execute(O buildOperation) {
            DefaultBuildOperationExecutor.this.execute(buildOperation, worker, parent);
        }
    }

    private static class DefaultBuildOperationState implements BuildOperationState {
        private final BuildOperationDescriptor description;
        private final AtomicBoolean running = new AtomicBoolean();
        private final long startTime;

        private DefaultBuildOperationState(BuildOperationDescriptor descriptor, long startTime) {
            this.startTime = startTime;
            this.description = descriptor;
        }

        BuildOperationDescriptor getDescription() {
            return description;
        }

        boolean isRunning() {
            return running.get();
        }

        void setRunning(boolean running) {
            this.running.set(running);
        }

        long getStartTime() {
            return startTime;
        }

        @Override
        public Object getId() {
            return description.getId();
        }

        @Override
        public Object getParentId() {
            return description.getParentId();
        }
    }

    private static class UnmanagedThreadOperation extends DefaultBuildOperationState {

        private static final AtomicLong UNMANAGED_THREAD_OPERATION_COUNTER = new AtomicLong(-1);

        private static UnmanagedThreadOperation create(TimeProvider timeProvider) {
            // TODO:pm Move this to WARN level once we fixed maven-publish, see gradle/gradle#1662
            LOGGER.debug("WARNING No operation is currently running in unmanaged thread: {}", Thread.currentThread().getName());
            OperationIdentifier id = new OperationIdentifier(UNMANAGED_THREAD_OPERATION_COUNTER.getAndDecrement());
            String displayName = "Unmanaged thread operation #" + id + " (" + Thread.currentThread().getName() + ')';
            return new UnmanagedThreadOperation(BuildOperationDescriptor.displayName(displayName).build(id, null), null, timeProvider.getCurrentTime());
        }

        private UnmanagedThreadOperation(BuildOperationDescriptor descriptor, BuildOperationState parent, long startTime) {
            super(descriptor, startTime);
        }
    }
}
