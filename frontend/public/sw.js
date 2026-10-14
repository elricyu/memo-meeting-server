// Retire the Scriberr worker previously registered at /sw.js on this domain.
// The new application uses live server requests; cached legacy navigation must stop.
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    await self.clients.claim()
    await self.registration.unregister()
    const windows = await self.clients.matchAll({ type: 'window' })
    await Promise.all(windows.map(client => client.navigate(client.url)))
  })())
})
