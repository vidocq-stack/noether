package results;

/** Two public constructors, one with a primitive parameter, and a private one the queries cannot call. */
public class Summary {
    private final String owner;
    private final long total;

    public Summary(String owner, long total) {
        this.owner = owner;
        this.total = total;
    }

    public Summary(String owner) {
        this(owner, -1);
    }

    private Summary() {
        this(null, 0);
    }

    @Override
    public String toString() {
        return owner + ":" + total;
    }
}
