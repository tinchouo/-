// Jugador de prueba para los creditos: entra y cuenta lo que le manda el server.
const mc = require('minecraft-protocol')
const c = mc.createClient({ host: '127.0.0.1', port: 25565, username: 'tester', version: '1.21.4' })
const cuenta = {}
const mostrar = new Set(['camera', 'game_state_change', 'set_title_text', 'set_title_subtitle', 'sound_effect', 'entity_sound_effect', 'stop_sound', 'system_chat', 'position'])
let t0 = Date.now(), mostrados = {}
c.on('packet', (data, meta) => {
  cuenta[meta.name] = (cuenta[meta.name] || 0) + 1
  if (!mostrar.has(meta.name)) return
  mostrados[meta.name] = (mostrados[meta.name] || 0) + 1
  if (mostrados[meta.name] > 40) return
  let s = JSON.stringify(data, (k, v) => typeof v === 'bigint' ? v.toString() : v)
  if (s.length > 400) s = s.slice(0, 400) + '...'
  console.log('PAQUETE', ((Date.now() - t0) / 1000).toFixed(2), meta.name, s)
})
c.on('login', () => console.log('BOT entro'))
c.on('error', e => console.log('BOT error', e.message))
c.on('end', r => console.log('BOT salio', r))
setInterval(() => console.log('CUENTA', ((Date.now() - t0) / 1000).toFixed(0), JSON.stringify(cuenta)), 20000)
setTimeout(() => { console.log('CUENTA_FINAL', JSON.stringify(cuenta)); process.exit(0) }, 400000)
