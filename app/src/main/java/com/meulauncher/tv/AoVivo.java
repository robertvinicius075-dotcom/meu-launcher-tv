package com.meulauncher.tv;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Tudo da aba "Ao vivo": ler lista M3U, ler guia de programacao (XMLTV),
 * baixar links e receber a lista enviada pelo celular.
 * Classe Java pura (nao usa nada do Android) para poder ser testada no computador.
 */
public class AoVivo {

    /** Instancia usada pela tela do player. */
    public static AoVivo atual;

    // ---------- Modelos ----------

    public static class Canal {
        public String nome = "";
        public String logo = "";
        public String grupo = "";
        public String tvgId = "";
        public String tvgNome = "";
        public String url = "";
    }

    public static class Programa {
        public long inicio;
        public long fim;
        public String titulo = "";
    }

    // ---------- Estado ----------

    private final File pasta;
    private List<Canal> canais = new ArrayList<Canal>();
    private String epgDoCabecalho = "";
    private String epgEscolhido = "";
    private String origemLista = "";
    private Map<String, List<Programa>> epg = new HashMap<String, List<Programa>>();
    private Map<String, String> epgNomes = new HashMap<String, String>();
    private long epgAtualizadoEm = 0;

    // Fila do player
    public List<Canal> fila = new ArrayList<Canal>();
    public int posicao = 0;

    public AoVivo(File pasta) {
        this.pasta = pasta;
        if (!pasta.exists()) {
            pasta.mkdirs();
        }
    }

    // ---------- Arquivos ----------

    private File arquivo(String nome) {
        return new File(pasta, nome);
    }

