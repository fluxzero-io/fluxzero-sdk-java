/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
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
 */

package io.fluxzero.sdk.scheduling;

import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.configuration.FluxzeroBuilder;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleSchedule;

import java.util.stream.Stream;

import static io.fluxzero.common.Guarantee.NONE;
import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.sdk.common.serialization.UnknownTypeStrategy.IGNORE;

/**
 * Internal handler responsible for executing scheduled commands when they are due. Typically, these commands have been
 * scheduled using {@link Fluxzero#scheduleCommand} methods or via the {@link MessageScheduler}.
 * <p>
 * This component listens to {@link Schedule} messages containing serialized command payloads wrapped in
 * {@link ScheduledCommand}. When a scheduled time is reached, the handler triggers the dispatch of the command. The
 * handler attempts to deserialize and dispatch the command using the standard {@link Fluxzero} command gateway.
 *
 * <p>Deserialization is attempted prior to dispatch to ensure that any configured
 * {@link io.fluxzero.sdk.publishing.DispatchInterceptor dispatch interceptors} are invoked. Many
 * interceptors (e.g. those for correlation, routing, data protection, or metrics) require access to the command payload
 * and metadata, and therefore rely on a proper
 * {@link io.fluxzero.sdk.common.serialization.DeserializingMessage} context.
 * <p>
 * If deserialization fails (e.g. due to an unknown type or missing class), the command is appended directly to the
 * lower level {@link io.fluxzero.sdk.publishing.client.GatewayClient command gateway client} using a raw
 * {@link SerializedMessage}.
 *
 * <p>Consumers typically do not invoke or register this handler directly. It is automatically configured
 * in the Fluxzero client unless explicitly disabled using
 * {@link FluxzeroBuilder#disableScheduledCommandHandler()}.
 *
 * @see ScheduledCommand
 * @see io.fluxzero.sdk.tracking.handling.HandleSchedule
 * @see Fluxzero#scheduleCommand
 */
@Consumer(name = "ScheduledCommandHandler", typeFilter = "\\Qio.fluxzero.sdk.scheduling.ScheduledCommand\\E")
public class ScheduledCommandHandler {
    @HandleSchedule
    void handle(ScheduledCommand schedule) {
        SerializedMessage command = schedule.getCommand();
        command.setTimestamp(Fluxzero.currentTime().toEpochMilli());
        var commands = Fluxzero.get().serializer().deserializeMessages(Stream.of(command), COMMAND, IGNORE)
                .map(DeserializingMessage::toMessage).toArray();
        if (commands.length != 0) {
            Fluxzero.sendAndForgetCommands(commands);
        } else {
            Fluxzero.get().client().getGatewayClient(COMMAND).append(NONE, command);
        }
    }
}
