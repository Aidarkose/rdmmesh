#!/usr/bin/env python3
"""
E19 Commit 3 — генератор pivot-XLSX-фикстуры для smoke pivot-импорта.

Производит `bootstrap/seed/credit_risk/transition-1Y.xlsx` с матрицей P из
постановки заказчика (4 неdefault-грейда AAA/A/BB/B, без явной D-колонки):

         AAA      A       BB      B
   AAA   0.880   0.112   0.006   0.001    (Σ = 0.999, residual → D = 0.001)
   A     0.023   0.865   0.095   0.015    (Σ = 0.998, residual → D = 0.002)
   BB    0.001   0.050   0.802   0.127    (Σ = 0.980, residual → D = 0.020)
   B     0.000   0.005   0.185   0.720    (Σ = 0.910, residual → D = 0.090)

Использование:
  python3 scripts/gen-transition-fixture.py [<out_path>]

По умолчанию пишет в `bootstrap/seed/credit_risk/transition-1Y.xlsx` (относительно
корня репо).

Использует только стандартную библиотеку Python — без openpyxl/pandas (на dev-машине
заказчика pip может отсутствовать; openpyxl не нужен — XLSX это просто ZIP-of-XML).
Сгенерированный файл проверяется fastexcel-reader'ом тех же версий, что использует
rdmmesh-authoring (см. MatrixPivotSheetParserTest — тот же формат, та же раскладка).
"""

import os
import sys
import zipfile
from xml.sax.saxutils import escape

# ── Данные ──────────────────────────────────────────────────────────────────

HORIZON = "1Y"
RATINGS = ["AAA", "A", "BB", "B"]
P = {
    "AAA": [0.880, 0.112, 0.006, 0.001],
    "A":   [0.023, 0.865, 0.095, 0.015],
    "BB":  [0.001, 0.050, 0.802, 0.127],
    "B":   [0.000, 0.005, 0.185, 0.720],
}

DEFAULT_OUT = os.path.join("bootstrap", "seed", "credit_risk", "transition-1Y.xlsx")


# ── Минимальный XLSX writer (stdlib only) ───────────────────────────────────

def _col_letter(c: int) -> str:
    """0-based column index → A1-нотация. Поддерживает A..ZZ — хватает для матриц до ~700×."""
    if c < 26:
        return chr(ord("A") + c)
    return chr(ord("A") + c // 26 - 1) + chr(ord("A") + c % 26)


def _cell_xml(r: int, c: int, value):
    """1-based row, 0-based col → <c r=.../> XML cell."""
    ref = f"{_col_letter(c)}{r}"
    if isinstance(value, str):
        # inlineStr — без sharedStrings.xml, проще для минимальной фикстуры.
        return f'<c r="{ref}" t="inlineStr"><is><t>{escape(value)}</t></is></c>'
    if isinstance(value, bool):  # bool ДО Number — bool это подкласс int в Python!
        return f'<c r="{ref}" t="b"><v>{1 if value else 0}</v></c>'
    if isinstance(value, (int, float)):
        return f'<c r="{ref}" t="n"><v>{value}</v></c>'
    raise TypeError(f"unsupported cell type {type(value).__name__} at {ref}: {value!r}")


def _sheet_xml(data):
    """data: list of rows; row = list of cell values (str/int/float/bool/None)."""
    rows_xml = []
    for r_idx, row in enumerate(data, start=1):
        cells = [_cell_xml(r_idx, c_idx, v) for c_idx, v in enumerate(row) if v is not None and v != ""]
        rows_xml.append(f'<row r="{r_idx}">{"".join(cells)}</row>')
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f'<sheetData>{"".join(rows_xml)}</sheetData>'
        "</worksheet>"
    )


def write_xlsx(path: str, sheet_name: str, data) -> None:
    files = {
        "[Content_Types].xml": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            "</Types>"
        ),
        "_rels/.rels": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
            'Target="xl/workbook.xml"/>'
            "</Relationships>"
        ),
        "xl/_rels/workbook.xml.rels": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
            'Target="worksheets/sheet1.xml"/>'
            "</Relationships>"
        ),
        "xl/workbook.xml": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            f'<sheets><sheet name="{escape(sheet_name)}" sheetId="1" r:id="rId1"/></sheets>'
            "</workbook>"
        ),
        "xl/worksheets/sheet1.xml": _sheet_xml(data),
    }

    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, content in files.items():
            zf.writestr(name, content)


# ── main ────────────────────────────────────────────────────────────────────

def main(argv):
    out = argv[1] if len(argv) > 1 else DEFAULT_OUT
    # Сборка pivot-данных: первая строка — пустая ячейка + заголовки to_rating;
    # далее каждая строка — from_rating + 4 числовые ячейки.
    data = [["", *RATINGS]]
    for from_r in RATINGS:
        data.append([from_r, *P[from_r]])
    write_xlsx(out, sheet_name=f"Migrations_{HORIZON}", data=data)
    sums = [sum(P[f]) for f in RATINGS]
    print(f"Wrote {out}")
    print(f"Sheet 'Migrations_{HORIZON}': {len(RATINGS)+1} rows × {len(RATINGS)+1} cols")
    print(f"Row sums: " + ", ".join(f"{f}={s:.3f}" for f, s in zip(RATINGS, sums)))
    print("→ IMPLICIT_DEFAULT парсер допишет колонку D с PD =",
          ", ".join(f"{1-s:.3f}" for s in sums))


if __name__ == "__main__":
    main(sys.argv)
