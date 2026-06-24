package bank.rdmmesh.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Compile-time enforcement of the bounded-context boundaries described in SPEC §3.3.
 *
 * <p>The rules below are intentionally minimal at bootstrap; as modules get filled in we
 * add more invariants (e.g., distribution does no DB writes — SPEC §3.3 row).
 */
/*
 * ImportOption.DoNotIncludeJars НЕ используется намеренно: classes сестринских
 * Maven-модулей (rdmmesh-workflow, rdmmesh-catalog, …) попадают в classpath
 * rdmmesh-app как JAR'ы. С DoNotIncludeJars ArchUnit их не видит, и strict-правила
 * вида `*_internal_only_used_by_*` падают «failed to check any classes». Фильтр по
 * package = "bank.rdmmesh" сам отсекает сторонние библиотеки (jdbi/dropwizard/…).
 */
@AnalyzeClasses(
        packages = "bank.rdmmesh",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public final class ModuleIsolationTest {

    /**
     * Sibling bounded contexts may not import each other directly. The composition root
     * (rdmmesh-app) and the shared API/spec layers are exempt because they are the only
     * legitimate cross-module touch points.
     */
    @ArchTest
    static final ArchRule modules_do_not_depend_on_each_other =
            SlicesRuleDefinition.slices()
                    .matching("bank.rdmmesh.(*)..")
                    .namingSlices("module $1")
                    .should()
                    .notDependOnEachOther()
                    .ignoreDependency(
                            // app may know everyone (it's the wiring root)
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(
                                    "bank.rdmmesh.app.."),
                            com.tngtech.archunit.base.DescribedPredicate.alwaysTrue())
                    .ignoreDependency(
                            com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
                            // depending on api / spec is always allowed
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage(
                                    "bank.rdmmesh.api..", "bank.rdmmesh.spec.."));

    /**
     * The audit module must not depend on a sibling module — it consumes events through
     * the in-process bus interface from {@code rdmmesh-api}. SPEC §3.3 row "audit".
     */
    @ArchTest
    static final ArchRule audit_only_depends_on_api_or_spec =
            classes()
                    .that().resideInAPackage("bank.rdmmesh.audit..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "bank.rdmmesh.audit..",
                            "bank.rdmmesh.api..",
                            "bank.rdmmesh.spec..",
                            "java..", "javax..", "jakarta..",
                            "org.slf4j..", "org.jdbi..",
                            "com.fasterxml..", "io.dropwizard..");

    /**
     * Никакой модуль не может тянуть {@code internal}-пакет соседа. Все классы соседнего
     * модуля доступны только через публичный API (root-package + любое не-internal
     * подмножество). Внутри одного модуля {@code resource → internal.service} — норма
     * (см. SPEC §3.3 и pattern catalog/identity/ownership).
     *
     * <p>Реализация — по одному правилу на каждый internal-пакет: «классы из {@code X.internal}
     * могут иметь зависимыми только классы из {@code X..} (свой же модуль) либо общую
     * api/spec/runtime инфраструктуру». Это проверяет именно изоляцию internal'а, не запрещая
     * легитимные intra-module пути от resource к service.
     *
     * <p><b>Note для будущих эпиков.</b> При появлении новых модулей дополнить список парой
     * {@code @ArchTest} + строкой в общем allow'е (см. {@code allowedConsumersOf}).
     */
    @ArchTest
    static final ArchRule catalog_internal_only_used_by_catalog =
            internalSliceUsedOnlyBy("catalog");
    @ArchTest
    static final ArchRule authoring_internal_only_used_by_authoring =
            internalSliceUsedOnlyBy("authoring");
    @ArchTest
    static final ArchRule workflow_internal_only_used_by_workflow =
            internalSliceUsedOnlyByStrict("workflow");
    @ArchTest
    static final ArchRule publishing_internal_only_used_by_publishing =
            internalSliceUsedOnlyByStrict("publishing");
    @ArchTest
    static final ArchRule distribution_internal_only_used_by_distribution =
            internalSliceUsedOnlyByStrict("distribution");
    @ArchTest
    static final ArchRule identity_internal_only_used_by_identity =
            internalSliceUsedOnlyBy("identity");
    @ArchTest
    static final ArchRule ownership_internal_only_used_by_ownership =
            internalSliceUsedOnlyByStrict("ownership");
    @ArchTest
    static final ArchRule audit_internal_only_used_by_audit =
            internalSliceUsedOnlyByStrict("audit");

    private static ArchRule internalSliceUsedOnlyBy(String moduleName) {
        return classes()
                .that().resideInAPackage("bank.rdmmesh." + moduleName + ".internal..")
                .should().onlyHaveDependentClassesThat()
                .resideInAnyPackage(
                        "bank.rdmmesh." + moduleName + "..",
                        // app — composition root, может wire'ить любой internal через
                        // публичные factory-методы (см. *Module.build()).
                        "bank.rdmmesh.app..",
                        "bank.rdmmesh.api..",
                        "bank.rdmmesh.spec..")
                .allowEmptyShould(true);
    }

    /**
     * Тот же контракт, но без {@code allowEmptyShould(true)} — снимается, как только в
     * соответствующем модуле появился хотя бы один класс в {@code internal..} (так
     * регрессии "пустое правило проходит" не маскируют новый код).
     */
    private static ArchRule internalSliceUsedOnlyByStrict(String moduleName) {
        return classes()
                .that().resideInAPackage("bank.rdmmesh." + moduleName + ".internal..")
                .should().onlyHaveDependentClassesThat()
                .resideInAnyPackage(
                        "bank.rdmmesh." + moduleName + "..",
                        "bank.rdmmesh.app..",
                        "bank.rdmmesh.api..",
                        "bank.rdmmesh.spec..");
    }

    /**
     * SPEC §3.3: модуль {@code distribution} read-only — никаких UPDATE/INSERT/DELETE.
     * Проверяется через запрет JDBI write-аннотаций ({@code @SqlUpdate}/{@code @SqlBatch})
     * — этого достаточно, потому что весь репозиторий ходит в БД через JDBI3 SqlObject.
     * Правило временно с {@code allowEmptyShould(true)}; снять как только в
     * {@code rdmmesh-distribution/src/main/java} появится первый класс.
     */
    @ArchTest
    static final ArchRule distribution_does_no_db_writes =
            noClasses()
                    .that().resideInAPackage("bank.rdmmesh.distribution..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.jdbi.v3.sqlobject.statement.SqlUpdate")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("org.jdbi.v3.sqlobject.statement.SqlBatch");
}
