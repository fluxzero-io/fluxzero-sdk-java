# JDBC message staging cleanup — S47

Status: Runtime checkpoint `5d9dac9e`; functional and storage-maintenance acceptance complete, clean-host canonical
throughput repin still pending.

## Scope and source identity

- SDK branch base: `2ea634cdcc1`, including the current message-broker recovery fixes from `origin/main`.
- Runtime control: `0bce458e`, including the database recovery slots, bounded prepared LTS writes and WebSocket fixes.
- Runtime checkpoint: `5d9dac9e` (`fix(jdbc): restore safe staging truncation`).
- Java 25, embedded Runtime, PostgreSQL at `127.0.0.1:64217`; no database tuning was changed.
- `DELETE` remains selectable only by the explicit benchmark constructor. All ordinary `JdbcMessageStore`
  constructors use `TRUNCATE`.

## Why a direct replacement was unsafe

PostgreSQL `TRUNCATE` is not MVCC-safe for a repeatable-read transaction whose snapshot predates the truncation. The
old two-query read could observe neither representation when one transaction atomically copied staging rows to LTS
and truncated staging:

1. the reader pins its old snapshot while reading LTS;
2. the writer inserts the compact LTS row, truncates staging and commits;
3. the old snapshot cannot see the new LTS row, while PostgreSQL exposes the truncated staging table as empty.

The checkpoint uses two complementary protocols:

- ordinary tracking reads select LTS and staging in **one SQL statement**; PostgreSQL obtains both relation locks for
  one statement, placing the atomic compaction wholly before or after it;
- repeatable-read model history loads take `ACCESS SHARE` on the event staging table as their **first transaction
  statement**, then read model membership and the exact immutable event payloads through that same connection. A
  compaction already in progress finishes before the snapshot; a later `TRUNCATE` waits for the reader. This avoids a
  nested pool connection and preserves the model state boundary.

Direct PostgreSQL validation covered both lock orderings. Writer-first produced `LTS=1, staging=0` after the reader
waited; reader-first produced `LTS=0, staging=1` and held the writer until commit. Evidence:
`/private/tmp/s47-staging-lock-proof.txt#5eaf5f71d26ba98302032bee4b6e456dd8ed49b51f557835e63f1d7fed1e8fce`.

## Forced-staging maintenance comparison

Both final runs wrote exactly 786,432 durable 32-byte result messages, used two producers, a 65,536-message request
window, backlog/group sizes 4,096/128, 32 insert threads and a 64-connection pool. Direct-tail writes were disabled so
the test exercised staging cleanup continuously. Both verified zero ordering or overlap violations.

| Cleanup | Throughput | p50 / p95 / p99 latency | Staging inserts / deletes | Dead tuples | Final staging relation |
| --- | ---: | ---: | ---: | ---: | ---: |
| `DELETE` | 40,200/s | 1,429 / 2,521 / 2,562 ms | 198,654 / 198,567 | **203,491** | **59.492 MiB** |
| `TRUNCATE` | **108,047/s** | **374 / 1,184 / 1,345 ms** | 68,613 / 0 | **0** | **0.078 MiB** |

On this deliberately staging-heavy route, `TRUNCATE` was 2.69x as fast, reduced p50 by 73.9%, eliminated dead
tuples and left a relation about 763x smaller. The different insert counts are a consequence of the faster writer
forming larger natural backlog batches; message count and correctness were identical.

Evidence:

- `DELETE`: `/private/tmp/staging-delete-matched-current.log#4b3747bef80e8fda4f7df182e383f06690f4e90919cbcf6ba2a7444e1018480f`
- `TRUNCATE`: `/private/tmp/staging-truncate-matched-current.log#47cb3710ed2e746db4e28a0bcca68342729c67253e09f7db095b7d23888ed3b1`

An earlier longer `DELETE` diagnostic additionally showed that autovacuum reduced the dead-tuple estimate but did not
shrink the already grown 167 MiB relation. Vacuum therefore does not remove the physical bloat concern.

## Full-route regression evidence

Spotlight and MediaAnalysis consumed roughly 70–120% host CPU during this sequence, so these are matched regression
diagnostics rather than new absolute pins. Every run used the latest SDK defaults and Java 25 with JFR disabled unless
explicitly marked.

| Route | Control runs | `TRUNCATE` runs | Geometric result | Correctness |
| --- | ---: | ---: | ---: | --- |
| full no-model command -> durable result, 10,485,760 measured | 590,388; 648,461/s | 533,636; 714,092/s | 618,744 -> 617,305/s (**-0.23%**) | exact results; no events |
| command -> model -> event + result, 4,194,304 measured | 198,662; 200,312/s | 185,778; 154,201/s | 199,485 -> 169,255/s (**-15.15%**) | exact commands, results, model/global events and 65,536 states |

The unprofiled model pair is deliberately **not** dismissed. It is also not a stable causal code result: the adjacent
batch-JFR pair reversed direction, at 174,864/s control versus 215,037/s checkpoint. More importantly, the weighted
active model-store capacity in those profiles improved from approximately 211,583/s to 244,565/s (+15.6%), while
the no-model ABBA result was neutral. This contradictory direction tracks the observed host-daemon swings and does
not identify a checkpoint mechanism that slows the model store.

Decision: accept the correctness and maintenance checkpoint, but do not replace the healthy historical no-model
~1M/s or model 425,606/s pins. Repeat the exact no-JFR control/checkpoint routes on a clean host before closing S47.

## Verification

- `JdbcMessageStoreTest`: 47/47, including writer-first combined reads, reader-first staging locks and materialized,
  packed and block exact reads within the protected snapshot.
- `JdbcModelCommitStoreTest`: 113/113.
- Complete Runtime reactor: `./mvnw -B install` — `BUILD SUCCESS` in 1m10s.
- Final focused evidence:
  `/private/tmp/s47-final-jdbc-message-store-test.log#b4eff6a25ba2a31ef7bad4cbb1fbe5984d71eb73c80a3811e07335c2c59b8fbd`.
- Full install evidence:
  `/private/tmp/s47-runtime-final-install.log#c6ed5f3a56a6e687deb3500e17e6707ac63abbb9051b893b3c230ff7bf515084`.

## Remaining clean-host gate

Run the same alternating pairs after `mds`, `mds_stores` and `mediaanalysisd` return to idle:

1. full no-model route: 10,485,760 warmup plus 10,485,760 measured, two senders;
2. full model/event/result route: 262,144 warmup plus 4,194,304 measured, one sender;
3. exact durable verification in every run, no JFR;
4. compare against direct parent `0bce458e`; historical 1M/425,606 pins are sanity checks, not the matched control.
