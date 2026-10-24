package com.meulauncher.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HomeActivity extends Activity {

    private static final String PAGINA = "file:///android_asset/index.html";

    private WebView webView;
    private boolean paginaPronta = false;

    private static class App {
        String pacote;
        String nome;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modoImersivo();

        try {
            criarWebView();
        } catch (Throwable erro) {
            // TV sem WebView funcionando: usa uma lista simples, mas nunca deixa a tela vazia
            criarListaSimples();
        }
    }

    private void modoImersivo() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    private void criarWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF101114);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings ajustes = webView.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new Ponte(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                paginaPronta = true;
            }

            @Override
            public void onReceivedError(WebView view, int codigo, String descricao, String url) {
                if (url != null && url.startsWith(PAGINA)) {
                    criarListaSimples();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // So aceita a pagina local; nada de abrir sites dentro do launcher
                return url == null || !url.startsWith("file:///android_asset/");
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        setContentView(webView);
        webView.loadUrl(PAGINA);
        webView.requestFocus();
    }

    // ---------- Ponte entre a pagina (JavaScript) e o Android ----------

    private class Ponte {
        @JavascriptInterface
        public String apps() {
            return listarAppsJson();
        }

        @JavascriptInterface
        public String icone(String pacote) {
            return iconeBase64(pacote);
        }

        @JavascriptInterface
        public void launch(final String pacote) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    abrirApp(pacote);
                }
            });
        }

        @JavascriptInterface
        public void settings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    abrirConfiguracoes();
                }
            });
        }
    }

    // ---------- Apps instalados ----------

    private List<App> carregarApps() {
        final PackageManager pm = getPackageManager();
        Set<String> vistos = new HashSet<String>();
        List<App> lista = new ArrayList<App>();

        String[] categorias = {
                Intent.CATEGORY_LEANBACK_LAUNCHER,
                Intent.CATEGORY_LAUNCHER
        };

        for (String categoria : categorias) {
            Intent filtro = new Intent(Intent.ACTION_MAIN, null);
            filtro.addCategory(categoria);

            List<ResolveInfo> achados = pm.queryIntentActivities(filtro, 0);
            for (ResolveInfo info : achados) {
                String pacote = info.activityInfo.packageName;
                if (pacote.equals(getPackageName())) {
                    continue;
                }
                if (vistos.add(pacote)) {
                    App app = new App();
                    app.pacote = pacote;
                    app.nome = String.valueOf(info.loadLabel(pm));
                    lista.add(app);
                }
            }
        }

        final Collator ordem = Collator.getInstance(new Locale("pt", "BR"));
        Collections.sort(lista, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                return ordem.compare(a.nome, b.nome);
            }
        });

        return lista;
    }

    private String listarAppsJson() {
        JSONArray resultado = new JSONArray();
        try {
            for (App app : carregarApps()) {
                JSONObject item = new JSONObject();
                item.put("p", app.pacote);
                item.put("n", app.nome);
                resultado.put(item);
            }
        } catch (Exception erro) {
            // devolve o que conseguiu montar
        }
        return resultado.toString();
    }

    private String iconeBase64(String pacote) {
        try {
            Drawable desenho = getPackageManager().getApplicationIcon(pacote);
            int tamanho = 96;
            Bitmap imagem = Bitmap.createBitmap(tamanho, tamanho, Bitmap.Config.ARGB_8888);
            Canvas tela = new Canvas(imagem);
            desenho.setBounds(0, 0, tamanho, tamanho);
            desenho.draw(tela);

            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            imagem.compress(Bitmap.CompressFormat.PNG, 100, saida);
            imagem.recycle();

            return "data:image/png;base64,"
                    + Base64.encodeToString(saida.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable erro) {
            return "";
        }
    }

    // ---------- Abrir apps e configuracoes ----------

    private void abrirApp(String pacote) {
        PackageManager pm = getPackageManager();

        // Apps de TV (Netflix, YouTube TV...) costumam ter so a entrada "leanback"
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pacote);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(pacote);
        }

        if (intent == null) {
            Toast.makeText(this, "Nao foi possivel abrir este aplicativo", Toast.LENGTH_SHORT).show();
            return;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception erro) {
            Toast.makeText(this, "Nao foi possivel abrir este aplicativo", Toast.LENGTH_SHORT).show();
        }
    }

    private void abrirConfiguracoes() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception erro) {
            Toast.makeText(this, "Configuracoes indisponiveis", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Plano B: lista simples, sem WebView ----------

    private void criarListaSimples() {
        final List<App> apps = carregarApps();

        final List<String> nomes = new ArrayList<String>();
        final List<String> pacotes = new ArrayList<String>();
        for (App app : apps) {
            nomes.add(app.nome);
            pacotes.add(app.pacote);
        }
        nomes.add("Configuracoes");
        pacotes.add("__config__");

        ListView lista = new ListView(this);
        lista.setBackgroundColor(0xFF101114);
        lista.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, nomes));
        lista.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> pai, View item, int posicao, long id) {
                String pacote = pacotes.get(posicao);
                if ("__config__".equals(pacote)) {
                    abrirConfiguracoes();
                } else {
                    abrirApp(pacote);
                }
            }
        });

        setContentView(lista);
        lista.requestFocus();
    }

    // ---------- Ciclo de vida ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null && paginaPronta) {
            webView.evaluateJavascript(
                    "if (window.recarregarApps) { window.recarregarApps(); }", null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Botao HOME apertado de novo: volta ao inicio da tela
        if (webView != null && paginaPronta) {
            webView.evaluateJavascript(
                    "if (window.irParaInicio) { window.irParaInicio(); }", null);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean temFoco) {
        super.onWindowFocusChanged(temFoco);
        if (temFoco) {
            modoImersivo();
        }
    }

    @Override
    public void onBackPressed() {
        // Tela inicial nao deve fechar com o botao Voltar
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
