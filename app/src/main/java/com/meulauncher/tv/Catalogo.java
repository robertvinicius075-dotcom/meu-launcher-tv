package com.meulauncher.tv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Catalogo de filmes e series (TMDB) para a tela inicial.
 * Classe Java pura (nao usa nada do Android) para poder ser testada no computador.
 * A chave do TMDB nunca fica no codigo: ela e digitada no celular e guardada so no aparelho.
 */
public class Catalogo {

    /** Endereco da API. Pode ser trocado nos testes. */
    public static String BASE = "https://api.themoviedb.org/3";

    private static final long VALIDADE_MS = 12L * 3600000L;

    // ---------- Servicos de streaming ----------

    private static class Servico {
        final String chave;
        final String rotulo;
        final int idPadrao;
        final String[] nomes;

        Servico(String chave, String rotulo, int idPadrao, String... nomes) {
            this.chave = chave;
            this.rotulo = rotulo;
            this.idPadrao = idPadrao;
            this.nomes = nomes;
        }
    }

    private static final Servico[] SERVICOS = new Servico[] {
        new Servico("netflix", "Netflix", 8, "netflix"),
        new Servico("prime", "Prime Video", 119, "amazonprimevideo", "primevideo"),
        new Servico("disney", "Disney+", 337, "disneyplus", "disney"),
        new Servico("globoplay", "Globoplay", 307, "globoplay"),
        new Servico("max", "Max", 1899, "max", "hbomax"),
        new Servico("apple", "Apple TV+", 350, "appletvplus", "appletv"),
        new Servico("paramount", "Paramount+", 531, "paramountplus", "paramount")
    };

    // ---------- Modelos ----------

    private static class Item {
        String id = "";
        String tipo = "";
        String titulo = "";
        String sinopse = "";
        String fundo = "";
        String poster = "";
        String logo = "";
        double nota = 0;
        String ano = "";
        double popularidade = 0;
        Set<String> servicos = new HashSet<String>();
    }

    private static class Fileira {
        String nome;
        String app = "";
        List<Item> itens = new ArrayList<Item>();
    }

    // ---------- Estado ----------

    private final File pasta;
    private String chave = "";
    private String cache = "";
    private long cacheEm = 0;
    private boolean atualizando = false;

    public Catalogo(File pasta) {
        this.pasta = pasta;
        if (!pasta.exists()) {
            pasta.mkdirs();
        }
    }

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

    public synchronized void carregarSalvos() {
        try {
            File f = arquivo("tmdb.chave");
            if (f.exists()) {
                chave = lerTexto(f).trim();
            }
        } catch (Exception e) {
            chave = "";
        }
        try {
            File f = arquivo("catalogo.json");
            if (f.exists()) {
                cache = lerTexto(f);
                cacheEm = f.lastModified();
            }
        } catch (Exception e) {
            cache = "";
            cacheEm = 0;
        }
    }

    // ---------- Chave ----------

    public synchronized boolean temChave() {
        return chave.length() > 0;
    }

    /** Guarda a chave (so no aparelho). Aceita a "Chave da API" ou o "Token de leitura". */
    public synchronized void definirChave(String nova) throws IOException {
        String limpa = nova == null ? "" : nova.trim().replace("\"", "").replace("'", "");
        if (limpa.startsWith("Bearer ")) {
            limpa = limpa.substring(7).trim();
        }
        if (limpa.length() < 20 || limpa.indexOf(' ') >= 0 || limpa.indexOf('\n') >= 0) {
            throw new IOException("Essa chave do TMDB parece incompleta. Copie a chave inteira.");
        }
        chave = limpa;
        gravarTexto(arquivo("tmdb.chave"), limpa);
        // A chave nova invalida o que ja estava guardado
        cache = "";
        cacheEm = 0;
        arquivo("catalogo.json").delete();
    }

    public synchronized void removerChave() {
        chave = "";
        cache = "";
        cacheEm = 0;
        arquivo("tmdb.chave").delete();
        arquivo("catalogo.json").delete();
    }

