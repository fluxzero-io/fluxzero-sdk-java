Use this recipe to prove one complete durable coordination slice instead of testing state, schedules, correlation, outbound HTTP, and queries in disconnected helpers. Keep the broader symmetric matrix as separate tests after this executable spine passes.

## Build one observable spine

Register the real command/state handlers, secondary-reference resolver, deadline handler, outbound publisher, and query. Pin time and required properties. One start phase should prove the accepted command, recorded event/state, active deadline, and exact processor requests together:

```java
import io.fluxzero.sdk.scheduling.Schedule;
import io.fluxzero.sdk.test.Given;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.test.Then;
import io.fluxzero.sdk.web.WebRequest;
import java.util.function.Predicate;

Instant now = Instant.parse("2030-06-01T10:00:00Z");
Instant deadline = now.plus(Duration.ofMinutes(20));
AssetJobId assetJobId = new AssetJobId("asset-job-7");
Predicate<Schedule> exactDeadline = schedule ->
        schedule.getPayload().equals(new ExpireAssetJob(assetJobId))
        && schedule.getScheduleId().equals("asset-job-deadline-" + assetJobId)
        && schedule.getDeadline().equals(deadline);

TestFixture fixture = TestFixture.create(
        StartAssetJobHandler.class,
        CaptionDecisionResolver.class,
        RecordProcessorDecision.class,
        ExpireAssetJob.class,
        AssetJobAcceptedEffects.class,
        ProcessorRequestPublisher.class,
        GetAssetJob.class)
        .atFixedTime(now)
        .withProperty("processor.caption.base-url", "https://caption.test/api")
        .withProperty("processor.artwork.base-url", "https://artwork.test/api");

var started = fixture.whenCommand(new StartAssetJob(
                assetJobId, "asset-4", "source-v12", "captions-en",
                "poster-square", OutputFormat.WEB))
        .expectResult(AssetJob.class)
        .expectOnlyEvents(RecordAssetJob.class)
        .expectOnlyNewSchedules(exactDeadline)
        .expectOnlySchedules(exactDeadline)
        .expectOnlyWebRequests(
                captionJobRequest(assetJobId, "https://caption.test/api/jobs"),
                artworkJobRequest(assetJobId, "https://artwork.test/api/jobs"));

AssetJob accepted = started.getResult(AssetJob.class);
String captionReference = accepted.captionReference();
String artworkReference = accepted.artworkReference();
```

`StartAssetJob` must only apply `RecordAssetJob`; it must not schedule or send a request immediately after `assertAndApply(...).get()`. `AssetJobAcceptedEffects` is a tracked `@HandleEvent` consumer that reloads the committed aggregate, creates the stable deadline, and publishes the requests. This ordering makes new aliases visible before a processor can answer. Read aggregate commit and effect boundaries before implementing this slice.

`AssetJobId` must also use a disjoint internal repository prefix such as `asset-job-id-`, while caption and artwork aliases use their own prefixes. Keep the public functional ID unchanged. This prevents a public ID equal to `caption-ref-<another reference>` from stealing and dropping the valid alias lookup.

When ID and deadline are part of the contract, pass a `Predicate<Schedule>` to both `expectOnlyNewSchedules(...)` and `expectOnlySchedules(...)`, comparing payload, `getScheduleId()`, and `getDeadline()` explicitly. The first proves creation during this phase; the second proves the deadline remains active after the phase. Passing a payload checks only the payload. Passing an expected `Schedule` additionally checks its deadline, but Fluxzero's fixture comparison does not compare its schedule ID. Use the exact-count `expectOnly...` forms for this row; use inclusive `expectNewSchedule(...)` or `expectSchedule(...)` only when additional schedules are deliberately allowed. Each `captionJobRequest(...)` helper must compare method, absolute path, `application/json`, and the entire typed body, including the stable component reference.

Drive the public query and a real secondary-reference message through separate phases. The resolver must publish the durable primary-ID command; that command, not the resolver, mutates aggregate state:

```java
var lookup = started.andThen().whenQuery(new GetAssetJob(assetJobId))
        .expectResult((AssetJobView view) ->
                view.status() == PENDING
                && view.captionReference().equals(captionReference)
                && view.artworkReference().equals(artworkReference)
                && view.deadline().equals(deadline));

var confirmed = lookup.andThen()
        .whenCommand(new CaptionCompleted(captionReference))
        .expectOnlyCommands(new RecordProcessorDecision(
                assetJobId, Component.CAPTION, captionReference, Decision.CONFIRMED))
        .expectNoWebRequests();

confirmed.andThen().whenQuery(new GetAssetJob(assetJobId))
        .expectResult((AssetJobView view) ->
                view.captionStatus() == ComponentStatus.CONFIRMED
                && view.status() == PENDING);
```

This asserts command → state/event → schedule → correlation → exact outbound HTTP → public query without calling handlers directly. Add the artwork mirror, terminal compensation, duplicate/conflicting decisions, cancellation, and deadline boundary as independent rows so each failure has one cause.

## Combine aggregate, correlation, and active-schedule reconstruction

Continuing with `andThen()` does not prove reconstruction. Create a new default fixture and seed only the recorded
artifacts that production would treat as durable: aggregate events plus the still-active command schedule. Then use a
secondary reference and the exact public query again. This is synthetic reconstruction with fresh in-memory stores,
not evidence that persistence survived an application restart.

```java
Schedule persistedDeadline = new Schedule(
        new ExpireAssetJob(assetJobId), "asset-job-deadline-" + assetJobId, deadline);

TestFixture reconstructed = TestFixture.create(
        CaptionDecisionResolver.class,
        RecordProcessorDecision.class,
        ExpireAssetJob.class,
        ProcessorRequestPublisher.class,
        GetAssetJob.class)
        .atFixedTime(now)
        .withProperty("processor.caption.base-url", "https://caption.test/api")
        .withProperty("processor.artwork.base-url", "https://artwork.test/api")
        .givenAppliedEvents(assetJobId, recordedStart, recordedArtworkConfirmation)
        .givenScheduledCommands(persistedDeadline);

var afterReconstruction = reconstructed
        .whenCommand(new CaptionRejected(captionReference, "unsupported source format"))
        .expectOnlyCommands(new RecordProcessorDecision(
                assetJobId, Component.CAPTION, captionReference, Decision.REJECTED))
        .expectOnlyWebRequests(artworkCancellationRequest(
                assetJobId, artworkReference, "https://artwork.test/api/cancellations"));

afterReconstruction.andThen().whenQuery(new GetAssetJob(assetJobId))
        .expectResult((AssetJobView view) ->
                view.status() == FAILED
                && view.captionStatus() == REJECTED
                && view.artworkStatus() == CONFIRMED
                && view.artworkCancellationRequested());
```

`givenAppliedEvents(...)` reconstructs aggregate aliases from supplied history, while `givenScheduledCommands(...)`
supplies an active command deadline. Use `givenStateful(...)` plus `givenSchedules(...)` only for a stateful-document
design with an ordinary `@HandleSchedule` payload. Prove the active deadline separately immediately before and at its
due time, and prove an earlier terminal transition removes it. Also assert no processing or compensation request is
re-emitted merely by seeding the reconstructed fixture. Add a retained external runtime/client test when the product
actually promises persistence-backed restart.

All outbound assertions remain logical-once assertions: the first transition may emit exactly one request with a stable idempotency reference, while a duplicate delivery emits none. Never sleep or call a live processor.
