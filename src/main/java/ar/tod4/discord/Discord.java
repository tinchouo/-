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
            "muertes", "1554183344529342504",
            "revividos", "");

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
    public String enviar(String canalK, String texto, boolean conEveryone) { return enviar(canalK, texto, conEveryone, false); }

    /** conUsuarios: las menciones <@id> del texto avisan (las de nadie mas). */
    public String enviar(String canalK, String texto, boolean conEveryone, boolean conUsuarios) {
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
                EnumSet menciones = EnumSet.noneOf(mt);
                if (conEveryone) menciones.add(Enum.valueOf(mt, "EVERYONE"));
                if (conUsuarios) menciones.add(Enum.valueOf(mt, "USER"));
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

    /** El mismo mensaje de muerte del chat. No salen los OP ni los cuyes; el gulag sale con su mensaje. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void muere(PlayerDeathEvent e) {
        if (!activo() || !pl.getConfig().getBoolean("discord.muertes", true)) return;
        Player p = e.getEntity();
        if (p.isOp() || ar.tod4.hook.Cuyes.activo(p)) return;
        boolean ev = pl.getConfig().getBoolean("discord.muertes_everyone", true);
        if (ar.tod4.hook.Gulag.muereEnGulag(p.getUniqueId())) {
            // en el gulag se muere para siempre: mensaje propio
            String g = pl.getConfig().getString("discord.gulag_mensaje",
                    ar.tod4.TOD4.GULAG_DISCORD);
            publicar("muertes", g.replace("\\n", "\n").replace("{jugador}", esc(p.getName())).replace("{dia}", String.valueOf(pl.dia()))
                    + "\n-# Día " + pl.dia() + " · " + pie().replaceFirst("^-#\\s*", "") + (ev ? "\n@everyone" : ""), ev);
            return;
        }
        String msg = e.deathMessage() == null ? p.getName() + " murió" : plano(e.deathMessage()).trim();
        if (msg.startsWith("☠")) msg = msg.substring(1).trim();
        publicar("muertes", "### ☠ " + esc(msg) + "\n-# Día " + pl.dia() + " · " + pie().replaceFirst("^-#\\s*", "")
                + (ev ? "\n@everyone" : ""), ev);
    }

    // ================================================================= revividos

    /**
     * Canal de revividos: "✨ Trick revivió a @Agus". Solo se etiqueta al revivido (sin @everyone).
     * Su Discord sale de: /tod4 discord vincular, la cuenta vinculada de DiscordSRV (/discord link),
     * o alguien del servidor de Discord que se llame igual. Si no se encuentra, sale su nombre en negrita.
     */
    public void publicarRevivido(String quien, String n) {
        if (!activo() || !pl.getConfig().getBoolean("discord.revividos", true) || canal("revividos") == null) return;
        publicar("revividos", textoRevivido(quien, n), false, true);
    }

    private String textoRevivido(String quien, String n) {
        String q = Bukkit.getOfflinePlayerIfCached(quien) != null || Bukkit.getPlayerExact(quien) != null ? "**" + esc(quien) + "**" : "Un administrador";
        String id = idDiscord(n);
        String a = id != null ? "<@" + id + ">" : "**" + esc(n) + "**";
        return pl.getConfig().getString("discord.revivido_mensaje", "### ✨ {quien} revivió a {revivido}").replace("\\n", "\n")
                .replace("{quien}", q).replace("{revivido}", a).replace("{dia}", String.valueOf(pl.dia()))
                + "\n-# Día " + pl.dia() + " · " + pie().replaceFirst("^-#\\s*", "");
    }

    private void publicar(String canalK, String texto, boolean conEveryone, boolean conUsuarios) {
        String err = enviar(canalK, texto, conEveryone, conUsuarios);
        if (err != null) pl.getLogger().warning("Discord (" + canalK + "): " + err);
    }

    /** El id de Discord de un jugador, o null. */
    public String idDiscord(String n) { String[] r = buscarId(n); return r == null ? null : r[0]; }

    /** {id, de donde salio} o null. */
    private String[] buscarId(String n) {
        String manual = pl.datos().y().getString("discord_ids." + n.toLowerCase());
        if (manual != null && !manual.isBlank()) return new String[]{manual, "vinculado con /tod4 discord vincular"};
        Plugin d = Bukkit.getPluginManager().getPlugin("DiscordSRV");
        if (d == null || !d.isEnabled()) return null;
        ClassLoader cl = d.getClass().getClassLoader();
        // cuenta vinculada en DiscordSRV
        try {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(n);
            if (op != null) {
                Object srv = Class.forName("github.scarsz.discordsrv.DiscordSRV", true, cl).getMethod("getPlugin").invoke(null);
                Object alm = srv.getClass().getMethod("getAccountLinkManager").invoke(srv);
                Object id = alm.getClass().getMethod("getDiscordId", java.util.UUID.class).invoke(alm, op.getUniqueId());
                if (id != null && !String.valueOf(id).isBlank()) return new String[]{String.valueOf(id), "cuenta vinculada de DiscordSRV"};
            }
        } catch (Throwable ignored) { }
        // alguien del servidor de Discord que se llame igual (apodo o usuario), si hay uno solo
        try {
            Object srv = Class.forName("github.scarsz.discordsrv.DiscordSRV", true, cl).getMethod("getPlugin").invoke(null);
            Object g = srv.getClass().getMethod("getMainGuild").invoke(srv);
            if (g == null) return null;
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (String metodo : new String[]{"getMembersByEffectiveName", "getMembersByName", "getMembersByNickname"}) {
                try {
                    Object l = g.getClass().getMethod(metodo, String.class, boolean.class).invoke(g, n, true);
                    if (l instanceof List<?> lista) for (Object mem : lista) ids.add(String.valueOf(mem.getClass().getMethod("getId").invoke(mem)));
                } catch (NoSuchMethodException ignored) { }
            }
            if (ids.size() == 1) return new String[]{ids.iterator().next(), "alguien del Discord que se llama igual"};
        } catch (Throwable ignored) { }
        return null;
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

    private String quienEs(String n) {
        String[] r = buscarId(n);
        return r == null ? "&cno encontré su Discord&7: va a salir su nombre en negrita, sin etiqueta. Vinculalo con &e/tod4 discord vincular " + n + " <id>"
                : "&aetiqueta a &f" + r[0] + " &7(" + r[1] + ")";
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
            case "canal" -> {
                if (a.length < 4 || !CANALES.containsKey(a[2].toLowerCase())) { Style.err(s, "Uso: &e/tod4 discord canal <" + String.join("|", CANALES.keySet()) + "> <id del canal>"); return; }
                String id = a[3].replaceAll("[^0-9]", "");
                if (id.isEmpty()) { Style.err(s, "El id del canal son solo números (click derecho en el canal > Copiar ID)."); return; }
                pl.getConfig().set("discord.canales." + a[2].toLowerCase(), id);
                pl.saveConfig();
                Style.ok(s, "Canal &f" + a[2].toLowerCase() + "&a: &f" + id + "&a. Probalo con &e/tod4 discord prueba " + a[2].toLowerCase());
            }
            case "vincular" -> {
                if (a.length < 4) { Style.err(s, "Uso: &e/tod4 discord vincular <jugador> <id de Discord|borrar>"); return; }
                String k = "discord_ids." + a[2].toLowerCase();
                if (a[3].equalsIgnoreCase("borrar")) {
                    pl.datos().set(k, null);
                    Style.ok(s, "Borrado. &7Ahora: " + quienEs(a[2]));
                    return;
                }
                String id = a[3].replaceAll("[^0-9]", "");
                if (id.length() < 15) { Style.err(s, "Eso no parece un id de Discord (click derecho en la persona > Copiar ID de usuario)."); return; }
                pl.datos().set(k, id);
                Style.ok(s, "&f" + a[2] + " &a→ &f" + id);
            }
            case "id" -> {
                if (a.length < 3) { Style.err(s, "Uso: &e/tod4 discord id <jugador>"); return; }
                Style.info(s, a[2] + ": " + quienEs(a[2]));
            }
            case "revivir" -> {
                // prueba: manda el mensaje de revivido (etiqueta de verdad a ese jugador) sin revivir a nadie
                if (a.length < 3) { Style.err(s, "Uso: &e/tod4 discord revivir <jugador> &7(manda el mensaje de prueba, no revive)"); return; }
                if (canal("revividos") == null) { Style.err(s, "Falta el canal: &e/tod4 discord canal revividos <id>"); return; }
                String err = enviar("revividos", textoRevivido(s.getName(), a[2]) + "\n-# (prueba, no revivió nadie)", false, true);
                if (err == null) Style.ok(s, "Mandado. " + quienEs(a[2]));
                else Style.err(s, err);
            }
            default -> {
                Style.ok(s, "&e/tod4 discord prueba [canal] &7- manda un mensaje de prueba (sin @everyone)");
                Style.ok(s, "&e/tod4 discord canal <nombre> <id> &7- pone el id de un canal (ej: revividos)");
                Style.ok(s, "&e/tod4 discord vincular <jugador> <id> &7- su Discord, para etiquetarlo al revivir");
                Style.ok(s, "&e/tod4 discord id <jugador> &7- a quién va a etiquetar");
                Style.ok(s, "&e/tod4 discord revivir <jugador> &7- prueba del mensaje de revivido (no revive)");
                Style.ok(s, "&e/tod4 discord ver <id> &7- muestra acá cómo saldría un mensaje");
                Style.ok(s, "&e/tod4 discord aviso <id> &7- publica un mensaje en Discord sin mandarlo en el juego");
                Style.ok(s, "&7Canales: " + String.join(", ", CANALES.keySet()));
            }
        }
    }
}
