package ar.tod4.discord;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import ar.tod4.TOD4;
import ar.tod4.avisos.Paleta;
import ar.tod4.misiones.Misiones;
import ar.tod4.misiones.Objetivo;
import ar.tod4.util.Items;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Anuncios en Discord (con el bot de DiscordSRV):
 *  - cambios: los mensajes naranjas (cambio de dificultad, nuevo mob...)  - avisos: los celestes
 *  - dungeons: los azules  - misiones: cada mision diaria u opcional nueva  - muertes: cada muerte que cuenta
 * Formato:  ## Titulo (el de Minecraft) — Día N  /  texto  /  (renglon vacio)  /  -# TrucOfDeath 4  /  @everyone
 * Los nombres de Minecraft salen en espanol (lang_es.json, el es_ar del juego).
 */
public final class Discord implements Listener {
    private static final String J = "github.scarsz.discordsrv.dependencies.jda.api.";
    private static final Map<String, String> CANALES = Map.of(
            "cambios", "1553108084304388216",
            "avisos", "1563954729883668621",
            "dungeons", "1563924288665427989",
            "misiones", "1553108308863225937",
            "muertes", "1554183344529342504");

    private final TOD4 pl;
    private final Map<String, String> lang = new HashMap<>();

    public Discord(TOD4 pl) {
        this.pl = pl;
        try (var r = new InputStreamReader(pl.getResource("lang_es.json"), StandardCharsets.UTF_8)) {
            Map<String, String> m = new Gson().fromJson(r, new TypeToken<Map<String, String>>() { }.getType());
            if (m != null) lang.putAll(m);
        } catch (Exception e) {
            pl.getLogger().warning("Discord: no se pudo leer lang_es.json (" + e + ")");
        }
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    // ================================================================= config

    private boolean activo() { return pl.getConfig().getBoolean("discord.activado", true); }

    private String canal(String k) {
        String id = pl.getConfig().getString("discord.canales." + k, CANALES.get(k));
        return id == null || id.isBlank() ? null : id.trim();
    }

    private String pie() { return pl.getConfig().getString("discord.pie", "-# TrucOfDeath 4"); }

    private boolean everyone() { return pl.getConfig().getBoolean("discord.everyone", true); }

    /** El canal de cada color de mensaje (null = no se publica). */
    public static String canalDe(Paleta p) {
        return switch (p) {
            case NARANJA -> "cambios";
            case CELESTE -> "avisos";
            case AZUL -> "dungeons";
            default -> null;
        };
    }

    // ================================================================= mandar (DiscordSRV, por reflexion)

    /** Manda el texto al canal. Devuelve un error para mostrar, o null si salio. */
    public String enviar(String canalK, String texto, boolean conEveryone) {
        if (!activo()) return "Discord está apagado (discord.activado: false).";
        String id = canal(canalK);
        if (id == null) return "No hay canal para \"" + canalK + "\".";
        Plugin d = Bukkit.getPluginManager().getPlugin("DiscordSRV");
        if (d == null || !d.isEnabled()) return "DiscordSRV no está instalado o no arrancó.";
        if (texto.length() > 1990) texto = texto.substring(0, 1990) + "…";
        try {
            ClassLoader cl = d.getClass().getClassLoader();
            Class<?> util = Class.forName("github.scarsz.discordsrv.util.DiscordUtil", true, cl);
            Object ch = util.getMethod("getTextChannelById", String.class).invoke(null, id);
            if (ch == null) return "El bot no encuentra el canal " + canalK + " (" + id + "): ¿DiscordSRV ya se conectó? ¿El bot lo puede ver?";
            Object accion = Class.forName(J + "entities.MessageChannel", true, cl).getMethod("sendMessage", CharSequence.class).invoke(ch, texto);
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<Enum> mt = (Class<Enum>) Class.forName(J + "entities.Message$MentionType", true, cl);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Collection<?> menciones = conEveryone ? EnumSet.of(Enum.valueOf(mt, "EVERYONE")) : EnumSet.noneOf(mt);
                Method am = Class.forName(J + "requests.restaction.MessageAction", true, cl).getMethod("allowedMentions", Collection.class);
                accion = am.invoke(accion, menciones);
            } catch (Throwable ignored) {
                // version de DiscordSRV sin esto: sale igual, con las menciones que permita DiscordSRV
            }
            Class.forName(J + "requests.RestAction", true, cl).getMethod("queue").invoke(accion);
            return null;
        } catch (Throwable t) {
            pl.getLogger().warning("Discord: no se pudo mandar a " + canalK + ": " + t);
            return "No se pudo mandar (" + t.getClass().getSimpleName() + "), mirá la consola.";
        }
    }

    private void publicar(String canalK, String texto, boolean conEveryone) {
        String err = enviar(canalK, texto, conEveryone);
        if (err != null) pl.getLogger().warning("Discord (" + canalK + "): " + err);
    }

