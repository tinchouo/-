// Un jugador de prueba: entra al server, y anota lo que le manda el server cuando le tiran el screamer.
const mc = require('minecraft-protocol')
const c = mc.createClient({ host: '127.0.0.1', port: 25565, username: 'tester', version: '1.21.4' })
const ver = new Set(['spawn_entity', 'entity_metadata', 'sound_effect', 'entity_sound_effect', 'entity_teleport',
  'remove_entities', 'system_chat', 'player_chat', 'profileless_chat', 'update_health', 'sync_entity_position', 'entity_position_sync', 'teleport_entity'])
let t0 = Date.now()
c.on('packet', (data, meta) => {
  if (!ver.has(meta.name)) return
  let s = JSON.stringify(data, (k, v) => typeof v === 'bigint' ? v.toString() : v)
  if (s.length > 700) s = s.slice(0, 700) + '...'
  console.log('PAQUETE', ((Date.now() - t0) / 1000).toFixed(2), meta.name, s)
})
c.on('login', () => { console.log('BOT entro'); setTimeout(() => { try { c.chat(':GENIO: probando'); console.log('BOT mando :GENIO:') } catch (e) { console.log('BOT chat error', e.message) } }, 15000) })
// si muere, reaparece (como apretar "Reaparecer")
c.on('update_health', d => { if (d.health <= 0) setTimeout(() => { try { c.write('client_command', { actionId: 0 }); console.log('BOT reaparecio') } catch (e) { console.log('BOT respawn error', e.message) } }, 500) })
c.on('error', e => console.log('BOT error', e.message))
c.on('end', r => console.log('BOT salio', r))
setTimeout(() => process.exit(0), 140000)
