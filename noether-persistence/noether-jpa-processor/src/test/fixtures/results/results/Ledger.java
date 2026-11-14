package results;

import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.SqlResultSetMapping;

/** Names its result classes in its queries: two named queries (a repeated annotation) and a result-set mapping. */
@Entity
@NamedQuery(name = "Ledger.summaries", query = "select new results.Summary(l.owner, l.total) from Ledger l")
@NamedQuery(name = "Ledger.faulty", query = "SELECT NEW results.Faulty(l.owner) FROM Ledger l")
@SqlResultSetMapping(name = "lines", classes = @ConstructorResult(targetClass = Line.class,
    columns = {@ColumnResult(name = "owner"), @ColumnResult(name = "total", type = Long.class)}))
public class Ledger {
    @Id long id;
    String owner;
    long total;
}
