package codes;

import jakarta.persistence.EnumeratedValue;

/** An enum stored as the value of a private field. */
public enum Grade {
    PASS("P"), FAIL("F");

    @EnumeratedValue
    private final String letter;

    Grade(String letter) {
        this.letter = letter;
    }
}
