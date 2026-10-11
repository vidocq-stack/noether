/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package coexist;

@jakarta.persistence.Entity
public class Book {
    @jakarta.persistence.Id
    public long id;
    public String title;
    @jakarta.persistence.ElementCollection
    public java.util.List<String> tags;
}
