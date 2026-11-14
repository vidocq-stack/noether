package results;

/** Its constructor declares a checked exception: left to the bootstrap. */
public class Faulty {
    public Faulty(String owner) throws java.io.IOException {
        if (owner == null) {
            throw new java.io.IOException("no owner");
        }
    }
}
