/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.Registration;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.AbstractNamespaced;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.HasLocalHandlers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.With;
import lombok.experimental.Delegate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.MessageType.SCHEDULE;
import static io.fluxzero.sdk.common.ClientUtils.isApplicationNamespace;
import static io.fluxzero.sdk.common.ClientUtils.setConsumerNamespace;
import static io.fluxzero.sdk.tracking.IndexUtils.indexFromTimestamp;

/**
 * Default implementation of the {@link MessageScheduler} interface.
 * <p>
 * This implementation uses a {@link SchedulingClient} to schedule and cancel scheduled messages. When a schedule has a
 * matching local handler, a {@link TaskScheduler} task wakes up at the deadline and invokes the local handler in-process.
 */
@AllArgsConstructor
public class DefaultMessageScheduler extends AbstractNamespaced<MessageScheduler>
        implements MessageScheduler, HasLocalHandlers {

    public DefaultMessageScheduler(Client client, Serializer serializer, DispatchInterceptor dispatchInterceptor, DispatchInterceptor commandDispatchInterceptor, UnaryOperator<DeserializingMessage> parentDataRestoration, TaskScheduler taskScheduler, HandlerRegistry localHandlerRegistry) {
        this.client = client;
        this.serializer = serializer;
        this.dispatchInterceptor = dispatchInterceptor;
        this.commandDispatchInterceptor = commandDispatchInterceptor;
        this.parentDataRestoration = parentDataRestoration;
        this.taskScheduler = taskScheduler;
        this.localHandlerRegistry = localHandlerRegistry;
    }

    @With
    private final Client client;
    private Guarantee defaultGuarantee = Guarantee.STORED;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final DispatchInterceptor commandDispatchInterceptor;
    /** Restores selected protected parent fields for an ownership-only read view; never for dispatch. */
    private final UnaryOperator<DeserializingMessage> parentDataRestoration;
    private final TaskScheduler taskScheduler;
    @Delegate
    private final HandlerRegistry localHandlerRegistry;
    private final ConcurrentMap<String, LocalScheduleTask> localScheduleTasks = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> localHandlerRegistration = ThreadLocal.withInitial(() -> false);

    @Getter(lazy = true)
    private final SchedulingClient schedulingClient = client.getSchedulingClient();

    @Override
    protected MessageScheduler createForNamespace(String namespace) {
        return withClient(client.forNamespace(namespace));
    }

    @Override
    public CompletableFuture<Void> schedule(Schedule message, boolean ifAbsent, Guarantee guarantee) {
        return schedule(message, ifAbsent, guarantee, null);
    }

    private CompletableFuture<Void> schedule(Schedule message, boolean ifAbsent, Guarantee guarantee,
                                              ParentSelection commandParents) {
        Guarantee resolvedGuarantee = resolveGuarantee(guarantee);
        if (Entity.isLoading()) {
            return CompletableFuture.completedFuture(null);
        }
        Object originalPayload = message.getPayload();
        message = (Schedule) dispatchInterceptor.interceptDispatch(message, SCHEDULE, null, client.namespace());
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        SerializedMessage initial = message.serialize(serializer);
        var initialData = initial.getData();
        SerializedMessage serializedMessage = dispatchInterceptor.modifySerializedMessage(
                initial, message, SCHEDULE, null);
        if (serializedMessage == null) {
            return CompletableFuture.completedFuture(null);
        }
        dispatchInterceptor.monitorDispatch(message, SCHEDULE, null, client.namespace(), false);
        Fluxzero fluxzero = Fluxzero.getOptionally().orElse(null);
        Schedule scheduledMessage = message;
        Schedule ownershipMessage = message.withMetadata(serializedMessage.getMetadata());
        if (!Objects.equals(initialData, serializedMessage.getData())
            && !serializedMessage.getMetadata().containsKey(ScheduleParents.METADATA_KEY)
            && (commandParents != null && commandParents.declared()
                || message.getMetadata().containsKey(ScheduleParents.BINDINGS_KEY)
                || ScheduleParents.hasOwningDeclarations(message))) {
            var decoded = serializer.deserializeMessages(Stream.of(serializedMessage), SCHEDULE).limit(2).toList();
            if (decoded.size() != 1) {
                throw new IllegalStateException("Schedule ownership requires exactly one decoded payload");
            }
            ownershipMessage = ownershipMessage.withPayload(decoded.getFirst().getPayload());
        }
        boolean unchangedCommand = ownershipMessage.getPayload() == originalPayload
                || ownershipMessage.getPayload() instanceof ScheduledCommand next && originalPayload instanceof ScheduledCommand prior
                   && Objects.equals(next.getCommand(), prior.getCommand());
        var parents = commandParents != null && unchangedCommand
                      && !serializedMessage.getMetadata().containsKey(ScheduleParents.METADATA_KEY)
                ? commandParents.ids() : resolveParents(ownershipMessage,
                        commandParents != null && commandParents.declared()
                        || ownershipMessage.getMetadata().containsKey(ScheduleParents.BINDINGS_KEY));
        var serializedSchedule = new SerializedSchedule(message.getScheduleId(),
                                                        message.getDeadline().toEpochMilli(), serializedMessage, ifAbsent);
        CompletableFuture<Void> completion = AsyncCompletionScope.takeOwnership(() -> {
            if (parents.isEmpty()) {
                return getSchedulingClient().schedule(resolvedGuarantee, serializedSchedule);
            }
            return getSchedulingClient().bindScheduleParents(parents)
                    .thenCompose(ThreadLocalContext.capture().wrap(bindings -> {
                        var bound = serializedMessage.withMetadata(
                                ScheduleParents.bind(serializedMessage.getMetadata(), client.namespace(), bindings));
                        return AsyncCompletionScope.takeOwnership(() -> getSchedulingClient().scheduleBoundToParents(
                                resolvedGuarantee, bindings,
                                new SerializedSchedule(serializedSchedule.getScheduleId(), serializedSchedule.getTimestamp(),
                                                       bound, ifAbsent)));
                    }));
        });
        return AsyncCompletionScope.register(completion.whenComplete(ThreadLocalContext.capture().wrap((ignored, error) -> {
            if (error == null) {
                scheduleLocalDelivery(scheduledMessage, ifAbsent, fluxzero);
            }
        })));
    }

    private java.util.List<String> resolveParents(Schedule message, boolean previouslyOwnedCommand) {
        if (!message.getMetadata().containsKey(ScheduleParents.METADATA_KEY)
            && previouslyOwnedCommand && message.getPayload() instanceof ScheduledCommand command) {
            var decoded = serializer.deserializeMessages(Stream.of(command.getCommand()), COMMAND).limit(2).toList();
            if (decoded.size() != 1) {
                throw new IllegalStateException("Scheduled command ownership requires exactly one decoded command");
            }
            return ScheduleParents.resolve(decoded.getFirst().toMessage(), COMMAND, serializer, parentDataRestoration);
        }
        return ScheduleParents.resolve(message, SCHEDULE, serializer, parentDataRestoration);
    }

    private record ParentSelection(java.util.List<String> ids, boolean declared) {}

    @Override
    public CompletableFuture<Void> scheduleCommand(Schedule schedule, boolean ifAbsent, Guarantee guarantee) {
        if (Entity.isLoading()) {
            return CompletableFuture.completedFuture(null);
        }
        var commandMessage = schedule.withMessageId(Fluxzero.currentIdentityProvider().nextTechnicalId());
        var intercepted = commandDispatchInterceptor.interceptDispatch(
                commandMessage, COMMAND, null, client.namespace());
        if (intercepted == null) {
            return CompletableFuture.completedFuture(null);
        }
        commandMessage = commandMessage.withPayload(intercepted.getPayload()).withMetadata(intercepted.getMetadata());
        SerializedMessage initialCommand = commandMessage.serialize(serializer);
        var initialCommandData = initialCommand.getData();
        SerializedMessage serializedCommand = commandDispatchInterceptor.modifySerializedMessage(
                initialCommand, commandMessage, COMMAND, null);
        if (serializedCommand == null) {
            return CompletableFuture.completedFuture(null);
        }
        Message ownershipCommand = commandMessage.withMetadata(serializedCommand.getMetadata());
        if (!Objects.equals(initialCommandData, serializedCommand.getData())
            && !serializedCommand.getMetadata().containsKey(ScheduleParents.METADATA_KEY)
            && ScheduleParents.hasOwningDeclarations(commandMessage)) {
            var decoded = serializer.deserializeMessages(Stream.of(serializedCommand), COMMAND).limit(2).toList();
            if (decoded.size() != 1) {
                throw new IllegalStateException("Scheduled command ownership requires exactly one decoded command");
            }
            ownershipCommand = decoded.getFirst().toMessage();
        }
        var parents = ScheduleParents.resolve(ownershipCommand,
                                              COMMAND, serializer, parentDataRestoration);
        var wrapped = schedule.withPayload(new ScheduledCommand(serializedCommand))
                .addMetadata("$commandType", schedule.getPayloadClass().getName());
        wrapped = wrapped.withMetadata(wrapped.getMetadata().without(ScheduleParents.METADATA_KEY)
                                               .without(ScheduleParents.BINDINGS_KEY).without(ScheduleParents.NAMESPACE_KEY));
        for (String key : java.util.List.of(ScheduleParents.METADATA_KEY,
                                           ScheduleParents.BINDINGS_KEY, ScheduleParents.NAMESPACE_KEY)) {
            if (serializedCommand.getMetadata().containsKey(key)) {
                wrapped = wrapped.addMetadata(key, serializedCommand.getMetadata().get(key));
            }
        }
        return schedule(wrapped, ifAbsent, guarantee,
                        new ParentSelection(parents, ScheduleParents.hasOwningDeclarations(ownershipCommand)));
    }

    @Override
    public void cancelSchedule(@NonNull Object scheduleId) {
        AsyncCompletionScope.register(cancelSchedule(scheduleId, defaultGuarantee));
    }

    // Framework cancellation retains the historical SENT acknowledgement boundary.
    void cancelScheduleAndWait(Object scheduleId) {
        cancelScheduleAndWait(scheduleId, Guarantee.SENT);
    }

    private void cancelScheduleAndWait(Object scheduleId, Guarantee guarantee) {
        try {
            AsyncCompletionScope.await(() -> cancelSchedule(scheduleId, guarantee));
        } catch (Exception e) {
            throw new SchedulerException(String.format("Failed to cancel schedule with id %s", scheduleId), e);
        }
    }

    private CompletableFuture<Void> cancelSchedule(Object scheduleId, Guarantee guarantee) {
        try {
            if (Entity.isLoading()) {
                return CompletableFuture.completedFuture(null);
            }
            cancelLocalDelivery(scheduleId.toString());
            return getSchedulingClient().cancelSchedule(scheduleId.toString(), guarantee);
        } catch (Exception e) {
            throw new SchedulerException(String.format("Failed to cancel schedule with id %s", scheduleId), e);
        }
    }

    @Override
    public Optional<Schedule> getSchedule(@NonNull Object scheduleId) {
        return Optional.ofNullable(getSchedulingClient().getSchedule(scheduleId.toString())).flatMap(
                s -> serializer.deserializeMessages(Stream.of(s.getMessage()), SCHEDULE).findFirst()
                        .map(DeserializingMessage::toMessage).map(
                                m -> new Schedule(m.getPayload(), m.getMetadata(), m.getMessageId(), m.getTimestamp(),
                                                  s.getScheduleId(), Instant.ofEpochMilli(s.getTimestamp()))));
    }

    @SneakyThrows
    public void handleLocally(Schedule schedule) {
        var result = localHandlerRegistry.handle(deserializingMessage(schedule));
        if (result.isPresent()) {
            result.get().get();
        }
    }

    @Override
    public Registration registerHandler(Object target) {
        return registerLocalHandler(() -> HasLocalHandlers.super.registerHandler(target));
    }

    @Override
    public Registration registerHandler(Object target, HandlerFilter handlerFilter) {
        return registerLocalHandler(() -> localHandlerRegistry.registerHandler(target, handlerFilter));
    }

    protected void scheduleLocalDelivery(Schedule schedule, boolean ifAbsent, Fluxzero fluxzero) {
        if (!shouldScheduleLocalDelivery(schedule)) {
            return;
        }
        Optional<Schedule> storedSchedule = getSchedule(schedule.getScheduleId());
        if (ifAbsent && storedSchedule.filter(s -> sameScheduledMessage(s, schedule)).isEmpty()) {
            return;
        }
        Schedule localSchedule = storedSchedule.orElse(schedule);
        LocalScheduleTask task = new LocalScheduleTask(localSchedule, fluxzero);
        LocalScheduleTask previous = localScheduleTasks.put(schedule.getScheduleId(), task);
        if (previous != null) {
            previous.cancel();
        }
        try {
            task.registration = taskScheduler.schedule(localSchedule.getDeadline(), task::run);
            if (localScheduleTasks.get(schedule.getScheduleId()) != task) {
                task.cancel();
            }
            if (!localHandlerRegistration.get()) {
                runIfExpired(task, localSchedule);
            }
        } catch (RuntimeException | Error e) {
            localScheduleTasks.remove(schedule.getScheduleId(), task);
            throw e;
        }
    }

    protected void runIfExpired(LocalScheduleTask task, Schedule schedule) {
        if (!schedule.getDeadline().isAfter(taskScheduler.clock().instant())) {
            try {
                task.run();
            } finally {
                task.cancel();
            }
        }
    }

    protected Registration registerLocalHandler(Supplier<Registration> registration) {
        Boolean previous = localHandlerRegistration.get();
        localHandlerRegistration.set(true);
        boolean registered = false;
        try {
            Registration result = registration.get();
            registered = true;
            return result;
        } finally {
            localHandlerRegistration.set(previous);
            if (registered && !previous) {
                taskScheduler.executeExpiredTasks();
            }
        }
    }

    protected boolean shouldScheduleLocalDelivery(Schedule schedule) {
        return localHandlerRegistration.get()
               || localHandlerRegistry.hasLocalHandlers()
               || localHandlerRegistry.canHandle(deserializingMessage(schedule));
    }

    protected void cancelLocalDelivery(String scheduleId) {
        Optional.ofNullable(localScheduleTasks.remove(scheduleId)).ifPresent(LocalScheduleTask::cancel);
    }

    protected void handleLocalSchedule(LocalScheduleTask task) {
        if (!localScheduleTasks.remove(task.schedule.getScheduleId(), task)) {
            return;
        }
        Fluxzero fluxzero = Optional.ofNullable(task.fluxzero).or(() -> Fluxzero.getOptionally()).orElse(null);
        Runnable localHandling = () -> getSchedule(task.schedule.getScheduleId())
                .filter(current -> sameSchedule(current, task.schedule))
                .ifPresent(current -> {
                    DeserializingMessage message = deserializingMessage(current);
                    if (localHandlerRegistry.canHandle(message)) {
                        try {
                            getSchedulingClient().cancelSchedule(current.getScheduleId(), Guarantee.NONE).get();
                            handleLocally(current);
                        } catch (Exception e) {
                            throw new SchedulerException(String.format(
                                    "Failed to handle local schedule with id %s", current.getScheduleId()), e);
                        }
                    }
                });
        if (fluxzero == null) {
            localHandling.run();
        } else {
            fluxzero.execute(fc -> localHandling.run());
        }
    }

    protected DeserializingMessage deserializingMessage(Schedule schedule) {
        var serializedMessage = schedule.serialize(serializer);
        serializedMessage.setIndex(indexFromTimestamp(schedule.getDeadline()));
        return setConsumerNamespace(new DeserializingMessage(
                serializedMessage, type -> serializer.convert(schedule.getPayload(), type), SCHEDULE, null, serializer),
                isApplicationNamespace(client) ? null : client.namespace());
    }

    protected boolean sameSchedule(Schedule left, Schedule right) {
        return sameScheduledMessage(left, right)
               && Objects.equals(left.getDeadline(), right.getDeadline());
    }

    protected boolean sameScheduledMessage(Schedule left, Schedule right) {
        return Objects.equals(left.getScheduleId(), right.getScheduleId())
               && Objects.equals(left.getMessageId(), right.getMessageId());
    }

    protected class LocalScheduleTask {
        private final Schedule schedule;
        private final Fluxzero fluxzero;
        private volatile Registration registration = Registration.noOp();

        protected LocalScheduleTask(Schedule schedule, Fluxzero fluxzero) {
            this.schedule = schedule;
            this.fluxzero = fluxzero;
        }

        protected void run() {
            handleLocalSchedule(this);
        }

        protected void cancel() {
            registration.cancel();
        }
    }

    /** Configures the concrete application delivery default before first use; namespace copies inherit it. */
    public DefaultMessageScheduler withDefaultGuarantee(Guarantee guarantee) {
        if (java.util.Objects.requireNonNull(guarantee) == Guarantee.DEFAULT) {
            throw new IllegalArgumentException("The default delivery guarantee must be concrete");
        }
        defaultGuarantee = guarantee;
        return this;
    }

    private Guarantee resolveGuarantee(Guarantee guarantee) {
        return guarantee == Guarantee.DEFAULT ? defaultGuarantee : guarantee;
    }
}