    private static String lerTexto(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                saida.write(buf, 0, n);
            }
            return new String(saida.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void gravarTexto(File f, String texto) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(texto.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    /** Le o que ja estava salvo (lista, link do guia e guia em cache). */
    public synchronized void carregarSalvos() {
        try {
            File f = arquivo("lista.m3u");
            if (f.exists()) {
                lerLista(lerTexto(f));
            }
        } catch (Exception e) {
            canais = new ArrayList<Canal>();
        }
        try {
            File f = arquivo("epg.url");
            if (f.exists()) {
                epgEscolhido = lerTexto(f).trim();
            }
        } catch (Exception e) {
            epgEscolhido = "";
        }
        try {
            File f = arquivo("origem.txt");
            if (f.exists()) {
                origemLista = lerTexto(f).trim();
            }
        } catch (Exception e) {
            origemLista = "";
        }
        try {
            lerCacheEpg();
        } catch (Exception e) {
            epg = new HashMap<String, List<Programa>>();
        }
    }

    // ---------- Lista M3U ----------

    private static final Pattern ATRIBUTO = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    /** Converte o texto M3U em canais. Devolve tambem o link do guia do cabecalho em guia[0]. */
    public static List<Canal> converterM3u(String texto, String[] guia) {
        List<Canal> lista = new ArrayList<Canal>();
        if (texto == null) {
            return lista;
        }
        if (texto.length() > 0 && texto.charAt(0) == '﻿') {
            texto = texto.substring(1);
        }
        if (guia != null && guia.length > 0) {
            guia[0] = "";
        }

        Canal pendente = null;
        String grupoExtra = "";
        String[] linhas = texto.split("\r?\n");
        for (int i = 0; i < linhas.length; i++) {
            String linha = linhas[i].trim();
            if (linha.length() == 0) {
                continue;
            }
            if (linha.startsWith("#EXTM3U")) {
                Matcher m = ATRIBUTO.matcher(linha);
                while (m.find()) {
                    String chave = m.group(1).toLowerCase(Locale.ROOT);
                    if ((chave.equals("url-tvg") || chave.equals("x-tvg-url")) && guia != null
                            && guia.length > 0 && guia[0].length() == 0) {
                        String valor = m.group(2).trim();
                        int virgula = valor.indexOf(',');
                        if (virgula > 0) {
                            valor = valor.substring(0, virgula).trim();
                        }
                        guia[0] = valor;
                    }
                }
            } else if (linha.startsWith("#EXTINF")) {
                pendente = new Canal();
                Matcher m = ATRIBUTO.matcher(linha);
                int ultimoFim = 0;
                while (m.find()) {
                    String chave = m.group(1).toLowerCase(Locale.ROOT);
                    String valor = m.group(2).trim();
                    ultimoFim = m.end();
                    if (chave.equals("tvg-id")) {
                        pendente.tvgId = valor;
                    } else if (chave.equals("tvg-name")) {
                        pendente.tvgNome = valor;
                    } else if (chave.equals("tvg-logo")) {
                        pendente.logo = valor;
                    } else if (chave.equals("group-title")) {
                        pendente.grupo = valor;
                    }
                }
                int virgula = linha.indexOf(',', ultimoFim);
                if (virgula < 0) {
                    virgula = linha.indexOf(',');
                }
                String nome = virgula >= 0 ? linha.substring(virgula + 1).trim() : "";
                if (nome.length() == 0) {
                    nome = pendente.tvgNome;
                }
                pendente.nome = nome;
                if (pendente.grupo.length() == 0) {
                    pendente.grupo = grupoExtra;
                }
            } else if (linha.startsWith("#EXTGRP:")) {
                grupoExtra = linha.substring(8).trim();
                if (pendente != null && pendente.grupo.length() == 0) {
                    pendente.grupo = grupoExtra;
                }
            } else if (linha.startsWith("#")) {
                continue;
            } else {
                // Linha de endereco do canal
                if (linha.indexOf("://") > 0) {
                    Canal c = pendente != null ? pendente : new Canal();
                    c.url = linha;
                    if (c.nome.length() == 0) {
                        c.nome = linha;
                    }
                    if (c.grupo.length() == 0) {
                        c.grupo = "Sem grupo";
                    }
                    lista.add(c);
                }
                pendente = null;
            }
        }
        return lista;
    }

    private void lerLista(String texto) {
        String[] guia = new String[] { "" };
        canais = converterM3u(texto, guia);
        epgDoCabecalho = guia[0] == null ? "" : guia[0];
    }

    /**
     * Recebe um link (http/https) ou o proprio texto da lista.
     * Devolve a mensagem para mostrar ao usuario. Pode demorar (baixa o arquivo).
     */
    public String definirLista(String entrada) throws IOException {
        String texto = entrada == null ? "" : entrada.trim();
        if (texto.length() == 0) {
            throw new IOException("Nada foi enviado.");
        }

        String origem = "";
        if ((texto.startsWith("http://") || texto.startsWith("https://"))
                && texto.indexOf('\n') < 0 && texto.indexOf(' ') < 0) {
            origem = texto;
            File tmp = arquivo("lista.baixando");
            baixarParaArquivo(texto, tmp);
            texto = lerTexto(tmp);
            tmp.delete();
        }

        String[] guia = new String[] { "" };
        List<Canal> novos = converterM3u(texto, guia);
        if (novos.isEmpty()) {
            throw new IOException("Nao encontrei canais nessa lista. Confira se ela esta no formato M3U.");
        }

        synchronized (this) {
            canais = novos;
            epgDoCabecalho = guia[0] == null ? "" : guia[0];
            origemLista = origem;
        }
        gravarTexto(arquivo("lista.m3u"), texto);
        gravarTexto(arquivo("origem.txt"), origem);
        return novos.size() + " canais adicionados.";
    }

    /** Atualiza a lista usando o link salvo. */
    public String atualizarLista() throws IOException {
        String origem;
        synchronized (this) {
            origem = origemLista;
        }
        if (origem.length() == 0) {
            throw new IOException("Essa lista foi colada como texto. Envie de novo para trocar.");
        }
        return definirLista(origem);
    }

    public synchronized void remover() {
        canais = new ArrayList<Canal>();
        epg = new HashMap<String, List<Programa>>();
        epgNomes = new HashMap<String, String>();
        epgDoCabecalho = "";
        epgEscolhido = "";
        origemLista = "";
        arquivo("lista.m3u").delete();
        arquivo("epg.url").delete();
        arquivo("epg.cache").delete();
        arquivo("origem.txt").delete();
    }

    public synchronized boolean temLista() {
        return !canais.isEmpty();
    }

    public synchronized boolean temOrigem() {
        return origemLista.length() > 0;
    }

    public synchronized int total() {
        return canais.size();
    }

    public synchronized long epgEm() {
        return epgAtualizadoEm;
    }

    public synchronized String enderecoDoGuia() {
        return epgEscolhido.length() > 0 ? epgEscolhido : epgDoCabecalho;
    }

    public synchronized void definirEnderecoDoGuia(String url) throws IOException {
        epgEscolhido = url == null ? "" : url.trim();
        if (epgEscolhido.length() > 0) {
            gravarTexto(arquivo("epg.url"), epgEscolhido);
        } else {
            arquivo("epg.url").delete();
        }
    }

    // ---------- Consultas para a tela ----------

    public static String aspas(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }

    /** {"total":N,"guiaEm":ms,"temGuia":bool,"origem":bool,"grupos":[{"g":"..","n":N},...]} */
    public synchronized String resumoJson() {
        Map<String, Integer> contagem = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < canais.size(); i++) {
            String g = canais.get(i).grupo;
            Integer n = contagem.get(g);
            contagem.put(g, n == null ? 1 : n + 1);
        }
        StringBuilder b = new StringBuilder();
        b.append("{\"total\":").append(canais.size());
        b.append(",\"guiaEm\":").append(epgAtualizadoEm);
        b.append(",\"temGuia\":").append(enderecoDoGuia().length() > 0);
        b.append(",\"origem\":").append(origemLista.length() > 0);
        b.append(",\"grupos\":[");
        boolean primeiro = true;
        for (Map.Entry<String, Integer> e : contagem.entrySet()) {
            if (!primeiro) {
                b.append(',');
            }
            primeiro = false;
            b.append("{\"g\":").append(aspas(e.getKey())).append(",\"n\":").append(e.getValue()).append('}');
        }
        b.append("]}");
        return b.toString();
    }

    private List<Integer> indicesDoGrupo(String grupo) {
        List<Integer> r = new ArrayList<Integer>();
        boolean todos = grupo == null || grupo.length() == 0;
        for (int i = 0; i < canais.size(); i++) {
            if (todos || canais.get(i).grupo.equals(grupo)) {
                r.add(i);
            }
        }
        return r;
    }

    /** {"total":N,"itens":[{"i":indice,"n":nome,"l":logo,"g":grupo},...]} */
    public synchronized String canaisJson(String grupo, int inicio, int quantidade) {
        List<Integer> ids = indicesDoGrupo(grupo);
        StringBuilder b = new StringBuilder();
        b.append("{\"total\":").append(ids.size()).append(",\"itens\":[");
        int fim = Math.min(ids.size(), inicio + quantidade);
        boolean primeiro = true;
        for (int k = Math.max(0, inicio); k < fim; k++) {
            Canal c = canais.get(ids.get(k));
            if (!primeiro) {
                b.append(',');
            }
            primeiro = false;
            b.append("{\"i\":").append(ids.get(k));
            b.append(",\"n\":").append(aspas(c.nome));
            b.append(",\"l\":").append(aspas(c.logo));
            b.append(",\"g\":").append(aspas(c.grupo)).append('}');
        }
        b.append("]}");
        return b.toString();
    }

    /** Monta a fila do player com os canais do grupo e a posicao do canal escolhido. */
    public synchronized boolean prepararFila(String grupo, int indiceGlobal) {
        List<Integer> ids = indicesDoGrupo(grupo);
        List<Canal> nova = new ArrayList<Canal>();
        int pos = 0;
        for (int k = 0; k < ids.size(); k++) {
            nova.add(canais.get(ids.get(k)));
            if (ids.get(k) == indiceGlobal) {
                pos = k;
            }
        }
        if (nova.isEmpty()) {
            return false;
        }
        fila = nova;
        posicao = pos;
        return true;
    }

    // ---------- Guia de programacao (EPG) ----------

    static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFD);
        t = t.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (t.endsWith("fhd")) {
            t = t.substring(0, t.length() - 3);
        } else if (t.endsWith("hd") || t.endsWith("sd")) {
            t = t.substring(0, t.length() - 2);
        }
        return t;
    }

    private String idDoGuia(Canal c) {
        if (c.tvgId.length() > 0 && epg.containsKey(c.tvgId)) {
            return c.tvgId;
        }
        String porNome = epgNomes.get(normalizar(c.nome));
        if (porNome != null && epg.containsKey(porNome)) {
            return porNome;
        }
        if (c.tvgNome.length() > 0) {
            porNome = epgNomes.get(normalizar(c.tvgNome));
            if (porNome != null && epg.containsKey(porNome)) {
                return porNome;
            }
        }
        return null;
    }

    private Programa[] agoraEDepois(Canal c, long agora) {
        String id = idDoGuia(c);
        if (id == null) {
            return null;
        }
        List<Programa> lista = epg.get(id);
        Programa atual = null;
        Programa proximo = null;
        for (int i = 0; i < lista.size(); i++) {
            Programa p = lista.get(i);
            if (p.inicio <= agora && agora < p.fim) {
                atual = p;
            } else if (p.inicio > agora && (proximo == null || p.inicio < proximo.inicio)) {
                proximo = p;
            }
        }
        if (atual == null && proximo == null) {
            return null;
        }
        return new Programa[] { atual, proximo };
    }

    /** Para os indices pedidos (separados por virgula): {"12":[{"t":"..","s":ms,"e":ms},...]} */
    public synchronized String guiaAgoraJson(String indicesCsv, long agora) {
        StringBuilder b = new StringBuilder("{");
        boolean primeiro = true;
        if (indicesCsv != null && indicesCsv.length() > 0) {
            String[] partes = indicesCsv.split(",");
            for (int k = 0; k < partes.length; k++) {
                int i;
                try {
                    i = Integer.parseInt(partes[k].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (i < 0 || i >= canais.size()) {
                    continue;
                }
                Programa[] par = agoraEDepois(canais.get(i), agora);
                if (par == null) {
                    continue;
                }
                if (!primeiro) {
                    b.append(',');
                }
                primeiro = false;
                b.append('"').append(i).append("\":[");
                boolean p1 = true;
                for (int q = 0; q < 2; q++) {
                    if (par[q] == null) {
                        continue;
                    }
                    if (!p1) {
                        b.append(',');
                    }
                    p1 = false;
                    b.append("{\"t\":").append(aspas(par[q].titulo));
                    b.append(",\"s\":").append(par[q].inicio);
                    b.append(",\"e\":").append(par[q].fim).append('}');
                }
                b.append(']');
            }
        }
        b.append('}');
        return b.toString();
    }

    /** Texto para o player: "Agora: X" e "Depois: Y" (vazio se nao ha guia). */
    public synchronized String textoAgora(Canal c, long agora) {
        Programa[] par = agoraEDepois(c, agora);
        if (par == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (par[0] != null) {
            b.append("Agora: ").append(par[0].titulo);
        }
        if (par[1] != null) {
            if (b.length() > 0) {
                b.append("\n");
            }
            b.append("Depois: ").append(par[1].titulo);
        }
        return b.toString();
    }

    /** Baixa e le o guia. Devolve a mensagem para o usuario. */
    public String atualizarGuia() throws IOException {
        String url;
        synchronized (this) {
            url = enderecoDoGuia();
        }
        if (url.length() == 0) {
            throw new IOException("Nenhum guia (EPG) informado. Envie o link do guia XMLTV.");
        }

        File tmp = arquivo("epg.baixando");
        baixarParaArquivo(url, tmp);

        long agora = System.currentTimeMillis();
        Map<String, List<Programa>> novoEpg = new HashMap<String, List<Programa>>();
        Map<String, String> novosNomes = new HashMap<String, String>();
        InputStream bruto = new PushbackInputStream(new FileInputStream(tmp), 2);
        try {
            PushbackInputStream pb = (PushbackInputStream) bruto;
            int b1 = pb.read();
            int b2 = pb.read();
            if (b2 >= 0) {
                pb.unread(b2);
            }
            if (b1 >= 0) {
                pb.unread(b1);
            }
            InputStream dados = pb;
            if (b1 == 0x1f && b2 == 0x8b) {
                dados = new GZIPInputStream(pb, 65536);
            }
            lerXmltv(dados, agora - 3L * 3600000L, agora + 12L * 3600000L, novoEpg, novosNomes);
        } finally {
            bruto.close();
            tmp.delete();
        }

        int total = 0;
        for (List<Programa> l : novoEpg.values()) {
            Collections.sort(l, new Comparator<Programa>() {
                @Override
                public int compare(Programa a, Programa b) {
                    return a.inicio < b.inicio ? -1 : (a.inicio > b.inicio ? 1 : 0);
                }
            });
            total += l.size();
        }
        if (total == 0) {
            throw new IOException("O guia nao tem programacao para agora.");
        }

        synchronized (this) {
            epg = novoEpg;
            epgNomes = novosNomes;
            epgAtualizadoEm = agora;
        }
        gravarCacheEpg();
        return "Guia atualizado: " + total + " programas.";
    }

    // Leitor simples de XMLTV, sem depender de bibliotecas do Android.
    static void lerXmltv(InputStream in, long de, long ate,
                         Map<String, List<Programa>> saida, Map<String, String> nomes) throws IOException {
        Reader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536);
        StringBuilder tag = new StringBuilder();
        StringBuilder txt = new StringBuilder();
        boolean emTag = false;

        boolean emPrograma = false;
        long pIni = 0;
        long pFim = 0;
        String pCanal = "";
        String pTitulo = null;
        boolean lendoTitulo = false;

        String canalId = "";
        boolean lendoNome = false;

        int c;
        while ((c = r.read()) != -1) {
            if (c == '<' && !emTag) {
                String texto = txt.toString();
                txt.setLength(0);
                if (lendoTitulo && pTitulo == null) {
                    pTitulo = decodificar(texto).trim();
                } else if (lendoNome && canalId.length() > 0) {
                    String chave = normalizar(decodificar(texto));
                    if (chave.length() > 0 && !nomes.containsKey(chave)) {
                        nomes.put(chave, canalId);
                    }
                }
                emTag = true;
                tag.setLength(0);
            } else if (c == '>' && emTag) {
                emTag = false;
                String t = tag.toString();
                if (t.startsWith("programme") && (t.length() == 9 || Character.isWhitespace(t.charAt(9)))) {
                    pIni = lerHora(atributo(t, "start"));
                    pFim = lerHora(atributo(t, "stop"));
                    pCanal = decodificar(atributo(t, "channel"));
                    pTitulo = null;
                    lendoTitulo = false;
                    emPrograma = !t.endsWith("/");
                } else if (t.equals("/programme")) {
                    if (emPrograma && pCanal.length() > 0 && pFim >= de && pIni <= ate && pIni > 0) {
                        Programa p = new Programa();
                        p.inicio = pIni;
                        p.fim = pFim > pIni ? pFim : pIni + 1800000L;
                        p.titulo = (pTitulo == null || pTitulo.length() == 0) ? "Sem titulo" : pTitulo;
                        List<Programa> l = saida.get(pCanal);
                        if (l == null) {
                            l = new ArrayList<Programa>();
                            saida.put(pCanal, l);
                        }
                        if (l.size() < 24) {
                            l.add(p);
                        }
                    }
                    emPrograma = false;
                    lendoTitulo = false;
                } else if (emPrograma && (t.equals("title") || t.startsWith("title "))) {
                    lendoTitulo = !t.endsWith("/");
                } else if (t.equals("/title")) {
                    lendoTitulo = false;
                } else if (t.startsWith("channel") && (t.length() == 7 || Character.isWhitespace(t.charAt(7)))) {
                    canalId = decodificar(atributo(t, "id"));
                } else if (t.equals("/channel")) {
                    canalId = "";
                    lendoNome = false;
                } else if (t.equals("display-name") || t.startsWith("display-name ")) {
                    lendoNome = !t.endsWith("/");
                } else if (t.equals("/display-name")) {
                    lendoNome = false;
                }
            } else if (emTag) {
                tag.append((char) c);
                // Comentarios e CDATA: nao tratamos; ignorados quando nao casam com nada acima
            } else if (lendoTitulo || lendoNome) {
                if (txt.length() < 400) {
                    txt.append((char) c);
                }
            }
        }
    }

    static String atributo(String tag, String nome) {
        String procura = nome + "=\"";
        int i = tag.indexOf(procura);
        while (i > 0 && !Character.isWhitespace(tag.charAt(i - 1))) {
            i = tag.indexOf(procura, i + 1);
        }
        if (i < 0) {
            return "";
        }
        int ini = i + procura.length();
        int fim = tag.indexOf('"', ini);
        return fim < 0 ? "" : tag.substring(ini, fim);
    }

    static String decodificar(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '&') {
                int fim = s.indexOf(';', i);
                if (fim > i && fim - i <= 8) {
                    String e = s.substring(i + 1, fim);
                    String troca = null;
                    if (e.equals("amp")) {
                        troca = "&";
                    } else if (e.equals("lt")) {
                        troca = "<";
                    } else if (e.equals("gt")) {
                        troca = ">";
                    } else if (e.equals("quot")) {
                        troca = "\"";
                    } else if (e.equals("apos")) {
                        troca = "'";
                    } else if (e.startsWith("#")) {
                        try {
                            int cod = e.startsWith("#x") || e.startsWith("#X")
                                    ? Integer.parseInt(e.substring(2), 16)
                                    : Integer.parseInt(e.substring(1));
                            troca = new String(Character.toChars(cod));
                        } catch (Exception ex) {
                            troca = null;
                        }
                    }
                    if (troca != null) {
                        b.append(troca);
                        i = fim + 1;
                        continue;
                    }
                }
            }
            b.append(ch);
            i++;
        }
        return b.toString();
    }

    /** "20261025143000 +0000" -> milissegundos desde 1970 (UTC). 0 se invalido. */
    static long lerHora(String s) {
        if (s == null) {
            return 0;
        }
        s = s.trim();
        if (s.length() < 8) {
            return 0;
        }
        try {
            int ano = Integer.parseInt(s.substring(0, 4));
            int mes = Integer.parseInt(s.substring(4, 6));
            int dia = Integer.parseInt(s.substring(6, 8));
            int h = s.length() >= 10 ? Integer.parseInt(s.substring(8, 10)) : 0;
            int mi = s.length() >= 12 ? Integer.parseInt(s.substring(10, 12)) : 0;
            int se = s.length() >= 14 ? Integer.parseInt(s.substring(12, 14)) : 0;
            long dias = diasDesdeEpoca(ano, mes, dia);
            long ms = ((dias * 24 + h) * 60 + mi) * 60 + se;
            ms *= 1000L;

            String resto = s.length() > 14 ? s.substring(14).trim() : "";
            if (resto.length() >= 5 && (resto.charAt(0) == '+' || resto.charAt(0) == '-')) {
                int oh = Integer.parseInt(resto.substring(1, 3));
                int om = Integer.parseInt(resto.substring(3, 5));
                long deslocamento = (oh * 60L + om) * 60000L;
                ms += resto.charAt(0) == '+' ? -deslocamento : deslocamento;
            }
            return ms;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long diasDesdeEpoca(int ano, int mes, int dia) {
        int a = mes <= 2 ? ano - 1 : ano;
        int era = (a >= 0 ? a : a - 399) / 400;
        int yoe = a - era * 400;
        int mp = (mes + 9) % 12;
        int doy = (153 * mp + 2) / 5 + dia - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097L + doe - 719468L;
    }

    // ---------- Cache do guia (so o que importa para agora) ----------

    private synchronized void gravarCacheEpg() throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("#T\t").append(epgAtualizadoEm).append('\n');
        for (Map.Entry<String, String> e : epgNomes.entrySet()) {
            b.append("N\t").append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        }
        for (Map.Entry<String, List<Programa>> e : epg.entrySet()) {
            for (Programa p : e.getValue()) {
                b.append("P\t").append(e.getKey().replace('\t', ' ')).append('\t')
                        .append(p.inicio).append('\t').append(p.fim).append('\t')
                        .append(p.titulo.replace('\t', ' ').replace('\n', ' ')).append('\n');
            }
        }
        gravarTexto(arquivo("epg.cache"), b.toString());
    }

    private void lerCacheEpg() throws IOException {
        File f = arquivo("epg.cache");
        if (!f.exists()) {
            return;
        }
        Map<String, List<Programa>> novoEpg = new HashMap<String, List<Programa>>();
        Map<String, String> novosNomes = new HashMap<String, String>();
        long quando = 0;
        BufferedReader leitor = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String linha;
            while ((linha = leitor.readLine()) != null) {
                String[] p = linha.split("\t", 5);
                if (p[0].equals("#T") && p.length >= 2) {
                    quando = Long.parseLong(p[1]);
                } else if (p[0].equals("N") && p.length >= 3) {
                    novosNomes.put(p[2], p[1]);
                } else if (p[0].equals("P") && p.length >= 5) {
                    Programa pr = new Programa();
                    pr.inicio = Long.parseLong(p[2]);
                    pr.fim = Long.parseLong(p[3]);
                    pr.titulo = p[4];
                    List<Programa> l = novoEpg.get(p[1]);
                    if (l == null) {
                        l = new ArrayList<Programa>();
                        novoEpg.put(p[1], l);
                    }
                    l.add(pr);
                }
            }
        } finally {
            leitor.close();
        }
        epg = novoEpg;
        epgNomes = novosNomes;
        epgAtualizadoEm = quando;
    }

    // ---------- Download ----------

    public static void baixarParaArquivo(String endereco, File destino) throws IOException {
        String atual = endereco;
        for (int voltas = 0; voltas < 8; voltas++) {
            HttpURLConnection con = (HttpURLConnection) new URL(atual).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(40000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) MeuLauncherTV");
            con.setRequestProperty("Accept-Encoding", "identity");
            int codigo;
            try {
                codigo = con.getResponseCode();
            } catch (IOException e) {
                throw new IOException("Nao consegui conectar. Confira o link e a internet da TV.");
            }
            if (codigo == 301 || codigo == 302 || codigo == 303 || codigo == 307 || codigo == 308) {
                String novo = con.getHeaderField("Location");
                con.disconnect();
                if (novo == null) {
                    throw new IOException("Link com redirecionamento invalido.");
                }
                atual = new URL(new URL(atual), novo).toString();
                continue;
            }
            if (codigo != 200) {
                con.disconnect();
                throw new IOException("O servidor respondeu erro " + codigo + ".");
            }
            InputStream in = con.getInputStream();
            OutputStream out = new FileOutputStream(destino);
            try {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 400L * 1024 * 1024) {
                        throw new IOException("Arquivo grande demais.");
                    }
                }
            } finally {
                out.close();
                in.close();
                con.disconnect();
            }
            return;
        }
        throw new IOException("Redirecionamentos demais.");
    }

    // ---------- Servidor para enviar a lista pelo celular ----------

    public interface Ouvinte {
        /** Chamado quando o celular envia. Devolve a mensagem que o celular vai mostrar. */
        String aoReceber(String lista, String guia);

        /** Chamado quando o celular envia a chave do catalogo de filmes (TMDB). */
        String aoReceberChave(String chave);
    }

    public static class Servidor {
        private ServerSocket socket;
        private Thread fio;
        private volatile boolean rodando = false;
        private final Ouvinte ouvinte;
        private int porta = 0;

        public Servidor(Ouvinte ouvinte) {
            this.ouvinte = ouvinte;
        }

        /** Tenta as portas 8080 a 8090. Devolve a porta ou 0 se nao deu. */
        public int iniciar() {
            if (rodando) {
                return porta;
            }
            for (int p = 8080; p <= 8090; p++) {
                try {
                    socket = new ServerSocket(p);
                    porta = p;
                    break;
                } catch (IOException e) {
                    socket = null;
                }
            }
            if (socket == null) {
                return 0;
            }
            rodando = true;
            fio = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (rodando) {
                        try {
                            final Socket s = socket.accept();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    atender(s);
                                }
                            }).start();
                        } catch (IOException e) {
                            break;
                        }
                    }
                }
            });
            fio.setDaemon(true);
            fio.start();
            return porta;
        }

        public void parar() {
            rodando = false;
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                // ignora
            }
            socket = null;
        }

        public boolean estaRodando() {
            return rodando;
        }

        public static String enderecoLocal() {
            try {
                Enumeration<NetworkInterface> placas = NetworkInterface.getNetworkInterfaces();
                String melhor = "";
                while (placas != null && placas.hasMoreElements()) {
                    NetworkInterface placa = placas.nextElement();
                    if (!placa.isUp() || placa.isLoopback()) {
                        continue;
                    }
                    Enumeration<InetAddress> ends = placa.getInetAddresses();
                    while (ends.hasMoreElements()) {
                        InetAddress a = ends.nextElement();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                            String ip = a.getHostAddress();
                            if (a.isSiteLocalAddress()) {
                                return ip;
                            }
                            if (melhor.length() == 0) {
                                melhor = ip;
                            }
                        }
                    }
                }
                return melhor;
            } catch (Exception e) {
                return "";
            }
        }

        private void atender(Socket s) {
            try {
                s.setSoTimeout(60000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();

                // Cabecalho
                ByteArrayOutputStream cab = new ByteArrayOutputStream();
                int estado = 0;
                int b;
                while (cab.size() < 65536 && (b = in.read()) != -1) {
                    cab.write(b);
                    if (b == '\r' && (estado == 0 || estado == 2)) {
                        estado++;
                    } else if (b == '\n' && (estado == 1 || estado == 3)) {
                        estado++;
                        if (estado == 4) {
                            break;
                        }
                    } else {
                        estado = 0;
                    }
                }
                String cabecalho = new String(cab.toByteArray(), "ISO-8859-1");
                String[] linhas = cabecalho.split("\r\n");
                String primeira = linhas.length > 0 ? linhas[0] : "";
                int tamanho = 0;
                for (int i = 1; i < linhas.length; i++) {
                    String l = linhas[i].toLowerCase(Locale.ROOT);
                    if (l.startsWith("content-length:")) {
                        try {
                            tamanho = Integer.parseInt(l.substring(15).trim());
                        } catch (NumberFormatException e) {
                            tamanho = 0;
                        }
                    }
                }

                String mensagem = "";
                if (primeira.startsWith("POST")) {
                    if (tamanho > 40 * 1024 * 1024) {
                        resposta(out, 413, pagina("Arquivo grande demais.", true));
                        s.close();
                        return;
                    }
                    byte[] corpo = new byte[tamanho];
                    int lidos = 0;
                    while (lidos < tamanho) {
                        int n = in.read(corpo, lidos, tamanho - lidos);
                        if (n < 0) {
                            break;
                        }
                        lidos += n;
                    }
                    String texto = new String(corpo, 0, lidos, "UTF-8");
                    String lista = "";
                    String guia = "";
                    String tmdb = "";
                    String[] campos = texto.split("&");
                    for (int i = 0; i < campos.length; i++) {
                        int igual = campos[i].indexOf('=');
                        if (igual < 0) {
                            continue;
                        }
                        String nome = campos[i].substring(0, igual);
                        String valor = URLDecoder.decode(campos[i].substring(igual + 1), "UTF-8");
                        if (nome.equals("lista")) {
                            lista = valor;
                        } else if (nome.equals("guia")) {
                            guia = valor;
                        } else if (nome.equals("tmdb")) {
                            tmdb = valor;
                        }
                    }
                    try {
                        boolean temChave = tmdb.trim().length() > 0;
                        boolean temLista = lista.trim().length() > 0;
                        StringBuilder resumo = new StringBuilder();
                        if (temChave) {
                            resumo.append(ouvinte.aoReceberChave(tmdb));
                        }
                        if (temLista || !temChave) {
                            if (resumo.length() > 0) {
                                resumo.append(' ');
                            }
                            resumo.append(ouvinte.aoReceber(lista, guia));
                        }
                        mensagem = resumo.toString();
                        resposta(out, 200, pagina(mensagem, false));
                    } catch (Exception e) {
                        String m = e.getMessage() == null ? "Erro." : e.getMessage();
                        resposta(out, 200, pagina(m, true));
                    }
                } else {
                    resposta(out, 200, pagina("", false));
                }
                out.flush();
                s.close();
            } catch (Exception e) {
                try {
                    s.close();
                } catch (IOException e2) {
                    // ignora
                }
            }
        }

        private static void resposta(OutputStream out, int codigo, String html) throws IOException {
            byte[] corpo = html.getBytes("UTF-8");
            String cab = "HTTP/1.1 " + codigo + " OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + corpo.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(cab.getBytes("ISO-8859-1"));
            out.write(corpo);
        }

        private static String escapar(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        static String pagina(String mensagem, boolean erro) {
            StringBuilder b = new StringBuilder();
            b.append("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">");
            b.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
            b.append("<title>Meu Launcher TV</title><style>");
            b.append("body{font-family:-apple-system,Arial,sans-serif;background:#101114;color:#fff;margin:0;padding:20px}");
            b.append("h1{font-size:22px;margin:0 0 6px}p{color:#a6a8b2;font-size:15px;line-height:1.4}");
            b.append("label{display:block;margin:18px 0 6px;font-weight:bold}");
            b.append("textarea,input{width:100%;box-sizing:border-box;border:0;border-radius:12px;padding:14px;font-size:16px;background:#1b1d23;color:#fff}");
            b.append("textarea{height:170px}");
            b.append("button{margin-top:20px;width:100%;padding:16px;border:0;border-radius:14px;font-size:18px;font-weight:bold;background:#b7c7ff;color:#101114}");
            b.append(".m{padding:14px;border-radius:12px;margin:14px 0;background:#1f3a2a;color:#bff0cf}");
            b.append(".e{background:#4a1f1f;color:#ffc9c9}</style></head><body>");
            b.append("<h1>Meu Launcher TV</h1>");
            b.append("<p>Envie sua lista de canais para a TV.</p>");
            if (mensagem.length() > 0) {
                b.append("<div class=\"m").append(erro ? " e" : "").append("\">").append(escapar(mensagem)).append("</div>");
            }
            b.append("<form method=\"post\" action=\"/enviar\" accept-charset=\"UTF-8\">");
            b.append("<label>Lista M3U (cole o link ou o texto)</label>");
            b.append("<textarea name=\"lista\" placeholder=\"https://...lista.m3u\"></textarea>");
            b.append("<label>Guia de programacao (EPG) - opcional</label>");
            b.append("<input name=\"guia\" placeholder=\"Link do guia XMLTV (.xml ou .xml.gz)\">");
            b.append("<p>Se a lista ja trouxer o endereco do guia, pode deixar vazio.</p>");
            b.append("<label>Catalogo de filmes e series (TMDB) - opcional</label>");
            b.append("<input name=\"tmdb\" placeholder=\"Chave da API do TMDB\" autocapitalize=\"off\" autocorrect=\"off\">");
            b.append("<p>A chave fica guardada so na TV. Voce pode enviar so a lista, so a chave, ou as duas.</p>");
            b.append("<button type=\"submit\">Enviar para a TV</button></form></body></html>");
            return b.toString();
        }
    }
}
