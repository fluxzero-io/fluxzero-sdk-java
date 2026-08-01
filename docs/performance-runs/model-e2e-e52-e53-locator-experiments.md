# E52/E53 model-stream locator experiments

These two same-binary opt-in experiments followed E51's PostgreSQL attribution without changing the canonical contract:
65,536 models, 65,536 warm-up commands, 1,048,576 measured commands, latest defaults `2026.07.27`, ordinary results,
published events and exact model checks. Both candidates were correct in their measured runs; neither is accepted.

## E52 — defer locator work to full pages

E52 stored each initial block's sorted hashes in the existing `model_hashes` column, hash-filtered the authoritative
tail and scheduled the derived locator only after a full 384-block page. Legacy empty-marker blocks retained the old
decode fallback and startup/destructive operations forced completion.

The intended database effect occurred: locator COPY calls fell from 2,594 to 72 and locator COPY execution fell from
7.429 s to 2.928 s. The synchronous consequence was worse: 3,960 hash-filtered authoritative-tail queries consumed
2.444 s. Candidate/control were 275,022/284,519 commands/s (-3.34%); p95 was 317.336/284.451 ms. E52 is rejected
because it moved derived-index cost onto the command model-load path.

## E53 — materialize hashed blocks server-side

E53 restored eager locator semantics but expanded the hashes already stored in authoritative blocks with eight
`insert ... select unnest(model_hashes)` statements in one transaction. Existing empty-marker ranges continued through
the legacy decoder, preserving rolling upgrade and UNLOGGED restart rebuild behavior. The focused test covered a
locked locator tail, hash filtering, an empty-marker block and a forced rebuild from cursor `-1`.

The server-side mechanism cut locator insertion execution from 6.953 s of client COPY in the matched control to
2.025 s. It still ran 270 wake transactions, 2,160 partition inserts and 270 cursor updates. Candidate/control were
279,032/336,033 commands/s (-16.96%); p95 was 306.365/221.813 ms. E53 is rejected. The pair proves that the locator is
a large asynchronous database consumer but not the current throughput-critical chain; lowering its aggregate service
time alone does not improve complete command throughput and can worsen database/cache concurrency.

The exact run values and artifact hashes are in
[`model-e2e-e52-e53-locator-experiments.csv`](model-e2e-e52-e53-locator-experiments.csv). The temporary E53 launcher SHA-256
was `5d79ca67a7e4f5564aa48a5734b1fe64213ce9592d5f8a5e2b298b1c2272f20a`.
