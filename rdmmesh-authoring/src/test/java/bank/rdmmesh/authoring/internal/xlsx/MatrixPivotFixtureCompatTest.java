package bank.rdmmesh.authoring.internal.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.authoring.internal.xlsx.MatrixPivotSheetParser.PivotParseResult;
import bank.rdmmesh.authoring.internal.xlsx.MatrixPivotSheetParser.RowResidualPolicy;

/**
 * Smoke-проверка совместимости fastexcel-reader'а с XLSX-файлом, сгенерированным
 * stdlib-Python скриптом {@code scripts/gen-transition-fixture.py}. Тест проверяет
 * именно фикстуру, которую увидит пользователь при ручном smoke pivot-импорта
 * (E19 Commit 3, §9 handoff).
 *
 * <p>Тест автоматически skip'ается, если фикстура отсутствует — она хранится в
 * репо ({@code bootstrap/seed/credit_risk/transition-1Y.xlsx}), но также может
 * быть собрана локально через {@code python3 scripts/gen-transition-fixture.py}.
 */
class MatrixPivotFixtureCompatTest {

    static boolean fixtureExists() {
        return Files.exists(fixturePath());
    }

    private static Path fixturePath() {
        // Maven запускается из директории модуля (rdmmesh-authoring/), фикстура в репо-корне.
        return Paths.get("..", "bootstrap", "seed", "credit_risk", "transition-1Y.xlsx")
                .toAbsolutePath()
                .normalize();
    }

    @Test
    @EnabledIf("fixtureExists")
    void fastexcel_reader_parses_python_generated_fixture_with_expected_residual_PDs() throws Exception {
        try (InputStream in = Files.newInputStream(fixturePath())) {
            MatrixPivotSheetParser parser =
                    new MatrixPivotSheetParser(new ObjectMapper(), "1Y", RowResidualPolicy.IMPLICIT_DEFAULT);
            PivotParseResult result = parser.parse(in);

            // 4×4 explicit + 4 dispised D-ячейки (residual 0.001 / 0.002 / 0.020 / 0.090).
            assertThat(result.rows()).hasSize(20);
            assertThat(result.implicitDefaultAdded()).isEqualTo(4);

            // Самый показательный: PD для grade B = 0.090.
            assertThat(result.rows())
                    .filteredOn(r -> r.keyParts().equals(java.util.List.of("B", "D", "1Y")))
                    .hasSize(1)
                    .first()
                    .satisfies(r -> {
                        double p = ((Number) r.attributes().get("probability")).doubleValue();
                        assertThat(p).isCloseTo(0.090, org.assertj.core.data.Offset.offset(1e-6));
                    });
        }
    }
}
