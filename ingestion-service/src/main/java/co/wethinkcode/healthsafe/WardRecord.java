package co.wethinkcode.healthsafe;

/**
 * One cleaned ward row as served by this service.
 *
 * <p>Every field is already normalized: ids are canonical ({@code W-05}), names are
 * title-cased, dates are ISO-8601, and anything that could not be trusted (missing,
 * placeholder or invalid values) is {@code null} with the reason recorded in
 * {@link #notes}.
 */
public class WardRecord {

    public String wardId;
    public String wing;
    public String department;
    public Integer bedsAvailable;
    public String status;
    public String lastUpdated;
    public Boolean specialUnit;

    /** Semicolon-joined list of everything that was fixed or flagged on this row. */
    public String notes;
}
