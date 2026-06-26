package io.fluxzero.sdk.scheduling;

import io.fluxzero.sdk.modeling.Id;
import lombok.Value;
import lombok.experimental.NonFinal;

/**
 * Represents a unique identifier for a schedule, consisting of a type and an id. It is simply a convenience class
 * used to prevent clashes between schedule ids, e.g.: two functionally different schedules involving the same entity
 * id.
 * <p>
 * This class is typically used to encapsulate and uniquely identify schedules within a system. The combination of type
 * and id can be used to distinguish between different schedule categories or instances.
 * <p>
 * Note: when a ScheduleId is used to schedule a message the scheduler actually persists the schedule with a String
 * schedule obtained via {@link #toString()}.
 * <p>
 * ScheduleIds can be used as-is or extended to form strongly typed schedule ids:
 * <pre>{@code
 * public class TaskExpiryId extends ScheduleId {
 *     public TaskExpiryId(TaskId taskId) {
 *         super("task-expiry", taskId);
 *     }
 * }
 * }</pre>
 */
@Value
@NonFinal
public class ScheduleId {
    /**
     * Represents the type associated with a schedule identifier.
     * <p>
     * This variable is used to categorize or distinguish schedules based on their broader classifications, such as
     * "task," or "booking." The type, combined with the id, forms a unique identifier for a schedule.
     * <p>
     * It is expected to hold a non-null, meaningful string value that conveys the nature or context of the specific
     * schedule.
     */
    String type;

    /**
     * Represents the unique identifier for a schedule within the system. This string field, in combination with the
     * `type` field, is used to distinguish individual schedule instances.
     * <p>
     * This field encapsulates the actual identifier component of a schedule, typically an entity id, serving as the
     * second part of a composite key that ensures the identification and management of schedules are unambiguous.
     * <p>
     * It is expected to be non-null and non-empty to provide meaningful schedule identification.
     */
    String id;

    /**
     * Constructs a new ScheduleId object using the specified type and identifier.
     *
     * @param type the type or category associated with the schedule. This parameter is used to distinguish schedules
     *             based on their broader classifications (e.g., "task", "booking"). It must be a non-null string and
     *             should provide context about the nature of the schedule.
     * @param id   the unique identifier for the schedule. This parameter is used to uniquely identify a specific schedule
     *             instance within the specified type. If the provided id is an instance of the {@code Id} class, its
     *             functional ID is extracted, otherwise the {@code toString()} value of the id is used.
     */
    public ScheduleId(String type, Object id) {
        this.type = type;
        this.id = id instanceof Id<?> i ? i.getFunctionalId() : id.toString() ;
    }

    @Override
    public String toString() {
        return "%s:%s".formatted(type, id);
    }
}
