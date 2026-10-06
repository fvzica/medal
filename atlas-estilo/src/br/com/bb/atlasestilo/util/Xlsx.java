package br.com.bb.atlasestilo.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Leitor mínimo de .xlsx em Java puro (sem POI): abre o zip, lê
 * xl/sharedStrings.xml e a primeira planilha (xl/worksheets/sheet1.xml,
 * ou a de menor número encontrada) e devolve as linhas como String[].
 * Suficiente para os imports administrativos (convenção SUPER PF1).
 */
public final class Xlsx {

    private Xlsx() { }

    public static List<String[]> ler(InputStream in) throws IOException {
        byte[] shared = null;
        TreeMap<Integer, byte[]> sheets = new TreeMap<>();

        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String nome = e.getName();
            if (nome.equals("xl/sharedStrings.xml")) {
                shared = lerTudo(zip);
            } else if (nome.matches("xl/worksheets/sheet(\\d+)\\.xml")) {
                int n = Integer.parseInt(nome.replaceAll("\\D", ""));
                sheets.put(n, lerTudo(zip));
            }
        }
        if (sheets.isEmpty()) {
            throw new IOException("Arquivo .xlsx sem planilhas (xl/worksheets/sheetN.xml).");
        }
        try {
            List<String> strings = shared == null ? new ArrayList<String>()
                                                  : lerSharedStrings(shared);
            return lerPlanilha(sheets.firstEntry().getValue(), strings);
        } catch (XMLStreamException ex) {
            throw new IOException("Falha ao interpretar o .xlsx: " + ex.getMessage(), ex);
        }
    }

    private static byte[] lerTudo(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static XMLStreamReader leitor(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(xml), "UTF-8");
    }

    private static List<String> lerSharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = leitor(xml);
        StringBuilder atual = null;
        boolean dentroT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String tag = r.getLocalName();
                if (tag.equals("si")) atual = new StringBuilder();
                else if (tag.equals("t")) dentroT = true;
            } else if (ev == XMLStreamConstants.CHARACTERS) {
                if (dentroT && atual != null) atual.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String tag = r.getLocalName();
                if (tag.equals("t")) dentroT = false;
                else if (tag.equals("si")) { out.add(atual.toString()); atual = null; }
            }
        }
        return out;
    }

    private static List<String[]> lerPlanilha(byte[] xml, List<String> strings)
            throws XMLStreamException {
        List<Map<Integer, String>> linhas = new ArrayList<>();
        int maxCol = 0;

        XMLStreamReader r = leitor(xml);
        Map<Integer, String> linha = null;
        int col = -1, colSeq = 0;
        String tipo = null;
        boolean dentroV = false, dentroT = false;
        StringBuilder valor = null;

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String tag = r.getLocalName();
                if (tag.equals("row")) {
                    linha = new HashMap<>(); colSeq = 0;
                } else if (tag.equals("c") && linha != null) {
                    String ref = r.getAttributeValue(null, "r");
                    tipo = r.getAttributeValue(null, "t");
                    col = ref != null ? colunaDe(ref) : colSeq;
                    colSeq = col + 1;
                    valor = new StringBuilder();
                } else if (tag.equals("v")) {
                    dentroV = true;
                } else if (tag.equals("t")) {
                    dentroT = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS) {
                if ((dentroV || dentroT) && valor != null) valor.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String tag = r.getLocalName();
                if (tag.equals("v")) dentroV = false;
                else if (tag.equals("t")) dentroT = false;
                else if (tag.equals("c") && linha != null && valor != null) {
                    String v = valor.toString();
                    if ("s".equals(tipo)) {
                        int idx;
                        try { idx = Integer.parseInt(v.trim()); } catch (NumberFormatException n) { idx = -1; }
                        v = idx >= 0 && idx < strings.size() ? strings.get(idx) : "";
                    } else if ("b".equals(tipo)) {
                        v = v.trim().equals("1") ? "1" : "0";
                    }
                    if (!v.isEmpty()) {
                        linha.put(col, v);
                        if (col + 1 > maxCol) maxCol = col + 1;
                    }
                    valor = null; tipo = null;
                } else if (tag.equals("row") && linha != null) {
                    linhas.add(linha); linha = null;
                }
            }
        }

        List<String[]> out = new ArrayList<>();
        for (Map<Integer, String> l : linhas) {
            if (l.isEmpty()) continue;
            String[] arr = new String[maxCol];
            for (int i = 0; i < maxCol; i++) {
                String v = l.get(i);
                arr[i] = v == null ? "" : v.trim();
            }
            out.add(arr);
        }
        return out;
    }

    /** "BC12" -> índice de coluna 0-based (54). */
    private static int colunaDe(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') col = col * 26 + (c - 'A' + 1);
            else break;
        }
        return Math.max(0, col - 1);
    }

    /**
     * Data vinda de célula: tenta os formatos texto (dd/mm/aaaa, ISO, epoch) e,
     * se for um número puro plausível, trata como serial de data do Excel
     * (dias desde 30/12/1899).
     */
    public static Long dataDeCelula(String s) {
        Long t = Texto.data(s);
        if (t != null) return t;
        if (s != null && s.trim().matches("\\d{4,6}(\\.0+)?")) {
            double serial = Double.parseDouble(s.trim());
            if (serial > 15000 && serial < 80000) { // ~1941 a ~2119
                long millis = Math.round((serial - 25569) * 86400000L); // 25569 = 01/01/1970
                return millis;
            }
        }
        return null;
    }
}
