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

package io.fluxzero.common.api.tracking;

import io.fluxzero.common.api.SerializedMessage;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Versioned, conservative projection of an application's effective consumer blueprint. Filters are delivery
 * optimizations, never authorization rules. Missing constraints and unknown versions allow delivery.
 *
 * @param subscriptionId unique effective-consumer identity shared by its transport sessions; null scopes to one session
 * @param revision monotonically increasing revision within one client session and consumer
 * @param version filter language version; currently {@code 1}
 * @param format payload format for which {@code payloadTypes} is complete; other formats remain eligible
 * @param payloadTypes eligible payload names/revisions when payloadRestricted is true
 * @param webRoutes eligible web routes when webRestricted is true
 * @param payloadRestricted whether payloadTypes is a complete constraint
 * @param webRestricted whether webRoutes is a complete constraint
 * @param upcastInputs inputs that must bypass both constraints because upcasting may change type or metadata
 * @param referenceOnly whether this read refers to a previously supplied snapshot instead of carrying its constraints
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
public record ConsumerFilter(String subscriptionId, long revision, int version, String format, List<PayloadType> payloadTypes,
                             List<WebRoute> webRoutes, List<PayloadType> upcastInputs,
                             boolean payloadRestricted, boolean webRestricted, boolean referenceOnly) {
    public static final int VERSION = 1;

    public ConsumerFilter {
        payloadTypes = payloadTypes == null ? List.of() : List.copyOf(payloadTypes);
        webRoutes = webRoutes == null ? List.of() : List.copyOf(webRoutes);
        upcastInputs = upcastInputs == null ? List.of() : List.copyOf(upcastInputs);
    }

    /**
     * Builds a selection from complete handler facts. Null lists indicate unknown constraints; explicit flags on the
     * wire preserve that distinction even for peers whose JSON collection handling normalizes null to empty.
     */
    public static ConsumerFilter select(String subscriptionId, long revision, int version, String format,
                                        List<PayloadType> payloadTypes, List<WebRoute> webRoutes,
                                        List<PayloadType> upcastInputs) {
        return new ConsumerFilter(subscriptionId, revision, version, format, payloadTypes, webRoutes, upcastInputs,
                                  payloadTypes != null, webRoutes != null, false);
    }

    /** A serialized input name, optionally limited to one revision or matching an entire package prefix. */
    public record PayloadType(String name, Integer revision, boolean prefix) {
        public PayloadType {
            Objects.requireNonNull(name, "name");
        }

        public PayloadType(String name, Integer revision) {
            this(name, revision, false);
        }
    }

    /** A method/origin and an SDK-compiled raw-path regular expression. Null method/origin mean any method/no origin. */
    public record WebRoute(String method, String origin, String pathRegex) {
        public WebRoute {
            Objects.requireNonNull(pathRegex, "pathRegex");
        }
    }

    /** Builds an unrestricted projection for metadata that cannot safely be narrowed. */
    public static ConsumerFilter unrestricted() {
        return ConsumerFilter.select(null, 0L, VERSION, null, null, null, List.of());
    }

    /** Combines alternative handlers; an unknown constraint widens the corresponding selection. */
    public static ConsumerFilter union(Collection<ConsumerFilter> filters) {
        List<PayloadType> payloads = new ArrayList<>();
        List<WebRoute> routes = new ArrayList<>();
        List<PayloadType> casters = new ArrayList<>();
        String format = null;
        boolean payloadsKnown = true, routesKnown = true;
        boolean first = true;
        for (ConsumerFilter filter : filters) {
            if (filter == null || filter.version != VERSION || filter.referenceOnly) {
                return unrestricted();
            }
            if (!first && !Objects.equals(format, filter.format)) {
                payloadsKnown = false;
            }
            format = filter.format;
            first = false;
            payloadsKnown &= filter.payloadRestricted;
            routesKnown &= filter.webRestricted;
            if (filter.payloadTypes != null) {
                payloads.addAll(filter.payloadTypes);
            }
            if (filter.webRoutes != null) {
                routes.addAll(filter.webRoutes);
            }
            casters.addAll(filter.upcastInputs);
        }
        return ConsumerFilter.select(null, 0L, VERSION, format, payloadsKnown ? payloads.stream().distinct().toList() : null,
                                  routesKnown ? routes.stream().distinct().toList() : null,
                                  casters.stream().distinct().toList());
    }

    /** Returns the same contract with its lifecycle revision and current serializer migration inputs. */
    public ConsumerFilter forRevision(String subscriptionId, long revision, String format, List<PayloadType> upcastInputs) {
        return new ConsumerFilter(subscriptionId, revision, version, format, payloadTypes, webRoutes, upcastInputs,
                                  payloadRestricted, webRestricted, false);
    }

    /** Compact reference to a full snapshot. An unknown reference must be resolved before claiming or scanning. */
    public ConsumerFilter asReference() {
        return new ConsumerFilter(subscriptionId, revision, version, null, List.of(), List.of(), List.of(),
                                  false, false, true);
    }

    /** Compiles once at the subscription boundary; no regex compilation or application class loading occurs per message. */
    public Predicate<SerializedMessage> compile() {
        if (referenceOnly || version != VERSION || !payloadRestricted && !webRestricted) {
            return message -> true;
        }
        Predicate<SerializedMessage> casters = compilePayloads(upcastInputs);
        Predicate<SerializedMessage> payloads = !payloadRestricted ? message -> true : compilePayloads(payloadTypes);
        List<CompiledRoute> routes = !webRestricted ? null : webRoutes.stream()
                .map(route -> new CompiledRoute(route, Pattern.compile(route.pathRegex))).toList();
        return message -> {
            if (message.getType() == null || casters.test(message)) {
                return true;
            }
            if ((format == null || Objects.equals(format, message.data().getFormat())) && !payloads.test(message)) {
                return false;
            }
            if (routes == null) {
                return true;
            }
            try {
                if (message.getMetadata() == null) {
                    return true;
                }
                String url = message.getMetadata().get("url");
                String method = message.getMetadata().get("method");
                if (url == null || method == null) {
                    return true;
                }
                URI uri = URI.create(url.startsWith("/") || url.contains("://") ? url : "/" + url);
                String path = uri.getRawPath();
                path = path == null || path.isBlank() ? "/" : path.startsWith("/") ? path : "/" + path;
                String origin = uri.getScheme() == null || uri.getHost() == null ? null
                        : uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
                for (CompiledRoute route : routes) {
                    if ((route.route.method == null || route.route.method.equals(method))
                        && Objects.equals(route.route.origin, origin) && route.path.matcher(path).matches()) {
                        return true;
                    }
                }
                return false;
            } catch (IllegalArgumentException e) {
                // Preserve the SDK's normal malformed-request handling instead of hiding the request.
                return true;
            }
        };
    }

    private static Predicate<SerializedMessage> compilePayloads(List<PayloadType> inputs) {
        Set<String> allRevisions = new HashSet<>();
        Map<String, Set<Integer>> revisions = new HashMap<>();
        List<PayloadType> prefixes = new ArrayList<>();
        for (PayloadType input : inputs) {
            if (input.prefix) {
                prefixes.add(input);
            } else if (input.revision == null) {
                allRevisions.add(input.name);
            } else {
                revisions.computeIfAbsent(input.name, ignored -> new HashSet<>()).add(input.revision);
            }
        }
        return message -> {
            String type = message.getType();
            if (type == null || allRevisions.contains(type)) {
                return true;
            }
            Set<Integer> accepted = revisions.get(type);
            if (accepted != null && accepted.contains(message.getRevision())) {
                return true;
            }
            for (PayloadType prefix : prefixes) {
                if (type.startsWith(prefix.name) && (prefix.revision == null || prefix.revision == message.getRevision())) {
                    return true;
                }
            }
            return false;
        };
    }

    private record CompiledRoute(WebRoute route, Pattern path) {
    }
}
