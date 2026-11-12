package ro.ghil.memoriesbe.credits;

/** What an event gets from the credit that paid for it: the free credit makes a trial event, a purchased one a full event. */
public enum Plan {
    TRIAL("trial", 300, 4L * 1024 * 1024 * 1024, 30, 1),
    FULL("full", 3000, 500L * 1024 * 1024 * 1024, 90, Integer.MAX_VALUE);

    private final String key;
    private final int maxPhotos;
    private final long maxBytes;
    private final int days;
    private final int maxCameras;

    Plan(String key, int maxPhotos, long maxBytes, int days, int maxCameras) {
        this.key = key;
        this.maxPhotos = maxPhotos;
        this.maxBytes = maxBytes;
        this.days = days;
        this.maxCameras = maxCameras;
    }

    public String key() { return key; }
    public int maxPhotos() { return maxPhotos; }
    public long maxBytes() { return maxBytes; }
    /** How long the gallery stays online after the event is created. */
    public int days() { return days; }
    public int maxCameras() { return maxCameras; }

    public static Plan of(String key) {
        for (Plan p : values()) if (p.key.equals(key)) return p;
        throw new IllegalArgumentException("Unknown plan: " + key);
    }
}
