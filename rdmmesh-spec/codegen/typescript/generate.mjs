// TS-кодеген spec → rdmmesh-ui/src/generated/.
//
// Запускается: npm run codegen (из rdmmesh-ui), либо `make codegen-ts` из корня.
// Параллель для Java POJO — jsonschema2pojo-maven-plugin в rdmmesh-spec/pom.xml.
//
// На E11.1 (read-only UI) сгенерированные типы используются как справочник по
// shape'ам сущностей. API-клиент в src/api/types.ts держит собственные wire-типы,
// потому что backend возвращает custom DTO (CodeItemDto, CodeSetSchemaDto), которые
// не совпадают со spec POJO в полях со «свободным» JSON (см. handoff E3 §1.4, E4 §1.7).

import { readdirSync, mkdirSync, writeFileSync, rmSync, existsSync } from "node:fs";
import { join, dirname, basename, extname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const here = dirname(fileURLToPath(import.meta.url));
const SCHEMA_ROOT = join(here, "..", "..", "schema");
const OUT_ROOT = join(here, "..", "..", "..", "rdmmesh-ui", "src", "generated");

// Зависимость json-schema-to-typescript живёт в rdmmesh-ui/node_modules. Скрипт лежит
// в rdmmesh-spec/codegen/typescript/ — node ESM resolver его оттуда не найдёт. Резолвим
// явно от UI-проекта (он же запускает codegen через `npm run codegen`).
const uiPkg = join(here, "..", "..", "..", "rdmmesh-ui", "package.json");
const reqFromUi = createRequire(pathToFileURL(uiPkg));
const jsonSchemaToTs = reqFromUi("json-schema-to-typescript");
const compileFromFile = jsonSchemaToTs.compileFromFile;

const FOLDERS = ["entity", "api", "events"];

const BANNER = `/**
 * AUTO-GENERATED — не править вручную.
 * Источник: rdmmesh-spec/schema/.
 * Команда: npm run codegen (из rdmmesh-ui) или make codegen-ts.
 */`;

const COMPILE_OPTIONS = {
  bannerComment: BANNER,
  declareExternallyReferenced: true,
  enableConstEnums: false,
  unreachableDefinitions: false,
  strictIndexSignatures: true,
  additionalProperties: false,
  style: { singleQuote: false, semi: true, trailingComma: "all" },
};

if (existsSync(OUT_ROOT)) {
  rmSync(OUT_ROOT, { recursive: true, force: true });
}
mkdirSync(OUT_ROOT, { recursive: true });

let total = 0;
for (const folder of FOLDERS) {
  const dir = join(SCHEMA_ROOT, folder);
  if (!existsSync(dir)) continue;
  mkdirSync(join(OUT_ROOT, folder), { recursive: true });

  const files = readdirSync(dir).filter((f) => f.endsWith(".json"));
  for (const file of files) {
    const src = join(dir, file);
    const out = join(OUT_ROOT, folder, basename(file, extname(file)) + ".ts");
    const ts = await compileFromFile(src, {
      ...COMPILE_OPTIONS,
      cwd: dir,
    });
    writeFileSync(out, ts, "utf-8");
    total += 1;
    process.stdout.write(`  ${folder}/${file} -> ${folder}/${basename(out)}\n`);
  }
}

writeFileSync(
  join(OUT_ROOT, "index.ts"),
  `${BANNER}\n\n` +
    FOLDERS.map((f) => `export * from "./${f}/index";`).join("\n") +
    "\n",
  "utf-8",
);

for (const folder of FOLDERS) {
  const dir = join(OUT_ROOT, folder);
  if (!existsSync(dir)) continue;
  const tsFiles = readdirSync(dir)
    .filter((f) => f.endsWith(".ts") && f !== "index.ts")
    .map((f) => basename(f, ".ts"));
  writeFileSync(
    join(dir, "index.ts"),
    `${BANNER}\n\n` +
      tsFiles.map((n) => `export * from "./${n}";`).join("\n") +
      "\n",
    "utf-8",
  );
}

process.stdout.write(`Generated ${total} schema files into ${OUT_ROOT}\n`);
