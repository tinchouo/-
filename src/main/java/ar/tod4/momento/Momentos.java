package ar.tod4.momento;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import ar.tod4.TOD4;
import ar.tod4.avisos.Paleta;
import ar.tod4.data.Store;
import ar.tod4.hook.Ext;
import ar.tod4.util.Ancho;
import ar.tod4.util.Items;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;

/**
 * Momentos Truco con pasos. Un momento tiene varios pasos (reglas y spawns de mobs). Al "iniciar" el momento no empieza
 * para los jugadores: el admin recibe un item por paso y el momento arranca cuando activa el primero (ruleta roja + aviso).
 * Cada paso nuevo se SUMA a los anteriores; solo reemplaza a una regla del mismo tipo (altura/punto se reemplazan entre si).
 * Reglas: tocar un bloque, estar en una dimension, arriba/abajo de una altura, cerca de un punto, tener un item, matar mobs.
 * Cada regla puede tener tiempo (o no), consecuencias al terminar el tiempo (entre ellas el gas: desde que se acaba el tiempo
 * le pega cada tanto al que no cumple, hasta que cumpla) y premio.
 * Spawn: hace aparecer un mob (vanilla, MythicMobs o uno de los creepers del dia 5) en la posicion de cada jugador.
 */
public final class Momentos implements Listener {

    public enum Tipo {
        TOCAR("Tocar un bloque", Material.RED_BED),
        DIMENSION("Estar en una dimensión", Material.ENDER_EYE),
        ALTURA("Arriba / abajo de una altura", Material.LADDER),
        CERCA("Estar cerca de un punto", Material.COMPASS),
        TENER("Tener un ítem", Material.CHEST),
        MATAR("Matar mobs", Material.IRON_SWORD);

        public final String label;
        public final Material icono;
        Tipo(String l, Material i) { label = l; icono = i; }
    }

    /** Un paso activo (regla con su estado, o un spawn que queda como "mensaje actual"). */
    private static final class Act {
        String path;
        Tipo tipo;                      // null = spawn
        String texto;
        long fin;                       // 0 = sin tiempo
        int totalSeg;
        long gasProx;
        boolean gas;                    // se acabo el tiempo y el gas esta pegando a los que no cumplen
        final Set<UUID> salvados = new HashSet<>();
        final Map<UUID, Integer> kills = new HashMap<>();
    }

    final TOD4 pl;
    final Store st;
    private final NamespacedKey K_PASO, K_FIN;

    // ---- estado
    private String mid;                                        // momento preparado o en curso
    private boolean iniciado;                                  // ya se activo algun paso
    private boolean ruleta;                                    // esta girando la ruleta
    private final Map<String, Act> activos = new LinkedHashMap<>();
    private final Set<UUID> participantes = new HashSet<>();
    private final Map<UUID, Long> congelados = new HashMap<>();
    private BukkitTask tarea;
    private ModoMob modoMob;
    private String avisoActual;                                // aviso del ultimo paso activado (arriba en la sidebar)
    private final Set<String> spawnListos = new HashSet<>();   // pasos de spawn ya anunciados: su item ahora spawnea con cada clic
    private final Map<UUID, Long> ultimoClic = new HashMap<>();