    // ---------- Consultas ----------

    /** Ultimo catalogo guardado (JSON) ou vazio. */
    public synchronized String json() {
        return cache;
    }

    public synchronized boolean precisaAtualizar() {
        if (chave.length() == 0 || atualizando) {
            return false;
        }
        return cache.length() == 0 || System.currentTimeMillis() - cacheEm > VALIDADE_MS;
    }

    public synchronized boolean estaAtualizando() {
        return atualizando;
    }

    // ---------- Atualizacao pela internet ----------

    /** Busca tudo no TMDB e guarda. Devolve a mensagem para o usuario. */
    public String atualizar() throws IOException {
        String minhaChave;
        synchronized (this) {
            if (chave.length() == 0) {
                throw new IOException("Falta a chave do TMDB.");
            }
            if (atualizando) {
                throw new IOException("O catalogo ja esta sendo atualizado.");
            }
            atualizando = true;
            minhaChave = chave;
        }
        try {
            String resultado = montar(minhaChave);
            synchronized (this) {
                cache = resultado;
                cacheEm = System.currentTimeMillis();
            }
            gravarTexto(arquivo("catalogo.json"), resultado);
            return "Catalogo atualizado.";
        } finally {
            synchronized (this) {
                atualizando = false;
            }
        }
    }

