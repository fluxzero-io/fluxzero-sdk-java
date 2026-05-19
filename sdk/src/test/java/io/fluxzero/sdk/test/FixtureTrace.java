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

package io.fluxzero.sdk.test;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.Tracker;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Records the messages and handler invocations observed by a {@link TestFixture}.
 */
final class FixtureTrace {
    private static final int MAX_RENDERED_NODES = 120;

    private final Map<String, TraceNode> messageNodes = new LinkedHashMap<>();
    private final List<TraceNode> roots = new ArrayList<>();
    private final List<String> missedMessages = new ArrayList<>();
    private final List<String> unexpectedMessages = new ArrayList<>();
    private final ThreadLocal<TraceNode> activeParent = new ThreadLocal<>();
    private final EnumSet<MessageType> visibleInfrastructureTypes = EnumSet.noneOf(MessageType.class);
    private String phase = "setup";

    synchronized void startPhase(String phase) {
        this.phase = phase;
    }

    synchronized void show(MessageType messageType) {
        visibleInfrastructureTypes.add(messageType);
    }

    synchronized void recordMissed(MessageType messageType, String topic, Collection<?> expectedMessages) {
        expectedMessages.stream()
                .map(expectedMessage -> describeDiagnosticMessage(messageType, topic, expectedMessage))
                .forEach(missedMessages::add);
    }

    synchronized void recordUnexpected(MessageType messageType, String topic, Collection<?> unexpectedMessages) {
        unexpectedMessages.stream()
                .map(unexpectedMessage -> describeDiagnosticMessage(messageType, topic, unexpectedMessage))
                .forEach(this.unexpectedMessages::add);
    }

    void monitorDispatch(Message message, MessageType messageType, String topic, String namespace) {
        if (message == null) {
            return;
        }
        TraceNode parent = activeParent.get();
        synchronized (this) {
            TraceNode node = messageNode(message, messageType, topic, namespace);
            attach(parent, node);
        }
    }

    HandlerScope beginHandling(DeserializingMessage message, HandlerInvoker invoker) {
        TraceNode previous = activeParent.get();
        TraceNode handlerNode;
        synchronized (this) {
            TraceNode messageNode = messageNode(message.toMessage(), message.getMessageType(), message.getTopic(),
                                                null);
            attach(previous, messageNode);
            handlerNode = TraceNode.handler(phase, describeHandler(invoker), message.getMessageId(),
                                            Tracker.current().map(Tracker::getName).orElse(null));
            attach(messageNode, handlerNode);
        }
        activeParent.set(handlerNode);
        return new HandlerScope(this, handlerNode, previous);
    }

    TraceScope beginTimeShift(String description) {
        TraceNode previous = activeParent.get();
        TraceNode node;
        synchronized (this) {
            node = TraceNode.time(phase, description);
            roots.add(node);
            attach(previous, node);
        }
        activeParent.set(node);
        return new TraceScope(this, previous);
    }

    ActionScope beginAction(String description) {
        TraceNode previous = activeParent.get();
        TraceNode node;
        synchronized (this) {
            node = TraceNode.action(phase, description);
            roots.add(node);
            attach(previous, node);
        }
        activeParent.set(node);
        return new ActionScope(this, node, previous);
    }

    String appendTo(String message) {
        String trace = render();
        if (trace.isBlank() || message.contains("Test trace:")) {
            return message;
        }
        return message + System.lineSeparator() + System.lineSeparator() + trace;
    }

    synchronized String timeoutMessage(String message) {
        return appendTo(message);
    }

    synchronized String render() {
        String body = renderBody();
        return body.isBlank() ? "" : "Test trace:" + System.lineSeparator()
                                     + System.lineSeparator() + body + System.lineSeparator();
    }

