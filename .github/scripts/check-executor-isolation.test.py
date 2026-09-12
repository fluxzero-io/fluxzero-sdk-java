#!/usr/bin/env python3
"""Regression tests for the production-bytecode executor guard."""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("guard", Path(__file__).with_name("check-executor-isolation.py"))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ExecutorIsolationTest(unittest.TestCase):
    def rejected(self, owner, name, descriptor):
        return list(guard.violations("example/Test", [(owner, name, descriptor)]))

    def test_async_overloads(self):
        for owner in ("java/util/concurrent/CompletableFuture", "java/util/concurrent/CompletionStage"):
            self.assertTrue(self.rejected(owner, "thenApplyAsync", "(Ljava/util/function/Function;)Ljava/util/concurrent/CompletableFuture;"))
            self.assertFalse(self.rejected(owner, "thenApplyAsync", "(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
            self.assertFalse(self.rejected(owner, "thenApply", "(Ljava/util/function/Function;)Ljava/util/concurrent/CompletableFuture;"))

    def test_delay_defaults_and_parallel_arrays(self):
        self.assertTrue(self.rejected("java/util/concurrent/CompletableFuture", "delayedExecutor", "(JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/Executor;"))
        self.assertTrue(self.rejected("java/util/concurrent/CompletableFuture", "defaultExecutor", "()Ljava/util/concurrent/Executor;"))
        self.assertTrue(self.rejected("java/util/concurrent/ForkJoinPool", "commonPool", "()Ljava/util/concurrent/ForkJoinPool;"))
        self.assertTrue(self.rejected("java/util/Arrays", "parallelSort", "([I)V"))

    def test_stream_isolation_requires_reviewed_owner_and_boundary(self):
        methods = [("java/util/List", "parallelStream", "()Ljava/util/stream/Stream;")]
        self.assertTrue(list(guard.violations("example/NewStream", methods)))
        owner = next(iter(guard.ISOLATED_STREAMS))
        self.assertTrue(list(guard.violations(owner, methods)))
        methods.append(("io/fluxzero/common/ObjectUtils", "inParallel", "(Ljava/util/function/Supplier;)Ljava/lang/Object;"))
        self.assertFalse(list(guard.violations(owner, methods)))

    def test_compiled_overloads_and_method_reference(self):
        source = '''
            import java.util.concurrent.*;
            import java.util.function.Supplier;
            class ExecutorProbe {
                static CompletableFuture<Void> implicit() { return CompletableFuture.runAsync(() -> {}); }
                static CompletableFuture<Void> explicit(Executor e) { return CompletableFuture.runAsync(() -> {}, e); }
                static Supplier<ForkJoinPool> reference() { return ForkJoinPool::commonPool; }
                static final long LONG_CONSTANT = 123456789L;
                static final double DOUBLE_CONSTANT = 1.25;
            }
        '''
        with tempfile.TemporaryDirectory(prefix="executor-guard-") as directory:
            path = Path(directory) / "ExecutorProbe.java"
            path.write_text(source)
            javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if "JAVA_HOME" in os.environ else "javac"
            subprocess.run([javac, "-proc:none", str(path)], check=True, capture_output=True)
            owner, methods = guard.references(path.with_suffix(".class"))
            self.assertEqual(owner, "ExecutorProbe")
            failures = list(guard.violations(owner, methods))
            self.assertEqual(len(failures), 2, failures)
            self.assertTrue(any("runAsync" in failure for failure in failures))
            self.assertTrue(any("commonPool" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