    private String armar(String titulo, List<String> cuerpo, boolean conEveryone) {
        StringBuilder sb = new StringBuilder("## ").append(titulo).append(" — Día ").append(pl.dia()).append('\n');
        // sin renglones vacios al principio ni al final
        int a = 0, b = cuerpo.size();
        while (a < b && cuerpo.get(a).isBlank()) a++;
        while (b > a && cuerpo.get(b - 1).isBlank()) b--;
        for (int i = a; i < b; i++) sb.append(cuerpo.get(i)).append('\n');
        sb.append('\n').append(pie());
        if (conEveryone) sb.append('\n').append("@everyone");
        return sb.toString();
    }

    // ================================================================= avisos (cambios / avisos / dungeons)

    /** Un mensaje de /tod4:avisos que se acaba de mandar a todos. */
    public void publicarAviso(ConfigurationSection m) {
        if (m == null || !activo()) return;
        Paleta pal = Paleta.of(m.getInt("paleta", 6));
        String k = canalDe(pal);
        if (k == null) return;
        publicar(k, textoAviso(m), everyone());
    }

    private String textoAviso(ConfigurationSection m) {
        Paleta pal = Paleta.of(m.getInt("paleta", 6));
        String t = m.getString("titulo", "ninguno");
        String titulo = t == null || t.isBlank() || t.equals("ninguno") ? pal.uso : md(t).replace("**", "").replace("__", "");
        List<String> cuerpo = new ArrayList<>();
        for (String l : m.getStringList("lineas")) {
            String x = l.trim();
            if (x.equals("---")) continue;
            if (x.isEmpty() || x.equals(".") || x.equalsIgnoreCase("vacio") || x.equalsIgnoreCase("espacio")) { cuerpo.add(""); continue; }
            cuerpo.add(md(l));
        }
        return armar(titulo, cuerpo, everyone());
    }

    private static final String[] TAGS = {"[ro]", "[r]", "[n]", "[am]", "[vo]", "[v]", "[ci]", "[c]", "[ao]", "[az]", "[rs]", "[m]", "[b]", "[go]", "[g]", "[ng]"};
    private static final Pattern COLORES = Pattern.compile("(?i)&[0-9a-fk-orx]|<#[0-9a-f]{6}>|&#[0-9a-f]{6}");

    /** Texto de los mensajes de Minecraft -> Discord: *resaltado* = negrita, _subrayado_, %%cursiva%%, ~~oculto~~ = spoiler. */
    String md(String s) {
        String t = s.replace("(tic)", "✔").replace("(cruz)", "✖").replace("(estrella)", "✦").replace("(alerta)", "⚠")
                .replace("(calavera)", "☠").replace("(flecha)", "▶").replace("(corazon)", "❤");
        for (String tg : TAGS) t = t.replace(tg, "");
        t = COLORES.matcher(t).replaceAll("");
        t = alternar(t, "*", "**");
        t = alternar(t, "_", "__");
        t = alternar(t, "%%", "*");
        t = alternar(t, "~~", "||");
        String dia = String.valueOf(pl.dia());
        return t.replace("{JUGADOR}", "todos").replace("{jugador}", "todos").replace("{DIA}", dia).replace("{dia}", dia).trim();
    }

    /** Cambia cada par de "marca" por "por" (si queda una suelta, se deja como texto). */
    private static String alternar(String t, String marca, String por) {
        String[] p = t.split(Pattern.quote(marca), -1);
        if (p.length < 3) return t;
        StringBuilder sb = new StringBuilder();
        int pares = (p.length - 1) / 2 * 2;   // marcas que tienen pareja
        for (int i = 0; i < p.length; i++) {
            sb.append(p[i]);
            if (i < p.length - 1) sb.append(i < pares ? por : marca);
        }
        return sb.toString();
    }

    // ================================================================= misiones

    /** Una mision diaria u opcional que se acaba de activar. */
    public void publicarMision(Misiones ms, String clase, String id) {
        if (!activo() || id == null) return;
        boolean dia = clase.equals(Misiones.DIA);
        List<String> c = new ArrayList<>();
        c.add("**Objetivo:**");
        String ultimo = null;
        for (Objetivo o : ms.objetivos(id)) {
            if (o.vacio()) continue;
            String header = o.tipo.visible + (o.tipo.consumible() && o.consumir ? " (se entrega al reclamar)" : "");
            if (o.conjunto && o.tipo != Objetivo.Tipo.EVENTO) {
                Objetivo.Meta m = o.metas().get(0);
                c.add(header + ": " + m.cant + " en total");
                ultimo = null;
                if (o.oculto) { c.add("- ???"); continue; }
                for (String x : m.objs) c.add("- " + esc(plano(Obj.nombre(x))));
                continue;
            }
            if (!header.equals(ultimo)) { c.add(header + ":"); ultimo = header; }
            for (Objetivo.Meta m : o.metas()) {
                String nom;
                if (o.oculto) nom = "???";
                else if (o.tipo == Objetivo.Tipo.EVENTO) nom = md(o.texto);
                else nom = esc(plano(Obj.nombre(m.objs.get(0))));
                boolean conCant = o.tipo != Objetivo.Tipo.EVENTO && o.tipo != Objetivo.Tipo.LOGRO && o.tipo != Objetivo.Tipo.DIMENSION;
                c.add("- " + (conCant ? m.cant + "x " : "") + nom);
            }
        }
        List<ItemStack> r = ms.recompensas(id);
        if (!r.isEmpty()) {
            c.add("");
            c.add("**Recompensa:**");
            for (ItemStack it : r) c.add("- " + it.getAmount() + "x " + esc(plano(Items.nombre(it))));
        }
        publicar("misiones", armar(dia ? "NUEVA MISIÓN DIARIA" : "NUEVA MISIÓN OPCIONAL", c, everyone()), everyone());
    }

