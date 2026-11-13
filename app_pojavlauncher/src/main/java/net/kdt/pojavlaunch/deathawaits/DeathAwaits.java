package net.kdt.pojavlaunch.deathawaits;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Tudo que e especifico do DeathAwaits Mobile:
 *  - baixa/atualiza o modpack pelo mesmo manifest do launcher do PC;
 *  - cria o perfil "DeathAwaits" e deixa ele selecionado;
 *  - o jogo ja abre conectando no servidor (ver Tools.getMinecraftClientArgs).
 */
public final class DeathAwaits {
    public static final String TAG = "DeathAwaits";
    public static final String MANIFEST_URL = "https://github.com/OtavioColela/deathawaits-launcher/releases/download/modpack/manifest.json";
    public static final String VERSION_ID = "DeathAwaits";
    public static final String PROFILE_KEY = "6e5f0d7a-1b2c-4d3e-8f90-0a1b2c3d4e5f"; // precisa ser UUID (o Amethyst renomeia chaves que nao sao)
    public static final String SERVER_HOST = "node.skycenterhosting.com.br";
    public static final String SERVER_PORT = "25567";
    public static final String SITE_URL = "https://deathawaits.com.br";
    public static final String DISCORD_URL = "https://discord.gg/gc73gQfb5F";
    private static final String PREF_VERSAO = "deathawaits_modpack_versao";
    private static final String PREF_CONTROLES = "deathawaits_controles_versao";
    /** Aumente quando mudar o assets/deathawaits.json para os jogadores receberem o layout novo. */
    private static final int CONTROLES_VERSAO = 1;
    public static final String ARQUIVO_CONTROLES = "deathawaits.json";

    /** Arquivos do modpack do PC que nao vao para o celular. */
    private static boolean ignorar(String caminho) {
        String c = caminho.toLowerCase();
        return c.startsWith("mods/optifine") || c.contains("shadersmod")
                || c.startsWith("config/sodium") || c.startsWith("config/iris")
                || c.equals("config/forge-client.toml") || c.equals("config/forge-common.toml") || c.equals("config/fml.toml");
    }

    private static volatile boolean rodando;

