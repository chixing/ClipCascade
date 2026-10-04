// This random installation ID is metadata, not an account or hardware identifier.
const DEVICE_ID_KEY = 'clipcascade_device_id';
let identityPromise = null;

function newDeviceId() {
  return 'mobile-' + 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const random = Math.floor(Math.random() * 16);
    return (c === 'x' ? random : (random & 3) | 8).toString(16);
  });
}

function getOrCreateDeviceId(storage) {
  if (!identityPromise) {
    identityPromise = (async () => {
      const stored = await storage.get(DEVICE_ID_KEY);
      if (typeof stored === 'string' && /^mobile-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(stored)) {
        return stored;
      }
      const created = newDeviceId();
      await storage.set(DEVICE_ID_KEY, created);
      return created;
    })().catch(error => {
      identityPromise = null;
      throw error;
    });
  }
  return identityPromise;
}

function withDeviceIdQuery(url, deviceId) {
  // React Native's URLSearchParams support differs between runtimes. Keep other
  // query values verbatim and replace only this nonsecret metadata parameter.
  const hashAt = url.indexOf('#');
  const fragment = hashAt < 0 ? '' : url.slice(hashAt);
  const base = hashAt < 0 ? url : url.slice(0, hashAt);
  const queryAt = base.indexOf('?');
  const path = queryAt < 0 ? base : base.slice(0, queryAt);
  const parts = queryAt < 0 ? [] : base.slice(queryAt + 1).split('&').filter(Boolean);
  const kept = parts.filter(part => {
    try {
      return decodeURIComponent(part.split('=')[0]) !== 'deviceId';
    } catch (_) {
      return true;
    }
  });
  kept.push('deviceId=' + encodeURIComponent(deviceId));
  return path + '?' + kept.join('&') + fragment;
}

module.exports = { DEVICE_ID_KEY, getOrCreateDeviceId, withDeviceIdQuery };
