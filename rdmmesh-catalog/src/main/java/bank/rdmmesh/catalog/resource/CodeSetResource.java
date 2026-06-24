package bank.rdmmesh.catalog.resource;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.catalog.internal.mapper.CatalogMappers;
import bank.rdmmesh.catalog.internal.service.CatalogService;
import bank.rdmmesh.spec.entity.CodeSet;
import io.dropwizard.auth.Auth;

/**
 * REST для catalog'а — CodeSet endpoints (SPEC §3.5).
 *
 * <p>Roles:
 * <ul>
 *   <li>READ ({@code GET}) — любой аутентифицированный;
 *   <li>CREATE ({@code POST /domains/{domain}/codesets}) — Schema Designer (создаёт CodeSet
 *       вместе с initial schema), Author по правилам домена считается subset'ом;
 *   <li>PATCH metadata — Author / Schema Designer / Admin.
 * </ul>
 *
 * <p>Endpoint наследует префикс {@code /api/v1/} от Dropwizard config (rootPath).
 */
@Path("/codesets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class CodeSetResource {

    private final CatalogService catalog;

    public CodeSetResource(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GET
    @Path("/{id}")
    public CodeSet get(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("expand") String expand /* reserved for future, не используется */) {
        UUID uid = CatalogMappers.parseUuid(id, "id");
        return catalog.findCodeSet(uid)
                .orElseThrow(() -> new NotFoundException("codeset " + id));
    }

    /**
     * Create под {@code /codesets/by-domain/{domainId}} вместо
     * {@code /domains/{id}/codesets}, чтобы не конфликтовать с {@link DomainResource} в
     * Jersey-роутинге (два root-resource'а с пересекающимися путями ведут к 404
     * при наличии параметра-сегмента). Endpoint {@code GET /codesets/by-domain/{domainId}}
     * — список CodeSet'ов в домене.
     */
    @GET
    @Path("/by-domain/{domainId}")
    public List<CodeSet> listInDomain(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domainId") String domainId) {
        UUID uid = CatalogMappers.parseUuid(domainId, "domainId");
        return catalog.listCodeSets(uid);
    }

    @POST
    @Path("/by-domain/{domainId}")
    @RolesAllowed({"RDM_SCHEMA_DESIGNER", "RDM_AUTHOR", "RDM_ADMIN"})
    public Response create(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domainId") String domainId,
            @Valid @NotNull NewCodeSetRequest req) {
        UUID dId = CatalogMappers.parseUuid(domainId, "domainId");
        try {
            CodeSet created = catalog.createCodeSet(
                    new CatalogService.NewCodeSet(
                            dId,
                            req.name,
                            req.displayName,
                            req.description,
                            req.labelRu,
                            req.labelEn,
                            req.tags,
                            CatalogMappers.writeJson(req.keySpec == null ? defaultKeySpec() : req.keySpec),
                            req.hierarchyMode == null ? "NONE" : req.hierarchyMode,
                            req.releaseChannels,
                            req.initialSchema == null ? "{}" : CatalogMappers.readJsonNode(req.initialSchema)),
                    principal.omUserId());
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public CodeSet patch(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull CodeSetPatchRequest req) {
        UUID uid = CatalogMappers.parseUuid(id, "id");
        return catalog.patchCodeSetMetadata(
                        uid,
                        new CatalogService.CodeSetPatch(
                                req.displayName,
                                req.description,
                                req.labelRu,
                                req.labelEn,
                                req.tags))
                .orElseThrow(() -> new NotFoundException("codeset " + id));
    }

    /**
     * Заменяет набор cross-codeset FK-связей справочника целиком (PUT-семантика —
     * полная замена, не merge). Каждая связь линкует колонку этого справочника
     * ({@code from_column} — имя key-part'а или атрибута) с колонкой другого справочника
     * ({@code to_codeset_id} + {@code to_column}), в т.ч. в другом домене. Backend не
     * проверяет referential integrity — связь описательна и публикуется в OpenMetadata как
     * FOREIGN_KEY (см. om-rdmmesh-source). Пустой/отсутствующий список очищает связи.
     */
    @PUT
    @Path("/{id}/references")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public CodeSet putReferences(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid SetReferencesRequest req) {
        UUID uid = CatalogMappers.parseUuid(id, "id");
        List<ReferenceDto> refs = (req == null || req.references == null) ? List.of() : req.references;
        return catalog.setReferences(uid, CatalogMappers.writeJson(refs))
                .orElseThrow(() -> new NotFoundException("codeset " + id));
    }

    private static java.util.Map<String, Object> defaultKeySpec() {
        // KeySpec по дефолту: одиночный код. Минимальный валидный объект для сохранения
        // в JSONB-поле, чтобы пользователь не обязательно писал key_spec вручную в pilot'е.
        // Структура совпадает с rdmmesh-spec/schema/entity/key-spec.json: parts: KeyPart[].
        return java.util.Map.of(
                "parts", List.of(java.util.Map.of("name", "code", "type", "STRING")));
    }

    public static final class NewCodeSetRequest {
        @JsonProperty("name")
        @NotEmpty
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$",
                message = "name must be lower snake_case, ≤64 chars")
        public String name;

        @JsonProperty("display_name")
        public String displayName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("tags")
        public String[] tags;

        @JsonProperty("key_spec")
        public Object keySpec;

        @JsonProperty("hierarchy_mode")
        public String hierarchyMode;

        @JsonProperty("release_channels")
        public String[] releaseChannels;

        @JsonProperty("initial_schema")
        public JsonNode initialSchema;
    }

    public static final class CodeSetPatchRequest {
        @JsonProperty("display_name")
        public String displayName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("tags")
        public String[] tags;
    }

    /** Тело {@code PUT /codesets/{id}/references} — полная замена набора связей. */
    public static final class SetReferencesRequest {
        @JsonProperty("references")
        @Valid
        public List<ReferenceDto> references;
    }

    /** Одна cross-codeset FK-связь. Сериализуется в JSONB-поле {@code column_refs}. */
    public static final class ReferenceDto {
        @JsonProperty("from_column")
        @NotEmpty
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$",
                message = "from_column must be lower snake_case, ≤64 chars")
        public String fromColumn;

        @JsonProperty("to_codeset_id")
        @NotEmpty
        public String toCodesetId;

        @JsonProperty("to_column")
        @NotEmpty
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$",
                message = "to_column must be lower snake_case, ≤64 chars")
        public String toColumn;

        /** Опциональная человекочитаемая метка связи (localized_label {ru,en}). */
        @JsonProperty("label")
        public JsonNode label;
    }
}
