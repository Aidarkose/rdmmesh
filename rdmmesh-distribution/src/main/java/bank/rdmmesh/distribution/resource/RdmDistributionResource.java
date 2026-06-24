package bank.rdmmesh.distribution.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.distribution.internal.VersionResolver;
import bank.rdmmesh.distribution.internal.service.DistributionService;
import bank.rdmmesh.distribution.internal.service.DistributionService.ExportResult;
import bank.rdmmesh.distribution.internal.service.DistributionService.ItemDto;
import bank.rdmmesh.distribution.internal.service.DistributionService.ItemsPage;
import bank.rdmmesh.distribution.internal.service.DistributionService.Query;

import io.dropwizard.auth.Auth;

/**
 * Consumer-facing REST для downstream-систем (Risk-engine, BI, ETL). SPEC §3.5:
 * {@code GET /rdm/{domain}/{codeset}/items|lookup|export}.
 *
 * <p>Все эндпоинты read-only и требуют валидный JWT (любая base-роль). Ответы кэш-нейтральны
 * для пилота — content_hash возвращается в payload, но HTTP {@code ETag}/{@code Cache-Control}
 * мы не выставляем (мягкий debt §3 в handoff'е E8).
 */
@Path("/rdm/{domain}/{codeset}")
@Produces(MediaType.APPLICATION_JSON)
public final class RdmDistributionResource {

    private static final int MAX_PAGE_SIZE = 10_000;
    private static final int DEFAULT_PAGE_SIZE = 1_000;

    private final DistributionService service;

    public RdmDistributionResource(DistributionService service) {
        this.service = service;
    }

    @GET
    @Path("/items")
    public ItemsPage items(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domain") String domain,
            @PathParam("codeset") String codeset,
            @QueryParam("version") @DefaultValue("published") String version,
            @QueryParam("as_of") String asOf,
            @QueryParam("knowledge_as_of") String knowledgeAsOf,
            @QueryParam("lang") @DefaultValue("ru") String lang,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("1000") int size) {
        Query q = buildQuery(domain, codeset, version, asOf, knowledgeAsOf, lang, page, size);
        try {
            return service.fetchItems(q);
        } catch (DistributionService.NotFoundException e) {
            throw new jakarta.ws.rs.NotFoundException(e.getMessage());
        }
    }

