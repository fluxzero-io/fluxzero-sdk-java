#!/usr/bin/env python3
"""Reject implicit JVM-common-pool scheduling in compiled production classes.

Run after the reactor build; optional arguments are additional target/classes roots (e.g. Runtime).
JDK/third-party internals and caller-supplied executors are outside this bytecode guard.
"""

from pathlib import Path
import struct
import sys


def references(path):
    data = path.read_bytes()
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"Invalid class file: {path}")
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    offset, index = 10, 1
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
              11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            pool[index] = (tag, data[offset:offset + length].decode("utf-8", errors="replace"))
            offset += length
        else:
            length = widths[tag]
            pool[index] = (tag, data[offset:offset + length])
            offset += length
            if tag in (5, 6):
                index += 1
        index += 1

    def text(index):
        return pool[index][1]

    def name(index):
        return text(struct.unpack(">H", pool[index][1])[0])

    class_index = struct.unpack_from(">H", data, offset + 2)[0]
    methods = []
    for entry in pool:
        if entry is not None and entry[0] in (10, 11):
            owner, signature = struct.unpack(">HH", entry[1])
            method, descriptor = struct.unpack(">HH", pool[signature][1])
            methods.append((name(owner), text(method), text(descriptor)))
    return name(class_index), methods


# Parallel stream terminals must execute inside ObjectUtils.inParallel/runInParallel.
ISOLATED_STREAMS = {
    "io/fluxzero/sdk/persisting/repository/ModelReplayCursor$Session",
}
# Explicit external Stream API; and a Runtime terminal already enclosed in its owned fetch ForkJoinPool.
CALLER_OR_OWNED_STREAMS = {
    "io/fluxzero/sdk/persisting/eventsourcing/AggregateEventStream",
    "io/fluxzero/runtime/tracking/ReadMessageStore",
}


def violations(class_name, methods):
    for owner, method, descriptor in methods:
        reason = None
        if owner in {"java/util/concurrent/CompletableFuture", "java/util/concurrent/CompletionStage"}:
            if (method.endswith("Async") or method == "delayedExecutor") and \
                    "Ljava/util/concurrent/Executor;" not in descriptor.split(")", 1)[0]:
                reason = "asynchronous operation without an explicit executor"
            if method == "defaultExecutor":
                reason = "implicit CompletableFuture default executor"
        if owner == "java/util/concurrent/ForkJoinPool" and method in {"commonPool", "getCommonPoolParallelism"}:
            reason = "JVM common-pool dependency"
        if owner == "java/util/concurrent/Executors" and method == "newWorkStealingPool":
            reason = "unnamed work-stealing pool; declare ownership explicitly"
        if owner == "java/util/Arrays" and method in {"parallelSort", "parallelPrefix", "parallelSetAll"}:
            reason = "parallel array operation without explicit isolation"
        if method in {"parallel", "parallelStream"} and owner.startswith("java/util/"):
            if class_name in ISOLATED_STREAMS:
                if not any(o == "io/fluxzero/common/ObjectUtils" and m in {"inParallel", "runInParallel"}
                           for o, m, _ in methods):
                    reason = "parallel terminal has lost its isolated-pool boundary"
            elif class_name not in CALLER_OR_OWNED_STREAMS:
                reason = "new parallel stream requires an explicit execution-owner review"
        if reason:
            yield f"{class_name}: {owner}.{method}{descriptor}: {reason}"


def main():
    root = Path(__file__).resolve().parents[2]
    roots = [root / module / "target/classes" for module in ("common", "sdk", "proxy", "test-server")]
    roots.extend(Path(argument) for argument in sys.argv[1:])
    failures = []
    count = 0
    for classes in roots:
        files = list(classes.rglob("*.class"))
        if not files:
            failures.append(f"No production classes found in {classes}; build first")
        for path in files:
            class_name, methods = references(path)
            failures.extend(violations(class_name, methods))
            count += 1
    if failures:
        sys.exit("\n".join(failures))
    print(f"Executor isolation checked: {count} production classes; no unapproved common-pool scheduling.")


if __name__ == "__main__":
    main()
