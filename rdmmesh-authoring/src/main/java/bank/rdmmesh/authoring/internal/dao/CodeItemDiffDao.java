package bank.rdmmesh.authoring.internal.dao;

import java.util.List;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * Тонкая обёртка вокруг SQL-функции {@code authoring.code_item_diff_base} (V021).
 * Возвращает «сырой» построковый diff между двумя версиями. Computation поля
 * {@code changed_fields} — в Java, потому что это произвольная глубина по JSONB.
 */
public interface CodeItemDiffDao {

    @SqlQuery(
            """
            SELECT op,
                   key_parts::text  AS key_parts_json,
                   before_doc::text AS before_doc_json,
                   after_doc::text  AS after_doc_json
              FROM authoring.code_item_diff_base(:fromVersionId, :toVersionId)
             WHERE op <> 'UNCHANGED'
             ORDER BY op, key_parts::text
            """)
    @RegisterConstructorMapper(DiffRow.class)
    List<DiffRow> diff(
            @Bind("fromVersionId") UUID fromVersionId,
            @Bind("toVersionId") UUID toVersionId);

    record DiffRow(
            String op,
            String keyPartsJson,
            String beforeDocJson,
            String afterDocJson) {}
}