    @GET
    @Path("/lookup/{key}")
    public ItemDto lookup(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domain") String domain,
            @PathParam("codeset") String codeset,
            @PathParam("key") String keyToken,
            @QueryParam("version") @DefaultValue("published") String version,
            @QueryParam("as_of") String asOf,
            @QueryParam("knowledge_as_of") String knowledgeAsOf,
            @QueryParam("lang") @DefaultValue("ru") String lang) {
        Query q = buildQuery(domain, codeset, version, asOf, knowledgeAsOf, lang, 1, 1);
        try {
            return service.lookup(q, keyToken)
                    .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("item не найден: " + keyToken));
        } catch (DistributionService.NotFoundException e) {
            throw new jakarta.ws.rs.NotFoundException(e.getMessage());
        }
    }

    @GET
    @Path("/export")
    public Response export(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domain") String domain,
            @PathParam("codeset") String codeset,
            @QueryParam("version") @DefaultValue("published") String version,
            @QueryParam("as_of") String asOf,
            @QueryParam("knowledge_as_of") String knowledgeAsOf,
            @QueryParam("lang") @DefaultValue("ru") String lang,
            @QueryParam("format") @DefaultValue("json") String format) {
        Query q = buildQuery(domain, codeset, version, asOf, knowledgeAsOf, lang, 1, MAX_PAGE_SIZE);
        ExportResult result;
        try {
            result = service.fetchAllItems(q);
        } catch (DistributionService.NotFoundException e) {
            throw new jakarta.ws.rs.NotFoundException(e.getMessage());
        }
        return switch (format.toLowerCase()) {
            case "json" -> Response.ok(result).build();
            case "csv" -> csvResponse(result);
            case "xlsx" -> xlsxResponse(result);
            case "parquet" -> throw new WebApplicationException(
                    "формат 'parquet' не реализован в MVP, используйте csv|json|xlsx", Response.Status.NOT_IMPLEMENTED);
            default -> throw new WebApplicationException(
                    "неизвестный format: " + format + " (ожидается csv|json|xlsx)", Response.Status.BAD_REQUEST);
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Query buildQuery(
            String domain,
            String codeset,
            String version,
            String asOf,
            String knowledgeAsOf,
            String lang,
            int page,
            int size) {
        if (page < 1) {
            throw new WebApplicationException("page должен быть >= 1", Response.Status.BAD_REQUEST);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new WebApplicationException(
                    "size должен быть в [1, " + MAX_PAGE_SIZE + "]", Response.Status.BAD_REQUEST);
        }
        if (size == 0) size = DEFAULT_PAGE_SIZE;
        try {
            VersionResolver.VersionSpec v = VersionResolver.parse(version);
            LocalDate asOfDate = VersionResolver.parseDate(asOf, "as_of");
            Instant knowledgeAt = VersionResolver.parseInstant(knowledgeAsOf, "knowledge_as_of");
            return new Query(domain, codeset, v, asOfDate, knowledgeAt, lang, page, size);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    private static Response xlsxResponse(ExportResult result) {
        StreamingOutput stream =
                (OutputStream out) -> bank.rdmmesh.distribution.internal.XlsxExporter.write(
                        result.items(), result.attributeOrder(), out);
        String filename = result.domain() + "_" + result.codeset() + "_v" + result.version() + ".xlsx";
        return Response.ok(stream)
                .type("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    private static Response csvResponse(ExportResult result) {
        StreamingOutput stream = (OutputStream out) -> writeCsv(result.items(), out);
        String filename = result.domain() + "_" + result.codeset() + "_v" + result.version() + ".csv";
        return Response.ok(stream)
                .type("text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    /**
     * Простой CSV-format: фиксированный набор колонок без attributes-разворачивания
     * (attributes становится одной колонкой с JSON-строкой). Для bulk-load в downstream
     * этого хватает; «attributes как отдельные колонки» — V1+ feature, требует
     * resolve'а CodeSetSchema здесь либо в клиенте.
     */
    /** Standalone JSON-сериализация без CSV-форматирования (CsvMapper.writeValueAsString
     *  добавляет trailing newline). Используется для сериализации array/map-полей CSV-row'а. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static void writeCsv(List<ItemDto> items, OutputStream out) throws IOException {
        CsvMapper csv = new CsvMapper();
        CsvSchema schema = CsvSchema.builder()
                .addColumn("key_parts")
                .addColumn("parent_key")
                .addColumn("label")
                .addColumn("description")
                .addColumn("attributes")
                .addColumn("order_index")
                .addColumn("status")
                .addColumn("effective_from")
                .addColumn("effective_to")
                .setUseHeader(true)
                .build();
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            try (var seqWriter = csv.writer(schema).writeValues(w)) {
                for (ItemDto i : items) {
                    seqWriter.write(toCsvRow(i));
                }
            }
        }
    }

    private static java.util.Map<String, Object> toCsvRow(ItemDto i) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("key_parts", writeJson(i.keyParts()));
        row.put("parent_key", i.parentKey() == null ? null : writeJson(i.parentKey()));
        row.put("label", i.label());
        row.put("description", i.description());
        row.put("attributes", writeJson(i.attributes()));
        row.put("order_index", i.orderIndex());
        row.put("status", i.status());
        row.put("effective_from", i.effectiveFrom());
        row.put("effective_to", i.effectiveTo());
        return row;
    }

    private static String writeJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            return String.valueOf(o);
        }
    }
}