    // ================================================================= muertes

    /** Las mismas que cuenta /tod4 muertes: no los OP, ni los cuyes, ni el gulag. Sin @everyone. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void muere(PlayerDeathEvent e) {
        if (!activo() || !pl.getConfig().getBoolean("discord.muertes", true)) return;
        Player p = e.getEntity();
        if (ar.tod4.hook.Gulag.muereEnGulag(p.getUniqueId()) || p.isOp() || ar.tod4.hook.Cuyes.activo(p)) return;
        String msg = e.deathMessage() == null ? p.getName() + " murió" : plano(e.deathMessage()).trim();
        if (msg.startsWith("☠")) msg = msg.substring(1).trim();
        publicar("muertes", "### ☠ " + esc(msg) + "\n-# Día " + pl.dia() + " · " + pie().replaceFirst("^-#\\s*", ""), false);
    }

    // ================================================================= texto plano en espanol

    /** Un texto de Minecraft (con nombres traducibles) como texto plano en espanol. */
    public String plano(Component c) {
        StringBuilder sb = new StringBuilder();
        render(c, sb);
        return COLORES.matcher(sb.toString()).replaceAll("");
    }

    private void render(Component c, StringBuilder sb) {
        if (c instanceof TextComponent t) sb.append(t.content());
        else if (c instanceof TranslatableComponent tc) {
            String f = lang.get(tc.key());
            if (f == null) f = tc.fallback() != null ? tc.fallback() : tc.key();
            List<String> args = new ArrayList<>();
            for (var a : tc.arguments()) args.add(plano(a.asComponent()));
            sb.append(formatear(f, args));
        } else {
            sb.append(PlainTextComponentSerializer.plainText().serialize(c.children(List.of())));
        }
        for (Component h : c.children()) render(h, sb);
    }

    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?([sd%])");

    private static String formatear(String f, List<String> args) {
        Matcher m = ARG.matcher(f);
        StringBuilder sb = new StringBuilder();
        int sig = 0;
        while (m.find()) {
            String rep;
            if (m.group(2).equals("%")) rep = "%";
            else {
                int i = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : sig++;
                rep = i >= 0 && i < args.size() ? args.get(i) : "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Que los nombres con _ o * no se vuelvan formato en Discord. */
    private static String esc(String s) { return s.replaceAll("([\\\\*_~|`>])", "\\\\$1"); }

    // ================================================================= /tod4 discord

    public void cmd(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        switch (sub) {
            case "prueba" -> {
                List<String> ks = a.length > 2 ? List.of(a[2].toLowerCase()) : List.copyOf(CANALES.keySet());
                for (String k : ks) {
                    String err = enviar(k, "Prueba de TOD4 en **" + k + "** (sin @everyone).\n-# TrucOfDeath 4", false);
                    if (err == null) Style.ok(s, "Mandado a &f" + k + "&a.");
                    else Style.err(s, k + ": " + err);
                }
            }
            case "aviso" -> {
                if (a.length < 3 || !pl.avisos().existe(a[2])) { Style.err(s, "Uso: &e/tod4 discord aviso <id del mensaje>"); return; }
                ConfigurationSection m = pl.avisos().seccion(a[2]);
                String k = canalDe(Paleta.of(m.getInt("paleta", 6)));
                if (k == null) { Style.err(s, "Ese color no se publica en Discord."); return; }
                String err = enviar(k, textoAviso(m), everyone());
                if (err == null) Style.ok(s, "Publicado en &f" + k + "&a.");
                else Style.err(s, err);
            }
            case "ver" -> {
                if (a.length < 3 || !pl.avisos().existe(a[2])) { Style.err(s, "Uso: &e/tod4 discord ver <id del mensaje>"); return; }
                s.sendMessage(textoAviso(pl.avisos().seccion(a[2])));
            }
            default -> {
                Style.ok(s, "&e/tod4 discord prueba [canal] &7- manda un mensaje de prueba (sin @everyone)");
                Style.ok(s, "&e/tod4 discord ver <id> &7- muestra acá cómo saldría un mensaje");
                Style.ok(s, "&e/tod4 discord aviso <id> &7- publica un mensaje en Discord sin mandarlo en el juego");
                Style.ok(s, "&7Canales: cambios, avisos, dungeons, misiones, muertes");
            }
        }
    }
}
