/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Noether, unchanged, as the Jakarta Persistence provider of a WAR on Open Liberty (Vidocq/noether#45):
 * Liberty's persistence container bootstraps the unit through {@code createContainerEntityManagerFactory}
 * (§9.6) with its own JTA data source, injects the transaction-scoped persistence context and drives the
 * transactions. Liberty's EclipseLink feature is off, so every statement here is Noether's.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("noether.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void theProviderWritesAndQueriesThroughTheServerDataSource() throws Exception {
        post("/books?title=" + encode("Germinal"));
        post("/books?title=" + encode("Germinal"));

        assertEquals("2", get("/books/count?title=" + encode("Germinal")));
    }

    @Test
    void aTransactionRolledBackByTheServerUndoesTheFlushedInsert() throws Exception {
        assertEquals("rolled back", post("/books/failing?title=" + encode("Doomed")));

        assertEquals("0", get("/books/count?title=" + encode("Doomed")));
    }

    @Test
    void aFindOutsideATransactionReadsTheCommittedRow() throws Exception {
        String id = post("/books?title=" + encode("Nana"));

        assertEquals("Nana", get("/books/" + id));
    }

    private static String post(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(BASE + path)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private static String get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(BASE + path)).GET());
    }

    private static String send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
