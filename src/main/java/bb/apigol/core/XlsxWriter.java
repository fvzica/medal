package bb.apigol.core;

import bb.apigol.core.Config;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class XlsxWriter {
    public static byte[] gerar(List<Map<String, Object>> list, Map<String, Object> map, String string, String string2) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream);
        XlsxWriter.put(zipOutputStream, "[Content_Types].xml", XlsxWriter.contentTypes());
        XlsxWriter.put(zipOutputStream, "_rels/.rels", XlsxWriter.rootRels());
        XlsxWriter.put(zipOutputStream, "xl/workbook.xml", XlsxWriter.workbook());
        XlsxWriter.put(zipOutputStream, "xl/_rels/workbook.xml.rels", XlsxWriter.workbookRels());
        XlsxWriter.put(zipOutputStream, "xl/styles.xml", XlsxWriter.styles());
        XlsxWriter.put(zipOutputStream, "xl/worksheets/sheet1.xml", XlsxWriter.sheetDados(list));
        XlsxWriter.put(zipOutputStream, "xl/worksheets/sheet2.xml", XlsxWriter.sheetTotais(map));
        XlsxWriter.put(zipOutputStream, "xl/worksheets/sheet3.xml", XlsxWriter.sheetGlossario(string, string2));
        zipOutputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

    private static String sheetDados(List<Map<String, Object>> list) {
        StringBuilder stringBuilder = XlsxWriter.openSheet();
        if (!list.isEmpty()) {
            int n;
            ArrayList<String> arrayList = new ArrayList<String>(list.get(0).keySet());
            stringBuilder.append("<row r=\"1\">");
            for (n = 0; n < arrayList.size(); ++n) {
                stringBuilder.append(XlsxWriter.cellStr(n, 1, (String)arrayList.get(n), true));
            }
            stringBuilder.append("</row>");
            n = 2;
            for (Map<String, Object> map : list) {
                stringBuilder.append("<row r=\"").append(n).append("\">");
                for (int i = 0; i < arrayList.size(); ++i) {
                    stringBuilder.append(XlsxWriter.cell(i, n, map.get(arrayList.get(i)), false));
                }
                stringBuilder.append("</row>");
                ++n;
            }
        }
        return XlsxWriter.closeSheet(stringBuilder);
    }

    private static String sheetTotais(Map<String, Object> map) {
        StringBuilder stringBuilder = XlsxWriter.openSheet();
        stringBuilder.append("<row r=\"1\">").append(XlsxWriter.cellStr(0, 1, "Indicador", true)).append(XlsxWriter.cellStr(1, 1, "Total", true)).append("</row>");
        int n = 2;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            stringBuilder.append("<row r=\"").append(n).append("\">").append(XlsxWriter.cellStr(0, n, entry.getKey(), false)).append(XlsxWriter.cell(1, n, entry.getValue(), false)).append("</row>");
            ++n;
        }
        return XlsxWriter.closeSheet(stringBuilder);
    }

    private static String sheetGlossario(String string, String string2) {
        StringBuilder stringBuilder = XlsxWriter.openSheet();
        stringBuilder.append("<row r=\"1\">").append(XlsxWriter.cellStr(0, 1, "Indicador", true)).append(XlsxWriter.cellStr(1, 1, "O que significa", true)).append("</row>");
        int n = 2;
        for (Map.Entry<String, String> entry : Config.GLOSSARIO.entrySet()) {
            stringBuilder.append("<row r=\"").append(n).append("\">").append(XlsxWriter.cellStr(0, n, entry.getKey(), false)).append(XlsxWriter.cellStr(1, n, entry.getValue(), false)).append("</row>");
            ++n;
        }
        stringBuilder.append("<row r=\"").append(++n).append("\">").append(XlsxWriter.cellStr(0, n, "Consulta", true)).append(XlsxWriter.cellStr(1, n, "Prefixo " + string + " / Nivel: " + string2, false)).append("</row>");
        return XlsxWriter.closeSheet(stringBuilder);
    }

    private static String cell(int n, int n2, Object object, boolean bl) {
        if (object instanceof Number) {
            String string = bl ? " s=\"1\"" : "";
            return "<c r=\"" + XlsxWriter.ref(n, n2) + "\"" + string + "><v>" + object + "</v></c>";
        }
        return XlsxWriter.cellStr(n, n2, object == null ? "" : object.toString(), bl);
    }

    private static String cellStr(int n, int n2, String string, boolean bl) {
        String string2 = bl ? " s=\"1\"" : "";
        return "<c r=\"" + XlsxWriter.ref(n, n2) + "\"" + string2 + " t=\"inlineStr\"><is><t xml:space=\"preserve\">" + XlsxWriter.esc(string) + "</t></is></c>";
    }

    private static String ref(int n, int n2) {
        StringBuilder stringBuilder = new StringBuilder();
        int n3 = n;
        do {
            stringBuilder.insert(0, (char)(65 + n3 % 26));
        } while ((n3 = n3 / 26 - 1) >= 0);
        return stringBuilder.append(n2).toString();
    }

    private static StringBuilder openSheet() {
        return new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
    }

    private static String closeSheet(StringBuilder stringBuilder) {
        return stringBuilder.append("</sheetData></worksheet>").toString();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>";
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
    }

    private static String workbook() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Prestamista\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Totais\" sheetId=\"2\" r:id=\"rId2\"/><sheet name=\"Glossario\" sheetId=\"3\" r:id=\"rId3\"/></sheets></workbook>";
    }

    private static String workbookRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet3.xml\"/><Relationship Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>";
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F4E79\"/></patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs></styleSheet>";
    }

    private static void put(ZipOutputStream zipOutputStream, String string, String string2) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(string));
        zipOutputStream.write(string2.getBytes("UTF-8"));
        zipOutputStream.closeEntry();
    }

    private static String esc(String string) {
        return string.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private XlsxWriter() {
    }
}
