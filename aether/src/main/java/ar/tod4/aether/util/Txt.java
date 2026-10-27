package ar.tod4.aether.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Colores estilo Skript: &a, &l, &#RRGGBB y <#RRGGBB>. */
public final class Txt {
    private Txt() {}

    private static final Pattern ANGLE_HEX = Pattern.compile("<#([0-9a-fA-F]{6})>");
    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors().build();

    public static String norm(String s) {
        if (s == null) return "";
        Matcher m = ANGLE_HEX.matcher(s);
        return m.replaceAll("&#$1").replace('§', '&');
    }

    /** Texto con colores -> componente (sin cursiva automatica). */
    public static Component c(String s) {
        return SER.deserialize(norm(s)).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> lines(String... s) {
        List<Component> out = new ArrayList<>();
        for (String x : s) out.add(c(x));
        return out;
    }

    public static List<Component> lines(List<String> s) {
        List<Component> out = new ArrayList<>();
        for (String x : s) out.add(c(x));
        return out;
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    public static String strip(String s) {
        return plain(c(s));
    }
}