    public Momentos(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "momentos");
        this.K_PASO = new NamespacedKey(pl, "momento_paso");
        this.K_FIN = new NamespacedKey(pl, "momento_fin");
        migrar();
        this.modoMob = new ModoMob(pl, this);
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getScheduler().runTaskTimer(pl, this::tickCorazones, 20L, 20L);
    }

    public Store store() { return st; }

    ModoMob modoMob() { return modoMob; }

    /** Al apagar el server: el que estaba eligiendo un mob recupera su inventario. */
    public void apagar() { if (modoMob != null) modoMob.cerrarTodo(); }

    /** Los momentos de antes (un solo objetivo) pasan a tener un unico paso. */
    private void migrar() {
        ConfigurationSection todos = st.y().getConfigurationSection("momentos");
        if (todos == null) return;
        for (String id : todos.getKeys(false)) {
            ConfigurationSection c = todos.getConfigurationSection(id);
            if (c == null || c.isConfigurationSection("pasos") || !c.isConfigurationSection("objetivo")) continue;
            String base = "momentos." + id + ".pasos.1.";
            st.y().set(base + "nombre", c.getString("nombre", id));
            for (String k : new String[]{"objetivo", "duracion", "modo", "cons", "recompensas", "aviso"}) {
                Object v = c.get(k);
                if (v == null) continue;
                if (v instanceof ConfigurationSection cs) {
                    for (Map.Entry<String, Object> en : cs.getValues(true).entrySet())
                        if (!(en.getValue() instanceof ConfigurationSection)) st.y().set(base + k + "." + en.getKey(), en.getValue());
                } else st.y().set(base + k, v);
                st.y().set("momentos." + id + "." + k, null);
            }
            st.touch();
        }
    }

    ConfigurationSection m(String path) {
        ConfigurationSection s = st.y().getConfigurationSection("momentos." + path);
        return s != null ? s : st.y().createSection("momentos." + path);
    }

    public List<String> ids() {
        ConfigurationSection s = st.y().getConfigurationSection("momentos");
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    public boolean existe(String id) { return st.y().isConfigurationSection("momentos." + id); }

    public String nombre(String path) { return m(path).getString("nombre", path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path); }

    /** Ids de los pasos de un momento, en orden. */
    public List<String> pasos(String id) {
        ConfigurationSection s = st.y().getConfigurationSection("momentos." + id + ".pasos");
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    public boolean esSpawn(String path) { return "SPAWN".equals(m(path).getString("tipo_paso", "REGLA")); }

    /** Paso de Cosmic Clones: mientras el momento sigue, a todos los jugadores los persiguen sus clones (en cualquier lugar). */
    public boolean esClones(String path) { return "CLONES".equals(m(path).getString("tipo_paso", "REGLA")); }

    Tipo tipo(String path) {
        try { return Tipo.valueOf(m(path).getString("objetivo.tipo", "TOCAR")); } catch (Exception e) { return Tipo.TOCAR; }
    }

    /** Preparado (con items para el admin) o en curso. */
    public String activo() { return mid; }

    /** El momento ya empezo para los jugadores. */
    public boolean enCurso() { return iniciado; }

    // ================================================================= texto

    public String objetivoCorto(String path) {
        ConfigurationSection c = m(path);
        if (esSpawn(path)) return "Aparece: " + c.getString("spawn.mob", "?") + " x" + c.getInt("spawn.cant", 1);
        if (esClones(path)) return "gigante".equals(c.getString("clones.modo", "fila")) ? "Clones cósmicos: gigante x" + c.getInt("clones.cant", 1)
                : "Clones cósmicos: fila de " + c.getInt("clones.cant", 10);
        List<String> objs = c.getStringList("objetivo.objs");
        String lista = objs.isEmpty() ? "?" : Txt.plain(Obj.nombre(objs.get(0))) + (objs.size() > 1 ? " (o similar)" : "");
        switch (tipo(path)) {
            case TOCAR: return "Toca: " + lista;
            case DIMENSION: return "Estar en: " + lista;
            case ALTURA: return (c.getBoolean("objetivo.arriba", true) ? "Arriba de Y " : "Abajo de Y ") + c.getInt("objetivo.y", 64);
            case CERCA: return "Llega al punto (" + c.getInt("objetivo.radio", 10) + " bloques)";
            case TENER: return "Ten " + c.getInt("objetivo.cant", 1) + "x " + lista;
            case MATAR: return "Mata " + c.getInt("objetivo.cant", 1) + "x " + lista;
            default: return "";
        }
    }

    /** Lo que dice la sidebar para este paso. */
    public String textoSidebar(String path) {
        String t = m(path).getString("texto");
        return t != null && !t.isBlank() ? t : objetivoCorto(path);
    }

    private static String familia(Tipo t) {
        if (t == null) return "spawn";
        return t == Tipo.ALTURA || t == Tipo.CERCA ? "pos" : t.name();
    }

    // ================================================================= cumplir

    private boolean cumpleAhora(Player p, Act a) {
        ConfigurationSection c = m(a.path);
        List<String> objs = c.getStringList("objetivo.objs");
        switch (a.tipo) {
            case DIMENSION:
                for (String o : objs) if (Obj.mundo(o, p.getWorld())) return true;
                return false;
            case ALTURA: {
                double y = p.getLocation().getY();
                int ref = c.getInt("objetivo.y", 64);
                return c.getBoolean("objetivo.arriba", true) ? y >= ref : y <= ref;
            }
            case CERCA: {
                Location l = c.getLocation("objetivo.punto");
                return l != null && l.getWorld() != null && l.getWorld().equals(p.getWorld())
                        && l.distance(p.getLocation()) <= c.getInt("objetivo.radio", 10);
            }
            case TENER: return Obj.contar(p.getInventory(), objs) >= c.getInt("objetivo.cant", 1);
            case MATAR: return a.kills.getOrDefault(p.getUniqueId(), 0) >= c.getInt("objetivo.cant", 1);
            default: return false;
        }
    }

    private boolean unaVez(Act a) {
        return a.tipo == Tipo.TOCAR || a.tipo == Tipo.MATAR || !"al_final".equals(m(a.path).getString("modo", "una_vez"));
    }

    private boolean salvoDe(Player p, Act a) {
        if (a.salvados.contains(p.getUniqueId())) return true;
        return !unaVez(a) && cumpleAhora(p, a);
    }

    public boolean participa(Player p) { return participantes.contains(p.getUniqueId()); }

    /** A salvo de todas las reglas activas. */
    public boolean aSalvo(Player p) {
        boolean alguna = false;
        for (Act a : activos.values()) {
            if (a.tipo == null) continue;
            alguna = true;
            if (!salvoDe(p, a)) return false;
        }
        return alguna;
    }

    /** Hay alguna regla de la que se pueda estar a salvo o en peligro (los spawns no cuentan). */
    public boolean hayReglas() {
        for (Act a : activos.values()) if (a.tipo != null) return true;
        return false;
    }

    private static boolean afecta(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    /** Los exentos (como las nutrias de Desafio): tienen el tag configurado en momentos.tag_exento. */
    /** Para otros sistemas (clones): no participa de los Momentos Truco. */
    public boolean exentoDeMomentos(Player p) { return exento(p); }

    private boolean exento(Player p) {
        if (ar.tod4.hook.Cuyes.activo(p)) return true;   // los cuyes (script cuyes.sk) nunca cuentan
        return p.getScoreboardTags().contains(pl.getConfig().getString("momentos.tag_exento", "exento_momento"));
    }

    private void salvar(Player p, Act a) {
        if (!participantes.contains(p.getUniqueId()) || !a.salvados.add(p.getUniqueId())) return;
        p.playSound(p.getLocation(), "entity.player.levelup", SoundCategory.MASTER, 1f, 1.4f);
    }

    private boolean esCama(Act a) {
        return a.tipo == Tipo.TOCAR && m(a.path).getStringList("objetivo.objs").equals(List.of("tag:minecraft:beds"));
    }

    // ================================================================= eventos

    @EventHandler(priority = EventPriority.MONITOR)
    public void toca(PlayerInteractEvent e) {
        if (!iniciado) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        String ox = Ext.oraxenBlock(e.getClickedBlock());
        for (Act a : new ArrayList<>(activos.values())) {
            if (a.tipo != Tipo.TOCAR) continue;
            for (String o : m(a.path).getStringList("objetivo.objs"))
                if (Obj.bloque(o, e.getClickedBlock().getType(), ox)) { salvar(e.getPlayer(), a); break; }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void mata(EntityDeathEvent e) {
        if (!iniciado) return;
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        String mm = Ext.mythic(e.getEntity());
        for (Act a : new ArrayList<>(activos.values())) {
            if (a.tipo != Tipo.MATAR) continue;
            for (String o : m(a.path).getStringList("objetivo.objs")) {
                if (Obj.mob(o, e.getEntity(), mm)) {
                    a.kills.merge(p.getUniqueId(), 1, Integer::sum);
                    if (cumpleAhora(p, a)) salvar(p, a);
                    break;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void congelado(PlayerMoveEvent e) {
        Long hasta = congelados.get(e.getPlayer().getUniqueId());
        if (hasta == null) return;
        if (System.currentTimeMillis() > hasta) { congelados.remove(e.getPlayer().getUniqueId()); return; }
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ())
            e.setTo(e.getFrom().setDirection(e.getTo().getDirection()));
    }

    /** Portales bloqueados mientras dura el momento (si ese momento lo pide). */
    @EventHandler(ignoreCancelled = true)
    public void portal(PlayerPortalEvent e) {
        if (!iniciado || mid == null || !m(mid).getBoolean("bloquear_portales", false)) return;
        if (!participantes.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        Style.err(e.getPlayer(), "Los portales están bloqueados durante el Momento Truco.");
    }

    // ---- items del admin

    @EventHandler(priority = EventPriority.HIGH)
    public void usaItem(PlayerInteractEvent e) {
        if (mid == null || e.getHand() != EquipmentSlot.HAND || e.getItem() == null || !e.getItem().hasItemMeta()) return;
        var pdc = e.getItem().getItemMeta().getPersistentDataContainer();
        String paso = pdc.get(K_PASO, PersistentDataType.STRING);
        boolean fin = pdc.has(K_FIN, PersistentDataType.BYTE);
        if (paso == null && !fin) return;
        e.setCancelled(true);
        if (e.getAction() == Action.PHYSICAL) return;
        // un clic puede llegar dos veces (bloque + aire): se ignora el segundo
        long ahora = System.currentTimeMillis();
        Long antes = ultimoClic.put(e.getPlayer().getUniqueId(), ahora);
        if (antes != null && ahora - antes < 150) return;
        if (fin) { terminarTodo(true); return; }
        activar(e.getPlayer(), paso);
    }

    @EventHandler(ignoreCancelled = true)
    public void tira(PlayerDropItemEvent e) {
        ItemStack it = e.getItemDrop().getItemStack();
        if (!it.hasItemMeta()) return;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        if (pdc.has(K_PASO, PersistentDataType.STRING) || pdc.has(K_FIN, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    // ================================================================= preparar / activar / terminar

    /** "Iniciar" desde el menu: no empieza para los jugadores, el admin recibe un item por paso. */
    public void preparar(Player admin, String id) {
        if (mid != null) { Style.err(admin, "Ya hay un Momento Truco preparado o en curso."); return; }
        if (pasos(id).isEmpty()) { Style.err(admin, "Ese momento no tiene pasos."); return; }
        mid = id;
        darItems(admin);
        Style.ok(admin, "Momento preparado. Usa los ítems de tu inventario para activar cada paso; el momento empieza con el primero.");
    }

    public void darItems(Player admin) {
        if (mid == null) return;
        quitarItems(admin);
        for (String pid : pasos(mid)) {
            String path = mid + ".pasos." + pid;
            admin.getInventory().addItem(itemPaso(path)).values().forEach(r -> Style.err(admin, "No hay lugar en tu inventario para todos los ítems."));
        }
        ItemStack fin = Items.of(Material.BARRIER, "&c&l✖ TERMINAR MOMENTO", "", "&7Termina todo sin castigar a nadie.");
        ItemMeta meta = fin.getItemMeta();
        meta.getPersistentDataContainer().set(K_FIN, PersistentDataType.BYTE, (byte) 1);
        fin.setItemMeta(meta);
        admin.getInventory().addItem(fin);
    }

    /** El item de un paso: antes de anunciarlo "activar"; un spawn ya anunciado pasa a ser el item que spawnea con cada clic. */
    private ItemStack itemPaso(String path) {
        ItemStack it;
        if (esSpawn(path) && spawnListos.contains(path)) {
            ConfigurationSection c = m(path);
            double vida = c.getDouble("spawn.vida", 0);
            it = Items.of(Material.SPAWNER, "&4&l☠ SPAWNEAR: &c&l" + nombre(path), "",
                    "&7Mob: &f" + c.getString("spawn.mob", "creeper") + " &7x" + Math.max(1, c.getInt("spawn.cant", 1)) + " por jugador",
                    "&7Vida: &f" + (vida > 0 ? corazones(vida) : "la normal"), "",
                    "&7Cada clic lo hace aparecer al instante en", "&7la posición de todos los participantes.", "",
                    "&e▶ Click para spawnear (las veces que quieras)");
        } else {
            Material mat = esSpawn(path) ? Material.CREEPER_HEAD : esClones(path) ? Material.ECHO_SHARD : tipo(path).icono;
            it = Items.of(mat, "&c&l⚡ " + nombre(path), "", "&7" + textoSidebar(path), "",
                    esSpawn(path) ? "&e▶ Click: ruleta y aviso. Después este ítem" : "&e▶ Click para activar",
                    esSpawn(path) ? "&e  pasa a spawnear el mob con cada clic." : "");
        }
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(K_PASO, PersistentDataType.STRING, path);
        it.setItemMeta(meta);
        return it;
    }

    /** Cambia el item de ese paso (en el inventario de quien lo tenga) por su version actual. */
    private void actualizarItem(String path) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack[] cont = p.getInventory().getContents();
            for (int i = 0; i < cont.length; i++) {
                ItemStack it = cont[i];
                if (it == null || !it.hasItemMeta()) continue;
                if (path.equals(it.getItemMeta().getPersistentDataContainer().get(K_PASO, PersistentDataType.STRING)))
                    p.getInventory().setItem(i, itemPaso(path));
            }
        }
    }

    private void quitarItems(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            var pdc = it.getItemMeta().getPersistentDataContainer();
            if (pdc.has(K_PASO, PersistentDataType.STRING) || pdc.has(K_FIN, PersistentDataType.BYTE)) p.getInventory().remove(it);
        }
    }

    /**
     * Activa un paso: ruleta roja, aviso vinculado y efecto.
     * Spawn: el primer clic es solo el anuncio (ruleta + aviso) y el item pasa a ser de spawn; desde ahi cada clic hace
     * aparecer el mob al instante en todos los participantes, sin ruleta, las veces que haga falta.
     */
    public void activar(Player admin, String path) {
        if (mid == null) return;
        if (!existe(path.substring(0, path.indexOf('.')))) return;
        if (esSpawn(path) && spawnListos.contains(path)) {
            int n = spawnear(m(path));
            admin.sendActionBar(Txt.c("&4☠ &cAparecieron &f" + n + " &cmobs"));
            admin.playSound(admin.getLocation(), "entity.evoker.prepare_summon", SoundCategory.MASTER, 0.6f, 1.2f);
            return;
        }
        if (ruleta) { Style.err(admin, "Espera: la ruleta todavía está girando."); return; }
        // sin ruleta: el paso se aplica al instante (por ejemplo un spawn por sorpresa)
        if (!m(path).getBoolean("ruleta", true)) {
            if (!iniciado) vinetaATodos();
            aplicar(path);
            return;
        }
        ruleta = true;
        pl.avisos().ruletaGlobal(Paleta.ROJO, () -> { if (!iniciado) vinetaATodos(); }, () -> {
            ruleta = false;
            if (mid == null) return;
            aplicar(path);
        });
    }

    private void aplicar(String path) {
        if (!iniciado) comenzar();
        ConfigurationSection c = m(path);
        String av = c.getString("aviso");
        if (av != null && pl.avisos().existe(av)) for (Player p : Bukkit.getOnlinePlayers()) pl.avisos().mostrar(av, p);
        avisoActual = av != null && pl.avisos().existe(av) ? av : null;
        Act a = new Act();
        a.path = path;
        a.texto = textoSidebar(path);
        if (esClones(path)) {
            // desde ahora (y hasta que termine el momento) a cada jugador lo siguen sus clones, este donde este
            pl.clones().momento("gigante".equals(c.getString("clones.modo", "fila")), c.getInt("clones.cant", 10));
            activos.put("clones", a);
            return;
        }
        if (esSpawn(path)) {
            // el anuncio no hace aparecer nada: desde ahora el item del admin spawnea con cada clic
            spawnListos.add(path);
            actualizarItem(path);
        } else {
            a.tipo = tipo(path);
            int dur = c.getInt("duracion", 0);
            if (dur > 0) { a.totalSeg = dur; a.fin = System.currentTimeMillis() + dur * 1000L; }
        }
        // las reglas anteriores siguen, salvo la del mismo tipo (que se reemplaza)
        activos.put(familia(a.tipo), a);
    }

    private void comenzar() {
        iniciado = true;
        participantes.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!afecta(p) || exento(p)) continue;
            participantes.add(p.getUniqueId());
            if (!conVineta.contains(p.getUniqueId())) ponerVineta(p);
        }
        if (tarea != null) tarea.cancel();
        tarea = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 10L, 10L);
    }

    private void tick() {
        if (!iniciado) return;
        long ahora = System.currentTimeMillis();
        for (Act a : new ArrayList<>(activos.values())) {
            if (a.tipo == null) continue;
            if (unaVez(a)) {
                for (UUID u : participantes) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null && !a.salvados.contains(u) && cumpleAhora(p, a)) salvar(p, a);
                }
            }
            // gas: desde que se acabo el tiempo, dano cada tanto a los que todavia no cumplen (la Resistencia lo baja)
            ConfigurationSection c = m(a.path);
            if (a.gas && ahora >= a.gasProx) {
                a.gasProx = ahora + (long) (Math.max(0.5, c.getDouble("gas.cada", 1.0)) * 1000);
                DamageSource src = DamageSource.builder(DamageType.MAGIC).build();
                double dano = Math.max(0.5, c.getDouble("gas.corazones", 2.5)) * 2.0;
                for (UUID u : participantes) {
                    Player p = Bukkit.getPlayer(u);
                    if (p == null || p.isDead() || !afecta(p) || salvoDe(p, a)) continue;
                    p.damage(dano, src);
                }
            }
            if (a.fin > 0 && ahora >= a.fin) terminarRegla(a);
        }
    }

    /** Se acabo el tiempo de una regla: premio a los que cumplieron y castigo a los que fallaron. */
    private void terminarRegla(Act a) {
        boolean gas = m(a.path).getBoolean("gas.on", false);
        // con gas la regla sigue en la sidebar: desde ahora el gas le pega al que no cumple hasta que cumpla
        if (gas) { a.fin = 0; a.gas = true; a.gasProx = System.currentTimeMillis(); }
        else activos.remove(familia(a.tipo), a);
        if (!unaVez(a)) for (UUID u : participantes) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && cumpleAhora(p, a)) a.salvados.add(u);
        }
        List<UUID> fallaron = new ArrayList<>();
        for (UUID u : participantes) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || p.isDead()) continue;
            if (a.salvados.contains(u)) {
                for (ItemStack it : recompensas(a.path)) p.getInventory().addItem(it.clone()).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
            } else {
                fallaron.add(u);
            }
        }
        long delay = Math.max(0, pl.getConfig().getLong("momentos.delay_castigo_seg", 15)) * 20L;
        String path = a.path;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            for (UUID u : fallaron) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && !p.isDead()) consecuencias(p, path);
            }
        }, delay);
    }

    /** Termina todo el momento (sin castigar) y le saca los items al admin. */
    public void terminarTodo(boolean avisar) {
        if (tarea != null) tarea.cancel();
        tarea = null;
        for (Player p : Bukkit.getOnlinePlayers()) quitarItems(p);
        activos.clear();
        participantes.clear();
        iniciado = false;
        ruleta = false;
        boolean habia = mid != null;
        mid = null;
        avisoActual = null;
        spawnListos.clear();
        if (pl.clones() != null) pl.clones().momentoTerminar();
        sacarVinetas();
        // sin mensajes en el chat: solo un sonido grave para todos
        if (avisar && habia) {
            String son = pl.getConfig().getString("momentos.sonido_fin", "entity.wither.spawn");
            float pitch = (float) pl.getConfig().getDouble("momentos.sonido_fin_tono", 0.6);
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), son, SoundCategory.MASTER, 0.8f, pitch);
        }
    }

    public void cancelar(Player quien) {
        if (mid == null) { Style.err(quien, "No hay ningún Momento Truco activo."); return; }
        terminarTodo(true);
        Style.ok(quien, "Momento Truco cancelado.");
    }

    @SuppressWarnings("unchecked")
    List<ItemStack> recompensas(String path) {
        List<ItemStack> out = new ArrayList<>();
        List<?> l = m(path).getList("recompensas");
        if (l != null) for (Object o : l) if (o instanceof ItemStack it) out.add(it);
        return out;
    }

    // ================================================================= spawn de mobs

    private int spawnear(ConfigurationSection c) {
        String mob = c.getString("spawn.mob", "creeper");
        int cant = Math.max(1, Math.min(20, c.getInt("spawn.cant", 1)));
        double vida = c.getDouble("spawn.vida", 0);
        int azar = c.getInt("spawn.azar", 0);
        List<Player> objetivo = new ArrayList<>();
        // le toca a todos los que estan jugando en este momento (no solo a los que estaban al empezar)
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.isDead() && afecta(p) && !exento(p)) objetivo.add(p);
        if (azar > 0 && azar < objetivo.size()) { Collections.shuffle(objetivo); objetivo = objetivo.subList(0, azar); }
        int n = 0;
        for (Player p : objetivo) for (int i = 0; i < cant; i++) {
            Entity e = hacerAparecer(mob, p.getLocation());
            if (e != null) n++;
            if (e instanceof LivingEntity le && vida > 0) {
                le.getPersistentDataContainer().set(pl.creepers().vidaFija(), PersistentDataType.BYTE, (byte) 1);
                poner(le, vida);
                // MythicMobs y otros plugins pueden volver a poner su vida un instante despues: se vuelve a fijar
                for (long d : new long[]{2, 10, 40}) Bukkit.getScheduler().runTaskLater(pl, () -> { if (le.isValid()) poner(le, vida); }, d);
            }
        }
        return n;
    }

    private static void poner(LivingEntity le, double vida) {
        AttributeInstance a = le.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) a.setBaseValue(vida);
        le.setHealth(Math.min(vida, a != null ? a.getValue() : vida));
    }

    /** mythic:Id | tod4:nuclear (random, mini, calamar, void) | creeper_cargado | cualquier mob de vanilla. */
    private Entity hacerAparecer(String mob, Location l) {
        String s = mob.toLowerCase();
        try {
            if (s.startsWith("mythic:")) return Ext.mythicSpawn(mob.substring(7), l);
            if (s.startsWith("tod4:")) return pl.creepers().aparecer(s.substring(5), l);
            if (s.equals("creeper_cargado")) {
                Entity e = l.getWorld().spawnEntity(l, EntityType.CREEPER);
                if (e instanceof Creeper cr) cr.setPowered(true);
                return e;
            }
            return l.getWorld().spawnEntity(l, EntityType.valueOf(s.toUpperCase()));
        } catch (Exception ex) {
            pl.getLogger().warning("Momento Truco: no pude hacer aparecer '" + mob + "': " + ex.getMessage());
            return null;
        }
    }

    // ================================================================= vineta roja

    private final Set<UUID> conVineta = new HashSet<>();

    /** Borde del mundo falso (solo lo ve ese jugador) para que la pantalla tenga la vineta roja (hace falta Graficos: Detallados). */
    private void ponerVineta(Player p) {
        if (!pl.getConfig().getBoolean("momentos.vineta", true)) return;
        double intensidad = Math.max(0.05, Math.min(0.99, pl.getConfig().getDouble("momentos.vineta_intensidad", 0.75)));
        org.bukkit.WorldBorder b = Bukkit.createWorldBorder();
        b.setCenter(0, 0);
        b.setSize(59_999_968);
        double dist = 59_999_968 / 2.0;
        b.setWarningDistance((int) Math.min(Integer.MAX_VALUE, dist / (1.0 - intensidad)));
        b.setWarningTime(0);
        p.setWorldBorder(b);
        conVineta.add(p.getUniqueId());
    }

    private void sacarVinetas() {
        for (UUID u : conVineta) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.setWorldBorder(null);
        }
        conVineta.clear();
    }

    private void vinetaATodos() {
        for (Player p : Bukkit.getOnlinePlayers()) if (afecta(p) && !exento(p)) ponerVineta(p);
    }

    // ================================================================= consecuencias

    private void consecuencias(Player p, String path) {
        ConfigurationSection c = m(path).getConfigurationSection("cons");
        if (c == null) return;
        int seg = c.getInt("efectos_seg", 10);
        if (c.getBoolean("tirar")) {
            for (ItemStack it : p.getInventory().getContents()) if (it != null && !it.getType().isAir()) p.getWorld().dropItemNaturally(p.getLocation(), it);
            p.getInventory().clear();
        }
        if (c.getBoolean("tp")) {
            Location l = c.getLocation("tp_pos");
            if (l != null) p.teleport(l);
        }
        if (c.getBoolean("mobs")) {
            String tipo = c.getString("mobs_tipo", "zombie");
            int n = Math.max(1, Math.min(20, c.getInt("mobs_cant", 3)));
            Location l = p.getLocation();
            if (tipo.startsWith("mythic:")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm mobs spawn -s " + tipo.substring(7) + " " + n + " "
                        + l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch());
            } else {
                try {
                    EntityType t = EntityType.valueOf(tipo.toUpperCase());
                    for (int i = 0; i < n; i++) l.getWorld().spawnEntity(l, t);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (c.getBoolean("wither")) p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, seg * 20, 0));
        if (c.getBoolean("poison")) p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, seg * 20, 0));
        if (c.getBoolean("slowness")) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seg * 20, 9));
        if (c.getBoolean("darkness")) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, seg * 20, 0));
        if (c.getBoolean("freeze")) {
            congelados.put(p.getUniqueId(), System.currentTimeMillis() + c.getInt("freeze_seg", 5) * 1000L);
            p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        }
        if (c.getBoolean("corazones_on")) {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) {
                double antes = a.getBaseValue();
                double nuevo = Math.max(2.0, antes - c.getInt("corazones", 3) * 2.0);
                a.setBaseValue(nuevo);
                if (p.getHealth() > nuevo) p.setHealth(nuevo);
                double quitado = antes - nuevo;
                int durSeg = c.getInt("corazones_seg", 0);
                if (quitado > 0) {
                    if (durSeg > 0) {
                        String base = "quitados." + p.getUniqueId() + "." + System.currentTimeMillis();
                        st.set(base + ".vida", quitado);
                        st.set(base + ".resta", durSeg);
                        Style.raw(p, "&c&l❤ &cPierdes " + corazones(quitado) + " durante &f" + duracion(durSeg)
                                + "&c. &7(Solo cuenta el tiempo que estás conectado.)");
                    } else {
                        Style.raw(p, "&c&l❤ &cPierdes " + corazones(quitado) + " para siempre.");
                    }
                }
            }
        }
        if (c.getBoolean("rayo")) p.getWorld().strikeLightning(p.getLocation());
        if (c.getBoolean("explosion")) p.getWorld().createExplosion(p.getLocation(), (float) c.getInt("fuerza", 4), false, true);
        if (c.getBoolean("medio") && !p.isDead()) p.setHealth(Math.min(p.getHealth(), 1.0));
        if (c.getBoolean("instakill") && !p.isDead()) p.setHealth(0);
    }

    // ================================================================= corazones que vuelven

    private static String corazones(double vida) {
        double c = vida / 2.0;
        String n = c == Math.floor(c) ? String.valueOf((int) c) : String.valueOf(c).replace('.', ',');
        return n + (c == 1 ? " corazón" : " corazones");
    }

    public static String duracion(int seg) {
        int h = seg / 3600, mi = (seg % 3600) / 60, s = seg % 60;
        if (h > 0) return h + " h" + (mi > 0 ? " " + mi + " min" : "");
        if (mi > 0) return mi + " min" + (s > 0 ? " " + s + " s" : "");
        return s + " s";
    }

    /** Cada segundo: descuenta el tiempo de los corazones quitados (solo a los conectados) y los devuelve al terminar. */
    private void tickCorazones() {
        ConfigurationSection s = st.y().getConfigurationSection("quitados");
        if (s == null) return;
        boolean cambio = false;
        for (String u : new ArrayList<>(s.getKeys(false))) {
            Player p;
            try { p = Bukkit.getPlayer(UUID.fromString(u)); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection q = s.getConfigurationSection(u);
            if (p == null || q == null || p.isDead()) continue;
            for (String k : new ArrayList<>(q.getKeys(false))) {
                int resta = q.getInt(k + ".resta") - 1;
                cambio = true;
                if (resta > 0) { q.set(k + ".resta", resta); continue; }
                double vida = q.getDouble(k + ".vida");
                AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
                if (a != null) a.setBaseValue(a.getBaseValue() + vida);
                q.set(k, null);
                Style.raw(p, "&a&l❤ &aRecuperaste " + corazones(vida) + ".");
                p.playSound(p.getLocation(), "entity.player.levelup", SoundCategory.MASTER, 1f, 1.2f);
            }
            if (q.getKeys(false).isEmpty()) s.set(u, null);
        }
        if (cambio) st.touch();
    }

    @EventHandler
    public void entra(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ConfigurationSection q = st.y().getConfigurationSection("quitados." + p.getUniqueId());
        if (q == null) return;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            for (String k : q.getKeys(false))
                Style.raw(p, "&c&l❤ &7Te faltan &f" + duracion(q.getInt(k + ".resta")) + " &7conectado para recuperar "
                        + corazones(q.getDouble(k + ".vida")) + ".");
        }, 60L);
    }

    // ================================================================= sidebar

    /** Titulo de la sidebar durante el momento: calaveras en rojo oscuro y MOMENTO TRUCO en rojo. */
    public static String tituloSidebar() {
        return "&4&l☠ &c&lMOMENTO TRUCO &4&l☠";
    }

    /**
     * La sidebar completa durante el momento (reemplaza todo lo demas):
     *   linea gris / el aviso vinculado del ultimo paso tal cual (sin titulo) / linea gris /
     *   por cada paso activo: "■ texto" (rojo oscuro) y debajo el reloj con el tiempo (si tiene) / linea gris /
     *   "No participas" (creativo, espectador o exento) / linea gris
     */
    public List<String> sidebar(Player p, String sep, int ancho) {
        List<String> out = new ArrayList<>();
        out.add(sep);
        List<String> av = pl.avisos().lineasSinTitulo(avisoActual, p);
        if (!av.isEmpty()) {
            List<String> renglones = new ArrayList<>();
            for (String l : av) renglones.addAll(Ancho.cortar(l, "", "", ancho));
            if (renglones.size() > 5) renglones = renglones.subList(0, 5);
            out.addAll(renglones);
            out.add(sep);
        }
        long ahora = System.currentTimeMillis();
        for (Act a : activos.values()) {
            // como "■ Perfil": el cuadradito rojo y el texto del paso; abajo, con la linea gris, el tiempo en rojo (si tiene)
            out.addAll(Ancho.cortar("&c&l" + Txt.norm(a.texto), "&4■ ", "&4  ", ancho));
            if (a.fin > 0) {
                long r = Math.max(0, (a.fin - ahora + 999) / 1000);
                out.add(" &8| &c" + pl.getConfig().getString("momentos.simbolo_tiempo", "⌚") + " " + String.format("%d:%02d", r / 60, r % 60));
            }
        }
        if (activos.isEmpty()) out.add("&4■ &c&lEsperando el siguiente paso...");
        out.add(sep);
        if (!participa(p)) {
            out.add("&e■ &eNo participas");
            out.add(sep);
        }
        return out;
    }
}
