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

package io.fluxzero.sdk.browser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reflection-free browser codec registry filled by generated application code.
 */
public final class BrowserCodecRegistry {
    private final Map<String, BrowserCodec> codecs = new LinkedHashMap<>();
    private final Map<String, BrowserCaster> upcasters = new LinkedHashMap<>();
    private final Map<String, BrowserCaster> downcasters = new LinkedHashMap<>();

    public void register(String typeName, BrowserCodec codec) {
        codecs.put(Objects.requireNonNull(typeName, "typeName"), Objects.requireNonNull(codec, "codec"));
    }

    public Map<String, Object> encode(String typeName, Object value) {
        return codec(typeName).encode(value);
    }

    public Object decode(String typeName, Map<String, Object> data) {
        return codec(typeName).decode(data);
    }

    public void registerUpcaster(String typeName, int revision, BrowserCaster caster) {
        upcasters.put(key(typeName, revision), Objects.requireNonNull(caster, "caster"));
    }

    public Object upcast(String typeName, int revision, Object value) {
        BrowserCaster caster = upcasters.get(key(typeName, revision));
        return caster == null ? value : caster.cast(value);
    }

    public void registerDowncaster(String typeName, int revision, BrowserCaster caster) {
        downcasters.put(key(typeName, revision), Objects.requireNonNull(caster, "caster"));
    }

    public Object downcast(String typeName, int revision, Object value) {
        BrowserCaster caster = downcasters.get(key(typeName, revision));
        return caster == null ? value : caster.cast(value);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("codecs", codecs.size());
        snapshot.put("upcasters", upcasters.size());
        snapshot.put("downcasters", downcasters.size());
        return snapshot;
    }

    private BrowserCodec codec(String typeName) {
        BrowserCodec codec = codecs.get(typeName);
        if (codec == null) {
            throw new IllegalArgumentException("No browser codec registered for " + typeName);
        }
        return codec;
    }

    private static String key(String typeName, int revision) {
        return typeName + "#" + revision;
    }
}
