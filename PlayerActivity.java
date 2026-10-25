package com.meulauncher.tv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * Tela cheia de video da aba "Ao vivo".
 * Seta para cima/baixo (ou CH+/CH-) troca de canal, OK mostra as informacoes, Voltar sai.
 */
public class PlayerActivity extends Activity {

    private ExoPlayer player;
    private SurfaceView superficie;
    private TextView info;
    private final Handler relogio = new Handler();
    private boolean tentouHls = false;
    private boolean infoFixa = false;

    private final Runnable esconderInfo = new Runnable() {
        @Override
        public void run() {
            if (!infoFixa) {
                info.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);

        superficie = new SurfaceView(this);
        raiz.addView(superficie, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(26);
        info.setPadding(60, 36, 60, 36);
        info.setBackgroundColor(0xB3000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        raiz.addView(info, lp);

        setContentView(raiz);
        modoImersivo();

        DefaultHttpDataSource.Factory fabrica = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 11; TV) MeuLauncherTV")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(fabrica))
                .build();
        player.setVideoSurfaceView(superficie);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException erro) {
                // Alguns links HLS nao terminam em .m3u8: tenta uma vez como HLS
                if (!tentouHls) {
                    tentouHls = true;
                    iniciarCanal(true);
                    return;
                }
                mostrarInfo("Canal indisponivel no momento. Use as setas para trocar de canal.", true);
            }

            @Override
            public void onPlaybackStateChanged(int estado) {
                if (estado == Player.STATE_BUFFERING) {
                    mostrarInfo(textoDoCanal() + "\nCarregando...", false);
                } else if (estado == Player.STATE_READY) {
                    mostrarInfo(textoDoCanal(), false);
                }
            }
        });

        iniciarCanal(false);
    }

    private void modoImersivo() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private AoVivo.Canal canalAtual() {
        AoVivo av = AoVivo.atual;
        if (av == null || av.fila == null || av.fila.isEmpty()) {
            return null;
        }
        int pos = av.posicao;
        if (pos < 0 || pos >= av.fila.size()) {
            pos = 0;
        }
        return av.fila.get(pos);
    }

    private String textoDoCanal() {
        AoVivo.Canal c = canalAtual();
        if (c == null) {
            return "";
        }
        AoVivo av = AoVivo.atual;
        String texto = (av.posicao + 1) + "  " + c.nome;
        String guia = av.textoAgora(c, System.currentTimeMillis());
        if (guia.length() > 0) {
            texto = texto + "\n" + guia;
        }
        return texto;
    }

    private void iniciarCanal(boolean comoHls) {
        AoVivo.Canal c = canalAtual();
        if (c == null) {
            finish();
            return;
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(c.url);
        if (comoHls || c.url.toLowerCase().contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        player.setMediaItem(item.build());
        player.prepare();
        player.setPlayWhenReady(true);
        mostrarInfo(textoDoCanal(), false);
    }

    private void mudarCanal(int passo) {
        AoVivo av = AoVivo.atual;
        if (av == null || av.fila.isEmpty()) {
            return;
        }
        int n = av.fila.size();
        av.posicao = ((av.posicao + passo) % n + n) % n;
        tentouHls = false;
        iniciarCanal(false);
    }

    private void mostrarInfo(String texto, boolean fixa) {
        infoFixa = fixa;
        info.setText(texto);
        info.setVisibility(View.VISIBLE);
        relogio.removeCallbacks(esconderInfo);
        if (!fixa) {
            relogio.postDelayed(esconderInfo, 4000);
        }
    }

    @Override
    public boolean onKeyDown(int tecla, KeyEvent evento) {
        if (tecla == KeyEvent.KEYCODE_DPAD_UP || tecla == KeyEvent.KEYCODE_CHANNEL_UP) {
            mudarCanal(-1);
            return true;
        }
        if (tecla == KeyEvent.KEYCODE_DPAD_DOWN || tecla == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            mudarCanal(1);
            return true;
        }
        if (tecla == KeyEvent.KEYCODE_DPAD_CENTER || tecla == KeyEvent.KEYCODE_ENTER) {
            if (info.getVisibility() == View.VISIBLE) {
                infoFixa = false;
                info.setVisibility(View.GONE);
            } else {
                mostrarInfo(textoDoCanal(), false);
            }
            return true;
        }
        return super.onKeyDown(tecla, evento);
    }

    @Override
    public void onWindowFocusChanged(boolean temFoco) {
        super.onWindowFocusChanged(temFoco);
        if (temFoco) {
            modoImersivo();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Sair do video ao apertar HOME ou abrir outro app, para nao ficar tocando escondido
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        relogio.removeCallbacks(esconderInfo);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
