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

    @With
    private final Client client;
    private final Serializer serializer;
    private final DispatchInterceptor dispatchInterceptor;
    private final DispatchInterceptor commandDispatchInterceptor;
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
        if (Entity.isLoading()) {
            return CompletableFuture.completedFuture(null);
        }
        message = (Schedule) dispatchInterceptor.interceptDispatch(message, SCHEDULE, null, client.namespace());
        if (message == null) {
            return CompletableFuture.completedFuture(null);
        }
        SerializedMessage serializedMessage = dispatchInterceptor.modifySerializedMessage(
                message.serialize(serializer), message, SCHEDULE, null);
        if (serializedMessage == null) {
            return CompletableFuture.completedFuture(null);
        }
        dispatchInterceptor.monitorDispatch(message, SCHEDULE, null, client.namespace(), false);
        Fluxzero fluxzero = Fluxzero.getOptionally().orElse(null);
        Schedule scheduledMessage = message;
        return getSchedulingClient().schedule(guarantee, new SerializedSchedule(message.getScheduleId(),
                                                                           message.getDeadline().toEpochMilli(),
                                                                           serializedMessage, ifAbsent))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        scheduleLocalDelivery(scheduledMessage, ifAbsent, fluxzero);
                    }
                });
    }

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
        SerializedMessage serializedCommand = commandDispatchInterceptor.modifySerializedMessage(
                commandMessage.serialize(serializer), commandMessage, COMMAND, null);
        if (serializedCommand == null) {
            return CompletableFuture.completedFuture(null);
        }
        return schedule(schedule.withPayload(new ScheduledCommand(serializedCommand))
                                .addMetadata("$commandType", schedule.getPayloadClass().getName()), ifAbsent,
                        guarantee);
    }

    @Override
    public void cancelSchedule(@NonNull Object scheduleId) {
        try {
            if (Entity.isLoading()) {
                return;
            }
            cancelLocalDelivery(scheduleId.toString());
            getSchedulingClient().cancelSchedule(scheduleId.toString()).get();
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
}
