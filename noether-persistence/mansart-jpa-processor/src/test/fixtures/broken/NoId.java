package broken;

import jakarta.persistence.Entity;

/** No identifier: the processor warns and leaves the class to the bootstrap, which reports the error. */
@Entity
public class NoId {
    private String name;
}
