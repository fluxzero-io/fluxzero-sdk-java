import { readFileSync } from "node:fs";
import test from "node:test";
import assert from "node:assert/strict";

const manifest = JSON.parse(readFileSync(new URL("../resources/conformance/feature-manifest.json", import.meta.url), "utf8"));

const required = [
  "handler.command",
  "handler.query",
  "handler.event",
  "handler.notification",
  "handler.error",
  "handler.metrics",
  "handler.result",
  "handler.custom",
  "handler.document",
  "handler.schedule",
  "handler.disabled",
  "handler.passive",
  "handler.skipExpiredRequests",
  "handler.allowedClasses",
  "handler.local",
  "handler.tracked",
  "handler.consumer",
  "gateway.dispatchInterceptor",
  "gateway.handlerInterceptor",
  "gateway.batchInterceptor",
  "gateway.recursivePublicationGuard",
  "gateway.timeout",
  "gateway.correlation",
  "gateway.routingKey",
  "gateway.dataProtection",
  "gateway.contentFiltering",
  "gateway.errorReporting",
  "modeling.trackSelf",
  "modeling.stateful",
  "modeling.aggregate",
  "modeling.entity",
  "modeling.apply",
  "modeling.snapshot",
  "modeling.repository",
  "modeling.selfHandling",
  "persistence.keyValue",
  "persistence.eventStore",
  "persistence.snapshotStore",
  "persistence.documentStore",
  "persistence.search",
  "persistence.cache",
  "web.method",
  "web.path",
  "web.pathParam",
  "web.queryParam",
  "web.headerParam",
  "web.cookieParam",
  "web.formParam",
  "web.bodyParam",
  "web.responseMapping",
  "web.routeMatching",
  "web.socket",
  "auth.userProvider",
  "auth.requiresUser",
  "auth.requiresAnyRole",
  "auth.forbidsUser",
  "auth.noUserRequired",
  "validation.request",
  "validation.constraints",
  "serialization.registerType",
  "serialization.generatedCodec",
  "serialization.upcast",
  "serialization.downcast",
  "serialization.filterContent"
];

test("manifest covers every browser-native app-level feature", () => {
  const actual = new Set(manifest.features);
  const missing = required.filter((feature) => !actual.has(feature));
  assert.deepEqual(missing, []);
});

test("manifest exposes the JS conformance API contract", () => {
  assert.equal(manifest.browserApi.runAll, "window.fluxzeroConformance.runAll()");
  assert.equal(manifest.browserApi.run, "window.fluxzeroConformance.run(name)");
  assert.equal(manifest.browserApi.report, "window.fluxzeroConformance.report()");
});

test("manifest does not hide duplicate feature keys", () => {
  assert.equal(new Set(manifest.features).size, manifest.features.length);
});