    private String montar(String k) throws IOException {
        Map<String, Item> unicos = new LinkedHashMap<String, Item>();
        List<Fileira> fileiras = new ArrayList<Fileira>();

        // 1) Em alta hoje (filmes e series). Se isto falhar, a chave esta errada ou sem internet.
        List<Item> emAlta = itens(pegar("/trending/all/day", "", k), "", unicos);
        if (emAlta.isEmpty()) {
            throw new IOException("O TMDB nao devolveu titulos.");
        }
        Fileira f1 = new Fileira();
        f1.nome = "Em alta hoje";
        f1.itens = cortar(emAlta, 20);
        fileiras.add(f1);

        // 2) Populares
        try {
            Fileira f2 = new Fileira();
            f2.nome = "Filmes populares";
            f2.itens = cortar(itens(pegar("/movie/popular", "&region=BR", k), "movie", unicos), 20);
            if (!f2.itens.isEmpty()) {
                fileiras.add(f2);
            }
        } catch (IOException e) {
            // segue sem essa fileira
        }
        try {
            Fileira f3 = new Fileira();
            f3.nome = "Series populares";
            f3.itens = cortar(itens(pegar("/tv/popular", "", k), "tv", unicos), 20);
            if (!f3.itens.isEmpty()) {
                fileiras.add(f3);
            }
        } catch (IOException e) {
            // segue sem essa fileira
        }

        // 3) Por servico de streaming (Brasil)
        Map<String, Integer> ids = descobrirIds(k);
        for (int s = 0; s < SERVICOS.length; s++) {
            Servico sv = SERVICOS[s];
            Integer id = ids.get(sv.chave);
            if (id == null) {
                id = sv.idPadrao;
            }
            List<Item> doServico = new ArrayList<Item>();
            String filtro = "&watch_region=BR&with_watch_monetization_types=flatrate&with_watch_providers="
                    + id + "&sort_by=popularity.desc";
            try {
                doServico.addAll(itens(pegar("/discover/movie", filtro, k), "movie", unicos));
            } catch (IOException e) {
                // ignora
            }
            try {
                doServico.addAll(itens(pegar("/discover/tv", filtro, k), "tv", unicos));
            } catch (IOException e) {
                // ignora
            }
            if (doServico.isEmpty()) {
                continue;
            }
            Collections.sort(doServico, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return a.popularidade < b.popularidade ? 1 : (a.popularidade > b.popularidade ? -1 : 0);
                }
            });
            Fileira f = new Fileira();
            f.nome = "Em alta · " + sv.rotulo;
            f.app = sv.chave;
            f.itens = cortar(doServico, 16);
            for (int i = 0; i < f.itens.size(); i++) {
                f.itens.get(i).servicos.add(sv.chave);
            }
            fileiras.add(f);
        }

        // 4) Banners: os primeiros titulos em alta que tenham imagem larga e sinopse
        List<Item> hero = new ArrayList<Item>();
        for (int i = 0; i < emAlta.size() && hero.size() < 6; i++) {
            Item it = emAlta.get(i);
            if (it.fundo.length() > 0 && it.sinopse.length() > 0) {
                hero.add(it);
            }
        }
        // 5) So para os banners: pede as imagens do titulo (logotipo e fundo sem texto).
        //    Se falhar, o banner continua com as imagens que ja vieram das listas.
        for (int i = 0; i < hero.size(); i++) {
            try {
                melhorarImagens(hero.get(i), k);
            } catch (IOException e) {
                // segue com o que ja tem
            }
        }

        StringBuilder b = new StringBuilder();
        b.append("{\"em\":").append(System.currentTimeMillis());
        b.append(",\"hero\":[");
        for (int i = 0; i < hero.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            itemJson(b, hero.get(i));
        }
        b.append("],\"fileiras\":[");
        for (int i = 0; i < fileiras.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Fileira f = fileiras.get(i);
            b.append("{\"nome\":").append(AoVivo.aspas(f.nome));
            b.append(",\"app\":").append(AoVivo.aspas(f.app));
            b.append(",\"itens\":[");
            for (int q = 0; q < f.itens.size(); q++) {
                if (q > 0) {
                    b.append(',');
                }
                itemJson(b, f.itens.get(q));
            }
            b.append("]}");
        }
        b.append("]}");
        return b.toString();
    }

    /**
     * Endpoint /{filme|serie}/{id}/images: escolhe o logotipo (portugues, depois ingles,
     * depois sem idioma) e um fundo panoramico sem texto (sem idioma) de boa resolucao.
     */
    @SuppressWarnings("unchecked")
    private void melhorarImagens(Item it, String k) throws IOException {
        Object r = pegar("/" + it.tipo + "/" + it.id + "/images", "&include_image_language=pt,en,null", k);
        if (!(r instanceof Map)) {
            return;
        }
        Map<String, Object> m = (Map<String, Object>) r;
        String logo = melhor(m.get("logos"), true, 0);
        if (logo.length() > 0) {
            it.logo = logo;
        }
        String fundo = melhor(m.get("backdrops"), false, 1280);
        if (fundo.length() > 0) {
            it.fundo = fundo;
        }
    }

    /** Escolhe o melhor caminho de imagem de uma lista do TMDB. */
    @SuppressWarnings("unchecked")
    private static String melhor(Object lista, boolean aceitaIdioma, int larguraMin) {
        if (!(lista instanceof List)) {
            return "";
        }
        String melhor = "";
        double melhorPonto = -1;
        for (Object o : (List<Object>) lista) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String caminho = texto(m.get("file_path"));
            if (caminho.length() == 0) {
                continue;
            }
            if (aceitaIdioma && !caminho.toLowerCase(Locale.US).endsWith(".png")) {
                continue; // logotipo precisa de fundo transparente
            }
            Object w = m.get("width");
            if (w instanceof Double && (Double) w < larguraMin) {
                continue;
            }
            String idioma = texto(m.get("iso_639_1"));
            double ponto;
            if (aceitaIdioma) {
                if (idioma.equals("pt")) {
                    ponto = 3000;
                } else if (idioma.equals("en")) {
                    ponto = 2000;
                } else if (idioma.length() == 0 || idioma.equals("null")) {
                    ponto = 1000;
                } else {
                    continue;
                }
            } else {
                if (idioma.length() > 0 && !idioma.equals("null")) {
                    continue; // fundo com texto de outro idioma nao serve
                }
                ponto = 1000;
            }
            Object v = m.get("vote_average");
            Object c = m.get("vote_count");
            ponto += (v instanceof Double ? (Double) v : 0) * 10 + (c instanceof Double ? Math.min((Double) c, 99) / 100.0 : 0);
            if (ponto > melhorPonto) {
                melhorPonto = ponto;
                melhor = caminho;
            }
        }
        return melhor;
    }

    private static List<Item> cortar(List<Item> l, int max) {
        return l.size() <= max ? l : new ArrayList<Item>(l.subList(0, max));
    }

    private static void itemJson(StringBuilder b, Item it) {
        b.append("{\"id\":").append(AoVivo.aspas(it.id));
        b.append(",\"t\":").append(AoVivo.aspas(it.tipo));
        b.append(",\"n\":").append(AoVivo.aspas(it.titulo));
        b.append(",\"s\":").append(AoVivo.aspas(it.sinopse));
        b.append(",\"f\":").append(AoVivo.aspas(it.fundo));
        b.append(",\"p\":").append(AoVivo.aspas(it.poster));
        b.append(",\"l\":").append(AoVivo.aspas(it.logo));
        b.append(",\"v\":").append(String.format(Locale.US, "%.1f", it.nota));
        b.append(",\"a\":").append(AoVivo.aspas(it.ano));
        b.append(",\"sv\":[");
        boolean primeiro = true;
        for (String s : it.servicos) {
            if (!primeiro) {
                b.append(',');
            }
            primeiro = false;
            b.append(AoVivo.aspas(s));
        }
        b.append("]}");
    }

    /** Converte a resposta do TMDB em itens; o mesmo titulo vira sempre o mesmo objeto. */
    @SuppressWarnings("unchecked")
    private static List<Item> itens(Object resposta, String tipoPadrao, Map<String, Item> unicos) {
        List<Item> saida = new ArrayList<Item>();
        if (!(resposta instanceof Map)) {
            return saida;
        }
        Object lista = ((Map<String, Object>) resposta).get("results");
        if (!(lista instanceof List)) {
            return saida;
        }
        for (Object o : (List<Object>) lista) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String tipo = texto(m.get("media_type"));
            if (tipo.length() == 0) {
                tipo = tipoPadrao;
            }
            if (!tipo.equals("movie") && !tipo.equals("tv")) {
                continue; // pessoas e outros
            }
            String id = texto(m.get("id"));
            if (id.length() == 0) {
                continue;
            }
            String titulo = texto(m.get(tipo.equals("movie") ? "title" : "name"));
            if (titulo.length() == 0) {
                titulo = texto(m.get("original_title"));
            }
            if (titulo.length() == 0) {
                titulo = texto(m.get("original_name"));
            }
            if (titulo.length() == 0) {
                continue;
            }
            String chaveItem = tipo + ":" + id;
            Item it = unicos.get(chaveItem);
            if (it == null) {
                it = new Item();
                it.id = id;
                it.tipo = tipo;
                it.titulo = titulo;
                String sinopse = texto(m.get("overview")).trim();
                if (sinopse.length() > 330) {
                    int corte = sinopse.lastIndexOf(' ', 320);
                    sinopse = sinopse.substring(0, corte > 200 ? corte : 320) + "...";
                }
                it.sinopse = sinopse;
                it.fundo = texto(m.get("backdrop_path"));
                it.poster = texto(m.get("poster_path"));
                Object nota = m.get("vote_average");
                it.nota = nota instanceof Double ? (Double) nota : 0;
                Object pop = m.get("popularity");
                it.popularidade = pop instanceof Double ? (Double) pop : 0;
                String data = texto(m.get(tipo.equals("movie") ? "release_date" : "first_air_date"));
                it.ano = data.length() >= 4 ? data.substring(0, 4) : "";
                unicos.put(chaveItem, it);
            }
            if (it.fundo.length() == 0 && it.poster.length() == 0) {
                continue; // sem nenhuma imagem nao serve para a tela
            }
            saida.add(it);
        }
        return saida;
    }

    private static String texto(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.floor(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(o);
    }

    /** Pergunta ao TMDB os numeros dos servicos no Brasil; se falhar, usa os numeros padrao. */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> descobrirIds(String k) {
        Map<String, Integer> r = new LinkedHashMap<String, Integer>();
        try {
            Object resposta = pegar("/watch/providers/movie", "&watch_region=BR", k);
            if (resposta instanceof Map) {
                Object lista = ((Map<String, Object>) resposta).get("results");
                if (lista instanceof List) {
                    for (Object o : (List<Object>) lista) {
                        if (!(o instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> m = (Map<String, Object>) o;
                        String nome = AoVivo.normalizar(texto(m.get("provider_name")));
                        String id = texto(m.get("provider_id"));
                        if (nome.length() == 0 || id.length() == 0) {
                            continue;
                        }
                        for (int s = 0; s < SERVICOS.length; s++) {
                            if (r.containsKey(SERVICOS[s].chave)) {
                                continue;
                            }
                            for (int n = 0; n < SERVICOS[s].nomes.length; n++) {
                                if (nome.equals(SERVICOS[s].nomes[n])) {
                                    try {
                                        r.put(SERVICOS[s].chave, Integer.valueOf(id));
                                    } catch (NumberFormatException e) {
                                        // ignora
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // usa os numeros padrao
        }
        return r;
    }

    // ---------- Pedido HTTP ----------

    private static Object pegar(String caminho, String extra, String k) throws IOException {
        boolean token = k.startsWith("eyJ") && k.indexOf('.') > 0;
        String url = BASE + caminho + "?language=pt-BR" + extra;
        if (!token) {
            url += "&api_key=" + URLEncoder.encode(k, "UTF-8");
        }
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(25000);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "MeuLauncherTV");
        if (token) {
            con.setRequestProperty("Authorization", "Bearer " + k);
        }
        int codigo;
        try {
            codigo = con.getResponseCode();
        } catch (IOException e) {
            throw new IOException("Sem conexao com o TMDB. Confira a internet da TV.");
        }
        if (codigo == 401 || codigo == 403) {
            con.disconnect();
            throw new IOException("O TMDB recusou a chave. Confira se copiou a chave inteira.");
        }
        if (codigo == 429) {
            con.disconnect();
            throw new IOException("O TMDB pediu para tentar mais tarde.");
        }
        if (codigo != 200) {
            con.disconnect();
            throw new IOException("O TMDB respondeu erro " + codigo + ".");
        }
        InputStream in = con.getInputStream();
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                saida.write(buf, 0, n);
                if (saida.size() > 8 * 1024 * 1024) {
                    throw new IOException("Resposta grande demais.");
                }
            }
            return Json.ler(new String(saida.toByteArray(), "UTF-8"));
        } finally {
            in.close();
            con.disconnect();
        }
    }

    // ---------- Leitor de JSON minimo (sem depender do Android) ----------

    static class Json {
        private final String s;
        private int i = 0;

        private Json(String s) {
            this.s = s;
        }

        static Object ler(String texto) throws IOException {
            Json j = new Json(texto);
            try {
                j.espaco();
                Object v = j.valor();
                return v;
            } catch (RuntimeException e) {
                throw new IOException("Resposta do TMDB em formato inesperado.");
            }
        }

        private void espaco() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object valor() {
            espaco();
            char c = s.charAt(i);
            if (c == '{') {
                return objeto();
            }
            if (c == '[') {
                return lista();
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int ini = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (ini == i) {
                throw new IllegalStateException("json");
            }
            return Double.valueOf(s.substring(ini, i));
        }

        private Map<String, Object> objeto() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            espaco();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                espaco();
                String k = string();
                espaco();
                if (s.charAt(i) != ':') {
                    throw new IllegalStateException("json");
                }
                i++;
                m.put(k, valor());
                espaco();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalStateException("json");
                }
            }
        }

        private List<Object> lista() {
            List<Object> l = new ArrayList<Object>();
            i++;
            espaco();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(valor());
                espaco();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new IllegalStateException("json");
                }
            }
        }

        private String string() {
            if (s.charAt(i) != '"') {
                throw new IllegalStateException("json");
            }
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n':
                            b.append('\n');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 'b':
                            b.append('\b');
                            break;
                        case 'f':
                            b.append('\f');
                            break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }
    }
}