    synchronized String renderBody() {
        List<TraceNode> visibleRoots = visibleRoots();
        if (visibleRoots.isEmpty() && missedMessages.isEmpty() && unexpectedMessages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        RenderState state = new RenderState();
        String currentPhase = null;
        for (TraceNode root : visibleRoots) {
            if (state.renderedNodes >= MAX_RENDERED_NODES) {
                break;
            }
            if (!Objects.equals(currentPhase, root.phase)) {
                if (currentPhase != null) {
                    builder.append(System.lineSeparator());
                }
                currentPhase = root.phase;
                builder.append(currentPhase);
            }
            renderNode(builder, root, "", state);
        }
        if (state.truncated) {
            builder.append(System.lineSeparator()).append("... trace truncated after ")
                    .append(MAX_RENDERED_NODES).append(" entries");
        }
        renderDiagnosticMessages(builder, "missed:", missedMessages);
        renderDiagnosticMessages(builder, "unexpected:", unexpectedMessages);
        return builder.toString();
    }

    private TraceNode messageNode(Message message, MessageType messageType, String topic, String namespace) {
        return messageNodes.computeIfAbsent(message.getMessageId(), ignored -> {
            TraceNode node = TraceNode.message(phase, messageType, topic, namespace, message);
            roots.add(node);
            return node;
        }).withNamespace(namespace);
    }

    private void attach(TraceNode parent, TraceNode node) {
        if (parent == null || parent == node || node.parent != null || isAncestor(node, parent)) {
            return;
        }
        roots.remove(node);
        parent.children.add(node);
        node.parent = parent;
    }

    private boolean isAncestor(TraceNode candidate, TraceNode node) {
        TraceNode current = node;
        while (current != null) {
            if (current == candidate) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }

    private void finishHandling(TraceNode handlerNode, Object result, Throwable error) {
        synchronized (this) {
            handlerNode.status = error == null ? "returned " + describeResult(result)
                    : "failed with " + describeError(error);
        }
    }

    private void finishAction(TraceNode actionNode, Object result, Throwable error) {
        synchronized (this) {
            actionNode.status = error == null ? result == null ? null : "returned " + describeResult(result)
                    : "failed with " + describeError(error);
        }
    }

    private void restoreActiveHandler(TraceNode previous) {
        if (previous == null) {
            activeParent.remove();
        } else {
            activeParent.set(previous);
        }
    }

    private void renderNode(StringBuilder builder, TraceNode node, String prefix, RenderState state) {
        if (!isVisible(node)) {
            return;
        }
        if (state.renderedNodes >= MAX_RENDERED_NODES) {
            state.truncated = true;
            return;
        }
        state.renderedNodes++;
        builder.append(System.lineSeparator()).append(prefix)
                .append(connector(node))
                .append(node.render());
        String childPrefix = prefix + "    ";
        List<TraceNode> visibleChildren = visibleChildren(node);
        for (TraceNode child : visibleChildren) {
            renderNode(builder, child, childPrefix, state);
        }
    }

    private static String describeHandler(HandlerInvoker invoker) {
        return simpleTypeName(invoker.getTargetClass());
    }

    private static String connector(TraceNode node) {
        return node.nodeType == NodeType.HANDLER ? "└ " : "- ";
    }

    private static String describeResult(Object result) {
        if (result == null) {
            return "null";
        }
        return simpleTypeName(result.getClass());
    }

    private static String describeError(Throwable error) {
        return simpleTypeName(error.getClass());
    }

    private List<TraceNode> visibleRoots() {
        return roots.stream().filter(this::isVisible).toList();
    }

    private List<TraceNode> visibleChildren(TraceNode node) {
        return node.children.stream().filter(this::isVisible).toList();
    }

    private boolean isVisible(TraceNode node) {
        if (node.nodeType != NodeType.MESSAGE) {
            return true;
        }
        return switch (node.messageType) {
            case METRICS, ERROR, RESULT -> visibleInfrastructureTypes.contains(node.messageType);
            default -> true;
        };
    }

    private static String describeMessagePayload(Message message, MessageType messageType) {
        if (messageType == MessageType.WEBREQUEST && message instanceof WebRequest request) {
            return request.getMethod() + " " + request.getPath();
        }
        if (messageType == MessageType.WEBRESPONSE && message instanceof WebResponse response) {
            return response.getStatus() == null ? simpleTypeName(message.getPayloadClass())
                    : response.getStatus() + " " + simpleTypeName(message.getPayloadClass());
        }
        return simpleTypeName(message.getPayloadClass());
    }

    private static String describeDiagnosticMessage(MessageType messageType, String topic, Object message) {
        StringBuilder builder = new StringBuilder().append(messageType).append(' ')
                .append(describeDiagnosticPayload(message, messageType));
        if (topic != null) {
            builder.append(" [topic=").append(topic).append(']');
        }
        return builder.toString();
    }

    private static String describeDiagnosticPayload(Object item, MessageType messageType) {
        if (item instanceof Message message) {
            return describeMessagePayload(message, messageType);
        }
        if (item instanceof Class<?> type) {
            return simpleTypeName(type);
        }
        if (item instanceof Predicate<?>) {
            return "matching predicate";
        }
        if (isMatcher(item)) {
            return describeMatcher(item);
        }
        return item == null ? "null" : simpleTypeName(item.getClass());
    }

    private static boolean isMatcher(Object item) {
        try {
            return item != null && Class.forName("org.hamcrest.Matcher").isInstance(item);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String describeMatcher(Object matcher) {
        try {
            Class<?> descriptionClass = Class.forName("org.hamcrest.Description");
            Object description = Class.forName("org.hamcrest.StringDescription").getConstructor().newInstance();
            Class.forName("org.hamcrest.Matcher").getMethod("describeTo", descriptionClass)
                    .invoke(matcher, description);
            String text = description.toString().replaceAll("\\R+", " ").strip();
            return text.isBlank() ? "matching matcher" : "matching " + text;
        } catch (Exception e) {
            return "matching matcher";
        }
    }

    private void renderDiagnosticMessages(StringBuilder builder, String heading, List<String> messages) {
        if (messages.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(System.lineSeparator()).append(System.lineSeparator());
        }
        builder.append(heading);
        messages.forEach(message -> builder.append(System.lineSeparator())
                .append("- ").append(message));
    }

    private static boolean isVisibleNamespace(String namespace) {
        return namespace != null && !namespace.isBlank()
               && !namespace.equals("public")
               && !namespace.equals("default");
    }

    private static String simpleTypeName(Class<?> type) {
        if (type == null) {
            return "Unknown";
        }
        if (type.isArray()) {
            return simpleTypeName(type.getComponentType()) + "[]";
        }
        String simpleName = type.getSimpleName();
        return simpleName.isBlank() ? type.getName() : simpleName;
    }

    static final class HandlerScope {
        private final FixtureTrace trace;
        private final TraceNode handlerNode;
        private final TraceNode previous;

        private HandlerScope(FixtureTrace trace, TraceNode handlerNode, TraceNode previous) {
            this.trace = trace;
            this.handlerNode = handlerNode;
            this.previous = previous;
        }

        void close(Object result, Throwable error) {
            trace.finishHandling(handlerNode, result, error);
            trace.restoreActiveHandler(previous);
        }
    }

    private static final class RenderState {
        private int renderedNodes;
        private boolean truncated;
    }

    static final class TraceScope implements AutoCloseable {
        private final FixtureTrace trace;
        private final TraceNode previous;

        private TraceScope(FixtureTrace trace, TraceNode previous) {
            this.trace = trace;
            this.previous = previous;
        }

        @Override
        public void close() {
            trace.restoreActiveHandler(previous);
        }
    }

    static final class ActionScope {
        private final FixtureTrace trace;
        private final TraceNode actionNode;
        private final TraceNode previous;

        private ActionScope(FixtureTrace trace, TraceNode actionNode, TraceNode previous) {
            this.trace = trace;
            this.actionNode = actionNode;
            this.previous = previous;
        }

        void close(Object result, Throwable error) {
            trace.finishAction(actionNode, result, error);
            trace.restoreActiveHandler(previous);
        }
    }

    private static final class TraceNode {
        private final String phase;
        private final NodeType nodeType;
        private final List<TraceNode> children = new ArrayList<>();
        private TraceNode parent;

        private MessageType messageType;
        private String topic;
        private String namespace;
        private String messageId;
        private String messageDescription;
        private String handlerDescription;
        private String consumerName;
        private String status = "started";

        private TraceNode(String phase, NodeType nodeType) {
            this.phase = phase;
            this.nodeType = nodeType;
        }

        static TraceNode message(String phase, MessageType messageType, String topic, String namespace,
                                 Message message) {
            TraceNode node = new TraceNode(phase, NodeType.MESSAGE);
            node.messageType = messageType;
            node.topic = topic;
            node.namespace = namespace;
            node.messageId = message.getMessageId();
            node.messageDescription = describeMessagePayload(message, messageType);
            return node;
        }

        static TraceNode time(String phase, String description) {
            TraceNode node = new TraceNode(phase, NodeType.TIME);
            node.messageDescription = description;
            return node;
        }

        static TraceNode action(String phase, String description) {
            TraceNode node = new TraceNode(phase, NodeType.ACTION);
            node.messageDescription = description;
            return node;
        }

        static TraceNode handler(String phase, String handlerDescription, String messageId, String consumerName) {
            TraceNode node = new TraceNode(phase, NodeType.HANDLER);
            node.handlerDescription = handlerDescription;
            node.messageId = messageId;
            node.consumerName = consumerName;
            return node;
        }

        TraceNode withNamespace(String namespace) {
            if (this.namespace == null) {
                this.namespace = namespace;
            }
            return this;
        }

        boolean isRequestMessage() {
            return nodeType == NodeType.MESSAGE && messageType.isRequest();
        }

        boolean isRequestWithoutHandler() {
            return isRequestMessage() && children.stream().noneMatch(child -> child.nodeType == NodeType.HANDLER);
        }

        String render() {
            if (nodeType == NodeType.HANDLER) {
                StringBuilder builder = new StringBuilder("handler ").append(handlerDescription);
                if (consumerName != null) {
                    builder.append(" [consumer=").append(consumerName).append(']');
                }
                if (shouldRenderHandlerStatus()) {
                    builder.append(" [").append(status).append(']');
                }
                return builder.toString();
            }
            if (nodeType == NodeType.TIME) {
                return messageDescription;
            }
            if (nodeType == NodeType.ACTION) {
                StringBuilder builder = new StringBuilder(messageDescription);
                if (status != null) {
                    builder.append(" [").append(status).append(']');
                }
                return builder.toString();
            }
            StringBuilder builder = new StringBuilder()
                    .append(messageType).append(' ').append(messageDescription);
            if (topic != null) {
                builder.append(" [topic=").append(topic).append(']');
            }
            if (isVisibleNamespace(namespace)) {
                builder.append(" [namespace=").append(namespace).append(']');
            }
            if (isRequestWithoutHandler()) {
                builder.append(" (no handler invocation observed)");
            }
            return builder.toString();
        }

        private boolean shouldRenderHandlerStatus() {
            if (status.startsWith("failed with ")) {
                return true;
            }
            return parent == null || parent.messageType == null || parent.messageType.isRequest();
        }
    }

    private enum NodeType {
        MESSAGE, HANDLER, TIME, ACTION
    }
}