    /** Botao JOGAR: confere/instala o modpack e depois abre o jogo. */
    public static void jogar(Activity act) {
        if (rodando) return;
        rodando = true;
        PojavApplication.sExecutorService.execute(() -> {
            try {
                instalarOuAtualizar();
                instalarControles(act);
                garantirPerfil();
                ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, -1, -1);
                rodando = false;
                // o JOGAR do Amethyst nao abre com tarefas em andamento, por isso a barra termina antes
                Tools.runOnUiThread(() -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
            } catch (Throwable t) {
                Log.e(TAG, "Falha preparando o DeathAwaits", t);
                ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, -1, -1);
                rodando = false;
                Tools.showErrorRemote(act.getString(R.string.da_erro_modpack), t);
            }
        });
    }

    static void instalarOuAtualizar() throws Exception {
        File jogo = new File(Tools.DIR_GAME_NEW);
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.da_verificando);
        JsonObject m;
        try {
            m = JsonParser.parseString(DownloadUtils.downloadString(MANIFEST_URL)).getAsJsonObject();
        } catch (IOException semInternet) {
            // sem internet: se ja tem o modpack instalado, deixa jogar assim mesmo
            if (new File(jogo, "versions/" + VERSION_ID + "/" + VERSION_ID + ".json").exists()) return;
            throw semInternet;
        }
        String versao = m.get("version").getAsString();
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        boolean mesmaVersao = versao.equals(prefs.getString(PREF_VERSAO, ""));

        // confere os arquivos do manifest (rapido pelo tamanho se a versao e a mesma; sha1 se mudou)
        boolean precisa = false;
        JsonArray files = m.getAsJsonArray("files");
        for (JsonElement e : files) {
            JsonObject f = e.getAsJsonObject();
            String caminho = f.get("path").getAsString();
            if (ignorar(caminho)) continue;
            File arq = new File(jogo, caminho);
            if (!arq.isFile() || arq.length() != f.get("size").getAsLong()
                    || (!mesmaVersao && !f.get("sha1").getAsString().equalsIgnoreCase(sha1(arq)))) { precisa = true; break; }
        }
        if (precisa) baixarEExtrair(m, jogo);

        if (m.has("delete")) for (JsonElement e : m.getAsJsonArray("delete")) {
            File d = new File(jogo, e.getAsString());
            if (d.isFile() && d.getCanonicalPath().startsWith(jogo.getCanonicalPath())) d.delete();
        }
        File mods = new File(jogo, "mods");
        File[] lista = mods.listFiles();
        if (lista != null) for (File f : lista) if (ignorar("mods/" + f.getName())) f.delete();

        criarServersDat(jogo);
        configuracaoLeve(jogo);
        prefs.edit().putString(PREF_VERSAO, versao).apply();
    }

    private static void baixarEExtrair(JsonObject m, File jogo) throws Exception {
        File zip = new File(Tools.DIR_CACHE, "deathawaits-modpack.zip");
        final long total = m.has("zipSize") ? m.get("zipSize").getAsLong() : -1;
        DownloadUtils.downloadFileMonitored(m.get("zipUrl").getAsString(), zip, new byte[65536], (atual, tam) -> {
            long t = tam > 0 ? tam : total;
            int pct = t > 0 ? (int) (atual * 100L / t) : 0;
            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, pct, R.string.da_baixando, atual / 1048576, Math.max(1, t / 1048576));
        });
        if (m.has("zipSha1") && !m.get("zipSha1").getAsString().equalsIgnoreCase(sha1(zip))) {
            zip.delete();
            throw new IOException("O download do modpack veio corrompido. Tente de novo.");
        }
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.da_instalando);
        String base = jogo.getCanonicalPath() + File.separator;
        try (ZipFile z = new ZipFile(zip)) {
            int n = z.size(), i = 0;
            Enumeration<? extends ZipEntry> en = z.entries();
            byte[] buf = new byte[65536];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                i++;
                if (e.isDirectory() || ignorar(e.getName())) continue;
                File dest = new File(jogo, e.getName());
                if (!dest.getCanonicalPath().startsWith(base)) continue; // seguranca: nada fora da pasta do jogo
                dest.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(e); FileOutputStream out = new FileOutputStream(dest)) {
                    for (int r; (r = in.read(buf)) > 0;) out.write(buf, 0, r);
                }
                ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, i * 100 / Math.max(1, n), R.string.da_instalando);
            }
        }
        zip.delete();
    }

    /** Layout de botoes do Crafting Dead (recarregar, modo de tiro, arma rapida, mochila...) em portugues. */
    static void instalarControles(android.content.Context ctx) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        File destino = new File(Tools.CTRLMAP_PATH, ARQUIVO_CONTROLES);
        if (destino.exists() && prefs.getInt(PREF_CONTROLES, 0) >= CONTROLES_VERSAO) return;
        try {
            Tools.copyAssetFile(ctx, ARQUIVO_CONTROLES, Tools.CTRLMAP_PATH, true);
            prefs.edit().putInt(PREF_CONTROLES, CONTROLES_VERSAO).apply();
        } catch (IOException e) { Log.w(TAG, "controles", e); }
    }

    /** Perfil "DeathAwaits" com a versao do modpack, selecionado. */
    static void garantirPerfil() {
        LauncherProfiles.load();
        MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(PROFILE_KEY);
        if (p == null) {
            p = new MinecraftProfile();
            p.name = "DeathAwaits";
        }
        p.lastVersionId = VERSION_ID;
        if (p.controlFile == null || p.controlFile.isEmpty()) p.controlFile = ARQUIVO_CONTROLES;
        LauncherProfiles.mainProfileJson.profiles.put(PROFILE_KEY, p);
        LauncherProfiles.write();
        LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, PROFILE_KEY).commit();
    }

    /**
     * Primeira vez no celular: graficos leves (o Crafting Dead e pesado) e resolucao interna de ~70%.
     * So mexe se o jogador ainda nao tiver configurado; depois ele muda como quiser.
     */
    private static void configuracaoLeve(File jogo) {
        File opt = new File(jogo, "options.txt");
        if (!opt.exists()) {
            try (FileOutputStream o = new FileOutputStream(opt)) {
                o.write(("renderDistance:2\nfancyGraphics:false\nao:0\nparticles:2\nclouds:false\nviewBobbing:false\n"
                        + "guiScale:0\nadvancedOpengl:false\nfboEnable:true\nlang:pt_BR\n").getBytes("UTF-8"));
            } catch (IOException e) { Log.w(TAG, "options.txt", e); }
        }
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (!prefs.contains("resolutionRatio")) {
            prefs.edit().putInt("resolutionRatio", 70).apply();
            LauncherPreferences.PREF_SCALE_FACTOR = 0.7f;
        }
    }

    /** servers.dat com o servidor (para quem sair e quiser voltar pelo Multiplayer). */
    private static void criarServersDat(File jogo) {
        File f = new File(jogo, "servers.dat");
        if (f.exists()) return;
        try (FileOutputStream o = new FileOutputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(b);
            d.writeByte(10); d.writeUTF("");
            d.writeByte(9); d.writeUTF("servers"); d.writeByte(10); d.writeInt(1);
            d.writeByte(8); d.writeUTF("name"); d.writeUTF("DeathAwaits");
            d.writeByte(8); d.writeUTF("ip"); d.writeUTF(SERVER_HOST + ":" + SERVER_PORT);
            d.writeByte(1); d.writeUTF("hideAddress"); d.writeByte(0);
            d.writeByte(0);
            d.writeByte(0);
            d.flush();
            o.write(b.toByteArray());
        } catch (IOException e) { Log.w(TAG, "servers.dat", e); }
    }

    /** Argumentos extras do jogo: entra direto no servidor. */
    public static String argumentosExtras(String versionId) {
        if (!VERSION_ID.equals(versionId)) return "";
        return " --server " + SERVER_HOST + " --port " + SERVER_PORT;
    }

    static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            for (int r; (r = in.read(b)) > 0;) md.update(b, 0, r);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }
}
