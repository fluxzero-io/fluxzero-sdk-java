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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
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
 * @param version filter language version; currently {@code 2}
 * @param format payload format for which {@code payloadTypes} is complete; other formats remain eligible
 * @param payloadTypes eligible payload names/revisions when payloadRestricted is true
 * @param routes eligible logical routes when routeRestricted is true
 * @param payloadRestricted whether payloadTypes is a complete constraint
 * @param routeRestricted whether routes is a complete constraint
 * @param upcastInputs inputs that must bypass both constraints because upcasting may change type or metadata
 * @param referenceOnly whether this read refers to a previously supplied snapshot instead of carrying its constraints
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
public record ConsumerFilter(String subscriptionId, long revision, int version, String format, List<PayloadType> payloadTypes,
                             List<RoutePattern> routes, List<PayloadType> upcastInputs,
                             boolean payloadRestricted, boolean routeRestricted, boolean referenceOnly) {
    public static final int VERSION = 2;

    public ConsumerFilter {
        payloadTypes = payloadTypes == null ? List.of() : List.copyOf(payloadTypes);
        routes = routes == null ? List.of() : List.copyOf(routes);
        upcastInputs = upcastInputs == null ? List.of() : List.copyOf(upcastInputs);
    }

    /**
     * Builds a selection from complete handler facts. Null lists indicate unknown constraints; explicit flags on the
     * wire preserve that distinction even for peers whose JSON collection handling normalizes null to empty.
     */
    public static ConsumerFilter select(String subscriptionId, long revision, int version, String format,
                                        List<PayloadType> payloadTypes, List<RoutePattern> routes,
                                        List<PayloadType> upcastInputs) {
        return new ConsumerFilter(subscriptionId, revision, version, format, payloadTypes, routes, upcastInputs,
                                  payloadTypes != null, routes != null, false);
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

    /**
     * Generic route matcher. The prefix regex consumes the beginning of the route; the value regex then matches the
     * entire remaining region with opaque, anchoring bounds. This preserves anchors and lookarounds of a compiled
     * value pattern without allowing it to inspect a producer-specific prefix. An empty prefix matches the full route.
     */
    public record RoutePattern(String prefixRegex, String valueRegex) {
        public RoutePattern {
            Objects.requireNonNull(prefixRegex, "prefixRegex");
            Objects.requireNonNull(valueRegex, "valueRegex");
        }
    }

    /** Builds an unrestricted projection for metadata that cannot safely be narrowed. */
    public static ConsumerFilter unrestricted() {
        return ConsumerFilter.select(null, 0L, VERSION, null, null, null, List.of());
    }

    /** Combines alternative handlers; an unknown constraint widens the corresponding selection. */
    public static ConsumerFilter union(Collection<ConsumerFilter> filters) {
        List<PayloadType> payloads = new ArrayList<>();
        List<RoutePattern> routes = new ArrayList<>();
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
            routesKnown &= filter.routeRestricted;
            if (filter.payloadTypes != null) {
                payloads.addAll(filter.payloadTypes);
            }
            if (filter.routes != null) {
                routes.addAll(filter.routes);
            }
            casters.addAll(filter.upcastInputs);
        }
        return ConsumerFilter.select(null, 0L, VERSION, format, payloadsKnown ? payloads.stream().distinct().toList() : null,
                                  routesKnown ? routes.stream().distinct().toList() : null,
                                  casters.stream().distinct().toList());
    }

    /** Returns the same contract with its lifecycle revision and current serializer migration inputs. */
    public ConsumerFilter forRevision(String subscriptionId, long revision, String format, List<PayloadType> upcastInputs) {
        return new ConsumerFilter(subscriptionId, revision, version, format, payloadTypes, routes, upcastInputs,
                                  payloadRestricted, routeRestricted, false);
    }

    /** Compact reference to a full snapshot. An unknown reference must be resolved before claiming or scanning. */
    public ConsumerFilter asReference() {
        return new ConsumerFilter(subscriptionId, revision, version, null, List.of(), List.of(), List.of(),
                                  false, false, true);
    }

    /** Compiles once at the subscription boundary; no regex compilation or application class loading occurs per message. */
    public Predicate<SerializedMessage> compile() {
        if (referenceOnly || version != VERSION || !payloadRestricted && !routeRestricted) {
            return message -> true;
        }
        Predicate<SerializedMessage> casters = compilePayloads(upcastInputs);
        Predicate<SerializedMessage> payloads = !payloadRestricted ? message -> true : compilePayloads(payloadTypes);
        Map<String, List<Pattern>> groupedRoutes = new LinkedHashMap<>();
        if (routeRestricted) {
            routes.forEach(route -> groupedRoutes.computeIfAbsent(route.prefixRegex, ignored -> new ArrayList<>())
                    .add(Pattern.compile(route.valueRegex)));
        }
        List<RouteGroup> compiledRoutes = !routeRestricted ? null : groupedRoutes.entrySet().stream()
                .map(entry -> new RouteGroup(Pattern.compile(entry.getKey()), List.copyOf(entry.getValue())))
                .toList();
        return message -> {
            if (message.getType() != null) {
                if (casters.test(message)) {
                    return true;
                }
                if ((format == null || Objects.equals(format, message.data().getFormat())) && !payloads.test(message)) {
                    return false;
                }
            }
            if (compiledRoutes == null || message.getRoute() == null) {
                return true;
            }
            String route = message.getRoute();
            for (RouteGroup candidate : compiledRoutes) {
                var prefix = candidate.prefix.matcher(route);
                if (prefix.lookingAt()) {
                    for (Pattern value : candidate.values) {
                        if (value.matcher(route).region(prefix.end(), route.length()).matches()) {
                            return true;
                        }
                    }
                }
            }
            return false;
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

    private record RouteGroup(Pattern prefix, List<Pattern> values) {
    }
}
